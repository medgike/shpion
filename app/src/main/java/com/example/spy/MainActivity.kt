package com.example.spy

import android.graphics.Bitmap
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.spy.game.GamePhase
import com.example.spy.game.GameServer
import com.example.spy.game.GameSettings
import com.example.spy.game.GameState
import com.example.spy.game.PlayerView
import com.example.spy.game.ServerMessage
import com.example.spy.game.WordBank
import com.example.spy.ui.theme.SpyTheme
import com.example.spy.util.generateQrBitmap
import com.example.spy.util.getLocalIpAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Палитра (единые цвета для контраста)
private val BG = Color(0xFF0F1220)
private val CARD = Color(0xFF1A1F35)
private val CARD2 = Color(0xFF232A47)
private val TXT = Color(0xFFEEF1FF)
private val MUTED = Color(0xFFB4BCDD)
private val ACCENT = Color(0xFFFF5470)
private val ACCENT2 = Color(0xFF5B8CFF)
private val OK = Color(0xFF39D98A)

/**
 * Влезает ли Mr. X в текущий состав. Зеркалит правило раздачи из GameState.startRound:
 * после добавления Mr. X мирных должно остаться БОЛЬШЕ, чем лишних
 * (исключение — дуэль 1 на 1 при двух игроках).
 */
private fun mrXFitsInParty(playerCount: Int, spyCount: Int): Boolean {
    val spyN = spyCount.coerceIn(0, maxOf(1, playerCount / 3))
    val impostors = spyN + 1
    val civilians = playerCount - impostors
    return if (impostors == 1) civilians >= 1 else civilians > impostors
}

/** Минимальное число игроков, при котором Mr. X раздастся при данном числе шпионов. */
private fun minPlayersForMrX(spyCount: Int): Int =
    (2..24).firstOrNull { mrXFitsInParty(it, spyCount) } ?: 24

/** Сколько пар слов доступно при выбранных категориях (пусто = все). */
private fun availablePairs(categories: Set<String>): Int =
    if (categories.isEmpty()) WordBank.pairs.size
    else WordBank.pairs.count { it.category in categories }

