package kz.bejiihiu.echorift

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.player.PlayerBedEnterEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.block.BlockDropItemEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Vector
import kotlin.random.Random

class EchoListener(
    private val config: PluginConfig,
    private val manager: EchoEventManager,
    private val debug: DebugLogger
) : Listener {
    private val random = Random.Default

    @EventHandler(ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        if (!manager.eventActive) {
            debug.info("Перемещение игнорируется: событие не активно.")
            return
        }
        val from = event.from
        val to = event.to
        if (from.blockX == to.blockX && from.blockZ == to.blockZ && from.world == to.world) return
        debug.info("Перемещение: ${event.player.name} ${from.blockX},${from.blockZ} -> ${to.blockX},${to.blockZ} (${from.world.name}).")
        val player = event.player
        val currentPoint = manager.isInPoint(to)
        val previousPoint = manager.isInPoint(from)
        if (currentPoint?.id != previousPoint?.id) {
            previousPoint?.let { manager.handlePlayerExit(player, it) }
            currentPoint?.let { manager.handlePlayerEnter(player, it) }
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val player = event.player
        val point = manager.isInPoint(player.location) ?: return
        debug.info("Игрок ${player.name} вышел с сервера внутри точки ${point.id}.")
        manager.handlePlayerExit(player, point)
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    fun onOreDrop(event: BlockDropItemEvent) {
        if (!manager.eventActive) {
            debug.info("Дроп руды пропущен: событие не активно.")
            return
        }
        val point = manager.isInPoint(event.block.location) ?: return
        if (point.distortion != DistortionType.ORE_DROP_SHIFT) {
            debug.info("Дроп руды: точка ${point.id} не имеет нужного искажения (${point.distortion}).")
            return
        }
        val mapping = config.oreDropShift.mappings[event.block.type] ?: return
        val roll = random.nextDouble()
        if (roll > config.oreDropShift.chance) {
            debug.info("Дроп руды: шанс не сработал (roll=$roll, шанс=${config.oreDropShift.chance}).")
            return
        }
        debug.info("Смещение дропа руды: ${event.block.x},${event.block.y},${event.block.z} игрок=${event.player.name}.")
        event.items.clear()
        val count = random.nextInt(mapping.min, mapping.max + 1)
        val total = (count * mapping.multiplier).toInt().coerceAtLeast(1)
        val drop = ItemStack(mapping.drop, total)
        debug.info("Дроп руды: выбран предмет=${mapping.drop}, базовыйCount=$count, множитель=${mapping.multiplier}, итог=$total.")
        event.block.world.dropItemNaturally(event.block.location, drop)
        val tool = event.player.inventory.itemInMainHand
        if (tool.type != Material.AIR && tool.itemMeta is org.bukkit.inventory.meta.Damageable) {
            val meta = tool.itemMeta as org.bukkit.inventory.meta.Damageable
            meta.damage += config.oreDropShift.extraToolDamage
            tool.itemMeta = meta
        }
        if (!config.oreDropShift.masking) {
            val player = event.player
            if (config.messages.oreReveal.isNotBlank()) {
                MessageUtil.send(player, config.messages.oreReveal)
            }
        }
        manager.addActivity(point, config.oreDropShift.activityGain)
    }

    @EventHandler(ignoreCancelled = true)
    fun onBedEnter(event: PlayerBedEnterEvent) {
        val point = manager.isInPoint(event.bed.location) ?: return
        if (point.distortion != DistortionType.MECHANIC_LOCK) {
            debug.info("Сон: точка ${point.id} без мех-лока (${point.distortion}), пропуск.")
            return
        }
        event.isCancelled = true
        debug.info("Мех-лок: запрет сна для ${event.player.name}.")
        manager.addActivity(point, config.mechanicLock.activityGain)
        MessageUtil.send(event.player, config.messages.deny)
        openSubstituteInventory(event.player)
        knockback(event.player)
    }

    @EventHandler(ignoreCancelled = true)
    fun onInventoryOpen(event: InventoryOpenEvent) {
        val player = event.player as? Player ?: return
        val point = manager.isInPoint(player.location) ?: return
        if (point.distortion != DistortionType.MECHANIC_LOCK) {
            debug.info("Инвентарь: точка ${point.id} без мех-лока (${point.distortion}), пропуск.")
            return
        }
        if (!config.mechanicLock.blockedInventories.contains(event.inventory.type.name)) {
            debug.info("Инвентарь ${event.inventory.type.name} не в блок-листе, пропуск.")
            return
        }
        event.isCancelled = true
        debug.info("Мех-лок: заблокирован инвентарь ${event.inventory.type.name} для ${player.name}.")
        manager.addActivity(point, config.mechanicLock.activityGain)
        MessageUtil.send(player, config.messages.deny)
        openSubstituteInventory(player)
        knockback(player)
    }

    @EventHandler(ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        val point = manager.isInPoint(event.block.location) ?: return
        if (point.distortion == DistortionType.MECHANIC_LOCK && config.mechanicLock.blockedBlocks.contains(event.block.type)) {
            event.isCancelled = true
            debug.info("Мех-лок: запрет размещения ${event.block.type} для ${event.player.name}.")
            manager.addActivity(point, config.mechanicLock.activityGain)
            MessageUtil.send(event.player, config.messages.deny)
            openSubstituteInventory(event.player)
            knockback(event.player)
            return
        }
        if (point.distortion != DistortionType.PLACEMENT_DECAY || !config.placementDecay.enabled) {
            debug.info("Декей размещения: искажение=${point.distortion}, enabled=${config.placementDecay.enabled}, пропуск.")
            return
        }
        if (!config.placementDecay.whitelist.contains(event.block.type)) {
            debug.info("Декей размещения: блок ${event.block.type} не в whitelist, пропуск.")
            return
        }
        val decayRoll = random.nextDouble()
        if (decayRoll > config.placementDecay.chance) {
            debug.info("Декей размещения: шанс не сработал (roll=$decayRoll, шанс=${config.placementDecay.chance}).")
            return
        }
        debug.info("Декей размещения запланирован для ${event.block.type} на ${event.block.x},${event.block.y},${event.block.z}.")
        manager.scheduleDecay(point, event.player.uniqueId, event.block.location, event.block.type)
        manager.addActivity(point, config.placementDecay.activityGain)
    }

    @EventHandler(ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEvent) {
        val player = event.player
        val point = manager.isInPoint(player.location) ?: return
        if (point.distortion != DistortionType.MECHANIC_LOCK) {
            debug.info("Взаимодействие: точка ${point.id} без мех-лока (${point.distortion}), пропуск.")
            return
        }
        val clicked = event.clickedBlock ?: return
        if (!config.mechanicLock.blockedBlocks.contains(clicked.type)) {
            debug.info("Взаимодействие: блок ${clicked.type} не в блок-листе, пропуск.")
            return
        }
        event.isCancelled = true
        debug.info("Мех-лок: запрет взаимодействия с ${clicked.type} для ${player.name}.")
        manager.addActivity(point, config.mechanicLock.activityGain)
        MessageUtil.send(player, config.messages.deny)
        openSubstituteInventory(player)
        knockback(player)
    }

    private fun openSubstituteInventory(player: Player) {
        val blocked: Set<String> = config.mechanicLock.blockedInventories
            .asSequence()
            .map { it.trim().uppercase() }
            .toSet()

        val source = if (config.mechanicLock.randomInventories.isNotEmpty()) {
            config.mechanicLock.randomInventories
        } else {
            config.mechanicLock.substituteInventories
        }

        val candidates: List<InventoryType> = source
            .asSequence()
            .mapNotNull { raw ->
                val name = raw.trim().uppercase() // работает даже если raw был Any?
                runCatching<InventoryType> { InventoryType.valueOf(name) }.getOrNull()
            }
            .filterNot { it.name in blocked }
            .toList()

        if (candidates.isEmpty()) {
            player.openInventory(player.inventory)
            debug.info("Открыт инвентарь игрока как замена для ${player.name}.")
            return
        }

        val type = candidates.random()
        val inventory = Bukkit.createInventory(player, type)
        player.openInventory(inventory)
        debug.info("Открыт заменяющий инвентарь ${type.name} для ${player.name}.")
    }


    private fun knockback(player: Player) {
        if (!config.mechanicLock.knockbackEnabled) return
        val direction = player.location.direction.multiply(-config.mechanicLock.knockbackStrength)
        player.velocity = Vector(direction.x, 0.3, direction.z)
        debug.info("Откидывание применено к ${player.name}.")
    }
}
