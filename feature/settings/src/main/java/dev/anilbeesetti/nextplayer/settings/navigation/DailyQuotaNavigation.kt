package dev.anilbeesetti.nextplayer.settings.navigation

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import dev.anilbeesetti.nextplayer.settings.screens.parent.ParentGateBackgroundReset
import dev.anilbeesetti.nextplayer.settings.screens.parent.ParentGateSession
import dev.anilbeesetti.nextplayer.settings.screens.parent.quota.DailyQuotaScreen
import dev.anilbeesetti.nextplayer.settings.screens.parent.quota.DailyQuotaViewModel
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * 每日观看额度的配置路由。只从家长设置页进入，因此同样不进 `Setting` 枚举。
 *
 * 进门已经过 PIN，这里不重复校验；但**退到后台仍然要锁**（[ParentGateBackgroundReset]）。
 * 注意这带来一个副作用：点「去开使用情况访问权限」会跳到系统页面，回来要重新输 PIN。
 * 这是既有规则的一致结果——跳支付、锁屏同样是这个行为——不在这里为它开例外，
 * 否则「哪些离开算离开」就又变成需要解释的规则。
 */
@Serializable
object DailyQuotaRoute : NavKey

fun NavBackStack<NavKey>.navigateToDailyQuota() {
    add(DailyQuotaRoute)
}

fun EntryProviderScope<NavKey>.dailyQuotaEntry(onNavigateUp: () -> Unit) {
    entry<DailyQuotaRoute> {
        val session = koinInject<ParentGateSession>()
        ParentGateBackgroundReset(session)

        val context = LocalContext.current
        val output = DailyQuotaViewModel.Output(
            navigateUp = onNavigateUp,
            openUsageAccessSettings = {
                // 「使用情况访问权限」是特殊权限，没有运行时申请 API，只能把家长送到系统页面。
                // 部分 ROM 没有这个 Activity，跳不过去时保持原地不动，比崩溃好。
                val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                try {
                    context.startActivity(intent)
                } catch (exception: ActivityNotFoundException) {
                    // 忽略：少数定制 ROM 把该页面藏了
                }
            },
        )
        val viewModel = koinViewModel<DailyQuotaViewModel>(
            parameters = { parametersOf(output) },
        )
        SideEffect { viewModel.output = output }
        DailyQuotaScreen(viewModel = viewModel)
    }
}
