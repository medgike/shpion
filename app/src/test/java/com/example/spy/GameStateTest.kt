package com.example.spy

import com.example.spy.game.GamePhase
import com.example.spy.game.GameSettings
import com.example.spy.game.GameState
import com.example.spy.game.Role
import com.example.spy.game.ServerMessage
import com.example.spy.game.WordBank
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Тесты игровой логики: раздача ролей, баланс составов и начисление очков.
 * GameState не тянет Android — гоняется обычным JVM-тестом.
 */
class GameStateTest {

    /** Собирает лобби из [n] игроков (хост + n-1 браузерных). Возвращает состояние и id игроков. */
    private fun lobby(n: Int, settings: GameSettings, seed: Int = 1): Pair<GameState, List<String>> {
        val state = GameState(settings, Random(seed))
        state.ensureHost("Ведущий")
        val ids = mutableListOf(GameState.HOST_ID)
        repeat(n - 1) { i -> ids += state.joinOrReconnect("Игрок$i", null, false).first.id }
        return state to ids
    }

    private fun GameState.rolesOf(ids: List<String>) = ids.map { player(it)!!.role }

    // ---------- Раздача ролей и баланс состава ----------

    @Test
    fun `mr X не раздаётся, когда лишних оказалось бы больше мирных`() {
        // Трое: 1 шпион + Mr. X = двое лишних против одного мирного.
        // Такой раунд мирный не может выиграть в принципе — роль обязана пропуститься.
        repeat(50) { seed ->
            val (state, ids) = lobby(
                n = 3,
                settings = GameSettings(spyCount = 1, hasMrX = true),
                seed = seed,
            )
            assertTrue(state.startRound())
            val roles = state.rolesOf(ids)
            assertEquals("seed=$seed", 0, roles.count { it == Role.MR_X })
            assertEquals("seed=$seed", 1, roles.count { it == Role.SPY })
        }
    }

    @Test
    fun `mr X раздаётся вместе со шпионом, когда мирных строго больше`() {
        // Пятеро: 1 шпион + Mr. X = двое лишних против троих мирных.
        repeat(50) { seed ->
            val (state, ids) = lobby(
                n = 5,
                settings = GameSettings(spyCount = 1, hasMrX = true),
                seed = seed,
            )
            assertTrue(state.startRound())
            val roles = state.rolesOf(ids)
            assertEquals("seed=$seed", 1, roles.count { it == Role.MR_X })
            assertEquals("seed=$seed", 1, roles.count { it == Role.SPY })
        }
    }

    @Test
    fun `в режиме только Mr X шпионов нет, а слово мирных общее`() {
        val (state, ids) = lobby(n = 5, settings = GameSettings(spyCount = 0, hasMrX = true))
        assertTrue(state.startRound())

        val roles = state.rolesOf(ids)
        assertEquals(1, roles.count { it == Role.MR_X })
        assertEquals(0, roles.count { it == Role.SPY })

        val mrX = ids.first { state.player(it)!!.isMrX }
        val civilianWords = ids.filter { it != mrX }.map { state.player(it)!!.word }.distinct()
        assertEquals("у всех мирных одно и то же слово", 1, civilianWords.size)
        assertEquals(GameState.MR_X_WORD, state.player(mrX)!!.word)
    }

    @Test
    fun `mr X знает категорию, но не получает слова и видит варианты для догадки`() {
        val (state, ids) = lobby(n = 5, settings = GameSettings(spyCount = 0, hasMrX = true))
        assertTrue(state.startRound())
        val mrX = ids.first { state.player(it)!!.isMrX }

        val role = state.roleFor(mrX)!!
        assertTrue("Mr. X помечен явным флагом, а не строкой-заглушкой", role.isMrX)
        assertEquals(GameState.MR_X_WORD, role.word)
        assertTrue("категория — единственное, что он знает", role.category.isNotBlank())

        // Список вариантов одинаков у всех, поэтому сам по себе никого не выдаёт
        val civilian = ids.first { !state.player(it)!!.isImpostor }
        assertEquals(role.guessOptions, state.roleFor(civilian)!!.guessOptions)
        assertTrue(
            "среди вариантов обязано быть настоящее слово мирных",
            state.player(civilian)!!.word in role.guessOptions,
        )
    }

