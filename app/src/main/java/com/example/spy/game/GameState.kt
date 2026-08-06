package com.example.spy.game

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * Игрок на стороне сервера (со всеми секретами).
 */
class Player(
    val id: String,
    var name: String,
    val token: String,
    val isHost: Boolean,
) {
    @Volatile var connected: Boolean = true
    @Volatile var word: String? = null       // выданное слово в текущем раунде
    @Volatile var role: Role = Role.CIVILIAN // роль в текущем раунде
    @Volatile var voteFor: String? = null    // за кого проголосовал
    @Volatile var spyGuess: String? = null   // догадка лишнего о слове мирных
    @Volatile var score: Int = 0             // очки за партию (копятся между раундами)

    val isSpy: Boolean get() = role == Role.SPY
    val isMrX: Boolean get() = role == Role.MR_X

    /** Шпион и Mr. X — «лишние»: только они могут угадывать слово мирных и приносят очки за поимку. */
    val isImpostor: Boolean get() = role != Role.CIVILIAN

    fun toView() = PlayerView(
        id = id, name = name, connected = connected, isHost = isHost, score = score,
    )
}

/**
 * Настройки партии.
 */
data class GameSettings(
    val minPlayers: Int = 2,
    val discussionSeconds: Int = 180,
    /** Категории слов, выбранные ведущим. Пусто = играем всеми. */
    val categories: Set<String> = emptySet(),
    val spyCount: Int = 1,
    val hasMrX: Boolean = false, // Mr. X: один игрок вообще без слова, блефует по ассоциациям
    // Авто-таймер: время обсуждения масштабируется от числа игроков.
    // Иначе — ручное discussionSeconds.
    val autoTimer: Boolean = true,
) {
    companion object {
        /**
         * Авто-время обсуждения на [participants] участников: 30 сек базово + 25 сек на игрока.
         * Границы важны: втроём 105 сек хватает на два круга ассоциаций, а без верхнего
         * потолка партия на 15 человек уезжала бы в 7 минут мёртвого ожидания.
         */
        fun autoSeconds(participants: Int): Int =
            (30 + 25 * participants).coerceIn(90, 420)
    }
}

/**
 * Потокобезопасное состояние одной игровой сессии.
 * Вся мутация состояния идёт через synchronized(lock), т.к. к серверу
 * одновременно обращаются несколько WebSocket-корутин.
 */
