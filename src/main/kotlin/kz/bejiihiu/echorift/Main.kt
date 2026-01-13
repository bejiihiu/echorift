package kz.bejiihiu.echorift

import org.bukkit.plugin.java.JavaPlugin

class Main : JavaPlugin() {
    private lateinit var configWrapper: PluginConfig
    private lateinit var manager: EchoEventManager
    private lateinit var debugLogger: DebugLogger

    override fun onEnable() {
        logger.info("[EchoRift] Запуск плагина.")
        saveDefaultConfig()
        configWrapper = PluginConfig(this)
        debugLogger = DebugLogger(this, configWrapper)
        manager = EchoEventManager(this, configWrapper, debugLogger)
        manager.loadPersistentState()

        server.pluginManager.registerEvents(EchoListener(configWrapper, manager, debugLogger), this)
        getCommand("echorift")?.let { command ->
            val executor = EchoCommand(this, manager, configWrapper, debugLogger)
            command.setExecutor(executor)
            command.tabCompleter = executor
        }

        if (manager.eventActive) {
            debugLogger.info("Событие уже активно из сохранения, запускаем задачи.")
            manager.scheduleTasks()
        } else {
            debugLogger.info("Событие не активно при старте, проверяем расписание.")
            manager.startIfScheduled()
        }
    }

    override fun onDisable() {
        debugLogger.info("Плагин выключается, останавливаем менеджер событий.")
        manager.shutdown(configWrapper.event.persistent)
        manager.savePersistentState()
        logger.info("[EchoRift] Плагин остановлен.")
    }
}
