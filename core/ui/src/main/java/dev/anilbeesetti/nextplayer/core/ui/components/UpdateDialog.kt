package dev.anilbeesetti.nextplayer.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.anilbeesetti.nextplayer.core.model.UpdateUiState
import dev.anilbeesetti.nextplayer.core.ui.R

/**
 * 在线升级的通用对话框，由 App 层（启动提醒）与关于页共用同一份实现。
 *
 * 通过回调把「确认 / 关闭 / 去授权」交回调用方（[dev.anilbeesetti.nextplayer.core.data.repository.UpdateManager]），
 * 这样对话框本身不耦合具体页面。下载中不允许误触关闭（避免重复走检查流程）。
 *
 * @param state  当前升级状态
 * @param onConfirm     点击「立即更新」/「去设置」
 * @param onDismiss     关闭对话框（下载中会被忽略）
 * @param onOpenInstallSettings 跳转到系统「安装未知来源」授权页
 */
@Composable
fun UpdateDialog(
    state: UpdateUiState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onOpenInstallSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state is UpdateUiState.Idle) return

    val dismiss: (@Composable () -> Unit)? =
        if (state is UpdateUiState.Available || state is UpdateUiState.NeedInstallPermission) {
            { CancelButton(onClick = onDismiss) }
        } else {
            null
        }

    NextDialog(
        modifier = modifier,
        onDismissRequest = {
            // 下载中不允许误触关掉，否则下一次重新走一遍检查
            if (state !is UpdateUiState.Downloading) onDismiss()
        },
        title = {
            Text(
                text = if (state is UpdateUiState.Available) {
                    stringResource(R.string.update_available_title, state.info.versionName)
                } else {
                    stringResource(R.string.update_title)
                },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (state) {
                    is UpdateUiState.Checking -> {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                        Text(text = stringResource(R.string.update_checking))
                    }
                    is UpdateUiState.UpToDate -> Text(text = stringResource(R.string.update_up_to_date))
                    is UpdateUiState.Available -> {
                        val mb = state.info.sizeBytes / 1024f / 1024f
                        Text(text = stringResource(R.string.update_size, "%.1f".format(mb)))
                        if (state.info.notes.isNotBlank()) {
                            Text(text = stringResource(R.string.update_notes, state.info.notes))
                        }
                        Text(
                            text = stringResource(R.string.update_wifi_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is UpdateUiState.Downloading -> {
                        LinearProgressIndicator(
                            progress = { state.percent / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(text = stringResource(R.string.update_downloading, state.percent))
                    }
                    is UpdateUiState.Installing -> Text(text = stringResource(R.string.update_install_hint))
                    is UpdateUiState.NeedInstallPermission -> Text(text = stringResource(R.string.update_permission_hint))
                    is UpdateUiState.Error -> Text(text = state.message, color = MaterialTheme.colorScheme.error)
                    is UpdateUiState.Idle -> Unit
                }
            }
        },
        confirmButton = {
            when (state) {
                is UpdateUiState.Available -> TextButton(onClick = onConfirm) {
                    Text(text = stringResource(R.string.update_now))
                }
                is UpdateUiState.NeedInstallPermission -> TextButton(onClick = onOpenInstallSettings) {
                    Text(text = stringResource(R.string.update_open_settings))
                }
                is UpdateUiState.Downloading -> TextButton(onClick = {}, enabled = false) {
                    Text(text = stringResource(R.string.update_downloading, state.percent))
                }
                else -> TextButton(onClick = onDismiss) {
                    Text(text = stringResource(R.string.update_close))
                }
            }
        },
        dismissButton = dismiss,
    )
}
