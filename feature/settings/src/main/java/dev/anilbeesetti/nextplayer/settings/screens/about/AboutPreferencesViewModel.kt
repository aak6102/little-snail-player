package dev.anilbeesetti.nextplayer.settings.screens.about

import android.content.Context
import androidx.lifecycle.viewModelScope
import dev.anilbeesetti.nextplayer.core.data.repository.UpdateManager
import dev.anilbeesetti.nextplayer.core.ui.base.MviViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinViewModel

@KoinViewModel
class AboutPreferencesViewModel(
    context: Context,
    private val updateManager: UpdateManager,
    @InjectedParam internal var output: Output,
) : MviViewModel<AboutPreferencesUiState, AboutPreferencesAction>() {

    data class Output(
        val navigateUp: () -> Unit,
        val openLibraries: () -> Unit,
        val openParentSettings: () -> Unit,
    )

    private val appContext = context.applicationContext

    private val stateInternal = MutableStateFlow(AboutPreferencesUiState(appVersion = context.appVersion()))
    override val state: StateFlow<AboutPreferencesUiState> = stateInternal.asStateFlow()

    override fun onAction(action: AboutPreferencesAction) {
        when (action) {
            is AboutPreferencesAction.NavigateUp -> output.navigateUp()
            is AboutPreferencesAction.OpenLibraries -> output.openLibraries()
            is AboutPreferencesAction.OpenParentSettings -> output.openParentSettings()
            // 检查 / 下载 / 安装全部交给 UpdateManager（单例、App 层也会用同一份状态渲染对话框）
            is AboutPreferencesAction.CheckForUpdate -> updateManager.checkManually()
            is AboutPreferencesAction.ConfirmUpdate -> updateManager.confirmUpdate()
            is AboutPreferencesAction.DismissUpdate -> updateManager.closeDialog()
            is AboutPreferencesAction.OpenInstallSettings -> updateManager.openInstallSettings()
        }
    }

    private fun Context.appVersion(): String {
        val packageInfo = packageManager.getPackageInfo(packageName, 0)

        @Suppress("DEPRECATION")
        val versionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            packageInfo.versionCode
        }

        return "${packageInfo.versionName} ($versionCode)"
    }
}

data class AboutPreferencesUiState(
    val appVersion: String,
)

sealed interface AboutPreferencesAction {
    data object NavigateUp : AboutPreferencesAction
    data object OpenLibraries : AboutPreferencesAction

    /** 连点版本号 7 次的隐藏入口。 */
    data object OpenParentSettings : AboutPreferencesAction

    data object CheckForUpdate : AboutPreferencesAction
    data object ConfirmUpdate : AboutPreferencesAction
    data object DismissUpdate : AboutPreferencesAction
    data object OpenInstallSettings : AboutPreferencesAction
}
