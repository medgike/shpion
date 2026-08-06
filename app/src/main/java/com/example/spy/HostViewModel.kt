package com.example.spy

import androidx.lifecycle.ViewModel
import com.example.spy.game.GameServer

/**
 * Держит игровой сервер ВНЕ жизненного цикла Activity.
 *
 * Зачем: раньше сервер жил как поле Activity и стартовал в LaunchedEffect, а
 * останавливался в onDestroy/onDispose. Любое изменение конфигурации (поворот
 * экрана, смена языка/темы, split-screen) пересоздаёт Activity → сервер
 * останавливался, все игроки отваливались и раунд сбрасывался.
 *
 * ViewModel переживает изменения конфигурации и очищается (onCleared) только
 * когда Activity действительно завершается — тогда и останавливаем сервер.
 */
class HostViewModel : ViewModel() {

    val server: GameServer = GameServer(port = 8080).also { it.start() }

    override fun onCleared() {
        super.onCleared()
        server.stop()
    }
}
