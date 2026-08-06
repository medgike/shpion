package com.example.spy.game

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Протокол обмена сообщениями хост-сервер <-> браузер игрока по WebSocket.
 * Всё сериализуется в JSON (kotlinx.serialization) с дискриминатором "type".
 *
 * Клиент -> Сервер: [ClientMessage]
 * Сервер -> Клиент: [ServerMessage]
 */

// ---------- Клиент -> Сервер ----------

@Serializable
sealed class ClientMessage {
    /** Первый кадр после подключения: игрок называет имя (или переподключается по токену). */
    @Serializable
    @SerialName("Join")
    data class Join(val name: String, val token: String? = null) : ClientMessage()

    /** Игрок проголосовал за подозреваемого (его playerId). */
    @Serializable
    @SerialName("Vote")
    data class Vote(val suspectId: String) : ClientMessage()

    /** Шпион пытается угадать слово мирных жителей (по желанию). */
    @Serializable
    @SerialName("SpyGuess")
    data class SpyGuess(val word: String) : ClientMessage()

    /** Обвинённый в фазе «последнего слова» отказывается от догадки («я мирный»). */
    @Serializable
    @SerialName("SkipGuess")
    data object SkipGuess : ClientMessage()

    /** Простой пинг для поддержания соединения. */
    @Serializable
    @SerialName("Ping")
    data object Ping : ClientMessage()
}

// ---------- Сервер -> Клиент ----------

@Serializable
sealed class ServerMessage {
    /** Подтверждение входа: сервер закрепил за игроком id и токен для переподключения. */
    @Serializable @SerialName("Joined")
    data class Joined(val playerId: String, val token: String, val isHost: Boolean) : ServerMessage()

    /** Актуальный список игроков в лобби/игре. */
    @Serializable @SerialName("Lobby")
    data class Lobby(
        val players: List<PlayerView>,
        val phase: String,
        val minPlayers: Int,
        val spyCount: Int = 1,
        val hasMrX: Boolean = false,
    ) : ServerMessage()

    /**
     * Личная выдача роли в начале раунда.
     * Каждый игрок получает СВОЁ слово. Шпион не помечается явно — он просто
     * получает другое слово и должен сам догадаться, что он лишний.
     */
    @Serializable @SerialName("RoleAssignment")
    data class RoleAssignment(
        val word: String,
        val category: String,
        val youMayBeSpy: Boolean, // всегда true у всех — чтобы никто не расслаблялся
        val roundNumber: Int,
        /**
         * Явный признак Mr. X. Раньше клиенты вычисляли роль сравнением word === "???",
         * из-за чего любое изменение строки-заглушки молча ломало половину экранов.
         * Мирным и шпионам сюда всегда приходит false.
         */
        val isMrX: Boolean = false,
        // Варианты слова мирных для догадки. Список ОДИНАКОВЫЙ у всех игроков,
        // поэтому сам факт его наличия никого не выдаёт.
        val guessOptions: List<String> = emptyList(),
    ) : ServerMessage()

    /** Идёт обсуждение: кто ходит, сколько времени осталось. */
    @Serializable @SerialName("Discussion")
    data class Discussion(
        val players: List<PlayerView>,
        val currentSpeakerId: String?,
        val secondsLeft: Int,
    ) : ServerMessage()

    /**
     * Началось голосование (или второй тур).
     * [candidateIds] — за кого можно голосовать; пустой список = за любого.
     * [isRevote] — второй тур: голоса разделились, выбираем между лидерами.
     */
    @Serializable @SerialName("VotingStarted")
    data class VotingStarted(
        val players: List<PlayerView>,
        val candidateIds: List<String> = emptyList(),
        val isRevote: Boolean = false,
    ) : ServerMessage()

    /** Обновление хода голосования (кто уже проголосовал). */
    @Serializable @SerialName("VoteProgress")
    data class VoteProgress(val votedPlayerIds: List<String>, val totalVoters: Int) : ServerMessage()

    /**
     * Фаза «последнее слово»: игрока обвинили, и у него есть [seconds] секунд,
     * чтобы угадать слово мирных (если он лишний) или пропустить (если мирный).
     * Наступает для ЛЮБОГО обвинённого — сама пауза не выдаёт его роль.
     */
    @Serializable @SerialName("FinalGuess")
    data class FinalGuessStarted(
        val accusedId: String,
        val accusedName: String,
        val seconds: Int,
    ) : ServerMessage()

    /** Итоги раунда. [scores] — таблица очков партии (отсортирована по убыванию). */
    @Serializable @SerialName("RoundResult")
    data class RoundResult(
        val spyIds: List<String>,
        val spyNames: List<String>,
        /** Кто был Mr. X (null — роли в раунде не было). Раньше его личность не раскрывалась вовсе. */
        val mrXId: String? = null,
        val mrXName: String? = null,
        val civilianWord: String,
        val spyWord: String,
        val accusedId: String?,
        val accusedName: String?,
        val spyCaught: Boolean,
        val spyGuessedWord: Boolean,
        val message: String,
        /** Чем кончился раунд — см. [Outcome]. Клиенты красят итог по этому полю. */
        val outcome: String = Outcome.NONE,
        val scores: List<PlayerView> = emptyList(),
    ) : ServerMessage() {
        object Outcome {
            const val CIVILIANS = "CIVILIANS" // лишнего поймали, слово он не угадал
            const val IMPOSTORS = "IMPOSTORS" // лишние не пойманы
            const val DRAW = "DRAW"           // пойман, но угадал слово мирных
            const val NONE = "NONE"           // лишних в раунде не было
        }
    }

    /** Общая ошибка/уведомление. */
    @Serializable @SerialName("Notice")
    data class Notice(val text: String, val isError: Boolean = false) : ServerMessage()

    /** Игрока исключил ведущий — браузер должен показать сообщение и не переподключаться. */
    @Serializable @SerialName("Kicked")
    data class Kicked(val reason: String = "Вас исключил ведущий") : ServerMessage()
}

/** То, что видят все игроки друг о друге (без секретов). */
@Serializable
data class PlayerView(
    val id: String,
    val name: String,
    val connected: Boolean,
    val isHost: Boolean,
    val score: Int = 0, // очки за партию (копятся между раундами)
)

/**
 * Роль игрока в текущем раунде.
 *
 * [SPY] получает ПОХОЖЕЕ слово из той же категории и сам не знает наверняка,
 * что он шпион. [MR_X] не получает слова вообще — он знает только категорию
 * и блефует, опираясь на чужие ассоциации.
 */
enum class Role { CIVILIAN, SPY, MR_X }

/** Фазы игры. */
enum class GamePhase {
    LOBBY,       // ждём игроков
    REVEAL,      // роли розданы, игроки смотрят свои слова
    DISCUSSION,  // обсуждение по кругу
    VOTING,      // голосование
    FINAL_GUESS, // обвинённый выбирает «последнее слово» (или пропускает)
    RESULT,      // итоги раунда
}
