package dev.anilbeesetti.nextplayer.feature.player.study

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.anilbeesetti.nextplayer.core.ui.R

/**
 * 进入疯狂模式前的预警浮层。
 *
 * 只做「提醒」，不锁播放、不吞返回键：它是给孩子的一句「该休息了」，
 * 不是惩罚——真正的代价是 30 秒后开始的「每 1 分钟 1 词」。
 * 孩子点「知道了」可暂时收起，但离开预警区间（去休息或继续看触发疯狂）后会再次弹出。
 */
@Composable
fun RestPreWarnOverlay(
    guard: RestGuard,
    modifier: Modifier = Modifier,
) {
    val state by guard.state.collectAsStateWithLifecycle()
    if (state.phase != RestPhase.PRE_WARN || !state.preWarnVisible) return

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.82f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 28.dp),
        ) {
            Text(
                text = stringResource(id = R.string.rest_pre_warn_title),
                color = Color(0xFFFAC775),
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(
                    id = R.string.rest_pre_warn_desc,
                    state.preWarnSecondsLeft,
                ),
                color = Color(0xFFEDEDED),
                fontSize = 17.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { guard.acknowledgePreWarn() },
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(text = stringResource(id = R.string.rest_pre_warn_dismiss), fontSize = 17.sp)
            }
        }
    }
}
