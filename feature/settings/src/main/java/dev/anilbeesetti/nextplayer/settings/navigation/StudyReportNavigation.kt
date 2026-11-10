package dev.anilbeesetti.nextplayer.settings.navigation

import androidx.compose.runtime.SideEffect
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import dev.anilbeesetti.nextplayer.settings.screens.parent.ParentGateBackgroundReset
import dev.anilbeesetti.nextplayer.settings.screens.parent.ParentGateSession
import dev.anilbeesetti.nextplayer.settings.screens.report.StudyReportScreen
import dev.anilbeesetti.nextplayer.settings.screens.report.StudyReportViewModel
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * 学习报告路由。只从家长设置页进入，因此同样不进 [dev.anilbeesetti.nextplayer.settings.Setting] 枚举。
 */
@Serializable
object StudyReportRoute : NavKey

fun NavBackStack<NavKey>.navigateToStudyReport() {
    add(StudyReportRoute)
}

fun EntryProviderScope<NavKey>.studyReportEntry(onNavigateUp: () -> Unit) {
    entry<StudyReportRoute> {
        // 报告页压在家长设置之上，这里进后台同样要清解锁状态：
        // 否则在报告页锁屏、平板放桌上，孩子返回一步就站在家长设置里。
        //
        // 只认宿主 Activity 的 ON_STOP。早先观察的是入口级 lifecycle，
        // 报告页自己被弹出时也会收到 ON_STOP，等于「返回报告页」顺手把家长设置锁了。
        val session = koinInject<ParentGateSession>()
        ParentGateBackgroundReset(session)

        val output = StudyReportViewModel.Output(navigateUp = onNavigateUp)
        val viewModel = koinViewModel<StudyReportViewModel>(
            parameters = { parametersOf(output) },
        )
        SideEffect { viewModel.output = output }
        StudyReportScreen(viewModel = viewModel)
    }
}
