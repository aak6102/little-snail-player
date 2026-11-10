package dev.anilbeesetti.nextplayer.settings.screens.parent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.components.CancelButton
import dev.anilbeesetti.nextplayer.core.ui.components.ClickablePreferenceItem
import dev.anilbeesetti.nextplayer.core.ui.components.NextDialog
import dev.anilbeesetti.nextplayer.core.ui.designsystem.NextIcons
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * 「授权与售后」区块。
 *
 * 三行分别对应三件事：我现在是什么状态、我是谁、出问题怎么找你。
 * 特意把机器码单独列一行并且可点击复制——换机时用户要念一串 16 位 hex 给客服，
 * 让他手抄是这个环节最大的摩擦来源。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ParentLicenseSection(
    state: ParentPreferencesUiState,
    onAction: (ParentPreferencesUiEvent) -> Unit,
) {
    val context = LocalContext.current
    val license = state.license
    var showRebindDialog by rememberSaveable { mutableStateOf(false) }

    Column(
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
    ) {
        // 未购买就把购买入口摆出来，体验期内也显示。
        // 之前只有「点授权状态那一行」这一条隐藏路径，家长开了体验之后就再也找不到买断的地方 ——
        // 这是最容易丢单的一处，所以做成显式按钮而不是藏在可点条目里。
        //
        // 注意条件不是 `!licensed`：0 元体验包发的是一张**正式票据**，licensed 就是 true，
        // 按旧条件写会把体验期内的购买入口整块藏掉，白白丢掉最能转化的那 7 天。
        if (!license.permanent && (!license.licensed || license.fromTrialPack)) {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Button(
                    onClick = { onAction(ParentPreferencesUiEvent.OpenPurchase) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = when {
                            license.fromTrialPack ->
                                stringResource(R.string.license_buy_trial_now, license.remainingDays)

                            license.trialActive ->
                                stringResource(R.string.license_buy_trial_now, license.trialDaysLeft)

                            else -> stringResource(R.string.license_buy)
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.license_buy_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        ClickablePreferenceItem(
            title = stringResource(R.string.license_status_title),
            description = licenseDescription(state),
            icon = NextIcons.Check,
            onClick = {
                if (license.pro) {
                    onAction(ParentPreferencesUiEvent.RetrySync)
                } else {
                    onAction(ParentPreferencesUiEvent.OpenPurchase)
                }
            },
            isFirstItem = true,
        )

        // 领取体验包的过程说明。
        // 「领过」不是错误（该去买），「失败」是错误（可以重试），两者颜色不同。
        when (val pack = state.trialPack) {
            TrialPackUiState.Idle -> Unit

            TrialPackUiState.Claiming -> Text(
                text = stringResource(R.string.license_trial_claiming),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            )

            TrialPackUiState.AlreadyUsed -> Text(
                text = stringResource(R.string.license_trial_used),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            )

            is TrialPackUiState.Failed -> Text(
                text = stringResource(R.string.license_trial_failed, pack.message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            )
        }

        ClickablePreferenceItem(
            title = stringResource(R.string.license_device_title),
            description = if (state.deviceId.isBlank()) {
                stringResource(R.string.license_sync)
            } else {
                "${state.formattedDeviceId}　"
            },
            icon = NextIcons.Dns,
            onClick = {
                copyToClipboard(context, "机器码", state.formattedDeviceId)
            },
        )

        ClickablePreferenceItem(
            title = stringResource(R.string.license_email),
            description = stringResource(R.string.license_device_description, state.contactEmail),
            icon = NextIcons.Share,
            // 直接唤邮件会把旧机器码和订单号留成空格子，家长十有八九直接发出去，
            // 客服再回信问一遍——一来一回就是一天。所以先让他在 App 里填好再发。
            onClick = { showRebindDialog = true },
            isLastItem = true,
        )
    }

    if (showRebindDialog) {
        RebindMailDialog(
            deviceId = state.formattedDeviceId,
            contactEmail = state.contactEmail,
            onDismiss = { showRebindDialog = false },
        )
    }
}

@Composable
private fun licenseDescription(state: ParentPreferencesUiState): String {
    val license = state.license
    return when {
        license.permanent -> stringResource(R.string.license_status_permanent)
        // 体验包签的也是正式票据，但说「已解锁」会让家长以为已经买过了，
        // 这里照实说还剩几天，到期时他才不会觉得被骗。
        license.licensed && license.fromTrialPack ->
            stringResource(R.string.license_status_trial, license.remainingDays)

        license.licensed -> stringResource(
            R.string.license_status_paid,
            formatDay(license.expireAtMs),
        )
        license.trialActive -> stringResource(R.string.license_status_trial, license.trialDaysLeft)
        license.trialStarted -> stringResource(R.string.license_status_trial_over)
        else -> stringResource(R.string.license_status_free)
    }
}

/**
 * 试用确认框。
 *
 * 明写「到期只停学习模式、记录不清空」，是为了消掉家长最大的两个顾虑：
 * 怕买了被套牢、怕试用结束孩子数据没了。
 */
@Composable
fun ClaimTrialPackDialog(onAction: (ParentPreferencesUiEvent) -> Unit) {
    NextDialog(
        onDismissRequest = { onAction(ParentPreferencesUiEvent.ShowDialog(null)) },
        title = {
            Text(
                text = stringResource(R.string.license_start_trial_title),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onAction(ParentPreferencesUiEvent.ConfirmClaimTrialPack) }) {
                Text(text = stringResource(R.string.license_start_trial_ok))
            }
        },
        dismissButton = {
            CancelButton(onClick = { onAction(ParentPreferencesUiEvent.ShowDialog(null)) })
        },
        content = {
            Text(
                text = stringResource(R.string.license_start_trial_message),
                style = MaterialTheme.typography.titleSmall,
            )
        },
    )
}

/**
 * 购买弹窗。
 *
 * 只有这一个入口会花钱：家长设置页 + 试用到期提示。
 * 播放界面永远不弹这个——孩子看动画看到一半跳出「29 元」，够把评分打到一星。
 */
@Composable
fun PurchaseDialog(
    state: PurchaseUiState,
    onAction: (ParentPreferencesUiEvent) -> Unit,
) {
    val purchase = state

    // 二维码倒计时。用 orderNo 作 key：刷新二维码会换单，倒计时必须跟着重置。
    var remaining by remember(purchase.orderNo) { mutableIntStateOf(purchase.expireInSeconds) }
    LaunchedEffect(purchase.orderNo, purchase.expireInSeconds) {
        remaining = purchase.expireInSeconds
        while (remaining > 0) {
            delay(1_000)
            remaining -= 1
        }
    }
    val expired = purchase.hasOrder && remaining <= 0

    // 下单慢的时候要说句话。国内连 Cloudflare 的 TLS 握手本来就常常要 2~5 秒，
    // 只放一个转圈，超过三秒家长就会怀疑是不是死机、退出去重点一次，
    // 反而触发重复下单。这里给一句「在连接、不会重复扣款」先把人稳住。
    var waitingLong by remember(purchase.loading) { mutableStateOf(false) }
    LaunchedEffect(purchase.loading) {
        waitingLong = false
        if (purchase.loading) {
            delay(3_000)
            waitingLong = true
        }
    }

    NextDialog(
        onDismissRequest = { onAction(ParentPreferencesUiEvent.ClosePurchase) },
        title = {
            Text(
                text = stringResource(R.string.purchase_title),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            when {
                purchase.paid -> {
                    TextButton(onClick = { onAction(ParentPreferencesUiEvent.ClosePurchase) }) {
                        Text(text = stringResource(R.string.purchase_close))
                    }
                }

                purchase.loading -> {
                    // 这里原来返回 Unit，按钮会凭空消失。家长看到「去支付」不见了，
                    // 第一反应是点空白处重来，于是又发一次下单请求。
                    // 留一个禁用的占位按钮，明确告诉他正在做这件事。
                    TextButton(onClick = {}, enabled = false) {
                        Text(text = stringResource(R.string.purchase_loading_button))
                    }
                }

                !purchase.hasOrder || expired -> {
                    TextButton(onClick = { onAction(ParentPreferencesUiEvent.SubmitPurchase) }) {
                        Text(
                            text = if (expired) {
                                stringResource(R.string.purchase_refresh_qr)
                            } else {
                                stringResource(R.string.purchase_confirm)
                            },
                        )
                    }
                }

                // 二维码已经在等付款。这里**不该**放一个「付款后自动解锁，无需操作」的按钮：
                // 它读起来是状态说明，点下去却会触发一次手动刷新，然后在二维码下方再渲染一遍
                // 同一句话，家长看到的就是「点完之后上面又冒出一段字」。
                // 轮询本来就 2 秒一次，正常人不需要这个动作；只有真超时了两分钟，
                // 才给一个「再确认一次」的补救入口。
                purchase.timedOut -> {
                    TextButton(onClick = { onAction(ParentPreferencesUiEvent.RefreshOrder) }) {
                        Text(text = stringResource(R.string.purchase_recheck))
                    }
                }

                else -> {
                    TextButton(onClick = { onAction(ParentPreferencesUiEvent.ClosePurchase) }) {
                        Text(text = stringResource(R.string.purchase_close))
                    }
                }
            }
        },
        dismissButton = {
            if (!purchase.paid) {
                CancelButton(onClick = { onAction(ParentPreferencesUiEvent.ClosePurchase) })
            }
        },
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when {
                    purchase.paid -> {
                        Text(
                            text = stringResource(R.string.purchase_paid),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    purchase.loading -> {
                        CircularProgressIndicator(modifier = Modifier.size(36.dp))
                        Text(
                            text = stringResource(R.string.purchase_loading),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (waitingLong) {
                            Text(
                                text = stringResource(R.string.purchase_loading_slow),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }

                    purchase.hasOrder && !expired -> {
                        QrCodeImage(
                            content = purchase.qrContent.orEmpty(),
                            modifier = Modifier.size(240.dp),
                        )
                        Text(
                            text = stringResource(R.string.purchase_scan_hint),
                            style = MaterialTheme.typography.titleSmall,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            text = stringResource(R.string.purchase_scan_subhint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            text = stringResource(R.string.purchase_amount, purchase.amountText) +
                                "　·　" +
                                stringResource(R.string.purchase_expires, remaining),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        // 二维码已经出来了，家长可能才反应过来「我要买的是永久版」。
                        // 没有这个入口，他只能关掉弹窗再点进来，而弹窗状态一旦被保留，
                        // 就会一直看到他不想买的那一档 —— 这条退路必须摆在明面上。
                        if (!purchase.paid) {
                            TextButton(onClick = { onAction(ParentPreferencesUiEvent.BackToPlans) }) {
                                Text(text = stringResource(R.string.purchase_switch_plan))
                            }
                        }
                        // 这行是**说明**，不是按钮。付款后自动解锁这句话必须一直在，
                        // 否则家长扫完码会盯着屏幕等，不知道要不要再点什么。
                        // 手动确认那一刻才换成「正在确认…」，避免和上一句同时出现、看着像重复渲染。
                        Text(
                            text = if (purchase.polling) {
                                stringResource(R.string.purchase_polling_hint)
                            } else {
                                stringResource(R.string.purchase_waiting)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (purchase.timedOut) {
                            Text(
                                text = stringResource(R.string.purchase_timed_out),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }

                    else -> {
                        // 没下单 / 二维码过期 / 下单失败：都回到选套餐这一步
                        Text(
                            text = stringResource(R.string.purchase_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        PurchasePlan.ALL.forEach { plan ->
                            PlanRow(
                                plan = plan,
                                selected = plan.key == purchase.selectedPlanKey,
                                onClick = { onAction(ParentPreferencesUiEvent.SelectPlan(plan.key)) },
                            )
                        }
                        purchase.error?.let { message ->
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun PlanRow(
    plan: PurchasePlan,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = plan.title, style = MaterialTheme.typography.titleSmall)
                    plan.badge?.let { badge ->
                        Text(
                            text = "　$badge",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(
                    text = plan.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(text = plan.priceText, style = MaterialTheme.typography.titleMedium)
        }
    }
}

/* ---------------- 侧效 ---------------- */

private fun copyToClipboard(context: Context, label: String, value: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    manager.setPrimaryClip(ClipData.newPlainText(label, value))
}

/**
 * 换绑申请表单。
 *
 * 客服核一次换绑要三样东西：**新机器码、旧机器码、订单号**。新机器码只有本机知道，
 * 所以由 App 直接填；旧机器码和订单号得家长从旧设备、从支付宝账单里翻出来，
 * 这里给清楚去哪找，并允许留空——旧设备开不了机、账单翻不到，都是常态，
 * 逼着必填只会让人放弃申请。
 */
/**
 * 换绑申请表单。
 *
 * 客服核一次换绑只要两样：**支付宝交易号 + 新机器码**。
 *
 * 不再要旧机器码——旧设备往往已经开不了机，家长根本取不到那串 hex；
 * 而交易号本身就能反查订单，订单里记着当初是哪台设备付的款，服务端自己知道该停用谁。
 */
@Composable
private fun RebindMailDialog(
    deviceId: String,
    contactEmail: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var tradeNo by rememberSaveable { mutableStateOf("") }

    NextDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.license_rebind_title)) },
        confirmButton = {
            TextButton(
                onClick = {
                    sendRebindMail(
                        context = context,
                        deviceId = deviceId,
                        contactEmail = contactEmail,
                        tradeNo = tradeNo.trim(),
                    )
                    onDismiss()
                },
            ) {
                Text(text = stringResource(R.string.license_rebind_send))
            }
        },
        dismissButton = { CancelButton(onClick = onDismiss) },
        content = {
            // 小屏 + 键盘弹起时内容会超出弹窗高度，允许滚动，否则输入框会被顶出屏幕
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = stringResource(R.string.license_rebind_new_device),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = deviceId.ifBlank { "—" },
                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                )
                Text(
                    text = stringResource(R.string.license_rebind_new_device_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(6.dp))

                OutlinedTextField(
                    value = tradeNo,
                    onValueChange = { tradeNo = it },
                    label = { Text(text = stringResource(R.string.license_rebind_order)) },
                    supportingText = { Text(text = stringResource(R.string.license_rebind_order_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    )
}

/**
 * 唤起邮件客户端。
 *
 * 正文按「字段：值」排，客服扫一眼就能对上三样东西，不用在信里找。
 * 空着的字段写明「未提供」而不是留白——留白会让客服以为漏读了一段。
 *
 * 整封正文先复制到剪贴板再拉起邮件：不少机型没配邮件客户端，`startActivity` 会静默失败；
 * 而且国内家长更可能直接把这串粘贴到微信里发给客服，只复制机器码会丢掉另两项。
 */
private fun sendRebindMail(
    context: Context,
    deviceId: String,
    contactEmail: String,
    tradeNo: String,
) {
    val body = buildString {
        appendLine("申请更换绑定设备")
        appendLine()
        appendLine("新机器码：${deviceId.ifBlank { "未获取到（请在 App 家长设置里查看）" }}")
        appendLine("支付宝交易号：${tradeNo.ifBlank { "未提供" }}")
        appendLine()
        appendLine("备注：")
    }
    copyToClipboard(context, "换绑申请", body)

    val intent = Intent(Intent.ACTION_SENDTO).apply {
        data = Uri.parse("mailto:")
        putExtra(Intent.EXTRA_EMAIL, arrayOf(contactEmail))
        putExtra(Intent.EXTRA_SUBJECT, "换绑申请 · 新机 ${deviceId.ifBlank { "未知" }}")
        putExtra(Intent.EXTRA_TEXT, body)
    }
    runCatching { context.startActivity(intent) }
}

private fun formatDay(epochMs: Long): String =
    if (epochMs <= 0L) "—" else SimpleDateFormat("yyyy年M月d日", Locale.getDefault()).format(Date(epochMs))
