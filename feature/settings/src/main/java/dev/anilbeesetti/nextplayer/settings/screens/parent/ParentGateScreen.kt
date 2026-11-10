package dev.anilbeesetti.nextplayer.settings.screens.parent

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.anilbeesetti.nextplayer.core.data.repository.ParentPinRepository
import dev.anilbeesetti.nextplayer.core.data.repository.PinVerifyResult
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.components.NextTopAppBar
import dev.anilbeesetti.nextplayer.core.ui.designsystem.NextIcons
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** 每次输错后清空输入框，避免家长手动删。 */
private const val PIN_LENGTH = 4
private const val RECOVERY_LENGTH = 8

/**
 * 家长 PIN 门禁。
 *
 * 替换掉原来的「关于页连点 7 次」。那次改动的理由很简单：
 * 连点拦住的是「不知道有这一页」的人，一旦孩子看见家长点过一遍就失效了；
 * 而 4 位 PIN 是实打实的凭证，家长输的时候孩子也不一定看得见。
 *
 * 代价是**忘记密码**变成真问题。留一条离线后路：恢复码（创建时强制展示一次）。
 * 连恢复码也没抄的，只能清除应用数据 —— 这一条在「忘记密码」页里明说，
 * 不承诺任何人工找回：PIN 只以哈希形式存在这台机器上，服务端没有副本。
 *
 * [GateMode.ChangePin] 复用同一套界面：先验证旧密码，然后直接进「设置新密码」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParentGateScreen(
    onPassed: () -> Unit,
    onCancel: () -> Unit,
    mode: GateMode = GateMode.Unlock,
) {
    val repository = koinInject<ParentPinRepository>()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var step by remember { mutableStateOf(Step.Loading) }
    var pin by remember { mutableStateOf("") }
    var firstPin by remember { mutableStateOf("") }
    var recoveryInput by remember { mutableStateOf("") }
    var recoveryCode by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var lockedUntil by remember { mutableStateOf(0L) }

    // 恢复码只显示这一次，按返回键跑掉就永久丢失。
    // 系统的返回（手势/三键）必须一起挡掉，只挡顶栏的箭头没有意义。
    BackHandler(enabled = step == Step.ShowRecovery) { /* 抄完点按钮才放行 */ }

    // 必须先等 DataStore 读出真实值再决定进哪个流程。
    // 直接用 stateNow 会在一瞬间读到默认值（isSet = false），
    // 把设过 PIN 的用户当成新用户塞进创建流程 —— 他输两遍新 PIN，旧的就没了。
    LaunchedEffect(Unit) {
        val snapshot = repository.awaitState()
        lockedUntil = snapshot.lockedUntilMs
        step = if (snapshot.isSet) Step.Verify else Step.Create
    }

    val locked = lockedUntil > System.currentTimeMillis()

    fun resetInput() {
        pin = ""
    }

    fun submitPin(value: String) {
        if (busy || value.length != PIN_LENGTH) return
        when (val current = step) {
            Step.Create -> {
                firstPin = value
                pin = ""
                error = null
                step = Step.Confirm
            }

            Step.Confirm -> {
                if (value != firstPin) {
                    error = null
                    firstPin = ""
                    pin = ""
                    step = Step.Create
                    // 用一句独立的提示，别和「密码不对」混在一起
                    error = "两次输入不一致，请重新设置"
                } else {
                    busy = true
                    scope.launch {
                        val code = repository.setup(value)
                        busy = false
                        pin = ""
                        recoveryCode = code
                        step = Step.ShowRecovery
                    }
                }
            }

            Step.Verify -> {
                busy = true
                scope.launch {
                    when (val result = repository.verify(value)) {
                        PinVerifyResult.Ok -> {
                            busy = false
                            // 改密码流程：验完旧的直接进「设置新的」，不走 onPassed，
                            // 否则页面会关掉，家长又得从设置页重新点一次。
                            if (mode == GateMode.ChangePin) {
                                firstPin = ""
                                pin = ""
                                error = null
                                step = Step.ResetPin
                            } else {
                                onPassed()
                            }
                        }

                        is PinVerifyResult.Wrong -> {
                            busy = false
                            pin = ""
                            error = "密码不对，还能再试 ${result.attemptsLeft} 次"
                        }

                        is PinVerifyResult.Locked -> {
                            busy = false
                            pin = ""
                            lockedUntil = result.untilMs
                            error = null
                        }

                        PinVerifyResult.NotSet -> {
                            busy = false
                            step = Step.Create
                        }
                    }
                }
            }

            Step.ResetPin -> {
                firstPin = value
                pin = ""
                error = null
                step = Step.ResetConfirm
            }

            Step.ResetConfirm -> {
                if (value != firstPin) {
                    firstPin = ""
                    pin = ""
                    step = Step.ResetPin
                    error = "两次输入不一致，请重新设置"
                } else {
                    busy = true
                    scope.launch {
                        val code = repository.resetPin(value)
                        busy = false
                        pin = ""
                        recoveryCode = code
                        step = Step.ShowRecovery
                    }
                }
            }

            else -> Unit
        }
    }

    // 输满 4 位自动提交：家长不用再找「确定」按钮
    LaunchedEffect(pin, step) {
        if (pin.length == PIN_LENGTH && (step == Step.Create ||
                step == Step.Confirm || step == Step.Verify ||
                step == Step.ResetPin || step == Step.ResetConfirm)
        ) {
            submitPin(pin)
        }
    }

    // 锁定时不需要循环刷新界面，只要在到点时放开输入即可
    LaunchedEffect(lockedUntil) {
        if (lockedUntil > System.currentTimeMillis()) {
            kotlinx.coroutines.delay(lockedUntil - System.currentTimeMillis() + 500)
            lockedUntil = 0L
        }
    }

    Scaffold(
        topBar = {
            NextTopAppBar(
                title = if (mode == GateMode.ChangePin) {
                    stringResource(R.string.gate_change_title)
                } else {
                    stringResource(R.string.parent_settings)
                },
                navigationIcon = {
                    // 恢复码页不给退：退出去这串数字就永远拿不回来了
                    val canLeave = step != Step.ShowRecovery
                    FilledTonalIconButton(
                        onClick = { if (canLeave) onCancel() },
                        enabled = canLeave,
                    ) {
                        Icon(
                            imageVector = NextIcons.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_up),
                        )
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (step) {
                Step.Loading -> {
                    Spacer(Modifier.height(48.dp))
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                }

                Step.ShowRecovery -> {
                    Text(
                        text = stringResource(R.string.gate_recovery_show_title),
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = stringResource(R.string.gate_recovery_show_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = recoveryCode.chunked(4).joinToString(" "),
                        style = MaterialTheme.typography.displaySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 4.sp,
                        ),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    TextButton(onClick = { clipboard.setText(AnnotatedString(recoveryCode)) }) {
                        Text(text = stringResource(R.string.gate_recovery_copy))
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = onPassed,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(text = stringResource(R.string.gate_recovery_copied))
                    }
                }

                Step.Recovery -> {
                    Text(
                        text = stringResource(R.string.gate_recovery_title),
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = stringResource(R.string.gate_recovery_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    OutlinedTextField(
                        value = recoveryInput,
                        onValueChange = { new -> recoveryInput = new.filter { it.isDigit() }.take(RECOVERY_LENGTH) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.NumberPassword,
                            imeAction = ImeAction.Done,
                        ),
                        textStyle = MaterialTheme.typography.headlineSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            textAlign = TextAlign.Center,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    error?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Button(
                        onClick = {
                            if (busy) return@Button
                            busy = true
                            scope.launch {
                                val ok = repository.verifyRecovery(recoveryInput)
                                busy = false
                                if (ok) {
                                    recoveryInput = ""
                                    error = null
                                    step = Step.ResetPin
                                } else {
                                    error = "恢复码不对"
                                }
                            }
                        },
                        enabled = recoveryInput.length == RECOVERY_LENGTH && !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(text = stringResource(R.string.gate_continue))
                    }
                    // 连恢复码也没抄：说清唯一的出路，不承诺任何人工找回。
                    // PIN 只以 PBKDF2 哈希存在本机的 parent_pin.json 里，我们也解不开。
                    Text(
                        text = stringResource(R.string.gate_lost_all),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = stringResource(R.string.gate_lost_all_detail),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }

                else -> {
                    Text(
                        text = when (step) {
                            Step.Create -> stringResource(R.string.gate_title_create)
                            Step.Confirm -> stringResource(R.string.gate_title_confirm)
                            Step.ResetPin -> stringResource(R.string.gate_reset_title)
                            Step.ResetConfirm -> stringResource(R.string.gate_title_confirm)
                            else -> stringResource(R.string.gate_title_verify)
                        },
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = when (step) {
                            Step.Create -> stringResource(R.string.gate_desc_create)
                            Step.Confirm -> stringResource(R.string.gate_desc_confirm)
                            Step.ResetPin -> stringResource(R.string.gate_reset_desc)
                            else -> stringResource(R.string.gate_desc_verify)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )

                    Spacer(Modifier.height(8.dp))

                    if (locked) {
                        val minutesLeft = ((lockedUntil - System.currentTimeMillis()) / 60_000L).toInt() + 1
                        Text(
                            text = stringResource(R.string.gate_locked, minutesLeft),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            text = stringResource(R.string.gate_locked_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    } else {
                        PinInput(
                            value = pin,
                            onValueChange = { pin = it; if (error != null) error = null },
                            enabled = !busy,
                            isError = error != null,
                        )
                    }

                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }

                    error?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                    }

                    if (step == Step.Verify && !locked) {
                        TextButton(onClick = { error = null; pin = ""; step = Step.Recovery }) {
                            Text(text = stringResource(R.string.gate_forgot))
                        }
                    }
                }
            }
        }
    }
}

/**
 * 四个方格的 PIN 输入。
 *
 * 底下是一个几乎不可见的 `BasicTextField`，负责接系统数字键盘的输入 ——
 * 不自绘键盘，是因为输入法、无障碍、单手操作这些细节系统已经做好了，
 * 而自绘键盘要把这些全部重写一遍。
 */
@Composable
private fun PinInput(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    isError: Boolean,
) {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(enabled) {
        if (enabled) runCatching { focusRequester.requestFocus() }
    }

    // 必须显式居中：下面那个隐藏输入框用 fillMaxWidth 把 Box 撑成了整行宽，
    // 而 Box 的默认对齐是 TopStart，四个方格就会贴到左边而不是跟着标题居中。
    Box(contentAlignment = Alignment.Center) {
        BasicTextField(
            value = value,
            onValueChange = { new -> onValueChange(new.filter { it.isDigit() }.take(PIN_LENGTH)) },
            enabled = enabled,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.NumberPassword,
                imeAction = ImeAction.Done,
            ),
            cursorBrush = SolidColor(Color.Transparent),
            textStyle = TextStyle(color = Color.Transparent, fontSize = 1.sp),
            modifier = Modifier
                .focusRequester(focusRequester)
                .fillMaxWidth()
                .height(1.dp)
                .alpha(0f),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            repeat(PIN_LENGTH) { index ->
                val filled = index < value.length
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            when {
                                isError -> MaterialTheme.colorScheme.errorContainer
                                filled -> MaterialTheme.colorScheme.primaryContainer
                                else -> MaterialTheme.colorScheme.surfaceContainerHighest
                            },
                        )
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                            shape = RoundedCornerShape(14.dp),
                        )
                        .clickable(enabled = enabled) { runCatching { focusRequester.requestFocus() } },
                    contentAlignment = Alignment.Center,
                ) {
                    if (filled) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.onSurface),
                        )
                    }
                }
            }
        }
    }
}

private enum class Step {
    /** 正在等 DataStore 读出真实状态，还没决定走创建还是验证。 */
    Loading,
    Create,
    Confirm,
    Verify,
    Recovery,
    ResetPin,
    ResetConfirm,
    ShowRecovery,
}

/** 进来是要「解锁进入」，还是要「改掉现有密码」。 */
enum class GateMode {
    Unlock,
    ChangePin,
}
