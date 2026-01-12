package kz.bejiihiu.echorift

import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.configuration.ConfigurationSection
import java.time.LocalTime
import java.time.ZoneId

class PluginConfig(private val plugin: Main) {
    val debug: DebugSettings = DebugSettings(plugin)
    val messages: Messages = Messages(plugin)
    val event: EventSettings = EventSettings(plugin)
    val points: PointSettings = PointSettings(plugin)
    val hints: HintSettings = HintSettings(plugin)
    val zoneEffects: ZoneEffects = ZoneEffects(plugin)
    val oreDropShift: OreDropShiftSettings = OreDropShiftSettings(plugin)
    val mechanicLock: MechanicLockSettings = MechanicLockSettings(plugin)
    val randomTickBoost: RandomTickBoostSettings = RandomTickBoostSettings(plugin)
    val hungerDrift: HungerDriftSettings = HungerDriftSettings(plugin)
    val placementDecay: PlacementDecaySettings = PlacementDecaySettings(plugin)
}

class DebugSettings(private val plugin: Main) {
    val enabled: Boolean = plugin.config.getBoolean("debug.enabled", true)
}

class Messages(private val plugin: Main) {
    val start: String = plugin.config.getString("messages.start") ?: ""
    val end: String = plugin.config.getString("messages.end") ?: ""
    val enter: String = plugin.config.getString("messages.enter") ?: ""
    val deny: String = plugin.config.getString("messages.deny") ?: ""
    val oreReveal: String = plugin.config.getString("messages.ore-reveal") ?: ""
    val collapseLocal: String = plugin.config.getString("messages.collapse-local") ?: ""
    val collapseGlobal: String = plugin.config.getString("messages.collapse-global") ?: ""
    val hintPrefix: String = plugin.config.getString("messages.hint-prefix") ?: ""
    val hints: List<String> = plugin.config.getStringList("messages.hints")
}

class EventSettings(private val plugin: Main) {
    val autoStart: Boolean = plugin.config.getBoolean("event.auto-start", true)
    val startTime: LocalTime = LocalTime.parse(plugin.config.getString("event.start-time") ?: "18:00")
    val zoneId: ZoneId = when (val value = plugin.config.getString("event.timezone") ?: "system") {
        "system" -> ZoneId.systemDefault()
        else -> runCatching { ZoneId.of(value) }.getOrDefault(ZoneId.systemDefault())
    }
    val persistent: Boolean = plugin.config.getBoolean("event.persistent", false)
}

class PointSettings(private val plugin: Main) {
    val maxActive: Int = plugin.config.getInt("points.max-active", 3)
    val spawnIntervalSeconds: Long = plugin.config.getLong("points.spawn-interval-seconds", 1800)
    val allowedWorlds: List<String> = plugin.config.getStringList("points.allowed-worlds")
    val minDistanceBlocks: Double = plugin.config.getDouble("points.min-distance-blocks", 500.0)
    val coordinateMode: String = plugin.config.getString("points.coordinate-mode") ?: "random-range"
    val randomRange: RangeSettings = RangeSettings(plugin.config.getConfigurationSection("points.random-range"))
    val ring: RingSettings = RingSettings(plugin.config.getConfigurationSection("points.ring"))
    val customList: List<PointLocation> = parseCustomList()
    val ttlRange: IntRange = rangeFrom(plugin.config.getConfigurationSection("points.ttl-seconds"), 900, 1800)
    val activityLimitRange: IntRange = rangeFrom(plugin.config.getConfigurationSection("points.activity-limit"), 60, 140)
    val enterCost: Int = plugin.config.getInt("points.enter-cost", 0)
    val stayCost: Int = plugin.config.getInt("points.stay-cost", 1)
    val stayIntervalSeconds: Long = plugin.config.getLong("points.stay-interval-seconds", 60)
    val localMessageRadius: Double = plugin.config.getDouble("points.local-message-radius", 64.0)
    val collapseGlobal: Boolean = plugin.config.getBoolean("points.collapse-global", true)
    val collapseLocal: Boolean = plugin.config.getBoolean("points.collapse-local", true)

