package dev.anilbeesetti.nextplayer.settings.composables

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.anilbeesetti.nextplayer.core.ui.components.CancelButton
import dev.anilbeesetti.nextplayer.core.ui.components.NextDialog
import dev.anilbeesetti.nextplayer.core.ui.components.tvListFocus

/**
 * 单选式选项弹窗。
 *
 * 默认点一下选项就完成（[confirmLabel] 为空时不渲染确认按钮），
 * 只有在需要「先调到一个值、再确认」的场景（自定义数字）才传入确认按钮。
 */
@Composable
fun OptionsDialog(
    text: String,
    onDismissClick: () -> Unit,
    confirmLabel: String? = null,
    onConfirmClick: (() -> Unit)? = null,
    options: LazyListScope.() -> Unit,
) {
    NextDialog(
        onDismissRequest = onDismissClick,
        title = {
            Text(text = text)
        },
        content = {
            HorizontalDivider()
            LazyColumn(
                contentPadding = PaddingValues(vertical = 8.dp),
                modifier = Modifier
                    .selectableGroup()
                    .tvListFocus(),
                content = options,
            )
            HorizontalDivider()
        },
        dismissButton = { CancelButton(onClick = onDismissClick) },
        confirmButton = {
            if (confirmLabel != null && onConfirmClick != null) {
                TextButton(onClick = onConfirmClick) {
                    Text(text = confirmLabel)
                }
            }
        },
    )
}
