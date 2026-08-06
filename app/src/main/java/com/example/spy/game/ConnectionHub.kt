package com.example.spy.game

import io.ktor.websocket.CloseReason
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.send
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/**
 * Держит активные WebSocket-соединения игроков и умеет слать им сообщения.
 * playerId -> сессия. Одно соединение на игрока (новое вытесняет старое).
 */
class ConnectionHub(private val json: Json) {

    private val sessions = ConcurrentHashMap<String, DefaultWebSocketSession>()
    private val sendLock = Mutex()

    fun register(playerId: String, session: DefaultWebSocketSession) {
        sessions[playerId] = session
    }

    /**
     * Снимает сессию, ТОЛЬКО если это всё ещё та же самая сессия.
     * Возвращает true, если сессия была активной и снята; false — если её уже
     * вытеснило новое подключение (переподключение игрока). Это защищает от гонки,
     * когда закрытие старого сокета ошибочно помечало бы игрока оффлайн.
     */
    fun unregister(playerId: String, session: DefaultWebSocketSession): Boolean {
        return sessions.remove(playerId, session)
    }

    suspend fun sendTo(playerId: String, message: ServerMessage) {
        val session = sessions[playerId] ?: return
        val text = json.encodeToString(ServerMessage.serializer(), message)
        runCatching { sendLock.withLock { session.send(Frame.Text(text)) } }
    }

    suspend fun broadcast(message: ServerMessage) {
        val text = json.encodeToString(ServerMessage.serializer(), message)
        sessions.values.forEach { session ->
            runCatching { sendLock.withLock { session.send(Frame.Text(text)) } }
        }
    }

    suspend fun broadcast(builder: (playerId: String) -> ServerMessage?) {
        sessions.forEach { (pid, session) ->
            val msg = builder(pid) ?: return@forEach
            val text = json.encodeToString(ServerMessage.serializer(), msg)
            runCatching { sendLock.withLock { session.send(Frame.Text(text)) } }
        }
    }

    suspend fun closePlayer(playerId: String, reason: String = "Исключён ведущим") {
        val session = sessions.remove(playerId) ?: return
        runCatching { session.close(CloseReason(CloseReason.Codes.NORMAL, reason)) }
    }

    suspend fun closeAll() {
        sessions.values.forEach {
            runCatching { it.close(CloseReason(CloseReason.Codes.NORMAL, "Сервер остановлен")) }
        }
        sessions.clear()
    }

    fun connectedPlayerIds(): Set<String> = sessions.keys.toSet()
}
