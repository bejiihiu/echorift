package kz.bejiihiu.echorift

import org.bukkit.plugin.java.JavaPlugin

class Main : JavaPlugin() {
    private lateinit var configWrapper: PluginConfig
    private lateinit var manager: EchoEventManager
    private lateinit var debugLogger: DebugLogger

    override fun onEnable() {
        saveDefaultConfig()
        configWrapper = PluginConfig(this)
        debugLogger = DebugLogger(this, configWrapper)
        manager = EchoEventManager(this, configWrapper, debugLogger)
        manager.loadPersistentState()

        server.pluginManager.registerEvents(EchoListener(configWrapper, manager, debugLogger), this)

        if (manager.eventActive) {
            debugLogger.info("Event already active from persistence, scheduling tasks.")
            manager.scheduleTasks()
        } else {
            debugLogger.info("Event not active on boot, checking schedule.")
            manager.startIfScheduled()
        }
    }

    override fun onDisable() {
        debugLogger.info("Plugin disabling, shutting down event manager.")
        manager.shutdown(configWrapper.event.persistent)
        manager.savePersistentState()
    }
}
