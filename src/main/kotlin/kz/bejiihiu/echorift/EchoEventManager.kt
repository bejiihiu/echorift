package kz.bejiihiu.echorift

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class EchoEventManager(private val plugin: Main, private val config: PluginConfig, private val debug: DebugLogger) {
    private val random = Random.Default
    private val globalScheduler = Bukkit.getGlobalRegionScheduler()
    private val persistenceManager = PersistenceManager(plugin)

    private val points = ConcurrentHashMap<UUID, EchoPoint>()
    private val playerZones = ConcurrentHashMap<UUID, MutableSet<UUID>>()

    private var spawnTask: ScheduledTask? = null
    private var ttlTask: ScheduledTask? = null
    private var hintTask: ScheduledTask? = null
    private var particleTask: ScheduledTask? = null
    private var soundTask: ScheduledTask? = null
    private var stayTask: ScheduledTask? = null
    private var hungerTask: ScheduledTask? = null
    private var tickBoostTask: ScheduledTask? = null

    var eventActive: Boolean = false
        private set
    var eventEndsAt: Instant? = null
        private set

    fun loadPersistentState() {
        if (!config.event.persistent) return
        val state = persistenceManager.load() ?: return
        points.clear()
        points.putAll(state.points)
        eventActive = state.active
        eventEndsAt = state.endsAt
        debug.info("Loaded persistent state: active=$eventActive points=${points.size} endsAt=$eventEndsAt")
        val now = Instant.now()
        if (eventEndsAt != null && now.isAfter(eventEndsAt)) {
            eventActive = false
            eventEndsAt = null
            points.clear()
            debug.info("Persistent event expired on boot, cleared state.")
        } else {
            points.values.removeIf { it.isExpired(now) }
            debug.info("Filtered expired points after load: remaining=${points.size}")
        }
    }

    fun startIfScheduled() {
        if (!config.event.autoStart) return
        val zone = config.event.zoneId
        val now = LocalDateTime.now(zone)
        val today = LocalDate.now(zone)
        val start = LocalDateTime.of(today, config.event.startTime)
        val end = LocalDateTime.of(today, java.time.LocalTime.of(23, 59))
        if (now.isAfter(end)) return
        if (now.isAfter(start)) {
            debug.info("Auto-start window open, starting event immediately.")
            startEvent(end.atZone(zone).toInstant())
            return
        }
        val delaySeconds = java.time.Duration.between(now, start).seconds
        debug.info("Scheduling auto-start in ${delaySeconds}s.")
        globalScheduler.runDelayed(plugin, { _ -> startEvent(end.atZone(zone).toInstant()) }, delaySeconds * 20)
    }

    fun startEvent(endsAt: Instant?) {
        if (eventActive) return
        eventActive = true
        eventEndsAt = endsAt
        debug.info("Event started. endsAt=$eventEndsAt")
        MessageUtil.broadcast(config.messages.start)
        scheduleTasks()
    }

    fun stopEvent() {
        if (!eventActive) return
        eventActive = false
        eventEndsAt = null
        debug.info("Event stopped, collapsing all points.")
        collapseAll("event-end")
        cancelTasks()
        MessageUtil.broadcast(config.messages.end)
    }

    fun shutdown(persistState: Boolean) {
        if (persistState) {
            debug.info("Shutdown: persistent mode, cancelling tasks without collapsing points.")
            cancelTasks()
            return
        }
        debug.info("Shutdown: session mode, stopping event.")
        stopEvent()
    }

    fun savePersistentState() {
        if (!config.event.persistent) return
        debug.info("Saving persistent state: active=$eventActive points=${points.size} endsAt=$eventEndsAt")
        persistenceManager.save(EventState(eventActive, eventEndsAt, points))
    }

    fun isInPoint(location: Location): EchoPoint? {
        val chunk = location.chunk
        return points.values.firstOrNull { it.contains(location.world.name, chunk.x, chunk.z) }
    }

    fun handlePlayerEnter(player: Player, point: EchoPoint) {
        val set = playerZones.computeIfAbsent(player.uniqueId) { mutableSetOf() }
        if (set.add(point.id)) {
            debug.info("Player ${player.name} entered point ${point.id} (${point.distortion}).")
            if (config.messages.enter.isNotBlank()) {
                MessageUtil.send(player, config.messages.enter)
            }
            addActivity(point, config.points.enterCost)
        }
    }

    fun handlePlayerExit(player: Player, point: EchoPoint) {
        playerZones[player.uniqueId]?.remove(point.id)
        debug.info("Player ${player.name} exited point ${point.id}.")
    }

    fun addActivity(point: EchoPoint, amount: Int) {
        if (amount <= 0) return
        synchronized(point) {
            point.activity += amount
            debug.info("Point ${point.id} activity +$amount => ${point.activity}/${point.activityLimit}.")
            if (point.activity >= point.activityLimit) {
                collapsePoint(point, "activity")
            }
        }
    }

    fun collapsePoint(point: EchoPoint, reason: String) {
        if (!points.containsKey(point.id)) return
        points.remove(point.id)
        playerZones.values.forEach { it.remove(point.id) }
        debug.info("Point ${point.id} collapsed (reason=$reason).")
        notifyCollapse(point)
    }

    private fun notifyCollapse(point: EchoPoint) {
        if (config.points.collapseLocal) {
            val radiusSquared = config.points.localMessageRadius * config.points.localMessageRadius
            Bukkit.getOnlinePlayers().forEach { player ->
                player.scheduler.run(plugin) { _ ->
                    if (player.world.name != point.worldName) return@run
                    val center = Location(player.world, point.centerX.toDouble(), player.location.y, point.centerZ.toDouble())
                    if (player.location.distanceSquared(center) <= radiusSquared) {
                        MessageUtil.send(player, config.messages.collapseLocal)
                    }
                }
            }
        }
        if (config.points.collapseGlobal) {
            MessageUtil.broadcast(config.messages.collapseGlobal)
        }
    }

    private fun collapseAll(reason: String) {
        debug.info("Collapsing all points (reason=$reason).")
        points.values.toList().forEach { collapsePoint(it, reason) }
    }

    fun activePoints(): Collection<EchoPoint> = points.values

    fun scheduleTasks() {
        cancelTasks()
        debug.info("Scheduling tasks: spawn=${config.points.spawnIntervalSeconds}s hint=${config.hints.intervalSeconds}s particle=${config.zoneEffects.particle.intervalSeconds}s sound=${config.zoneEffects.sound.intervalSeconds}s.")
        spawnTask = globalScheduler.runAtFixedRate(plugin, { _ -> spawnPoint() }, 20, config.points.spawnIntervalSeconds * 20)
        ttlTask = globalScheduler.runAtFixedRate(plugin, { _ -> checkTtl() }, 40, 40)
        hintTask = globalScheduler.runAtFixedRate(plugin, { _ -> broadcastHint() }, config.hints.intervalSeconds * 20, config.hints.intervalSeconds * 20)
        particleTask = globalScheduler.runAtFixedRate(plugin, { _ -> tickZoneParticles() }, 40, config.zoneEffects.particle.intervalSeconds * 20)
        soundTask = globalScheduler.runAtFixedRate(plugin, { _ -> tickZoneSounds() }, 40, config.zoneEffects.sound.intervalSeconds * 20)
        stayTask = globalScheduler.runAtFixedRate(plugin, { _ -> tickStayActivity() }, config.points.stayIntervalSeconds * 20, config.points.stayIntervalSeconds * 20)
        hungerTask = globalScheduler.runAtFixedRate(plugin, { _ -> tickHunger() }, config.hungerDrift.intervalSeconds * 20, config.hungerDrift.intervalSeconds * 20)
        tickBoostTask = globalScheduler.runAtFixedRate(plugin, { _ -> tickRandomBoost() }, 20, 20)
    }

    private fun cancelTasks() {
        listOf(spawnTask, ttlTask, hintTask, particleTask, soundTask, stayTask, hungerTask, tickBoostTask).forEach { it?.cancel() }
        spawnTask = null
        ttlTask = null
        hintTask = null
        particleTask = null
        soundTask = null
        stayTask = null
        hungerTask = null
        tickBoostTask = null
        debug.info("Cancelled all scheduled tasks.")
    }

    private fun checkTtl() {
        if (!eventActive) return
        val now = Instant.now()
        if (eventEndsAt != null && now.isAfter(eventEndsAt)) {
            debug.info("Event TTL reached, stopping event.")
            stopEvent()
            return
        }
        points.values.filter { it.isExpired(now) }.forEach { collapsePoint(it, "ttl") }
    }

    private fun spawnPoint() {
        if (!eventActive) return
        if (points.size >= config.points.maxActive) {
            debug.info("Spawn skipped: max active points reached (${points.size}/${config.points.maxActive}).")
            return
        }
        val world = pickWorld() ?: return
        val location = pickLocation(world) ?: return
        if (!isLocationValid(location, world)) {
            debug.info("Spawn rejected: invalid location ${location.blockX},${location.blockZ} in ${world.name}.")
            return
        }
        val distortion = pickDistortion() ?: return
        val ttlSeconds = random.nextInt(config.points.ttlRange.first, config.points.ttlRange.last + 1)
        val activityLimit = random.nextInt(config.points.activityLimitRange.first, config.points.activityLimitRange.last + 1)
        val now = Instant.now()
        val point = EchoPoint(UUID.randomUUID(), world.name, location.blockX, location.blockZ, now, now.plusSeconds(ttlSeconds.toLong()), distortion, activityLimit, 0)
        points[point.id] = point
        debug.info("Spawned point ${point.id} at ${point.centerX},${point.centerZ} in ${point.worldName} distortion=${point.distortion} ttl=${ttlSeconds}s limit=$activityLimit.")
    }

    private fun pickWorld(): World? {
        val worlds = if (config.points.allowedWorlds.isEmpty()) Bukkit.getWorlds() else config.points.allowedWorlds.mapNotNull { Bukkit.getWorld(it) }
        val picked = worlds.randomOrNull()
        if (picked == null) {
            debug.info("Spawn aborted: no valid worlds.")
        }
        return picked
    }

    private fun pickLocation(world: World): Location? {
        return when (config.points.coordinateMode.lowercase()) {
            "ring" -> {
                val radius = random.nextInt(config.points.ring.minRadius, config.points.ring.maxRadius + 1)
                val angle = random.nextDouble(0.0, Math.PI * 2)
                val x = config.points.ring.centerX + (cos(angle) * radius).toInt()
                val z = config.points.ring.centerZ + (sin(angle) * radius).toInt()
                Location(world, x.toDouble(), 64.0, z.toDouble())
            }
            "custom-list" -> {
                val entry = config.points.customList.randomOrNull()
                if (entry == null) {
                    debug.info("Custom list empty, cannot spawn point.")
                    null
                } else {
                    Location(world, entry.x.toDouble(), 64.0, entry.z.toDouble())
                }
            }
            else -> {
                val x = random.nextInt(config.points.randomRange.minX, config.points.randomRange.maxX + 1)
                val z = random.nextInt(config.points.randomRange.minZ, config.points.randomRange.maxZ + 1)
                Location(world, x.toDouble(), 64.0, z.toDouble())
            }
        }
    }

    private fun isLocationValid(location: Location, world: World): Boolean {
        val chunkX = location.blockX shr 4
        val chunkZ = location.blockZ shr 4
        for (point in points.values) {
            if (point.worldName != world.name) continue
            val distance = distance2D(location.blockX, location.blockZ, point.centerX, point.centerZ)
            if (distance < config.points.minDistanceBlocks) return false
            if (abs((point.centerX shr 4) - chunkX) <= 2 && abs((point.centerZ shr 4) - chunkZ) <= 2) {
                return false
            }
        }
        return true
    }

    private fun distance2D(x1: Int, z1: Int, x2: Int, z2: Int): Double {
        val dx = (x1 - x2).toDouble()
        val dz = (z1 - z2).toDouble()
        return kotlin.math.sqrt(dx * dx + dz * dz)
    }

    private fun pickDistortion(): DistortionType? {
        val weighted = mutableListOf<Pair<DistortionType, Int>>()
        if (config.oreDropShift.enabled) weighted.add(DistortionType.ORE_DROP_SHIFT to config.oreDropShift.weight)
        if (config.mechanicLock.enabled) weighted.add(DistortionType.MECHANIC_LOCK to config.mechanicLock.weight)
        if (config.randomTickBoost.enabled) weighted.add(DistortionType.RANDOM_TICK_BOOST to config.randomTickBoost.weight)
        if (config.hungerDrift.enabled) weighted.add(DistortionType.HUNGER_DRIFT to config.hungerDrift.weight)
        if (config.placementDecay.enabled) weighted.add(DistortionType.PLACEMENT_DECAY to config.placementDecay.weight)
        val total = weighted.sumOf { it.second }
        if (total <= 0) {
            debug.info("No distortions enabled for spawn.")
            return null
        }
        var roll = random.nextInt(total) + 1
        for ((type, weight) in weighted) {
            roll -= weight
            if (roll <= 0) {
                debug.info("Picked distortion $type.")
                return type
            }
        }
        val fallback = weighted.firstOrNull()?.first
        if (fallback != null) {
            debug.info("Fallback distortion $fallback.")
        }
        return fallback
    }

    private fun broadcastHint() {
        if (!eventActive) return
        val hints = config.messages.hints
        if (hints.isEmpty()) return
        val message = config.messages.hintPrefix + hints.random()
        debug.info("Broadcasting hint.")
        MessageUtil.broadcast(message)
    }

    private fun tickZoneParticles() {
        if (!eventActive) return
        points.values.forEach { point ->
            val world = Bukkit.getWorld(point.worldName) ?: return@forEach
            val chunkX = point.centerX shr 4
            val chunkZ = point.centerZ shr 4
            world.regionScheduler.run(plugin, chunkX, chunkZ) { _ ->
                val center = Location(world, point.centerX.toDouble(), world.spawnLocation.y.toDouble(), point.centerZ.toDouble())
                world.spawnParticle(config.zoneEffects.particle.type, center, config.zoneEffects.particle.count, config.zoneEffects.particle.radius, 1.0, config.zoneEffects.particle.radius)
            }
        }
    }

    private fun tickZoneSounds() {
        if (!eventActive) return
        points.values.forEach { point ->
            val world = Bukkit.getWorld(point.worldName) ?: return@forEach
            val chunkX = point.centerX shr 4
            val chunkZ = point.centerZ shr 4
            world.regionScheduler.run(plugin, chunkX, chunkZ) { _ ->
                val center = Location(world, point.centerX.toDouble(), world.spawnLocation.y.toDouble(), point.centerZ.toDouble())
                world.playSound(center, config.zoneEffects.sound.type, config.zoneEffects.sound.volume, config.zoneEffects.sound.pitch)
            }
        }
    }

    private fun tickStayActivity() {
        if (!eventActive) return
        val stayCost = config.points.stayCost
        if (stayCost <= 0) return
        for (player in Bukkit.getOnlinePlayers()) {
            player.scheduler.run(plugin) { _ ->
                val point = isInPoint(player.location) ?: return@run
                debug.info("Stay activity: ${player.name} in point ${point.id}.")
                addActivity(point, stayCost)
            }
        }
    }

    private fun tickHunger() {
        if (!eventActive || !config.hungerDrift.enabled) return
        for (player in Bukkit.getOnlinePlayers()) {
            player.scheduler.run(plugin) { _ ->
                val point = isInPoint(player.location) ?: return@run
                if (point.distortion != DistortionType.HUNGER_DRIFT) return@run
                player.exhaustion = (player.exhaustion + config.hungerDrift.exhaustionDelta).coerceAtLeast(0f)
                debug.info("Hunger drift: ${player.name} exhaustion +${config.hungerDrift.exhaustionDelta}.")
                addActivity(point, config.hungerDrift.activityGain)
            }
        }
    }

    private fun tickRandomBoost() {
        if (!eventActive || !config.randomTickBoost.enabled) return
        val remainingChecks = AtomicInteger(config.randomTickBoost.maxChecksPerTick)
        for (player in Bukkit.getOnlinePlayers()) {
            player.scheduler.run(plugin) { _ ->
                if (remainingChecks.get() <= 0) return@run
                val point = isInPoint(player.location) ?: return@run
                if (point.distortion != DistortionType.RANDOM_TICK_BOOST) return@run
                val base = player.location
                repeat(config.randomTickBoost.checksPerPlayer) {
                    if (remainingChecks.getAndDecrement() <= 0) return@repeat
                    val offsetX = random.nextInt(-4, 5)
                    val offsetZ = random.nextInt(-4, 5)
                    val offsetY = random.nextInt(-1, 2)
                    val target = base.clone().add(offsetX.toDouble(), offsetY.toDouble(), offsetZ.toDouble())
                    val block = target.block
                    if (!config.randomTickBoost.blocks.contains(block.type)) return@repeat
                    if (random.nextDouble() > config.randomTickBoost.chance) return@repeat
                    val data = block.blockData
                    if (data is org.bukkit.block.data.Ageable) {
                        val max = data.maximumAge
                        if (data.age < max) {
                            data.age = (data.age + 1).coerceAtMost(max)
                            block.blockData = data
                            debug.info("Boosted growth at ${block.x},${block.y},${block.z} for ${player.name}.")
                            addActivity(point, config.randomTickBoost.activityGain)
                        }
                    } else if (block.type == org.bukkit.Material.SUGAR_CANE || block.type == org.bukkit.Material.BAMBOO) {
                        val above = block.location.clone().add(0.0, 1.0, 0.0).block
                        if (above.type.isAir) {
                            above.type = block.type
                            debug.info("Boosted vertical growth at ${block.x},${block.y},${block.z} for ${player.name}.")
                            addActivity(point, config.randomTickBoost.activityGain)
                        }
                    }
                }
            }
        }
    }

    fun scheduleDecay(point: EchoPoint, playerId: UUID, location: Location, type: org.bukkit.Material) {
        val world = location.world
        val chunkX = location.blockX shr 4
        val chunkZ = location.blockZ shr 4
        world.regionScheduler.runDelayed(plugin, chunkX, chunkZ, { _ ->
            if (!points.containsKey(point.id)) return@runDelayed
            val block = location.block
            if (block.type != type) return@runDelayed
            block.type = org.bukkit.Material.AIR
            val particleLocation = location.clone().add(0.5, 0.5, 0.5)
            world.spawnParticle(config.placementDecay.particles.type, particleLocation, config.placementDecay.particles.count, 0.3, 0.3, 0.3)
            world.playSound(location, config.placementDecay.sound.type, config.placementDecay.sound.volume, config.placementDecay.sound.pitch)
            if (config.placementDecay.returnItem) {
                val item = type.createItemStack()
                val player = Bukkit.getPlayer(playerId)
                if (player != null) {
                    player.scheduler.run(plugin) { _ ->
                        val leftover = player.inventory.addItem(item)
                        if (leftover.isNotEmpty()) {
                            world.regionScheduler.run(plugin, chunkX, chunkZ) { _ ->
                                leftover.values.forEach { world.dropItemNaturally(location, it) }
                            }
                        }
                    }
                } else {
                    world.dropItemNaturally(location, item)
                }
            }
            debug.info("Placement decay executed at ${location.blockX},${location.blockY},${location.blockZ} for point ${point.id}.")
            addActivity(point, config.placementDecay.activityGain)
        }, config.placementDecay.delaySeconds * 20)
    }
}