class GameState(
    var settings: GameSettings = GameSettings(),
    private val random: Random = Random.Default,
) {
    private val lock = Any()

    private val players = ConcurrentHashMap<String, Player>()
    private val order = mutableListOf<String>() // порядок подключения = порядок хода

    @Volatile var phase: GamePhase = GamePhase.LOBBY
        private set
    @Volatile var roundNumber: Int = 0
        private set

    private var civilianWord: String = ""
    private var spyWord: String = ""
    private var category: String = ""
    private var speakerIndex: Int = 0
    // Единый набор кандидатов для угадывания слова мирных (одинаковый у всех — никого не выдаёт)
    private var roundGuessOptions: List<String> = emptyList()
    // Случайный порядок речи текущего раунда (перемешивается каждый раунд)
    private var speakingOrder: List<String> = emptyList()
    // Второй тур голосования при равенстве голосов
    private var revoteUsed: Boolean = false
    private var revoteCandidates: List<String>? = null
    // Обвинённый в фазе «последнего слова»
    private var finalAccusedId: String? = null
    // Сыгранные пары слов — не повторяем, пока банк не исчерпан
    private val usedPairs = mutableSetOf<WordPair>()

    companion object {
        const val HOST_ID = "HOST"

        /** Слово-заглушка, которое видит Mr. X вместо настоящего слова. */
        const val MR_X_WORD = "???"
    }

    // ---------- Настройки ----------

    /** Обновить настройки партии (только в лобби). */
    fun updateSettings(newSettings: GameSettings) = synchronized(lock) {
        settings = newSettings
    }

    // ---------- Хост как игрок ----------

    /**
     * Регистрирует хоста как обычного игрока с фиксированным id.
     * Хост управляется из Compose UI, а не через браузер, поэтому всегда «онлайн».
     */
    fun ensureHost(name: String): Player = synchronized(lock) {
        val existing = players[HOST_ID]
        if (existing != null) {
            existing.name = name.trim().ifBlank { "Ведущий" }
            existing.connected = true
            return existing
        }
        val host = Player(
            id = HOST_ID,
            name = name.trim().ifBlank { "Ведущий" },
            token = HOST_ID,
            isHost = true,
        )
        players[HOST_ID] = host
        order.add(0, HOST_ID) // хост первый в очереди хода
        return host
    }

    // ---------- Лобби ----------

    /** Добавить игрока или переподключить по токену. Возвращает (player, isNew). */
    fun joinOrReconnect(name: String, token: String?, isHost: Boolean): Pair<Player, Boolean> =
        synchronized(lock) {
            // переподключение
            if (token != null) {
                val existing = players.values.firstOrNull { it.token == token }
                if (existing != null) {
                    existing.connected = true
                    if (name.isNotBlank()) existing.name = name.trim()
                    return existing to false
                }
            }
            val player = Player(
                id = UUID.randomUUID().toString().take(8),
                name = name.trim().ifBlank { "Игрок" },
                token = UUID.randomUUID().toString(),
                isHost = isHost,
            )
            players[player.id] = player
            order.add(player.id)
            return player to true
        }

    fun markDisconnected(playerId: String) = synchronized(lock) {
        players[playerId]?.connected = false
    }

    fun removePlayer(playerId: String) = synchronized(lock) {
        players.remove(playerId)
        order.remove(playerId)
    }

    /** Исключить игрока по решению ведущего. Хоста исключить нельзя. Возвращает true, если удалён. */
    fun kickPlayer(playerId: String): Boolean = synchronized(lock) {
        if (playerId == HOST_ID) return false
        val removed = players.remove(playerId) != null
        order.remove(playerId)
        removed
    }

    /** Является ли игрок шпионом в текущем раунде (для показа роли хосту). */
    fun isSpy(playerId: String): Boolean = players[playerId]?.isSpy == true

    fun playersView(): List<PlayerView> = synchronized(lock) {
        order.mapNotNull { players[it]?.toView() }
    }

    fun player(id: String): Player? = players[id]

    fun connectedCount(): Int = synchronized(lock) { players.values.count { it.connected } }

    // ---------- Раунд ----------

    /** Начать новый раунд: раздать слова, назначить шпиона(ов) и/или Mr. X. */
    fun startRound(): Boolean = synchronized(lock) {
        val active = order.mapNotNull { players[it] }.filter { it.connected }
        if (active.size < settings.minPlayers) return false

        // Пара без повторов: сыгранные помним; исчерпали банк — начинаем заново
        val pair = WordBank.randomPairExcluding(settings.categories, usedPairs, random)
            ?: run {
                usedPairs.clear()
                WordBank.randomPairExcluding(settings.categories, usedPairs, random)
            }
            ?: return false
        usedPairs += pair

        val flip = random.nextBoolean()
        civilianWord = if (flip) pair.civilian else pair.spy
        spyWord = if (flip) pair.spy else pair.civilian
        category = pair.category

        // Если Mr. X включён, шпионов может быть 0 (режим «только Mr. X»)
        // Если Mr. X выключен — минимум 1 шпион (защита от некорректных настроек)
        val minSpy = if (settings.hasMrX) 0 else 1
        val effectiveSpyCount = if (!settings.hasMrX && settings.spyCount < 1) 1 else settings.spyCount
        val spyN = effectiveSpyCount.coerceIn(minSpy, maxOf(1, active.size / 3))
        val shuffled = active.shuffled(random)
        val spies = shuffled.take(spyN).map { it.id }.toSet()

        // Mr. X: выбираем случайно среди НЕ-шпионов, но только если после его добавления
        // мирных ОСТАНЕТСЯ БОЛЬШЕ, чем лишних. Старое условие (active.size > spyN + 1)
        // допускало 1 шпион + Mr. X на троих — двое лишних против одного мирного,
        // где мирный не мог выиграть в принципе.
        // Исключение — дуэль 1 на 1 при двух игроках: это тестовый режим.
        val impostorsWithMrX = spyN + 1
        val civiliansWithMrX = active.size - impostorsWithMrX
        val mrXAllowed = settings.hasMrX && when (impostorsWithMrX) {
            1 -> civiliansWithMrX >= 1
            else -> civiliansWithMrX > impostorsWithMrX
        }
        val mrXId: String? = if (mrXAllowed) {
            shuffled.filter { it.id !in spies }.random(random).id
        } else null

        active.forEach { p ->
            p.voteFor = null
            p.spyGuess = null
            p.role = when {
                p.id in spies -> Role.SPY
                p.id == mrXId -> Role.MR_X
                else -> Role.CIVILIAN
            }
            p.word = when (p.role) {
                Role.SPY -> spyWord
                Role.MR_X -> MR_X_WORD
                Role.CIVILIAN -> civilianWord
            }
        }

        // Порядок речи: СВОЙ отдельный shuffle каждый раунд (не тот, что для ролей, —
        // иначе шпионы всегда оказывались бы первыми в очереди). Убирает системную
        // несправедливость «ведущий всегда говорит первым».
        speakingOrder = active.map { it.id }.shuffled(random)

        // Кандидаты для угадывания слова мирных — ОДИН общий список для всех:
        // 6 слов категории, среди них и слово мирных, и слово шпиона (приманка).
        // И мирный, и шпион видят в списке своё слово — список никого не выдаёт.
        roundGuessOptions = WordBank.wordsFromCategory(
            category = category, count = 6,
            mustInclude = civilianWord, alsoInclude = spyWord, random = random,
        )

        roundNumber += 1
        speakerIndex = 0
        revoteUsed = false
        revoteCandidates = null
        finalAccusedId = null
        phase = GamePhase.DISCUSSION
        true
    }

    /**
     * Время обсуждения для текущего раунда.
     * Авто-режим считается по УЧАСТНИКАМ раунда (получившим слово) — см. [GameSettings.autoSeconds].
     */
    fun discussionSecondsForRound(): Int = synchronized(lock) {
        if (!settings.autoTimer) return settings.discussionSeconds
        GameSettings.autoSeconds(players.values.count { it.word != null })
    }

    fun roleFor(playerId: String): ServerMessage.RoleAssignment? = synchronized(lock) {
        val p = players[playerId] ?: return null
        val w = p.word ?: return null
        ServerMessage.RoleAssignment(
            word = w,
            category = category,
            youMayBeSpy = true,
            roundNumber = roundNumber,
            isMrX = p.isMrX,
            // Общий список у всех: шпион угадывает слово мирных, Mr. X — тоже,
            // мирные могут тыкать для маскировки (их догадки игнорируются).
            guessOptions = roundGuessOptions,
        )
    }

    fun currentSpeakerId(): String? = synchronized(lock) {
        // Очередь речи — по перемешанному порядку раунда. Фильтруем по УЧАСТНИКАМ
        // (получившим слово), а не по онлайну: временный обрыв связи не сдвигает очередь.
        val ids = speakingOrder.filter { players[it]?.word != null }
        if (ids.isEmpty()) null else ids[speakerIndex % ids.size]
    }

    /** Участники раунда в порядке речи, затем зрители (для экрана обсуждения). */
    fun speakingOrderView(): List<PlayerView> = synchronized(lock) {
        val speakers = speakingOrder.mapNotNull { players[it]?.takeIf { p -> p.word != null } }
        val speakerIds = speakers.map { it.id }.toSet()
        val spectators = order.mapNotNull { players[it] }.filter { it.id !in speakerIds }
        (speakers + spectators).map { it.toView() }
    }

    fun nextSpeaker() = synchronized(lock) { speakerIndex += 1 }

    /** Перейти к голосованию. Возвращает false, если фаза уже не DISCUSSION
     *  (защита от двойного запуска: таймер + ручная кнопка ведущего). */
    fun startVoting(): Boolean = synchronized(lock) {
        if (phase != GamePhase.DISCUSSION) return false
        phase = GamePhase.VOTING
        revoteUsed = false
        revoteCandidates = null
        finalAccusedId = null
        players.values.forEach { it.voteFor = null }
        true
    }

    /** Итог попытки завершить голосование (атомарно, под локом). */
    sealed class VotingEnd {
        /** Фаза уже не VOTING — кто-то завершил раньше, ничего не делаем. */
        data object NotInVoting : VotingEnd()
        /** Голоса разделились — второй тур между лидерами (голоса сброшены). */
        data class Revote(val candidateIds: List<String>) : VotingEnd()
        /** Есть обвинённый — фаза «последнего слова»: даём ему шанс на догадку. */
        data class FinalGuess(val accusedId: String, val accusedName: String) : VotingEnd()
        /** Голосование завершено, фаза переведена в RESULT — можно считать итог. */
        data object Finished : VotingEnd()
    }

    /**
     * Атомарно решает, чем заканчивается голосование:
     * - равенство голосов в первом туре -> переголосование между лидерами;
     * - единоличный лидер онлайн -> фаза «последнего слова» (шанс на догадку
     *   ДО показа результата — иначе проголосовавший последним шпион физически
     *   не успевал отправить догадку);
     * - иначе -> RESULT.
     * Один synchronized-блок исключает гонки между конкурирующими корутинами.
     */
    fun resolveVotingEnd(): VotingEnd = synchronized(lock) {
        if (phase != GamePhase.VOTING) return VotingEnd.NotInVoting
        val participants = players.values.filter { it.word != null }
        val tally = HashMap<String, Int>()
        participants.forEach { v ->
            // Голоса за уже удалённых (кикнутых) не считаем
            v.voteFor?.let { if (players.containsKey(it)) tally[it] = (tally[it] ?: 0) + 1 }
        }
        val maxVotes = tally.values.maxOrNull()
        val top = tally.filter { it.value == maxVotes }.keys.toList()
        if (top.size > 1 && !revoteUsed) {
            revoteUsed = true
            revoteCandidates = top
            players.values.forEach { it.voteFor = null }
            return VotingEnd.Revote(top)
        }
        if (top.size == 1) {
            val accused = players[top.first()]
            // «Последнее слово» даём ЛЮБОМУ обвинённому (и мирному тоже!) —
            // если бы пауза была только для шпиона, она сама выдавала бы роль.
            if (accused != null && accused.connected) {
                finalAccusedId = accused.id
                phase = GamePhase.FINAL_GUESS
                return VotingEnd.FinalGuess(accused.id, accused.name)
            }
        }
        phase = GamePhase.RESULT
        VotingEnd.Finished
    }

    /** Обвинённый в фазе «последнего слова» (null — фаза не идёт). */
    fun finalAccusedIdOrNull(): String? = synchronized(lock) { finalAccusedId }

    /** Атомарный переход FINAL_GUESS -> RESULT. true только при первом вызове. */
    fun finishFinalGuess(): Boolean = synchronized(lock) {
        if (phase != GamePhase.FINAL_GUESS) return false
        phase = GamePhase.RESULT
        true
    }

    /** Кандидаты второго тура (null — обычное голосование). */
    fun revoteCandidatesOrNull(): List<String>? = synchronized(lock) { revoteCandidates }

    fun castVote(voterId: String, suspectId: String): Boolean = synchronized(lock) {
        val voter = players[voterId] ?: return false
        // Голосуют только участники раунда. Зритель, вошедший посреди игры, раньше
        // получал «успех», но его голос нигде не учитывался — молчаливое враньё.
        if (voter.word == null) return false
        if (phase != GamePhase.VOTING) return false
        if (!players.containsKey(suspectId)) return false
        // Во втором туре голосовать можно только за лидеров первого
        revoteCandidates?.let { if (suspectId !in it) return false }
        voter.voteFor = suspectId
        true
    }

    /**
     * Принять догадку о слове мирных.
     *
     * Фаза проверяется намеренно: без неё лишний мог переслать догадку уже ПОСЛЕ того,
     * как обвинили другого игрока, и задним числом добрать бонусные очки.
     * В «последнем слове» отвечает только сам обвинённый.
     */
    fun setSpyGuess(playerId: String, guess: String) = synchronized(lock) {
        val p = players[playerId] ?: return@synchronized
        val allowed = when (phase) {
            GamePhase.VOTING -> p.isImpostor
            GamePhase.FINAL_GUESS -> p.id == finalAccusedId
            else -> false
        }
        if (allowed) p.spyGuess = guess
    }

    fun votedPlayerIds(): List<String> = synchronized(lock) {
        players.values
            .filter { it.word != null && it.connected && it.voteFor != null }
            .map { it.id }
    }

    /** Сколько онлайн-участников раунда мы ждём в голосовании (знаменатель прогресса). */
    fun expectedVoterCount(): Int = synchronized(lock) {
        players.values.count { it.word != null && it.connected }
    }

    /**
     * Все ли проголосовали. Ждём только участников раунда, которые сейчас ОНЛАЙН:
     * если игрок погасил экран (ушёл в оффлайн) — он не блокирует голосование,
     * но при этом остаётся в игре и его роль по-прежнему учитывается в итогах.
     */
    fun allVoted(): Boolean = synchronized(lock) {
        val voters = players.values.filter { it.word != null && it.connected }
        voters.isNotEmpty() && voters.all { it.voteFor != null }
    }

    /**
     * Подсчёт итогов раунда + начисление очков партии.
     *
     * ВАЖНО: считаем по УЧАСТНИКАМ раунда (тем, кому раздали слово), а не по текущему
     * онлайну. Иначе, если шпион/Mr. X погасит экран, он бы выпал из подсчёта и вся
     * логика победителя ломалась бы. Фаза уже переведена в RESULT через resolveVotingEnd().
     */
    fun computeResult(): ServerMessage.RoundResult = synchronized(lock) {
        val participants = players.values.filter { it.word != null }
        val spies = participants.filter { it.isSpy }
        val spyIds = spies.map { it.id }
        val spyNames = spies.map { it.name }

        // Кого обвинили: игрок с наибольшим числом голосов (считаем все голоса участников,
        // в т.ч. тех, кто успел проголосовать и уйти в оффлайн; голоса за кикнутых — нет).
        val tally = HashMap<String, Int>()
        participants.forEach { v ->
            v.voteFor?.let { if (players.containsKey(it)) tally[it] = (tally[it] ?: 0) + 1 }
        }
        val maxVotes = tally.values.maxOrNull()
        val topCandidates = tally.filter { it.value == maxVotes }.keys
        // При равенстве голосов обвинение не проходит
        val tallyAccusedId = if (topCandidates.size == 1) topCandidates.first() else null
        // Если раунд прошёл через «последнее слово», обвинённый уже ОБЪЯВЛЕН всем —
        // итог обязан совпадать с объявлением. Пересчёт по tally здесь опасен:
        // кикни обвинённого во время «последнего слова» — голоса за него выпадут
        // из подсчёта (голоса за кикнутых не считаем), и обвинённым внезапно стал бы
        // игрок со вторым местом, которого никто не обвинял. Кикнули объявленного —
        // обвинение считается несостоявшимся, а не переигранным.
        val announced = finalAccusedId
        val accusedId = if (announced != null) {
            announced.takeIf { players.containsKey(it) }
        } else tallyAccusedId
        val accused = accusedId?.let { players[it] }

        val mrXPlayer = participants.firstOrNull { it.isMrX }
        val hasSpies = spies.isNotEmpty()
        val hasMrX = mrXPlayer != null

        val spyCaught = accused?.isSpy == true
        val mrXCaught = accused?.isMrX == true
        val impostorCaught = spyCaught || mrXCaught

        // Угадал ли именно ПОЙМАННЫЙ слово мирных (и шпион, и Mr. X хранят догадку в spyGuess).
        val accusedGuessedWord = accused?.spyGuess?.trim()
            ?.equals(civilianWord, ignoreCase = true) == true
        // Угадал ли хоть кто-то из импостеров (для бонуса при непойманной команде).
        val impostors = spies + listOfNotNull(mrXPlayer)
        val anyImpostorGuessed = impostors.any {
            it.spyGuess?.trim()?.equals(civilianWord, ignoreCase = true) == true
        }
        // Флаг для отчёта (совместимость с полем RoundResult).
        val spyGuessedWord = spies.any {
            it.spyGuess?.trim()?.equals(civilianWord, ignoreCase = true) == true
        }

        val caughtRole = when {
            mrXCaught -> "Mr. X"
            spyCaught -> "Шпион"
            else -> null
        }
        val outcome = when {
            !hasSpies && !hasMrX -> ServerMessage.RoundResult.Outcome.NONE
            impostorCaught && accusedGuessedWord -> ServerMessage.RoundResult.Outcome.DRAW
            impostorCaught -> ServerMessage.RoundResult.Outcome.CIVILIANS
            else -> ServerMessage.RoundResult.Outcome.IMPOSTORS
        }
        val impostorsWinMsg = when {
            hasSpies && hasMrX -> "Шпион и Mr. X победили! Их не вычислили."
            hasMrX -> "Mr. X победил! Его не вычислили."
            else -> "Шпион победил! Его не вычислили."
        }
        val impostorsWinGuessMsg = when {
            hasSpies && hasMrX -> "Шпион и Mr. X победили и угадали слово мирных!"
            hasMrX -> "Mr. X победил и угадал слово мирных!"
            else -> "Шпион победил дважды: не пойман и угадал слово!"
        }

        val message = when {
            // Импостеров нет вовсе (не хватило игроков на роль) — просто закрываем раунд.
            !hasSpies && !hasMrX -> "Голосование завершено."
            // Пойман лишний (шпион ИЛИ Mr. X)
            impostorCaught && accusedGuessedWord ->
                "Ничья! $caughtRole пойман, но угадал слово мирных."
            impostorCaught ->
                "Мирные победили! $caughtRole пойман и не угадал слово."
            // Никого из лишних не поймали
            anyImpostorGuessed -> impostorsWinGuessMsg
            else -> impostorsWinMsg
        }

        // ---------- Очки партии ----------
        // Главный принцип: платим за ДЕДУКЦИЮ, а не за удачу оказаться в победившей команде.
        // Раньше мирный получал +2 просто за то, что кто-то другой вычислил лишнего,
        // и всего +1 за собственное точное попадание — выгоднее было молчать и плыть
        // по течению. Теперь наоборот: личное попадание +2, командный бонус +1.
        //
        // Мирный:  +2 лично за голос точно в лишнего (даже если общий голос ушёл не туда),
        //          +1 сверху, если лишнего в итоге поймали.
        // Лишний:  не пойман — шпион +4, Mr. X +5 (его роль сложнее), +1 за угаданное слово.
        //          пойман, но угадал слово мирных — +2 (та самая «ничья»).
        //          не пойман, но спалился напарник — половина награды: раунд команда
        //          проиграла, однако лично он отработал чисто и не должен уходить в ноль.
        if (hasSpies || hasMrX) {
            val impostorIds = impostors.map { it.id }.toSet()
            participants.filter { it.id !in impostorIds }.forEach { c ->
                if (c.voteFor in impostorIds) c.score += 2
                if (impostorCaught) c.score += 1
            }
            impostors.forEach { imp ->
                val guessed = imp.spyGuess?.trim()
                    ?.equals(civilianWord, ignoreCase = true) == true
                val fullReward = if (imp.isMrX) 5 else 4
                when {
                    imp.id == accusedId -> if (guessed) imp.score += 2
                    impostorCaught -> imp.score += (fullReward + 1) / 2
                    else -> imp.score += fullReward + (if (guessed) 1 else 0)
                }
            }
        }

        ServerMessage.RoundResult(
            spyIds = spyIds,
            spyNames = spyNames,
            mrXId = mrXPlayer?.id,
            mrXName = mrXPlayer?.name,
            civilianWord = civilianWord,
            spyWord = spyWord,
            accusedId = accusedId,
            accusedName = accused?.name,
            spyCaught = spyCaught,
            spyGuessedWord = spyGuessedWord,
            message = message,
            outcome = outcome,
            scores = order.mapNotNull { players[it]?.toView() }
                .sortedByDescending { it.score },
        )
    }

    fun resetToLobby() = synchronized(lock) {
        phase = GamePhase.LOBBY
        revoteUsed = false
        revoteCandidates = null
        finalAccusedId = null
        speakingOrder = emptyList()
        // Очки НЕ сбрасываем — партия длится, пока живёт сервер (весь игровой вечер)
        players.values.forEach {
            it.word = null; it.role = Role.CIVILIAN; it.voteFor = null; it.spyGuess = null
        }
    }
}
