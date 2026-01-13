package kz.bejiihiu.echorift

class DebugLogger(private val plugin: Main, private val config: PluginConfig) {
    fun info(message: String) {
        if (!config.debug.enabled) return
        plugin.logger.info("[EchoRift] $message")
    }

    fun warn(message: String) {
        if (!config.debug.enabled) return
        plugin.logger.warning("[EchoRift] $message")
    }
}