class MainActivity : ComponentActivity() {
    // Сервер живёт в ViewModel и переживает повороты экрана / смену конфигурации.
    private val vm: HostViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            SpyTheme {
                HostScreen(vm.server)
            }
        }
    }

    // ────────────────────────────────────────────────
    // ROOT
    // ────────────────────────────────────────────────
    @Composable
    fun HostScreen(srv: GameServer) {
        var localIp by remember { mutableStateOf<String?>(null) }
        val port = srv.port

        LaunchedEffect(Unit) {
            // Определение IP — блокирующий вызов, уводим с main-потока.
            localIp = withContext(Dispatchers.IO) { getLocalIpAddress() }
        }

        val phase by srv.phase.collectAsState()
        val players by srv.players.collectAsState()
        val playerCount by srv.playerCount.collectAsState()
        val timerSeconds by srv.timerSeconds.collectAsState()
        val lastResult by srv.lastResult.collectAsState()
        val hostRole by srv.hostRole.collectAsState()
        val currentSpeaker by srv.currentSpeaker.collectAsState()

        val url = "http://${localIp ?: "?"}:$port"
        val qrBitmap: Bitmap = remember(url) { generateQrBitmap(url) }

        Scaffold(
            containerColor = BG,
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "🕵️ Шпион",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = TXT,
                )
                Text("Ведущий", fontSize = 13.sp, color = MUTED)
                Spacer(Modifier.height(8.dp))

                AnimatedContent(targetState = phase, label = "phase") { currentPhase ->
                    when (currentPhase) {
                        GamePhase.LOBBY -> LobbyView(url, qrBitmap, players, playerCount, srv)
                        GamePhase.DISCUSSION -> DiscussionView(players, timerSeconds, hostRole, currentSpeaker, srv)
                        GamePhase.REVEAL -> DiscussionView(players, timerSeconds, hostRole, currentSpeaker, srv)
                        GamePhase.VOTING -> VotingView(players, srv)
                        GamePhase.FINAL_GUESS -> FinalGuessView(players, timerSeconds, srv)
                        GamePhase.RESULT -> ResultView(lastResult, srv)
                    }
                }
            }
        }
    }

    // ────────────────────────────────────────────────
    // LOBBY (QR + настройки + список игроков + кик)
    // ────────────────────────────────────────────────
    @Composable
    fun LobbyView(
        url: String,
        qrBitmap: Bitmap,
        players: List<PlayerView>,
        playerCount: Int,
        server: GameServer,
    ) {
        val settings = server.state.settings
        var hostName by remember { mutableStateOf("Ведущий") }
        var minutes by remember { mutableStateOf(settings.discussionSeconds / 60) }
        var spies by remember { mutableStateOf(settings.spyCount) }
        var mrX by remember { mutableStateOf(settings.hasMrX) }
        var autoTimer by remember { mutableStateOf(settings.autoTimer) }
        var pickedCategories by remember { mutableStateOf(settings.categories) }

        // применяем настройки на сервер при каждом изменении
        fun push() {
            server.hostUpdateSettings(
                settings.copy(
                    discussionSeconds = minutes * 60,
                    spyCount = spies,
                    hasMrX = mrX,
                    autoTimer = autoTimer,
                    categories = pickedCategories,
                )
            )
        }

        // Сколько шпионов реально можно раздать: не больше 1 на каждые 3 игрока
        // (та же формула, что и при старте раунда). Так число шпионов не «усыхает»
        // молча — хост просто не может выставить больше, чем доступно.
        val maxSpies = maxOf(1, playerCount / 3)
        val minSpies = if (mrX) 0 else 1
        // Если игроков стало меньше (кто-то вышел) — опускаем счётчик до допустимого.
        LaunchedEffect(playerCount, mrX) {
            val clamped = spies.coerceIn(minSpies, maxSpies)
            if (clamped != spies) { spies = clamped; push() }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // --- QR ---
            item {
                SectionCard {
                    Text(
                        "Отсканируйте QR или откройте в браузере:",
                        fontSize = 14.sp, color = MUTED, textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        url, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                        color = ACCENT2, textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(12.dp))
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = "QR-код",
                        modifier = Modifier.size(190.dp).clip(RoundedCornerShape(12.dp)),
                    )
                }
            }

            // --- Ваше имя (хост тоже играет) ---
            item {
                SectionCard {
                    Text("Ваше имя (вы тоже играете)", fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold, color = TXT)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = hostName,
                        onValueChange = {
                            hostName = it.take(16); server.hostUpdateName(hostName)
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 16.sp),
                    )
                }
            }

            // --- Настройки партии ---
            item {
                SectionCard {
                    Text("Настройки партии", fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold, color = TXT)
                    Spacer(Modifier.height(10.dp))
                    // Авто-таймер: 30 сек + 25 сек на игрока
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Авто-время", fontSize = 15.sp, color = TXT,
                                fontWeight = FontWeight.SemiBold)
                            Text("30 сек + 25 сек на игрока (1:30–7:00)",
                                fontSize = 12.sp, color = MUTED)
                        }
                        Button(
                            onClick = { autoTimer = !autoTimer; push() },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (autoTimer) OK else CARD2,
                                contentColor = if (autoTimer) BG else TXT,
                            ),
                        ) { Text(if (autoTimer) "ВКЛ" else "ВЫКЛ", fontSize = 13.sp,
                            fontWeight = FontWeight.Bold) }
                    }
                    Spacer(Modifier.height(8.dp))
                    Stepper(
                        label = "Время обсуждения",
                        value = if (autoTimer) {
                            val auto = GameSettings.autoSeconds(playerCount)
                            "≈${(auto + 59) / 60} мин"
                        } else "$minutes мин",
                        onMinus = { if (!autoTimer && minutes > 1) { minutes--; push() } },
                        onPlus = { if (!autoTimer && minutes < 15) { minutes++; push() } },
                        minusEnabled = !autoTimer && minutes > 1,
                        plusEnabled = !autoTimer && minutes < 15,
                    )
                    Spacer(Modifier.height(8.dp))
                    Stepper(
                        label = "Шпионов",
                        value = "$spies",
                        onMinus = { if (spies > minSpies) { spies--; push() } },
                        onPlus = { if (spies < maxSpies) { spies++; push() } },
                        minusEnabled = spies > minSpies,
                        plusEnabled = spies < maxSpies,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Максимум — 1 шпион на 3 игроков. Игроков: $playerCount → доступно шпионов: $maxSpies.",
                        fontSize = 12.sp, color = MUTED,
                    )
                    Spacer(Modifier.height(10.dp))
                    // Mr. X
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Mr. X", fontSize = 15.sp, color = TXT,
                                fontWeight = FontWeight.SemiBold)
                            Text("Не знает слова вообще — только категорию",
                                fontSize = 12.sp, color = MUTED)
                        }
                        Button(
                            onClick = {
                                mrX = !mrX
                                // Без Mr. X должен быть хотя бы 1 шпион
                                if (!mrX && spies < 1) spies = 1
                                push()
                            },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (mrX) OK else CARD2,
                                contentColor = if (mrX) BG else TXT,
                            ),
                        ) { Text(if (mrX) "ВКЛ" else "ВЫКЛ", fontSize = 13.sp,
                            fontWeight = FontWeight.Bold) }
                    }
                    // Честно предупреждаем, что роль в этом составе не раздастся,
                    // вместо того чтобы молча выдать раунд без Mr. X.
                    if (mrX && !mrXFitsInParty(playerCount, spies)) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "При $playerCount игроках и $spies шпион(ах) Mr. X не выйдет: " +
                                "мирных должно остаться больше, чем лишних. " +
                                "Нужно ${minPlayersForMrX(spies)}+ игроков или меньше шпионов.",
                            fontSize = 12.sp, color = ACCENT,
                        )
                    }
                }
            }

            // --- Категории слов ---
            item {
                SectionCard {
                    Text("Категории слов", fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold, color = TXT)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (pickedCategories.isEmpty()) "Играем всеми (${WordBank.pairs.size} пар)"
                        else "Выбрано: ${pickedCategories.size} — ${availablePairs(pickedCategories)} пар",
                        fontSize = 12.sp, color = MUTED,
                    )
                    Spacer(Modifier.height(10.dp))
                    CategoryChips(
                        selected = pickedCategories,
                        onToggle = { cat ->
                            pickedCategories =
                                if (cat in pickedCategories) pickedCategories - cat
                                else pickedCategories + cat
                            push()
                        },
                        onClear = { pickedCategories = emptySet(); push() },
                    )
                }
            }

            // --- Игроки ---
            item {
                Text(
                    "Игроки ($playerCount):",
                    fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = TXT,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                )
            }
            items(players, key = { it.id }) { player ->
                PlayerRow(
                    player = player,
                    onKick = if (!player.isHost) {
                        { server.hostKickPlayer(player.id) }
                    } else null,
                )
            }

            // --- Старт ---
            item {
                Spacer(Modifier.height(8.dp))
                var showRules by remember { mutableStateOf(false) }
                OutlinedButton(
                    onClick = { showRules = !showRules },
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = RoundedCornerShape(14.dp),
                ) { Text(if (showRules) "Скрыть правила" else "Правила игры", color = TXT) }
                if (showRules) {
                    Spacer(Modifier.height(6.dp))
                    RulesCard(spyCount = spies, hasMrX = mrX)
                }
                Spacer(Modifier.height(10.dp))
                val enough = playerCount >= settings.minPlayers
                Button(
                    onClick = { server.hostStartRound() },
                    enabled = enough,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ACCENT, contentColor = Color.White,
                        disabledContainerColor = CARD2, disabledContentColor = MUTED,
                    ),
                ) {
                    Text(
                        if (!enough) "Нужно минимум ${settings.minPlayers} игрока"
                        else "Начать раунд",
                        fontWeight = FontWeight.Bold, fontSize = 16.sp,
                    )
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }

    // ────────────────────────────────────────────────
    // DISCUSSION (хост видит своё слово + управление)
    // ────────────────────────────────────────────────
    @Composable
    fun DiscussionView(
        players: List<PlayerView>,
        timerSeconds: Int,
        hostRole: ServerMessage.RoleAssignment?,
        currentSpeaker: String,
        server: GameServer,
    ) {
        val mins = timerSeconds / 60
        val secs = timerSeconds % 60
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Слово хоста
            item {
                SectionCard {
                    if (hostRole != null) {
                        Text(hostRole.category.uppercase(), fontSize = 13.sp,
                            color = ACCENT2, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text(hostRole.word, fontSize = 38.sp,
                            fontWeight = FontWeight.Bold, color = TXT)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (hostRole.isMrX)
                                "Вы — Mr. X. Слова нет: вы знаете только категорию. Блефуйте!"
                            else "Это ваше слово. Не выдайте себя.",
                            fontSize = 13.sp, color = MUTED, textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            // Таймер + чей ход
            item {
                SectionCard {
                    Text("Обсуждение", fontSize = 18.sp,
                        fontWeight = FontWeight.Bold, color = TXT)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        String.format("%d:%02d", mins, secs),
                        fontSize = 34.sp, fontWeight = FontWeight.Bold, color = ACCENT2,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (currentSpeaker.isNotEmpty()) "Говорит: $currentSpeaker"
                        else "По кругу называйте ассоциацию",
                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        color = TXT, textAlign = TextAlign.Center,
                    )
                }
            }
            items(players, key = { it.id }) { player ->
                PlayerRow(player)
            }
            item {
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { server.hostNextSpeaker() },
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) { Text("Следующий", color = TXT) }
                    Button(
                        onClick = { server.hostStartVoting() },
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ACCENT, contentColor = Color.White),
                    ) { Text("Голосование", fontWeight = FontWeight.Bold) }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }

    // ────────────────────────────────────────────────
    // VOTING (хост голосует как игрок)
    // ────────────────────────────────────────────────
    @Composable
    fun VotingView(
        players: List<PlayerView>,
        server: GameServer,
    ) {
        // Кандидаты второго тура (null — обычное голосование). Смена тура
        // пересоздаёт состояние voted — голос хоста сбрасывается вместе с серверным.
        val revoteCandidates by server.voteCandidates.collectAsState()
        var voted by remember(revoteCandidates) { mutableStateOf<String?>(null) }
        var guessed by remember { mutableStateOf<String?>(null) }
        val hostRole = server.hostRole.collectAsState().value
        val isMrX = hostRole?.isMrX == true
        val guessOptions = hostRole?.guessOptions ?: emptyList()
        val isRevote = revoteCandidates != null

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                SectionCard {
                    Text(
                        when {
                            isRevote -> "Переголосование!"
                            isMrX -> "Кто лишний?"
                            else -> "Кто шпион?"
                        },
                        fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        color = if (isRevote) ACCENT else TXT,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        when {
                            voted != null -> "Ваш голос учтён"
                            isRevote -> "Голоса разделились — выберите между лидерами"
                            else -> "Выберите подозреваемого"
                        },
                        fontSize = 13.sp, color = MUTED, textAlign = TextAlign.Center,
                    )
                }
            }
            // Кандидаты: во втором туре — только лидеры; всегда кроме самого хоста
            items(
                players.filter { p ->
                    p.id != GameState.HOST_ID &&
                        (revoteCandidates?.contains(p.id) ?: true)
                },
                key = { it.id },
            ) { player ->
                val selected = voted == player.id
                Button(
                    onClick = { voted = player.id; server.hostVoteFor(player.id) },
                    modifier = Modifier.fillMaxWidth().height(50.dp).padding(vertical = 3.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (selected) ACCENT else CARD2,
                        contentColor = if (selected) Color.White else TXT,
                    ),
                ) { Text(player.name, fontWeight = FontWeight.SemiBold) }
            }
            // Догадка о слове мирных: у Mr. X — сразу, у остальных — после голоса.
            // Сетка одинаковая у всех и включает слово шпиона как приманку.
            if (guessOptions.isNotEmpty() && (isMrX || voted != null || guessed != null)) {
                item {
                    Spacer(Modifier.height(10.dp))
                    SectionCard {
                        Text(
                            if (isMrX) "Вы — Mr. X! Угадайте слово мирных:"
                            else "Думаете, вы шпион? Угадайте слово мирных (по желанию):",
                            fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = ACCENT2,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(8.dp))
                        guessOptions.forEach { word ->
                            val sel = guessed == word
                            Button(
                                onClick = {
                                    guessed = word
                                    server.hostSpyGuess(word)
                                },
                                modifier = Modifier.fillMaxWidth().height(46.dp)
                                    .padding(vertical = 2.dp),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (sel) ACCENT2 else CARD2,
                                    contentColor = if (sel) Color.White else TXT,
                                ),
                            ) { Text(word, fontWeight = FontWeight.SemiBold) }
                        }
                    }
                }
            }
        }
    }

    // ────────────────────────────────────────────────
    // FINAL GUESS («последнее слово» обвинённого)
    // ────────────────────────────────────────────────
    @Composable
    fun FinalGuessView(
        players: List<PlayerView>,
        timerSeconds: Int,
        server: GameServer,
    ) {
        val accusedId by server.finalAccusedId.collectAsState()
        val hostRole = server.hostRole.collectAsState().value
        val guessOptions = hostRole?.guessOptions ?: emptyList()
        var guessed by remember { mutableStateOf<String?>(null) }
        val accusedName = players.firstOrNull { it.id == accusedId }?.name ?: "…"
        val hostAccused = accusedId == GameState.HOST_ID

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                SectionCard {
                    Text(
                        if (hostAccused) "Вас обвинили!" else "Обвинили: $accusedName",
                        fontSize = 18.sp, fontWeight = FontWeight.Bold, color = ACCENT,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (hostAccused)
                            "Если вы лишний — угадайте слово мирных, это спасёт вас до ничьей. Если мирный — пропустите. Выбор окончателен: первое же нажатие завершает раунд."
                        else
                            "Ждём его последнего слова… Если он лишний — может угадать слово мирных и спастись до ничьей.",
                        fontSize = 13.sp, color = MUTED, textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "$timerSeconds сек",
                        fontSize = 24.sp, fontWeight = FontWeight.Bold, color = ACCENT2,
                    )
                }
            }
            if (hostAccused) {
                if (guessOptions.isNotEmpty()) {
                    item {
                        SectionCard {
                            guessOptions.forEach { word ->
                                val sel = guessed == word
                                Button(
                                    onClick = {
                                        guessed = word
                                        // Выбор слова = финальное решение, раунд завершается
                                        server.hostSpyGuess(word)
                                    },
                                    modifier = Modifier.fillMaxWidth().height(46.dp)
                                        .padding(vertical = 2.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (sel) ACCENT2 else CARD2,
                                        contentColor = if (sel) Color.White else TXT,
                                    ),
                                ) { Text(word, fontWeight = FontWeight.SemiBold) }
                            }
                        }
                    }
                }
                item {
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = { server.hostSkipFinalGuess() },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) { Text("Я мирный — пропустить", color = TXT) }
                    Spacer(Modifier.height(20.dp))
                }
            }
        }
    }

    // ────────────────────────────────────────────────
    // RESULT
    // ────────────────────────────────────────────────
    @Composable
    fun ResultView(result: ServerMessage.RoundResult?, server: GameServer) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                SectionCard {
                    Text("Итоги", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TXT)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        result?.message ?: "",
                        fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                        color = when (result?.outcome) {
                            ServerMessage.RoundResult.Outcome.CIVILIANS -> OK
                            ServerMessage.RoundResult.Outcome.IMPOSTORS -> ACCENT
                            ServerMessage.RoundResult.Outcome.DRAW -> ACCENT2
                            else -> TXT
                        },
                        textAlign = TextAlign.Center, lineHeight = 24.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    // Шпион — показываем только если шпионы были
                    if (!result?.spyNames.isNullOrEmpty()) {
                        Text("Шпион: ${result?.spyNames?.joinToString() ?: "—"}",
                            fontSize = 15.sp, color = ACCENT)
                        Spacer(Modifier.height(4.dp))
                    }
                    // Mr. X раскрываем наравне со шпионом — иначе после раунда
                    // так и остаётся непонятно, кого нужно было ловить.
                    result?.mrXName?.let { name ->
                        Text("Mr. X: $name", fontSize = 15.sp, color = ACCENT)
                        Spacer(Modifier.height(4.dp))
                    }
                    Text("Слово мирных: ${result?.civilianWord ?: "—"}",
                        fontSize = 15.sp, color = OK)
                    // Слово шпиона — только если шпионы были
                    if (!result?.spyNames.isNullOrEmpty()) {
                        Text("Слово шпиона: ${result?.spyWord ?: "—"}",
                            fontSize = 15.sp, color = ACCENT2)
                    }
                    if (result?.accusedName != null) {
                        Spacer(Modifier.height(4.dp))
                        Text("Обвинили: ${result.accusedName}", fontSize = 14.sp, color = MUTED)
                    }
                }
            }
            // Таблица очков партии
            if (!result?.scores.isNullOrEmpty()) {
                item {
                    SectionCard {
                        Text("Очки партии", fontSize = 16.sp,
                            fontWeight = FontWeight.Bold, color = TXT)
                        Spacer(Modifier.height(6.dp))
                        result?.scores?.forEachIndexed { i, p ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    (if (i == 0) "👑 " else "") + p.name +
                                        if (p.isHost) " (вы)" else "",
                                    fontSize = 14.sp, color = TXT,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f),
                                )
                                Text("★ ${p.score}", fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold, color = ACCENT2)
                            }
                        }
                    }
                }
            }
            item {
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = { server.hostBackToLobby() },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ACCENT2, contentColor = Color.White),
                ) { Text("Новый раунд", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
                Spacer(Modifier.height(20.dp))
            }
        }
    }

    // ────────────────────────────────────────────────
    // Переиспользуемые кусочки
    // ────────────────────────────────────────────────
    @Composable
    fun SectionCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = CARD),
            modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        ) {
            Column(
                modifier = Modifier.padding(18.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                content = content,
            )
        }
    }

    @Composable
    fun Stepper(
        label: String,
        value: String,
        onMinus: () -> Unit,
        onPlus: () -> Unit,
        minusEnabled: Boolean = true,
        plusEnabled: Boolean = true,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, fontSize = 15.sp, color = TXT, modifier = Modifier.weight(1f))
            OutlinedButton(
                onClick = onMinus,
                enabled = minusEnabled,
                modifier = Modifier.size(44.dp),
                shape = RoundedCornerShape(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            ) { Text("–", fontSize = 20.sp, color = if (minusEnabled) TXT else MUTED) }
            Text(
                value, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TXT,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(64.dp),
            )
            OutlinedButton(
                onClick = onPlus,
                enabled = plusEnabled,
                modifier = Modifier.size(44.dp),
                shape = RoundedCornerShape(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            ) { Text("+", fontSize = 20.sp, color = if (plusEnabled) TXT else MUTED) }
        }
    }

    /** Сетка категорий: тап переключает категорию, пустой выбор = играем всем банком. */
    @Composable
    fun CategoryChips(
        selected: Set<String>,
        onToggle: (String) -> Unit,
        onClear: () -> Unit,
    ) {
        Column(Modifier.fillMaxWidth()) {
            WordBank.categories.chunked(2).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    row.forEach { cat ->
                        val on = cat in selected
                        Button(
                            onClick = { onToggle(cat) },
                            modifier = Modifier.weight(1f).height(42.dp),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                horizontal = 6.dp,
                            ),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (on) ACCENT2 else CARD2,
                                contentColor = if (on) Color.White else TXT,
                            ),
                        ) {
                            Text(cat, fontSize = 12.sp, maxLines = 1,
                                fontWeight = FontWeight.SemiBold)
                        }
                    }
                    // Одиночную кнопку в неполном ряду не растягиваем на всю ширину
                    if (row.size < 2) Spacer(Modifier.weight(1f))
                }
            }
            if (selected.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onClear,
                    modifier = Modifier.fillMaxWidth().height(42.dp),
                    shape = RoundedCornerShape(10.dp),
                ) { Text("Сбросить — играть всеми", color = TXT, fontSize = 13.sp) }
            }
        }
    }

    @Composable
    fun PlayerRow(player: PlayerView, onKick: (() -> Unit)? = null) {
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = CARD2),
            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.size(10.dp).clip(CircleShape)
                        .background(if (player.connected) OK else Color(0xFF5A5F7A))
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    player.name,
                    fontWeight = FontWeight.SemiBold, color = TXT,
                    modifier = Modifier.weight(1f),
                )
                Text("★ ${player.score}", fontSize = 12.sp, color = MUTED)
                Spacer(Modifier.width(8.dp))
                if (player.isHost) {
                    Text("вы (ведущий)", fontSize = 11.sp, color = ACCENT2)
                }
                if (onKick != null) {
                    TextButton(onClick = onKick) {
                        Text("Кикнуть", fontSize = 13.sp, color = ACCENT)
                    }
                }
            }
        }
    }

    @Composable
    fun RulesCard(spyCount: Int, hasMrX: Boolean) {
        val onlySpy = spyCount > 0 && !hasMrX
        val onlyMrX = spyCount == 0 && hasMrX
        val both = spyCount > 0 && hasMrX

        SectionCard {
            Text("Правила", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TXT)
            Spacer(Modifier.height(8.dp))

            val rules = buildString {
                appendLine("Каждому тайно выдаётся слово (или роль). По кругу все называют ПО ОДНОЙ ассоциации к своему слову — одно слово или короткую фразу. Порядок хода перемешивается каждый раунд. Совет: два круга ассоциаций — и голосуйте.")
                appendLine()
                appendLine("Затем голосование: каждый отдаёт один голос за того, кого считает лишним. Обвинённым становится тот, у кого голосов СТРОГО больше всех. При равенстве — ПЕРЕГОЛОСОВАНИЕ между лидерами (один раз). Если и второй тур вничью — никого не обвинили.")
                appendLine()
                appendLine("У обвинённого есть «ПОСЛЕДНЕЕ СЛОВО» (30 сек): если он лишний — может угадать слово мирных и спастись до ничьей; если мирный — просто пропускает.")
                appendLine()

                if (onlySpy) {
                    appendLine("РЕЖИМ: Шпион")
                    appendLine()
                    appendLine("Большинство получает одно и то же слово. Шпион — ПОХОЖЕЕ, но другое слово из той же категории (например, «Чай» вместо «Кофе»). Шпион сам не знает наверняка, что он шпион.")
                    appendLine()
                    appendLine("Мирные: по ассоциациям вычислить того, кто описывает «не то» слово.")
                    appendLine("Шпион: маскироваться — давать ассоциации, подходящие обоим словам. Если поймают, можно один раз попытаться назвать слово мирных.")
                    appendLine()
                    appendLine("ИСХОДЫ:")
                    appendLine("• Поймали шпиона, слово он НЕ угадал → Мирные победили.")
                    appendLine("• Поймали шпиона, но он УГАДАЛ слово мирных (из вариантов) → Ничья.")
                    appendLine("• Обвинили мирного или дважды разделились голоса → Шпион победил.")
                    appendLine("• Шпиона не нашли, да ещё он угадал слово → Шпион победил вдвойне.")
                    appendLine()
                    appendLine("Если шпионов ДВОЕ: у обоих одинаковое слово, и они не знают друг друга. За раунд ловят одного — обвинили любого из шпионов → мирные победили.")
                } else if (onlyMrX) {
                    appendLine("РЕЖИМ: Mr. X")
                    appendLine()
                    appendLine("Все получают одинаковое слово, кроме Mr. X. Он не знает НИЧЕГО, кроме категории — слова у него нет вообще. Ему приходится по чужим ассоциациям вычислять слово и одновременно выдавать свои, чтобы не спалиться.")
                    appendLine()
                    appendLine("Мирные: вычислить того, кто «плавает» и не знает слова.")
                    appendLine("Mr. X: понять тему по ассоциациям и не выдать себя. Если поймают — выбрать слово мирных из 6 вариантов.")
                    appendLine()
                    appendLine("ИСХОДЫ:")
                    appendLine("• Поймали Mr. X, слово он НЕ угадал → Мирные победили.")
                    appendLine("• Поймали Mr. X, но он УГАДАЛ слово → Ничья.")
                    appendLine("• Обвинили мирного или дважды разделились голоса → Mr. X победил.")
                } else if (both) {
                    appendLine("РЕЖИМ: Шпион + Mr. X")
                    appendLine()
                    appendLine("Большинство — одно слово. Шпион(ы) — похожее другое. Mr. X — вообще без слова, только категория. Лишних несколько, но обвинить за раунд можно только одного.")
                    appendLine()
                    appendLine("Mr. X раздаётся только если мирных всё равно остаётся больше, чем лишних — иначе роль в этом раунде пропускается.")
                    appendLine()
                    appendLine("Мирные: найти любого лишнего.")
                    appendLine("Шпион: маскироваться; если пойман — угадать слово мирных.")
                    appendLine("Mr. X: блефовать; если пойман — выбрать слово из 6 вариантов.")
                    appendLine()
                    appendLine("ИСХОДЫ:")
                    appendLine("• Поймали лишнего (шпиона ИЛИ Mr. X), слово он НЕ угадал → Мирные победили.")
                    appendLine("• Поймали лишнего, и он УГАДАЛ слово мирных → Ничья.")
                    appendLine("• Обвинили мирного или дважды разделились голоса → Лишние победили.")
                }
                appendLine()
                appendLine("ОЧКИ (копятся всю партию). Платят за твою собственную дедукцию, а не за удачу оказаться в победившей команде:")
                appendLine("• Твой голос попал в лишнего → +2 лично (даже если общий голос ушёл не туда).")
                appendLine("• Лишнего в итоге поймали → ещё +1 каждому мирному.")
                appendLine("• Шпион не пойман → +4. Mr. X не пойман → +5 (роль сложнее).")
                appendLine("• Непойманный лишний угадал слово → ещё +1.")
                appendLine("• Пойманный лишний угадал слово → +2 ему (ничья).")
                appendLine("• Лишний уцелел, но спалился напарник → половина награды.")
            }

            Text(
                rules.trimEnd(),
                fontSize = 13.sp,
                color = MUTED,
                lineHeight = 20.sp,
            )
        }
    }
}
