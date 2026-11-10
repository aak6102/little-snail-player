package dev.anilbeesetti.nextplayer.settings.screens.parent

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.koin.core.annotation.Single

/**
 * 家长设置的解锁状态。
 *
 * 只活在内存里，进程一死就清空——重启 App 必须重新输 PIN。
 *
 * 它**不是**时间窗口。早先的实现留了「离开 30 秒内免输」，等于给「家长设完就把平板递给孩子」
 * 这个最常见的动作留了门。现在只有两种「真正离开」才清零：
 * 点返回退回设置列表、或者 App 进后台（锁屏、按 Home、跳支付）。
 *
 * 去学习报告页再回来**不算**离开：那还是同一次家长操作，不该让人重输一遍。
 *
 * 状态用 Compose 的 [mutableStateOf]，不再由调用方复制一份 `remember`：
 * 从报告页返回时 entry 的组合会被重建，`remember` 的副本会重新从会话读初值，
 * 一旦两边不同步（会话已锁但副本还是 true，或反过来）就会出现「明明没离开却要重输」。
 * 现在只有这一份真相，界面直接订阅它。
 */
@Single
class ParentGateSession {

    var isUnlocked by mutableStateOf(false)
        private set

    fun markUnlocked() {
        isUnlocked = true
    }

    fun reset() {
        isUnlocked = false
    }
}