    private fun rangeFrom(section: ConfigurationSection?, minDefault: Int, maxDefault: Int): IntRange {
        val min = section?.getInt("min", minDefault) ?: minDefault
        val max = section?.getInt("max", maxDefault) ?: maxDefault
        return min..max
    }

    private fun parseCustomList(): List<PointLocation> {
        val list = plugin.config.getStringList("points.custom-list")
        return list.mapNotNull { entry ->
            val parts = entry.split(",")
            if (parts.size < 2) return@mapNotNull null
            val x = parts[0].trim().toIntOrNull() ?: return@mapNotNull null
            val z = parts[1].trim().toIntOrNull() ?: return@mapNotNull null
            PointLocation(x, z)
        }
    }
}

data class PointLocation(val x: Int, val z: Int)

class RangeSettings(section: ConfigurationSection?) {
    val minX: Int = section?.getInt("min-x", -2000) ?: -2000
    val maxX: Int = section?.getInt("max-x", 2000) ?: 2000
    val minZ: Int = section?.getInt("min-z", -2000) ?: -2000
    val maxZ: Int = section?.getInt("max-z", 2000) ?: 2000
}

class RingSettings(section: ConfigurationSection?) {
    val centerX: Int = section?.getInt("center-x", 0) ?: 0
    val centerZ: Int = section?.getInt("center-z", 0) ?: 0
    val minRadius: Int = section?.getInt("min-radius", 500) ?: 500
    val maxRadius: Int = section?.getInt("max-radius", 1500) ?: 1500
}

class HintSettings(private val plugin: Main) {
    val intervalSeconds: Long = plugin.config.getLong("hints.interval-seconds", 600)
}

class ZoneEffects(private val plugin: Main) {
    val particle: ParticleSettings = ParticleSettings(plugin.config.getConfigurationSection("zone-effects.particle"))
    val sound: SoundSettings = SoundSettings(plugin.config.getConfigurationSection("zone-effects.sound"))
}

class ParticleSettings(section: ConfigurationSection?) {
    val type: Particle = Particle.valueOf(section?.getString("type") ?: "PORTAL")
    val count: Int = section?.getInt("count", 16) ?: 16
    val radius: Double = section?.getDouble("radius", 6.0) ?: 6.0
    val intervalSeconds: Long = section?.getLong("interval-seconds", 12) ?: 12
}

class SoundSettings(section: ConfigurationSection?) {
    val type: Sound = Sound.valueOf(section?.getString("type") ?: "BLOCK_AMETHYST_BLOCK_CHIME")
    val volume: Float = (section?.getDouble("volume", 0.8) ?: 0.8).toFloat()
    val pitch: Float = (section?.getDouble("pitch", 1.2) ?: 1.2).toFloat()
    val intervalSeconds: Long = section?.getLong("interval-seconds", 20) ?: 20
}

class OreDropShiftSettings(private val plugin: Main) {
    val enabled: Boolean = plugin.config.getBoolean("ore-drop-shift.enabled", true)
    val weight: Int = plugin.config.getInt("ore-drop-shift.weight", 4)
    val chance: Double = plugin.config.getDouble("ore-drop-shift.chance", 0.7)
    val masking: Boolean = plugin.config.getBoolean("ore-drop-shift.masking", true)
    val extraToolDamage: Int = plugin.config.getInt("ore-drop-shift.extra-tool-damage", 1)
    val activityGain: Int = plugin.config.getInt("ore-drop-shift.activity-gain", 2)
    val mappings: Map<Material, DropMapping> = buildMappings()

