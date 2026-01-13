package kz.bejiihiu.echorift

import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.time.Instant
import java.util.UUID

class PersistenceManager(plugin: Main) {
    private val file = File(plugin.dataFolder, "data.yml")

    fun save(state: EventState) {
        val config = YamlConfiguration()
        config.set("event.active", state.active)
        config.set("event.ends-at", state.endsAt?.toEpochMilli())
        val list = state.points.values.map { point ->
            mapOf(
                "id" to point.id.toString(),
                "world" to point.worldName,
                "center-x" to point.centerX,
                "center-z" to point.centerZ,
                "created-at" to point.createdAt.toEpochMilli(),
                "ends-at" to point.endsAt.toEpochMilli(),
                "distortion" to point.distortion.name,
                "activity" to point.activity,
                "activity-limit" to point.activityLimit
            )
        }
        config.set("points", list)
        config.save(file)
    }

    fun load(): EventState? {
        if (!file.exists()) return null
        val config = YamlConfiguration.loadConfiguration(file)
        val active = config.getBoolean("event.active", false)
        val endsAt = config.getLong("event.ends-at", 0L).takeIf { it > 0 }?.let { Instant.ofEpochMilli(it) }
        val pointsSection = config.getList("points") ?: emptyList<Any>()
        val points = pointsSection.mapNotNull { entry ->
            val map = entry as? Map<*, *> ?: return@mapNotNull null
            val id = (map["id"] as? String)?.let(UUID::fromString) ?: return@mapNotNull null
            val world = map["world"] as? String ?: return@mapNotNull null
            val centerX = (map["center-x"] as? Int) ?: (map["center-x"] as? Number)?.toInt() ?: return@mapNotNull null
            val centerZ = (map["center-z"] as? Int) ?: (map["center-z"] as? Number)?.toInt() ?: return@mapNotNull null
            val createdAt = (map["created-at"] as? Number)?.toLong()?.let(Instant::ofEpochMilli) ?: Instant.now()
            val ends = (map["ends-at"] as? Number)?.toLong()?.let(Instant::ofEpochMilli) ?: Instant.now()
            val distortion = (map["distortion"] as? String)?.let { DistortionType.valueOf(it) } ?: return@mapNotNull null
            val activity = (map["activity"] as? Number)?.toInt() ?: 0
            val activityLimit = (map["activity-limit"] as? Number)?.toInt() ?: 0
            EchoPoint(id, world, centerX, centerZ, createdAt, ends, distortion, activityLimit, activity)
        }.associateBy { it.id }.toMutableMap()
        return EventState(active, endsAt, points)
    }
}

data class EventState(
    var active: Boolean,
    var endsAt: Instant?,
    val points: MutableMap<UUID, EchoPoint>
)
