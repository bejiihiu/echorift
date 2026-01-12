package kz.bejiihiu.echorift

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import org.bukkit.entity.Player

object MessageUtil {
    private val miniMessage = MiniMessage.miniMessage()

    fun deserialize(input: String): Component = miniMessage.deserialize(input)

    fun send(player: Player, message: String) {
        if (message.isBlank()) return
        player.sendMessage(deserialize(message))
    }

    fun broadcast(message: String) {
        if (message.isBlank()) return
        Bukkit.getServer().broadcast(deserialize(message))
    }
}
