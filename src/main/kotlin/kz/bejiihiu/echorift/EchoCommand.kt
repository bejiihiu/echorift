package kz.bejiihiu.echorift

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

class EchoCommand(
    private val plugin: Main,
    private val manager: EchoEventManager,
    private val config: PluginConfig,
    private val debug: DebugLogger
) : CommandExecutor, TabCompleter {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        debug.info("Выполнена команда /$label с аргументами: ${args.joinToString(" ")} (от ${sender.name}).")
        if (!sender.isOp) {
            sender.sendMessage("§cТолько операторы могут использовать эту команду.")
            debug.info("Команда /$label отклонена: ${sender.name} не оператор.")
            return true
        }

        if (args.isEmpty()) {
            sendHelp(sender, label)
            return true
        }

        return when (args[0].lowercase()) {
            "start" -> {
                val endsAt = parseEndInstant(args.getOrNull(1))
                debug.info("Параметры старта: rawMinutes=${args.getOrNull(1)} рассчитанноеОкончание=$endsAt.")
                debug.info("OP-команда: запуск события, endsAt=$endsAt, инициатор=${sender.name}.")
                manager.startEvent(endsAt)
                sender.sendMessage("§aСобытие запущено.")
                true
            }
            "stop" -> {
                debug.info("OP-команда: остановка события, инициатор=${sender.name}.")
                manager.stopEvent()
                sender.sendMessage("§cСобытие остановлено.")
                true
            }
            "status" -> {
                val active = manager.eventActive
                val points = manager.activePointsCount()
                val endsAt = manager.eventEndsAt
                val remaining = endsAt?.let { java.time.Duration.between(Instant.now(), it).seconds } ?: 0
                debug.info("Статус: активен=$active, точек=$points, окончание=$endsAt, осталось=${remaining}s.")
                sender.sendMessage("§eEchoRift: активен=$active, точек=$points, окончание=$endsAt, осталось=${remaining}s.")
                debug.info("OP-команда: статус запрошен (${sender.name}).")
                true
            }
            "spawn" -> {
                debug.info("OP-команда: форс-спавн точки, инициатор=${sender.name}.")
                val spawned = manager.forceSpawnPoint()
                debug.info("Результат форс-спавна: spawned=$spawned.")
                if (spawned) {
                    sender.sendMessage("§aТочка попыталась заспавниться (смотри логи).")
                } else {
                    sender.sendMessage("§cНе удалось создать точку (ограничения/валидация).")
                }
                true
            }
            "collapse" -> {
                debug.info("OP-команда: коллапс всех точек, инициатор=${sender.name}.")
                manager.collapseAllPoints(CollapseReason.OP_COMMAND)
                sender.sendMessage("§cВсе точки схлопнуты.")
                true
            }
            "reload" -> {
                debug.info("OP-команда: перезагрузка конфигурации, инициатор=${sender.name}.")
                plugin.reloadConfig()
                sender.sendMessage("§aКонфигурация перезагружена. Для полного эффекта перезапусти сервер.")
                true
            }
            else -> {
                debug.warn("Неизвестная подкоманда: ${args[0]}.")
                sendHelp(sender, label)
                true
            }
        }
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>
    ): List<String> {
        if (!sender.isOp) return emptyList()
        if (args.size == 1) {
            return listOf("start", "stop", "status", "spawn", "collapse", "reload")
                .filter { it.startsWith(args[0], ignoreCase = true) }
        }
        if (args.size == 2 && args[0].equals("start", ignoreCase = true)) {
            return listOf("10", "30", "60", "120")
                .filter { it.startsWith(args[1]) }
        }
        return emptyList()
    }

    private fun sendHelp(sender: CommandSender, label: String) {
        sender.sendMessage("§6/$label start [минуты]§7 — запустить событие.")
        sender.sendMessage("§6/$label stop§7 — остановить событие.")
        sender.sendMessage("§6/$label status§7 — показать статус.")
        sender.sendMessage("§6/$label spawn§7 — форсировать спавн точки.")
        sender.sendMessage("§6/$label collapse§7 — схлопнуть все точки.")
        sender.sendMessage("§6/$label reload§7 — перезагрузить конфиг.")
    }

    private fun parseEndInstant(rawMinutes: String?): Instant? {
        val minutes = rawMinutes?.toLongOrNull()
        if (minutes != null && minutes > 0) {
            return Instant.now().plusSeconds(minutes * 60)
        }
        val zone = config.event.zoneId
        val today = LocalDate.now(zone)
        val end = LocalDateTime.of(today, java.time.LocalTime.of(23, 59))
        return end.atZone(zone).toInstant()
    }
}