    private fun buildMappings(): Map<Material, DropMapping> {
        val section = plugin.config.getConfigurationSection("ore-drop-shift.mappings") ?: return emptyMap()
        return section.getKeys(false).mapNotNull { key ->
            val mat = Material.matchMaterial(key) ?: return@mapNotNull null
            val mapping = section.getConfigurationSection(key) ?: return@mapNotNull null
            val drop = Material.matchMaterial(mapping.getString("drop") ?: "") ?: return@mapNotNull null
            val min = mapping.getInt("min", 1)
            val max = mapping.getInt("max", min)
            val multiplier = mapping.getDouble("multiplier", 1.0)
            mat to DropMapping(drop, min, max, multiplier)
        }.toMap()
    }
}

class DropMapping(val drop: Material, val min: Int, val max: Int, val multiplier: Double)

class MechanicLockSettings(private val plugin: Main) {
    val enabled: Boolean = plugin.config.getBoolean("mechanic-lock.enabled", true)
    val weight: Int = plugin.config.getInt("mechanic-lock.weight", 3)
    val activityGain: Int = plugin.config.getInt("mechanic-lock.activity-gain", 1)
    val knockbackEnabled: Boolean = plugin.config.getBoolean("mechanic-lock.knockback.enabled", true)
    val knockbackStrength: Double = plugin.config.getDouble("mechanic-lock.knockback.strength", 0.4)
    val blockedInventories: Set<String> = plugin.config.getStringList("mechanic-lock.blocked-inventories").map { it.uppercase() }.toSet()
    val blockedBlocks: Set<Material> = plugin.config.getStringList("mechanic-lock.blocked-blocks").mapNotNull { Material.matchMaterial(it) }.toSet()
    val substituteInventories: List<String> = plugin.config.getStringList("mechanic-lock.substitute-inventories").map { it.uppercase() }
}

class RandomTickBoostSettings(private val plugin: Main) {
    val enabled: Boolean = plugin.config.getBoolean("random-tick-boost.enabled", true)
    val weight: Int = plugin.config.getInt("random-tick-boost.weight", 3)
    val chance: Double = plugin.config.getDouble("random-tick-boost.chance", 0.35)
    val checksPerPlayer: Int = plugin.config.getInt("random-tick-boost.checks-per-player", 6)
    val maxChecksPerTick: Int = plugin.config.getInt("random-tick-boost.max-checks-per-tick", 200)
    val activityGain: Int = plugin.config.getInt("random-tick-boost.activity-gain", 1)
    val blocks: Set<Material> = plugin.config.getStringList("random-tick-boost.blocks").mapNotNull { Material.matchMaterial(it) }.toSet()
}

class HungerDriftSettings(private val plugin: Main) {
    val enabled: Boolean = plugin.config.getBoolean("hunger-drift.enabled", true)
    val weight: Int = plugin.config.getInt("hunger-drift.weight", 3)
    val intervalSeconds: Long = plugin.config.getLong("hunger-drift.interval-seconds", 6)
    val exhaustionDelta: Float = plugin.config.getDouble("hunger-drift.exhaustion-delta", 0.4).toFloat()
    val activityGain: Int = plugin.config.getInt("hunger-drift.activity-gain", 1)
}

class PlacementDecaySettings(private val plugin: Main) {
    val enabled: Boolean = plugin.config.getBoolean("placement-decay.enabled", true)
    val weight: Int = plugin.config.getInt("placement-decay.weight", 2)
    val chance: Double = plugin.config.getDouble("placement-decay.chance", 0.3)
    val delaySeconds: Long = plugin.config.getLong("placement-decay.delay-seconds", 20)
    val returnItem: Boolean = plugin.config.getBoolean("placement-decay.return-item", true)
    val activityGain: Int = plugin.config.getInt("placement-decay.activity-gain", 1)
    val whitelist: Set<Material> = plugin.config.getStringList("placement-decay.whitelist").mapNotNull { Material.matchMaterial(it) }.toSet()
    val particles: ParticleSettings = ParticleSettings(plugin.config.getConfigurationSection("placement-decay.particles"))
    val sound: SoundSettings = SoundSettings(plugin.config.getConfigurationSection("placement-decay.particles.sound"))
}
