package dev.anilbeesetti.nextplayer.feature.player.study.quota

import android.content.ActivityNotFoundException
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.anilbeesetti.nextplayer.core.ui.R

/**
 * 播放中的额度提示条：顶部一条窄条，3 秒自动消失，**不暂停、不挡画面**。
 *
 * 刻意做得比 [dev.anilbeesetti.nextplayer.feature.player.study.RestPreWarnOverlay] 轻：
 * 那个是「再不休息就要被拦」的强提示，必须全屏压住；这个只是报个数。
 * 两者强度如果一样，孩子很快就会对所有浮层脱敏，真正要紧的那条也会被忽略。
 */
@Composable
fun QuotaNoticeBar(
    guard: QuotaGuard,
    modifier: Modifier = Modifier,
) {
    val state by guard.state.collectAsStateWithLifecycle()
    val notice = state.notice ?: return

    val text = when (notice.kind) {
        QuotaNoticeKind.INTERVAL -> stringResource(
            id = R.string.quota_notice_interval,
            notice.watchedMinutes,
            notice.remainingMinutes,
        )

        QuotaNoticeKind.LOW_BALANCE -> stringResource(
            id = R.string.quota_notice_low_balance,
            notice.minutes,
        )

        QuotaNoticeKind.LAST_MINUTE -> stringResource(id = R.string.quota_notice_last_minute)

        QuotaNoticeKind.REWARD -> stringResource(
            id = R.string.quota_notice_reward,
            notice.minutes,
        )
    }

    val accent = when (notice.kind) {
        QuotaNoticeKind.REWARD -> Color(0xFF9FE1CB)
        QuotaNoticeKind.LOW_BALANCE, QuotaNoticeKind.LAST_MINUTE -> Color(0xFFFAC775)
        QuotaNoticeKind.INTERVAL -> Color(0xFFEDEDED)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 32.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .background(Color(0xF2202020), RoundedCornerShape(20.dp))
                .padding(horizontal = 18.dp, vertical = 10.dp),
        ) {
            Text(
                text = text,
                color = accent,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * 额度耗尽的全屏锁。
 *
 * 吞掉所有点击（否则透过的点击会操作到下面的播放器控件），但**不吞返回键**：
 * 返回是孩子退出播放器的合理出口，真把人锁在里面只会换来一次强制重启。
 * 真正的拦截由 [QuotaGuard] 的状态 + PlayerService 的暂停保证，UI 只是告知。
 *
 * 锁屏上必须给出「去学习」这条出路：孩子被拦下的这一刻是他最愿意去换额度的时刻，
 * 也是唯一能让这套机制闭环的地方。没有可赚的规则时才只留一个退出按钮。
 */
@Composable
fun QuotaExhaustedOverlay(
    guard: QuotaGuard,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by guard.state.collectAsStateWithLifecycle()
    if (state.phase != QuotaPhase.EXHAUSTED) return

    val context = LocalContext.current
    val pending = state.pendingRules.firstOrNull()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xF2121212))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 28.dp),
        ) {
            Text(
                text = stringResource(id = R.string.quota_exhausted_title),
                color = Color(0xFFFAC775),
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = stringResource(id = R.string.quota_exhausted_used, state.watchedMinutes),
                color = Color(0xFFEDEDED),
                fontSize = 17.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(id = R.string.quota_exhausted_reset),
                color = Color(0xFFB4B2A9),
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
            )

            if (pending != null) {
                Spacer(Modifier.height(22.dp))
                Text(
                    text = stringResource(
                        id = R.string.quota_exhausted_pending,
                        pending.appLabel,
                        pending.requiredMinutesClamped,
                        pending.rewardMinutesClamped,
                    ),
                    color = Color(0xFF9FE1CB),
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = { launchStudyApp(context, pending.packageName) },
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        text = stringResource(id = R.string.quota_exhausted_go_study),
                        fontSize = 17.sp,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onExit) {
                Text(
                    text = stringResource(id = R.string.quota_exhausted_dismiss),
                    color = Color(0xFFB4B2A9),
                    fontSize = 16.sp,
                )
            }
        }
    }
}

/** 跳转学习 App。包被卸载后 `getLaunchIntentForPackage` 返回 null，此时静默不动。 */
private fun launchStudyApp(context: Context, packageName: String) {
    val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return
    try {
        context.startActivity(intent)
    } catch (exception: ActivityNotFoundException) {
        // 规则指向的 App 在设置之后被卸载了，按钮点了没反应比崩溃好。
    }
}
