package dev.anilbeesetti.nextplayer.settings.composables

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.components.RadioTextButton

/**
 * 「预设 + 自定义」的数字选项弹窗。
 *
 * 家长 80% 的情况会直接点预设（10 分钟 / 2 道题 / 2 分钟），
 * 但总有需要细调的时候。自定义做成列表末尾的一行、点开原地展开输入框，
 * 不弹二级窗口——手机上两层弹窗已经很笨重了。
 *
 * 展开后的值先留在输入框里，点「完成」才写盘：中途点「取消」或点窗外，
 * 改动一并丢弃，不会出现「改了几下没确认也生效了」的意外。
 *
 * 为什么从步进器换成输入框：范围放宽到 1–120 之后，从 10 连点到 90 要按 80 下，
 * 家长会放弃。输入框能直接键入，配合自动弹数字键盘，一次输入到位。
 */
@Composable
fun NumberOptionDialog(
    title: String,
    presets: List<Int>,
    current: Int,
    min: Int,
    max: Int,
    /** 数字后面的单位，如「分钟」「道」。输入框后缀与预设行共用。 */
    unit: String,
    label: @Composable (Int) -> String,
    onConfirm: (Int) -> Unit,
    onDismissClick: () -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    // 存的是原始文本而不是 Int：用户清空重输时中间态是空串，
    // 用 Int 中转会被立刻夹回 min，「删掉再打 12」会变成「1」。
    var text by remember(current) { mutableStateOf(current.toString()) }

    // 空串或纯 0 时回落到当前值，而不是悄悄写进一个越界的数字
    fun commit() {
        val value = text.toIntOrNull()?.coerceIn(min, max) ?: current
        onConfirm(value)
    }

    OptionsDialog(
        text = title,
        onDismissClick = onDismissClick,
        confirmLabel = if (editing) stringResource(R.string.parent_number_done) else null,
        onConfirmClick = ::commit,
    ) {
        items(presets) { value ->
            RadioTextButton(
                text = label(value),
                selected = !editing && value == current,
                onClick = { onConfirm(value) },
            )
        }
        item {
            RadioTextButton(
                text = stringResource(R.string.parent_number_custom),
                // 当前值不在预设里时，一打开就应该看出「现在用的是自定义」
                selected = editing || current !in presets,
                onClick = { editing = true },
            )
        }
        if (editing) {
            item {
                NumberField(
                    text = text,
                    // 只留数字并限长 3 位：范围最大 120，再多一位必然是误触。
                    // 不在输入过程中夹紧，否则「先删空再输 12」会被打断。
                    onTextChange = { raw -> text = raw.filter(Char::isDigit).take(3) },
                    unit = unit,
                    min = min,
                    max = max,
                )
            }
        }
    }
}

/** 数字输入框：自动聚焦并弹出数字键盘，回车即完成。 */
@Composable
private fun NumberField(
    text: String,
    onTextChange: (String) -> Unit,
    unit: String,
    min: Int,
    max: Int,
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    // 等一帧再请求焦点：Dialog 的 window 还没 attach 时请求会抛
    // "FocusRequester is not initialized"，这里延到首帧之后最稳
    LaunchedEffect(Unit) {
        withFrameNanos { }
        focusRequester.requestFocus()
    }

    OutlinedTextField(
        value = text,
        onValueChange = onTextChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .focusRequester(focusRequester),
        singleLine = true,
        textStyle = MaterialTheme.typography.titleMedium,
        suffix = { Text(text = unit) },
        supportingText = {
            Text(text = stringResource(R.string.parent_number_range, min, max))
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
    )
}
