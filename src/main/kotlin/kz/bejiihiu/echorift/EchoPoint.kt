package kz.bejiihiu.echorift

import java.time.Instant
import java.util.UUID

enum class DistortionType {
    ORE_DROP_SHIFT,
    MECHANIC_LOCK,
    RANDOM_TICK_BOOST,
    HUNGER_DRIFT,
    PLACEMENT_DECAY
}

data class EchoPoint(
    val id: UUID,
    val worldName: String,
    val centerX: Int,
    val centerZ: Int,
    val createdAt: Instant,
    var endsAt: Instant,
    val distortion: DistortionType,
    val activityLimit: Int,
    var activity: Int
) {
    fun isExpired(now: Instant): Boolean = now.isAfter(endsAt)
    fun contains(world: String, chunkX: Int, chunkZ: Int): Boolean {
        if (world != worldName) return false
        val centerChunkX = centerX shr 4
        val centerChunkZ = centerZ shr 4
        val minChunkX = centerChunkX - 1
        val maxChunkX = centerChunkX + 2
        val minChunkZ = centerChunkZ - 1
        val maxChunkZ = centerChunkZ + 2
        return chunkX in minChunkX..maxChunkX && chunkZ in minChunkZ..maxChunkZ
    }
}
