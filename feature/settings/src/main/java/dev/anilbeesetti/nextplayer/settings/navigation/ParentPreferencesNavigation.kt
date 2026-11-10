package dev.anilbeesetti.nextplayer.settings.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import dev.anilbeesetti.nextplayer.settings.screens.parent.GateMode
import dev.anilbeesetti.nextplayer.settings.screens.parent.ParentGateBackgroundReset
import dev.anilbeesetti.nextplayer.settings.screens.parent.ParentGateScreen
import dev.anilbeesetti.nextplayer.settings.screens.parent.ParentGateSession
import dev.anilbeesetti.nextplayer.settings.screens.parent.ParentPreferencesScreen
import dev.anilbeesetti.nextplayer.settings.screens.parent.ParentPreferencesViewModel
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * 家长设置路由。
 *
 * 门禁规则：**离开就锁，进来必输**。
 *
 * 解锁状态存在 [ParentGateSession] 里，界面直接订阅它，不再往 `remember` 里复制一份。
 *
 * 什么算「真正离开」：
 * - 这个路由从返回栈里被弹掉（左上角返回 / 系统返回手势）；
 * - App 退到前台之下（锁屏、Home、跳去支付）。
 *
 * 去学习报告**不算**：那条路由是压在家长设置之上的，家长设置还在栈里。
 * 这一点早先用「页面 lifecycle 收到 ON_STOP」来判断，在 Navigation 3 下是错的——
 * 被压在下面的入口本身就会走一遍 ON_STOP，于是「看报告」被当成「离开了」，
 * 返回就得重输 PIN。现在改成看返回栈 + 宿主 Activity 的 lifecycle，两件事才分得开。
 */
@Serializable
object ParentPreferencesRoute : NavKey

fun NavBackStack<NavKey>.navigateToParentPreferences() {
    add(ParentPreferencesRoute)
}

fun EntryProviderScope<NavKey>.parentPreferencesEntry(
    backStack: NavBackStack<NavKey>,
    onNavigateUp: () -> Unit,
    onNavigateToReport: () -> Unit,
) {
    entry<ParentPreferencesRoute> {
        val session = koinInject<ParentGateSession>()

        // 改密码就地切门禁页，不新增路由：多一条路由就要多处理一次「改完回哪」，
        // 而这里语义很清楚——改完回到这一页，依然是解锁的。
        // 用 remember 而不是 rememberSaveable：进程恢复回来停在「改密码」页没有意义。
        var changingPin by remember { mutableStateOf(false) }

        // 兜底：路由真的从栈里消失了才算离开。跳学习报告时它还在栈里，不动。
        DisposableEffect(backStack) {
            onDispose {
                if (!backStack.contains(ParentPreferencesRoute)) session.reset()
            }
        }

        // App 退到后台才算离开。这里观察的是宿主 Activity，页面跳转不会触发。
        ParentGateBackgroundReset(session) { changingPin = false }

        if (!session.isUnlocked || changingPin) {
            ParentGateScreen(
                mode = if (changingPin) GateMode.ChangePin else GateMode.Unlock,
                onPassed = {
                    session.markUnlocked()
                    changingPin = false
                },
                onCancel = {
                    if (changingPin) changingPin = false else onNavigateUp()
                },
            )
        } else {
            // 系统返回手势只在「家长设置是栈顶」时才当作离开。
            // 不加这个判断的话，在报告页按返回也会走到这里——把还压在栈底的家长设置一起锁掉，
            // 于是返回后又要重输一次 PIN。
            BackHandler(enabled = backStack.lastOrNull() == ParentPreferencesRoute) {
                session.reset()
                onNavigateUp()
            }

            val output = ParentPreferencesViewModel.Output(
                // 走返回键才是真正离开：清掉会话，下次进入必须重输。
                // 去学习报告用的是 navigateToReport，不经过这里，所以返回时仍免密。
                navigateUp = {
                    session.reset()
                    onNavigateUp()
                },
                navigateToReport = onNavigateToReport,
            )
            val viewModel = koinViewModel<ParentPreferencesViewModel>(
                parameters = { parametersOf(output) },
            )
            SideEffect { viewModel.output = output }
            ParentPreferencesScreen(
                viewModel = viewModel,
                onChangePin = { changingPin = true },
            )
        }
    }
}
