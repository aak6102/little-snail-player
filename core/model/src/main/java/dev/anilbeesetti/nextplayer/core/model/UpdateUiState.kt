package dev.anilbeesetti.nextplayer.core.model

/**
 * 在线升级的 UI 状态机。
 *
 * 放在 model 层（无 Compose 依赖），供 [UpdateManager]、关于页与 App 启动提醒共用同一份状态。
 * 具体的「检查中 / 可更新 / 下载进度 / 安装」流转都由 [dev.anilbeesetti.nextplayer.core.data.repository.UpdateManager] 驱动。
 */
sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data object UpToDate : UpdateUiState
    data class Available(val info: UpdateInfo) : UpdateUiState
    data class Downloading(val percent: Int) : UpdateUiState
    data object Installing : UpdateUiState
    data object NeedInstallPermission : UpdateUiState
    data class Error(val message: String) : UpdateUiState
}