    @Test
    fun `роль Mr X достаётся не только ведущему`() {
        // Регрессия: раньше при малом составе Mr. X всегда выпадал хосту.
        val hostGotIt = (0 until 200).count { seed ->
            val (state, _) = lobby(4, GameSettings(spyCount = 0, hasMrX = true), seed)
            state.startRound()
            state.player(GameState.HOST_ID)!!.isMrX
        }
        assertTrue("хост стал Mr. X $hostGotIt/200 раз — распределение перекошено",
            hostGotIt in 20..80)
    }

    // ---------- Очки ----------

    /** Прогоняет раунд до итога: все мирные голосуют за [suspect], лишний — за первого мирного. */
    private fun playRound(
        state: GameState,
        ids: List<String>,
        suspect: String,
        finalGuess: String? = null,
    ): ServerMessage.RoundResult {
        assertTrue(state.startVoting())
        val impostor = ids.first { state.player(it)!!.isImpostor }
        val firstCivilian = ids.first { !state.player(it)!!.isImpostor }
        ids.forEach { id ->
            val target = if (id == impostor) firstCivilian else suspect
            if (target != id) state.castVote(id, target)
        }
        when (val end = state.resolveVotingEnd()) {
            is GameState.VotingEnd.FinalGuess -> {
                if (finalGuess != null) state.setSpyGuess(end.accusedId, finalGuess)
                assertTrue(state.finishFinalGuess())
            }
            is GameState.VotingEnd.Finished -> Unit
            else -> throw AssertionError("неожиданный итог голосования: $end")
        }
        return state.computeResult()
    }

    @Test
    fun `поимка Mr X - мирным очки за личное попадание плюс командный бонус`() {
        val (state, ids) = lobby(n = 4, settings = GameSettings(spyCount = 0, hasMrX = true))
        assertTrue(state.startRound())
        val mrX = ids.first { state.player(it)!!.isMrX }

        val result = playRound(state, ids, suspect = mrX)

        assertEquals(ServerMessage.RoundResult.Outcome.CIVILIANS, result.outcome)
        assertEquals("Mr. X теперь раскрывается в итогах", state.player(mrX)!!.name, result.mrXName)
        assertEquals(mrX, result.mrXId)
        assertEquals(mrX, result.accusedId)

        // +2 за собственный точный голос, +1 командный бонус за поимку
        ids.filter { it != mrX }.forEach {
            assertEquals("мирный ${state.player(it)!!.name}", 3, state.player(it)!!.score)
        }
        assertEquals("пойманный без догадки уходит в ноль", 0, state.player(mrX)!!.score)
    }

    @Test
    fun `пойманный Mr X угадал слово мирных - ничья и плюс два ему`() {
        val (state, ids) = lobby(n = 4, settings = GameSettings(spyCount = 0, hasMrX = true))
        assertTrue(state.startRound())
        val mrX = ids.first { state.player(it)!!.isMrX }
        val civilianWord = state.player(ids.first { it != mrX })!!.word!!

        val result = playRound(state, ids, suspect = mrX, finalGuess = civilianWord)

        assertEquals(ServerMessage.RoundResult.Outcome.DRAW, result.outcome)
        assertEquals(2, state.player(mrX)!!.score)
    }

    @Test
    fun `непойманный Mr X получает пять очков, мирные - ноль`() {
        val (state, ids) = lobby(n = 4, settings = GameSettings(spyCount = 0, hasMrX = true))
        assertTrue(state.startRound())
        val mrX = ids.first { state.player(it)!!.isMrX }
        // Все обвиняют мирного, который точно не Mr. X
        val scapegoat = ids.first { it != mrX && it != ids.first { id -> !state.player(id)!!.isImpostor } }

        val result = playRound(state, ids, suspect = scapegoat)

        assertEquals(ServerMessage.RoundResult.Outcome.IMPOSTORS, result.outcome)
        assertEquals("Mr. X сложнее шпиона, поэтому награда выше", 5, state.player(mrX)!!.score)
        ids.filter { it != mrX }.forEach {
            assertEquals("мирные не получают ничего за провал", 0, state.player(it)!!.score)
        }
    }

