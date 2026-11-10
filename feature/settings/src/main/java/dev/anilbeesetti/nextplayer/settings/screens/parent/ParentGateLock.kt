package dev.anilbeesetti.nextplayer.settings.screens.parent

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner

/** 从任意 Compose [Context] 往上找到宿主 Activity。 */
internal tailrec fun Context.findActivityOrNull(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivityOrNull()
    else -> null
}

/**
 * App 真正离开前台的兜底：锁屏、按 Home、跳出去支付，都清掉家长解锁状态。
 *
 * 之前这里观察的是 `LocalLifecycleOwner`。在 Navigation 3 里它是**入口级**的 LifecycleOwner，
 * 父页面被新页面压在下面时会收到 ON_STOP ——「进学习报告」于是被误判成「家长离开了」，
 * 返回时自然又要输一次 PIN。改成观察宿主 Activity 的 lifecycle：
 * 页面之间跳转不会让 Activity 停下，只有 App 真的退到后台才会。
 */
@Composable
fun ParentGateBackgroundReset(
    session: ParentGateSession,
    onReset: () -> Unit = {},
) {
    // 只有 ComponentActivity 才带 lifecycle；这里是 Compose 界面的宿主，必然是它。
    val owner = LocalContext.current.findActivityOrNull() as? LifecycleOwner
    DisposableEffect(owner) {
        if (owner == null) return@DisposableEffect onDispose { }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                session.reset()
                onReset()
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}
