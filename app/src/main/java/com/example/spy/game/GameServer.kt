package com.example.spy.game

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json

/**
 * Встроенный игровой сервер.
 * Поднимает HTTP (отдаёт клиентскую страницу) + WebSocket (управляет игрой).
 * Работает в своей CoroutineScope — останавливается вызовом stop().
 */
class GameServer(
    val port: Int = 8080,
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val json = Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val state = GameState()
    private val hub = ConnectionHub(json)

    /** Сколько игроков онлайн (для UI хоста). */
    private val _playerCount = MutableStateFlow(0)
    val playerCount: StateFlow<Int> = _playerCount

    /** Текущая фаза (для UI хоста). */
    private val _phase = MutableStateFlow(GamePhase.LOBBY)
    val phase: StateFlow<GamePhase> = _phase

    /** Список игроков (для UI хоста). */
    private val _players = MutableStateFlow<List<PlayerView>>(emptyList())
    val players: StateFlow<List<PlayerView>> = _players

    /** Последний результат раунда (для UI хоста). */
    private val _lastResult = MutableStateFlow<ServerMessage.RoundResult?>(null)
    val lastResult: StateFlow<ServerMessage.RoundResult?> = _lastResult

    /** Таймер обсуждения: осталось секунд. */
    private val _timerSeconds = MutableStateFlow(0)
    val timerSeconds: StateFlow<Int> = _timerSeconds

    /** Слово и роль хоста (для показа на экране хоста). */
    private val _hostRole = MutableStateFlow<ServerMessage.RoleAssignment?>(null)
    val hostRole: StateFlow<ServerMessage.RoleAssignment?> = _hostRole

    /** Текущий говорящий (имя, для показа хосту). */
    private val _currentSpeaker = MutableStateFlow("")
    val currentSpeaker: StateFlow<String> = _currentSpeaker

    /** Кандидаты второго тура голосования (null — обычное голосование). */
    private val _voteCandidates = MutableStateFlow<List<String>?>(null)
    val voteCandidates: StateFlow<List<String>?> = _voteCandidates

    /** Обвинённый в фазе «последнего слова» (null — фаза не идёт). */
    private val _finalAccusedId = MutableStateFlow<String?>(null)
    val finalAccusedId: StateFlow<String?> = _finalAccusedId

    private var finalGuessJob: Job? = null

    companion object {
        /** Сколько секунд у обвинённого на «последнее слово». */
        const val FINAL_GUESS_SECONDS = 30
    }

    private var timerJob: Job? = null

    private var engine: EmbeddedServer<*, *>? = null

    init {
        // Регистрируем хоста как игрока с фиксированным id
        state.ensureHost("Ведущий")
    }

    private fun Application.module() {
        // Ping/timeout держат соединение живым и позволяют вовремя обнаружить мёртвые
        // сокеты (иначе idle-таймауты роутера рвали бы связь без keep-alive).
        install(WebSockets) {
            pingPeriodMillis = 20_000
            timeoutMillis = 40_000
        }

        routing {
            // Отдаём клиентскую страницу (HTML)
            get("/") {
                call.respondText(ClientPage.html, ContentType.Text.Html, HttpStatusCode.OK)
            }

            // WebSocket для игроков
            webSocket("/ws") {
                var playerId: String? = null
                try {
                    incoming.consumeEach { frame ->
                        if (frame is Frame.Text) {
                            val text = frame.readText()
                            val msg = runCatching {
                                json.decodeFromString(ClientMessage.serializer(), text)
                            }.getOrNull() ?: return@consumeEach

                            when (msg) {
                                is ClientMessage.Join -> {
                                    val (player, _) = state.joinOrReconnect(
                                        name = msg.name,
                                        token = msg.token,
                                        isHost = false,
                                    )
                                    playerId = player.id
                                    hub.register(player.id, this)
                                    hub.sendTo(player.id, ServerMessage.Joined(
                                        playerId = player.id,
                                        token = player.token,
                                        isHost = player.isHost,
                                    ))
                                    // Обновляем присутствие у всех (не выбивая никого из текущего экрана).
                                    broadcastLobby()
                                    // Если раунд уже идёт — возвращаем (пере)подключившегося в игру:
                                    // сначала его роль/слово, затем актуальный экран текущей фазы —
                                    // чтобы он не «завис» на карточке роли и мог голосовать.
                                    if (state.phase != GamePhase.LOBBY) {
                                        state.roleFor(player.id)?.let { hub.sendTo(player.id, it) }
                                        sendCurrentPhaseTo(player.id)
                                    }
                                }
                                is ClientMessage.Vote -> {
                                    val pid = playerId ?: return@consumeEach
                                    if (state.castVote(pid, msg.suspectId)) {
                                        broadcastVoteProgress()
                                        if (state.allVoted()) {
                                            finishVoting()
                                        }
                                    } else if (state.player(pid)?.word == null) {
                                        // Зритель, вошедший посреди раунда, — объясняем,
                                        // а не молча глотаем голос.
                                        hub.sendTo(pid, ServerMessage.Notice(
                                            "Вы зритель этого раунда — вступите со следующего",
                                            isError = true,
                                        ))
                                    }
                                }
                                is ClientMessage.SpyGuess -> {
                                    val pid = playerId ?: return@consumeEach
                                    state.setSpyGuess(pid, msg.word)
                                    // В фазе «последнего слова» выбор обвинённого завершает раунд
                                    if (state.phase == GamePhase.FINAL_GUESS &&
                                        state.finalAccusedIdOrNull() == pid
                                    ) {
                                        finishFinalGuess()
                                    }
                                }
                                is ClientMessage.SkipGuess -> {
                                    val pid = playerId ?: return@consumeEach
                                    if (state.phase == GamePhase.FINAL_GUESS &&
                                        state.finalAccusedIdOrNull() == pid
                                    ) {
                                        finishFinalGuess()
                                    }
                                }
                                is ClientMessage.Ping -> { /* keep-alive */ }
                            }
                        }
                    }
                } finally {
                    playerId?.let { pid ->
                        // Помечаем оффлайн ТОЛЬКО если это всё ещё активная сессия игрока.
                        // Если игрок уже переподключился (новая сессия вытеснила эту) —
                        // не трогаем его статус. Игрок при этом остаётся в игре, не удаляется:
                        // погасший экран/потеря связи — не повод выкидывать из партии.
                        val wasActive = hub.unregister(pid, this)
                        if (wasActive) {
                            state.markDisconnected(pid)
                            refreshHostUI()
                            broadcastLobby()
                            // Если ушёл последний, кого ждали в голосовании — доводим до итога.
                            maybeFinishVoting()
                        }
                    }
                }
            }
        }
    }

    fun start() {
        engine = embeddedServer(CIO, port = port) {
            module()
        }.also { it.start(wait = false) }
    }

    fun stop() {
        timerJob?.cancel()
        // Аккуратно закрываем WS-сессии ДО отмены scope (иначе launch{closeAll()} мог быть
        // отменён раньше, чем успеет выполниться). Ограничиваем по времени, чтобы не подвесить UI.
        runCatching {
            runBlocking { withTimeoutOrNull(600) { hub.closeAll() } }
        }
        engine?.stop(500, 1000)
        engine = null
        scope.cancel()
    }

    // ---------- Команды от хоста (из Compose UI) ----------

    /** Обновить настройки партии из экрана хоста. */
    fun hostUpdateSettings(settings: GameSettings) {
        state.updateSettings(settings)
        refreshHostUI()
        // Чтобы у игроков в лобби сразу обновились правила/режим (spyCount, Mr. X).
        scope.launch { broadcastLobby() }
    }

    /** Хост меняет своё имя как игрока. */
    fun hostUpdateName(name: String) {
        state.ensureHost(name)
        refreshHostUI()
    }

    /** Хост исключает игрока из лобби. */
    fun hostKickPlayer(playerId: String) = scope.launch {
        val wasFinalAccused = state.phase == GamePhase.FINAL_GUESS &&
            state.finalAccusedIdOrNull() == playerId
        val removed = state.kickPlayer(playerId)
        if (removed) {
            hub.sendTo(playerId, ServerMessage.Kicked())
            hub.closePlayer(playerId)
            refreshHostUI()
            broadcastLobby()
            // Если кикнули последнего, кого ждали в голосовании — доводим до итога.
            maybeFinishVoting()
            // Кикнули обвинённого — не ждём его «последнее слово»
            if (wasFinalAccused) finishFinalGuess()
        }
    }

    /** Хост голосует как игрок. */
    fun hostVoteFor(suspectId: String) = scope.launch {
        if (state.castVote(GameState.HOST_ID, suspectId)) {
            broadcastVoteProgress()
            if (state.allVoted()) {
                finishVoting()
            }
        }
    }

    /** Хост, если он шпион или Mr. X, пробует угадать слово мирных. */
    fun hostSpyGuess(word: String) {
        state.setSpyGuess(GameState.HOST_ID, word)
        // Если хост — обвинённый в фазе «последнего слова», его выбор завершает раунд
        if (state.phase == GamePhase.FINAL_GUESS &&
            state.finalAccusedIdOrNull() == GameState.HOST_ID
        ) {
            scope.launch { finishFinalGuess() }
        }
    }

    /** Хост-обвинённый пропускает «последнее слово» («я мирный»). */
    fun hostSkipFinalGuess() = scope.launch {
        if (state.phase == GamePhase.FINAL_GUESS &&
            state.finalAccusedIdOrNull() == GameState.HOST_ID
        ) {
            finishFinalGuess()
        }
    }

    /** Хост запускает раунд. */
    fun hostStartRound() = scope.launch {
        if (!state.startRound()) {
            return@launch
        }
        // Сначала кладём роль хоста в StateFlow, и только потом переключаем фазу и
        // делаем suspend-рассылку — иначе экран ведущего успел бы показаться без слова.
        _hostRole.value = state.roleFor(GameState.HOST_ID)
        _phase.value = state.phase
        // Раздаём роли всем игрокам через браузер
        hub.broadcast { pid -> state.roleFor(pid) }
        refreshHostUI()
        startDiscussionTimer()
    }

    /** Хост переключает на следующего говорящего. */
    fun hostNextSpeaker() = scope.launch {
        state.nextSpeaker()
        refreshHostUI()
        broadcastDiscussion()
    }

    /** Хост запускает голосование (или оно начинается автоматически по таймеру). */
    fun hostStartVoting() = scope.launch {
        timerJob?.cancel()
        // Переходим к голосованию только из фазы обсуждения — так ручная кнопка и
        // сработавший таймер не запустят голосование дважды (и не обнулят уже поданные голоса).
        if (!state.startVoting()) return@launch
        _voteCandidates.value = null
        _phase.value = GamePhase.VOTING
        refreshHostUI()
        hub.broadcast(ServerMessage.VotingStarted(players = state.playersView()))
    }

    /** Хост возвращает в лобби. */
    fun hostBackToLobby() = scope.launch {
        timerJob?.cancel()
        finalGuessJob?.cancel()
        finalGuessJob = null
        state.resetToLobby()
        _phase.value = GamePhase.LOBBY
        _lastResult.value = null
        _hostRole.value = null
        _voteCandidates.value = null
        _finalAccusedId.value = null
        refreshHostUI()
        broadcastLobby()
    }

    // ---------- Внутренняя логика ----------

    private fun startDiscussionTimer() {
        timerJob?.cancel()
        // Авто-режим: время зависит от числа участников раунда
        val total = state.discussionSecondsForRound()
        _timerSeconds.value = total
        timerJob = scope.launch {
            var left = total
            while (left > 0) {
                broadcastDiscussion(left)
                delay(1000)
                left -= 1
                _timerSeconds.value = left
            }
            // Время вышло — голосование
            hostStartVoting()
        }
    }

    private suspend fun broadcastDiscussion(secondsLeft: Int = _timerSeconds.value) {
        hub.broadcast(
            ServerMessage.Discussion(
                // Список в порядке речи раунда — игроки видят, кто за кем ходит
                players = state.speakingOrderView(),
                currentSpeakerId = state.currentSpeakerId(),
                secondsLeft = secondsLeft,
            )
        )
    }

    /**
     * Разослать обновление состава игроков с учётом ТЕКУЩЕЙ фазы.
     * Сообщение Lobby на клиенте сбрасывает экран в лобби и обнуляет выданное слово,
     * поэтому его шлём только в фазе LOBBY. Если кто-то вошёл/вышел посреди раунда —
     * рассылаем сообщение соответствующей фазы, не выбивая остальных из игры.
     */
    private suspend fun broadcastLobby() {
        refreshHostUI()
        when (state.phase) {
            GamePhase.LOBBY -> hub.broadcast(
                ServerMessage.Lobby(
                    players = state.playersView(),
                    phase = state.phase.name,
                    minPlayers = state.settings.minPlayers,
                    spyCount = state.settings.spyCount,
                    hasMrX = state.settings.hasMrX,
                )
            )
            GamePhase.DISCUSSION, GamePhase.REVEAL -> broadcastDiscussion()
            // В голосовании НЕ шлём VotingStarted (он сбросил бы выбор у всех) —
            // достаточно обновить счётчик проголосовавших.
            GamePhase.VOTING -> broadcastVoteProgress()
            GamePhase.FINAL_GUESS -> { /* ждём «последнее слово» — экраны не трогаем */ }
            GamePhase.RESULT -> { /* итог уже разослан отдельно */ }
        }
    }

    private suspend fun broadcastVoteProgress() {
        val voted = state.votedPlayerIds()
        hub.broadcast(
            ServerMessage.VoteProgress(
                votedPlayerIds = voted,
                totalVoters = state.expectedVoterCount(),
            )
        )
    }

    /** Отправить одному игроку экран текущей фазы (для входа/переподключения посреди раунда). */
    private suspend fun sendCurrentPhaseTo(playerId: String) {
        when (state.phase) {
            GamePhase.DISCUSSION, GamePhase.REVEAL -> hub.sendTo(
                playerId,
                ServerMessage.Discussion(
                    players = state.speakingOrderView(),
                    currentSpeakerId = state.currentSpeakerId(),
                    secondsLeft = _timerSeconds.value,
                )
            )
            GamePhase.VOTING -> {
                val revote = state.revoteCandidatesOrNull()
                hub.sendTo(playerId, ServerMessage.VotingStarted(
                    players = state.playersView(),
                    candidateIds = revote ?: emptyList(),
                    isRevote = revote != null,
                ))
                hub.sendTo(
                    playerId,
                    ServerMessage.VoteProgress(
                        votedPlayerIds = state.votedPlayerIds(),
                        totalVoters = state.expectedVoterCount(),
                    )
                )
            }
            GamePhase.FINAL_GUESS -> {
                // Реконнект посреди «последнего слова» (в т.ч. самого обвинённого)
                val accusedId = state.finalAccusedIdOrNull()
                val accusedName = accusedId?.let { state.player(it)?.name }
                if (accusedId != null && accusedName != null) {
                    hub.sendTo(playerId, ServerMessage.FinalGuessStarted(
                        accusedId = accusedId,
                        accusedName = accusedName,
                        seconds = _timerSeconds.value,
                    ))
                }
            }
            GamePhase.RESULT -> _lastResult.value?.let { hub.sendTo(playerId, it) }
            GamePhase.LOBBY -> { /* лобби рассылается отдельно */ }
        }
    }

    /** Доводит голосование до итога, если фаза ещё VOTING и все онлайн-участники проголосовали. */
    private suspend fun maybeFinishVoting() {
        if (state.phase == GamePhase.VOTING && state.allVoted()) {
            finishVoting()
        }
    }

    private suspend fun finishVoting() {
        // resolveVotingEnd атомарен: конкурирующие корутины не устроят ни двойной
        // подсчёт, ни «результат поверх второго тура».
        when (val end = state.resolveVotingEnd()) {
            is GameState.VotingEnd.Revote -> {
                // Голоса разделились — второй тур между лидерами
                _voteCandidates.value = end.candidateIds
                refreshHostUI()
                hub.broadcast(ServerMessage.VotingStarted(
                    players = state.playersView(),
                    candidateIds = end.candidateIds,
                    isRevote = true,
                ))
                broadcastVoteProgress()
            }
            is GameState.VotingEnd.FinalGuess -> {
                // Обвинённый получает «последнее слово» ДО показа результата —
                // даже если он проголосовал последним и раунд завершился мгновенно.
                _voteCandidates.value = null
                _finalAccusedId.value = end.accusedId
                refreshHostUI()
                hub.broadcast(ServerMessage.FinalGuessStarted(
                    accusedId = end.accusedId,
                    accusedName = end.accusedName,
                    seconds = FINAL_GUESS_SECONDS,
                ))
                startFinalGuessTimer()
            }
            is GameState.VotingEnd.Finished -> {
                broadcastResult()
            }
            is GameState.VotingEnd.NotInVoting -> Unit
        }
    }

    private fun startFinalGuessTimer() {
        finalGuessJob?.cancel()
        _timerSeconds.value = FINAL_GUESS_SECONDS
        finalGuessJob = scope.launch {
            var left = FINAL_GUESS_SECONDS
            while (left > 0) {
                delay(1000)
                left -= 1
                _timerSeconds.value = left
            }
            // Обнуляем ссылку ДО вызова, чтобы finishFinalGuess не отменил сам себя
            finalGuessJob = null
            finishFinalGuess()
        }
    }

    /** Завершить фазу «последнего слова» и показать итог (ровно один раз). */
    private suspend fun finishFinalGuess() {
        finalGuessJob?.cancel()
        finalGuessJob = null
        if (!state.finishFinalGuess()) return
        broadcastResult()
    }

    private suspend fun broadcastResult() {
        val result = state.computeResult()
        _lastResult.value = result
        _phase.value = GamePhase.RESULT
        _voteCandidates.value = null
        _finalAccusedId.value = null
        refreshHostUI()
        hub.broadcast(result)
    }

    private fun refreshHostUI() {
        _playerCount.value = state.connectedCount()
        _players.value = state.playersView()
        _phase.value = state.phase
        // Текущий говорящий
        val speakerId = state.currentSpeakerId()
        val speakerName = speakerId?.let { state.player(it)?.name } ?: ""
        _currentSpeaker.value = if (speakerId == GameState.HOST_ID) "Вы" else speakerName
    }
}
