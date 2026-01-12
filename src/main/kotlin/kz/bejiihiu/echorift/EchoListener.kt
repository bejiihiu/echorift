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
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.InventoryType
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
        if (!manager.eventActive) return
        val from = event.from
        val to = event.to ?: return
        if (from.blockX == to.blockX && from.blockZ == to.blockZ && from.world == to.world) return
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
        manager.handlePlayerExit(player, point)
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    fun onOreDrop(event: BlockDropItemEvent) {
        if (!manager.eventActive) return
        val point = manager.isInPoint(event.block.location) ?: return
        if (point.distortion != DistortionType.ORE_DROP_SHIFT) return
        val mapping = config.oreDropShift.mappings[event.block.type] ?: return
        if (random.nextDouble() > config.oreDropShift.chance) return
        debug.info("Ore drop shift triggered at ${event.block.x},${event.block.y},${event.block.z} by ${event.player?.name}.")
        event.items.clear()
        val count = random.nextInt(mapping.min, mapping.max + 1)
        val total = (count * mapping.multiplier).toInt().coerceAtLeast(1)
        val drop = ItemStack(mapping.drop, total)
        event.block.world.dropItemNaturally(event.block.location, drop)
        val tool = event.player?.inventory?.itemInMainHand
        if (tool != null && tool.type != Material.AIR && tool.itemMeta is org.bukkit.inventory.meta.Damageable) {
            val meta = tool.itemMeta as org.bukkit.inventory.meta.Damageable
            meta.damage += config.oreDropShift.extraToolDamage
            tool.itemMeta = meta
        }
        if (!config.oreDropShift.masking) {
            val player = event.player
            if (player != null && config.messages.oreReveal.isNotBlank()) {
                MessageUtil.send(player, config.messages.oreReveal)
            }
        }
        manager.addActivity(point, config.oreDropShift.activityGain)
    }

    @EventHandler(ignoreCancelled = true)
    fun onBedEnter(event: PlayerBedEnterEvent) {
        val point = manager.isInPoint(event.bed.location) ?: return
        if (point.distortion != DistortionType.MECHANIC_LOCK) return
        event.isCancelled = true
        debug.info("Mechanic lock: blocked bed enter for ${event.player.name}.")
        manager.addActivity(point, config.mechanicLock.activityGain)
        MessageUtil.send(event.player, config.messages.deny)
        openSubstituteInventory(event.player)
        knockback(event.player)
    }

    @EventHandler(ignoreCancelled = true)
    fun onInventoryOpen(event: InventoryOpenEvent) {
        val player = event.player as? Player ?: return
        val point = manager.isInPoint(player.location) ?: return
        if (point.distortion != DistortionType.MECHANIC_LOCK) return
        if (!config.mechanicLock.blockedInventories.contains(event.inventory.type.name)) return
        event.isCancelled = true
        debug.info("Mechanic lock: blocked inventory ${event.inventory.type.name} for ${player.name}.")
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
            debug.info("Mechanic lock: blocked placement ${event.block.type} for ${event.player.name}.")
            manager.addActivity(point, config.mechanicLock.activityGain)
            MessageUtil.send(event.player, config.messages.deny)
            openSubstituteInventory(event.player)
            knockback(event.player)
            return
        }
        if (point.distortion != DistortionType.PLACEMENT_DECAY || !config.placementDecay.enabled) return
        if (!config.placementDecay.whitelist.contains(event.block.type)) return
        if (random.nextDouble() > config.placementDecay.chance) return
        debug.info("Placement decay scheduled for ${event.block.type} at ${event.block.x},${event.block.y},${event.block.z}.")
        manager.scheduleDecay(point, event.player.uniqueId, event.block.location, event.block.type)
        manager.addActivity(point, config.placementDecay.activityGain)
    }

    @EventHandler(ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEvent) {
        val player = event.player
        val point = manager.isInPoint(player.location) ?: return
        if (point.distortion != DistortionType.MECHANIC_LOCK) return
        val clicked = event.clickedBlock ?: return
        if (!config.mechanicLock.blockedBlocks.contains(clicked.type)) return
        event.isCancelled = true
        debug.info("Mechanic lock: blocked interaction with ${clicked.type} for ${player.name}.")
        manager.addActivity(point, config.mechanicLock.activityGain)
        MessageUtil.send(player, config.messages.deny)
        openSubstituteInventory(player)
        knockback(player)
    }

    private fun openSubstituteInventory(player: Player) {
        val candidates = config.mechanicLock.substituteInventories.mapNotNull { name ->
            runCatching { InventoryType.valueOf(name) }.getOrNull()
        }.filterNot { config.mechanicLock.blockedInventories.contains(it.name) }
        if (candidates.isEmpty()) {
            player.openInventory(player.inventory)
            debug.info("Opened player inventory as substitute for ${player.name}.")
            return
        }
        val type = candidates.random()
        val inventory = Bukkit.createInventory(player, type)
        player.openInventory(inventory)
        debug.info("Opened substitute inventory ${type.name} for ${player.name}.")
    }

    private fun knockback(player: Player) {
        if (!config.mechanicLock.knockbackEnabled) return
        val direction = player.location.direction.multiply(-config.mechanicLock.knockbackStrength)
        player.velocity = Vector(direction.x, 0.3, direction.z)
        debug.info("Applied knockback to ${player.name}.")
    }
}