    @Test
    fun `точный голос приносит очки даже когда общий голос ушёл не туда`() {
        // Ключевая правка баланса: раньше выгоднее было плыть по течению —
        // командный бонус (+2) был больше личного попадания (+1).
        val (state, ids) = lobby(n = 5, settings = GameSettings(spyCount = 0, hasMrX = true))
        assertTrue(state.startRound())
        val mrX = ids.first { state.player(it)!!.isMrX }
        val civilians = ids.filter { it != mrX }
        val scapegoat = civilians[0]
        val sharpEye = civilians[1] // единственный, кто угадал верно

        assertTrue(state.startVoting())
        state.castVote(mrX, scapegoat)
        state.castVote(sharpEye, mrX)
        civilians.drop(2).forEach { state.castVote(it, scapegoat) }
        state.castVote(scapegoat, civilians[2])

        // scapegoat: 3 голоса (mrX + двое), mrX: 1 (sharpEye) -> обвинён мирный
        val end = state.resolveVotingEnd()
        assertTrue(end is GameState.VotingEnd.FinalGuess)
        assertEquals(scapegoat, (end as GameState.VotingEnd.FinalGuess).accusedId)
        state.finishFinalGuess()
        state.computeResult()

        assertEquals("попал в лишнего — получил своё, несмотря на проигрыш",
            2, state.player(sharpEye)!!.score)
        assertEquals(0, state.player(scapegoat)!!.score)
    }

    // ---------- Приём догадки ----------

    @Test
    fun `догадка не принимается вне голосования и последнего слова`() {
        val (state, ids) = lobby(n = 4, settings = GameSettings(spyCount = 0, hasMrX = true))
        assertTrue(state.startRound())
        val mrX = ids.first { state.player(it)!!.isMrX }
        val civilianWord = state.player(ids.first { it != mrX })!!.word!!

        // Фаза обсуждения — рано
        assertEquals(GamePhase.DISCUSSION, state.phase)
        state.setSpyGuess(mrX, civilianWord)
        assertNull("в обсуждении догадку принимать нельзя", state.player(mrX)!!.spyGuess)

        // Голосование — можно
        assertTrue(state.startVoting())
        state.setSpyGuess(mrX, civilianWord)
        assertEquals(civilianWord, state.player(mrX)!!.spyGuess)
    }

    @Test
    fun `в последнем слове отвечает только сам обвинённый`() {
        val (state, ids) = lobby(n = 5, settings = GameSettings(spyCount = 1, hasMrX = true))
        assertTrue(state.startRound())
        val mrX = ids.first { state.player(it)!!.isMrX }
        val spy = ids.first { state.player(it)!!.isSpy }
        val civilians = ids.filter { it != mrX && it != spy }
        val civilianWord = state.player(civilians.first())!!.word!!

        assertTrue(state.startVoting())
        // Все обвиняют Mr. X
        ids.filter { it != mrX }.forEach { state.castVote(it, mrX) }
        state.castVote(mrX, civilians.first())

        val end = state.resolveVotingEnd() as GameState.VotingEnd.FinalGuess
        assertEquals(mrX, end.accusedId)

        // Шпион пытается дослать догадку, пока идёт чужое «последнее слово»
        state.setSpyGuess(spy, civilianWord)
        assertNull("не обвинённый не может добрать бонус задним числом",
            state.player(spy)!!.spyGuess)

        state.setSpyGuess(mrX, civilianWord)
        assertEquals(civilianWord, state.player(mrX)!!.spyGuess)
    }

