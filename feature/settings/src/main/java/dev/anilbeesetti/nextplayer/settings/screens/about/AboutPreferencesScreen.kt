package dev.anilbeesetti.nextplayer.settings.screens.about

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.anilbeesetti.nextplayer.core.common.extensions.appIcon
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.components.NextTopAppBar
import dev.anilbeesetti.nextplayer.core.ui.components.rememberTvListFocusRequester
import dev.anilbeesetti.nextplayer.core.ui.components.tvFocusDown
import dev.anilbeesetti.nextplayer.core.ui.components.tvListFocus
import dev.anilbeesetti.nextplayer.core.ui.designsystem.NextIcons

private const val UPSTREAM_URL = "https://github.com/anilbeesetti/nextplayer"

/**
 * 本应用自己的源码仓库地址（GPL-3.0 要求提供源码获取途径）。
 *
 * **发布正式包前必须填成真实地址**；留空时「查看源码」会提示「即将公布」，
 * 这样即使忘了改，也不会把死链发到用户手里。
 */
private const val SOURCE_URL = ""

@Composable
fun AboutPreferencesScreen(viewModel: AboutPreferencesViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AboutPreferencesScreenContent(state = state, onAction = viewModel::onAction)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AboutPreferencesScreenContent(
    state: AboutPreferencesUiState,
    onAction: (AboutPreferencesAction) -> Unit,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val listFocusRequester = rememberTvListFocusRequester()
    Scaffold(
        topBar = {
            NextTopAppBar(
                title = stringResource(id = R.string.about_name),
                navigationIcon = {
                    FilledTonalIconButton(onClick = { onAction(AboutPreferencesAction.NavigateUp) }, modifier = Modifier.tvFocusDown(listFocusRequester)) {
                        Icon(
                            imageVector = NextIcons.ArrowBack,
                            contentDescription = stringResource(id = R.string.navigate_up),
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
                .verticalScroll(rememberScrollState())
                .tvListFocus(listFocusRequester)
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .padding(vertical = 16.dp),
        ) {
            AboutApp(
                appVersion = state.appVersion,
                onUpstreamClick = {
                    uriHandler.openUriOrShowToast(
                        uri = UPSTREAM_URL,
                        context = context,
                    )
                },
                onLibrariesClick = { onAction(AboutPreferencesAction.OpenLibraries) },
                onCheckUpdateClick = { onAction(AboutPreferencesAction.CheckForUpdate) },
            )
            // 捐助入口原来指向上游作者（Ko-fi / PayPal / 印度 UPI）。本应用是付费解锁的
            // 衍生产品，再挂别人的收款渠道会让家长以为钱付给了我们，UPI 在国内更是完全
            // 用不了，所以整块移除。对上游的致谢留在上面的「原项目」入口和程序库列表里。
        }
    }
}

@Composable
fun AboutApp(
    appVersion: String,
    modifier: Modifier = Modifier,
    onUpstreamClick: () -> Unit,
    onLibrariesClick: () -> Unit,
    onCheckUpdateClick: () -> Unit,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val appIcon = remember { context.appIcon()?.asImageBitmap() }

    val colorPrimary = MaterialTheme.colorScheme.primaryContainer
    val colorTertiary = MaterialTheme.colorScheme.tertiaryContainer

    val transition = rememberInfiniteTransition()
    val fraction by transition.animateFloat(
        initialValue =0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 5000),
            repeatMode = RepeatMode.Reverse,
        ),
    )
    val cornerRadius = 24.dp

    Column(
        modifier = modifier
            .padding(
                vertical = 16.dp,
                horizontal = 8.dp,
            )
            .drawWithCache {
                val cx = size.width - size.width * fraction
                val cy = size.height * fraction

                val gradient = Brush.radialGradient(
                    colors = listOf(colorPrimary, colorTertiary),
                    center = Offset(cx, cy),
                    radius = 800f,
                )

                onDrawBehind {
                    drawRoundRect(
                        brush = gradient,
                        cornerRadius = CornerRadius(
                            cornerRadius.toPx(),
                            cornerRadius.toPx(),
                        ),
                    )
                }
            }
            .padding(all = 24.dp)
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            appIcon?.let {
                Image(
                    bitmap = it,
                    contentDescription = "App Logo",
                    modifier = Modifier.size(48.dp).clip(CircleShape),
                )
            }
            Column {
                Text(
                    text = stringResource(id = R.string.app_name),
                    fontSize = 22.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = appVersion,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onLibrariesClick,
                colors = ButtonDefaults.buttonColors(
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    disabledContentColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = .12f),
                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                    disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = .12f),
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .weight(1f),
            ) {
                Text(text = stringResource(R.string.libraries))
            }
            Button(
                onClick = onUpstreamClick,
                colors = ButtonDefaults.buttonColors(
                    contentColor = MaterialTheme.colorScheme.onTertiary,
                    disabledContentColor = MaterialTheme.colorScheme.onTertiary.copy(alpha = .12f),
                    containerColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.8f),
                    disabledContainerColor = MaterialTheme.colorScheme.tertiary.copy(alpha = .12f),
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .height(52.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_github),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = stringResource(R.string.upstream_project))
            }
        }

        // 单独占一行：三个按钮挤在一排会让「检查更新」四个字被压得太窄
        Button(
            onClick = onCheckUpdateClick,
            colors = ButtonDefaults.buttonColors(
                contentColor = MaterialTheme.colorScheme.onPrimary,
                disabledContentColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = .12f),
                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = .12f),
            ),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            Text(text = stringResource(R.string.update_check))
        }

        // 开源合规（GPL-3.0）：必须让用户有途径拿到源码，同时说明名称与标识不在
        // 源码授权范围内。地址留空时给提示而不开空链接，见 SOURCE_URL 注释。
        TextButton(
            onClick = {
                if (SOURCE_URL.isBlank()) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.source_code_pending),
                        Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    uriHandler.openUriOrShowToast(uri = SOURCE_URL, context = context)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_github),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = stringResource(R.string.view_source_code))
        }

        Text(
            text = stringResource(R.string.gpl_notice),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
        )
    }
}

internal fun UriHandler.openUriOrShowToast(uri: String, context: Context) {
    try {
        openUri(uri = uri)
    } catch (e: Exception) {
        Toast.makeText(context, context.getString(R.string.error_opening_link), Toast.LENGTH_SHORT).show()
    }
}