    @Test
    fun `зритель, вошедший посреди раунда, не может голосовать и не блокирует итог`() {
        val (state, ids) = lobby(n = 4, settings = GameSettings(spyCount = 0, hasMrX = true))
        assertTrue(state.startRound())
        // Новый игрок заходит после раздачи ролей — слова у него нет
        val spectator = state.joinOrReconnect("Опоздавший", null, false).first.id
        assertNull(state.player(spectator)!!.word)

        assertTrue(state.startVoting())
        assertEquals("голос зрителя не принимается", false, state.castVote(spectator, ids[1]))

        // Участники голосуют — зритель не мешает allVoted
        val mrX = ids.first { state.player(it)!!.isMrX }
        ids.forEach { id -> state.castVote(id, if (id == mrX) ids.first { it != mrX } else mrX) }
        assertTrue("зритель без голоса не блокирует завершение", state.allVoted())
    }

    @Test
    fun `кик обвинённого в последнем слове не повышает второе место до обвинённого`() {
        val (state, ids) = lobby(n = 5, settings = GameSettings(spyCount = 0, hasMrX = true))
        assertTrue(state.startRound())
        val mrX = ids.first { state.player(it)!!.isMrX }
        val civilians = ids.filter { it != mrX }

        assertTrue(state.startVoting())
        // 3 голоса за Mr. X, 1 — за мирного (второе место)
        civilians.take(3).forEach { state.castVote(it, mrX) }
        state.castVote(mrX, civilians[0])
        state.castVote(civilians[3], mrX)

        val end = state.resolveVotingEnd()
        assertTrue(end is GameState.VotingEnd.FinalGuess)
        assertEquals(mrX, (end as GameState.VotingEnd.FinalGuess).accusedId)

        // Обвинённый рейдж-квитит — ведущий его кикает
        assertTrue(state.kickPlayer(mrX))
        assertTrue(state.finishFinalGuess())
        val result = state.computeResult()

        // Старый баг: голоса за кикнутого выпадали из подсчёта, и «обвинённым»
        // в итогах становился мирный с одним голосом, которого никто не обвинял.
        assertNull("обвинение несостоялось, а не переиграно", result.accusedId)
        assertNull(result.accusedName)
    }

    // ---------- Категории и таймер ----------

    @Test
    fun `выбранные категории ограничивают банк слов`() {
        val (state, _) = lobby(
            n = 4,
            settings = GameSettings(spyCount = 0, hasMrX = true, categories = setOf("Животные")),
        )
        repeat(10) {
            assertTrue(state.startRound())
            assertEquals("Животные", state.roleFor(GameState.HOST_ID)!!.category)
            state.resetToLobby()
        }
    }

    @Test
    fun `авто-таймер зажат в разумные границы`() {
        assertEquals("втроём не должно быть 105 сек в обрез", 105, GameSettings.autoSeconds(3))
        assertEquals(90, GameSettings.autoSeconds(2))   // нижний порог
        assertEquals(420, GameSettings.autoSeconds(20)) // верхний потолок
    }

    @Test
    fun `банк слов целостен`() {
        val pairs = WordBank.pairs
        // usedPairs — это Set<WordPair>, дубликат схлопнулся бы и «съел» раунд без повтора
        assertEquals("в банке есть дубликаты пар", pairs.size, pairs.toSet().size)
        pairs.forEach {
            assertTrue("пара из одинаковых слов: $it", it.civilian != it.spy)
            assertTrue("пустое слово в паре: $it",
                it.civilian.isNotBlank() && it.spy.isNotBlank())
        }
        // В каждой категории должно хватать слов на сетку из 6 вариантов для догадки
        WordBank.categories.forEach { cat ->
            val words = pairs.filter { it.category == cat }
                .flatMap { listOf(it.civilian, it.spy) }.distinct()
            assertTrue("категория '$cat': всего ${words.size} слов, мало на 6 вариантов",
                words.size >= 6)
        }
    }

    @Test
    fun `пары слов не повторяются, пока банк не исчерпан`() {
        val (state, _) = lobby(
            n = 4,
            settings = GameSettings(spyCount = 0, hasMrX = true, categories = setOf("Праздники")),
        )
        val seen = mutableSetOf<String>()
        repeat(3) {
            assertTrue(state.startRound())
            val word = state.player(GameState.HOST_ID)!!.word!!
            assertNotNull(word)
            seen += word
            state.resetToLobby()
        }
        assertEquals("подряд выпали одинаковые слова", 3, seen.size)
    }
}
