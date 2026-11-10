package dev.anilbeesetti.nextplayer

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import androidx.window.core.layout.WindowSizeClass
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import dev.anilbeesetti.nextplayer.core.common.extensions.isTelevision
import dev.anilbeesetti.nextplayer.core.common.service.system.SystemService
import dev.anilbeesetti.nextplayer.core.data.repository.UpdateManager
import dev.anilbeesetti.nextplayer.core.media.services.MediaOperationsService
import dev.anilbeesetti.nextplayer.core.model.ThemeConfig
import dev.anilbeesetti.nextplayer.core.model.UpdateInfo
import dev.anilbeesetti.nextplayer.core.model.UpdateUiState
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.components.LocalNavigationBottomPadding
import dev.anilbeesetti.nextplayer.core.ui.components.UpdateDialog
import dev.anilbeesetti.nextplayer.core.ui.components.LocalTopLevelBottomBarVisibleSetter
import dev.anilbeesetti.nextplayer.core.ui.components.LocalTopLevelFabSetter
import dev.anilbeesetti.nextplayer.core.ui.components.TopLevelFabState
import dev.anilbeesetti.nextplayer.core.ui.components.thenIf
import dev.anilbeesetti.nextplayer.core.ui.theme.NextPlayerTheme
import dev.anilbeesetti.nextplayer.navigation.NextNavigationBar
import dev.anilbeesetti.nextplayer.navigation.NextNavigationRail
import dev.anilbeesetti.nextplayer.navigation.TopLevelDestination
import dev.anilbeesetti.nextplayer.navigation.TopLevelNavState
import dev.anilbeesetti.nextplayer.navigation.mediaNavGraph
import dev.anilbeesetti.nextplayer.navigation.moreNavGraph
import dev.anilbeesetti.nextplayer.navigation.navigationTransition
import dev.anilbeesetti.nextplayer.navigation.networkNavGraph
import dev.anilbeesetti.nextplayer.navigation.openPlaylist
import dev.anilbeesetti.nextplayer.navigation.playlistNavGraph
import dev.anilbeesetti.nextplayer.navigation.rememberTopLevelNavState
import dev.anilbeesetti.nextplayer.navigation.settingsNavGraph
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class MainActivity : FragmentActivity() {

    private val mediaOperationsService: MediaOperationsService by inject()

    private val systemService: SystemService by inject()

    private val viewModel: MainViewModel by viewModel()

    private val updateManager: UpdateManager by inject()

    private var pendingPlaylistId by mutableStateOf<Long?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingPlaylistId = PlaylistShortcut.playlistId(intent)
    }

    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            pendingPlaylistId = PlaylistShortcut.playlistId(intent)
        }
        systemService.initialize(this@MainActivity)
        mediaOperationsService.initialize(this@MainActivity)

        installSplashScreen().setKeepOnScreenCondition {
            viewModel.state.value is MainActivityUiState.Loading
        }

        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            val shouldUseDarkTheme = shouldUseDarkTheme(state = state)

            LaunchedEffect(shouldUseDarkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(
                        lightScrim = Color.TRANSPARENT,
                        darkScrim = Color.TRANSPARENT,
                        detectDarkMode = { shouldUseDarkTheme },
                    ),
                    navigationBarStyle = SystemBarStyle.auto(
                        lightScrim = Color.TRANSPARENT,
                        darkScrim = Color.TRANSPARENT,
                        detectDarkMode = { shouldUseDarkTheme },
                    ),
                )
            }

            NextPlayerTheme(
                darkTheme = shouldUseDarkTheme,
                highContrastDarkTheme = shouldUseHighContrastDarkTheme(state = state),
                dynamicColor = shouldUseDynamicTheming(state = state),
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    val navState = rememberTopLevelNavState()

                    LaunchedEffect(pendingPlaylistId) {
                        pendingPlaylistId?.let { playlistId ->
                            navState.openPlaylist(playlistId)
                            pendingPlaylistId = null
                        }
                    }

                    val mediaStack = navState.backStacks.getValue(TopLevelDestination.MEDIA.route)
                    val playlistStack = navState.backStacks.getValue(TopLevelDestination.PLAYLISTS.route)
                    val networkStack = navState.backStacks.getValue(TopLevelDestination.NETWORK.route)
                    val moreStack = navState.backStacks.getValue(TopLevelDestination.MORE.route)

                    val provider = entryProvider {
                        mediaNavGraph(context = this@MainActivity, backStack = mediaStack)
                        playlistNavGraph(context = this@MainActivity, backStack = playlistStack)
                        networkNavGraph(context = this@MainActivity, backStack = networkStack)
                        moreNavGraph(context = this@MainActivity, backStack = moreStack)
                        settingsNavGraph(backStack = navState.currentStack)
                    }

                    val topLevelFabStates = remember { mutableStateMapOf<String, TopLevelFabState>() }
                    var showTopLevelBottomBar by remember { mutableStateOf(true) }
                    Box(modifier = Modifier.fillMaxSize()) {
                        NavigationLayout(
                            state = navState,
                            fabStates = topLevelFabStates,
                            showBottomBar = showTopLevelBottomBar,
                        ) { layoutPaddingValues ->
                        val railPadding = layoutPaddingValues.calculateStartPadding(LocalLayoutDirection.current)
                        val navigationInsetsDecorator = remember(navState, railPadding) {
                            NavEntryDecorator<NavKey> { entry ->
                                // Reserve rail space per entry without resizing the animated display.
                                Box(
                                    Modifier.thenIf(navState.topLevelContentKeys.contains(entry.contentKey)) {
                                        padding(start = railPadding)
                                            .consumeWindowInsets(WindowInsets.displayCutout.only(WindowInsetsSides.Start))
                                    },
                                ) {
                                    entry.Content()
                                }
                            }
                        }
                        CompositionLocalProvider(
                            LocalNavigationBottomPadding provides layoutPaddingValues.calculateBottomPadding(),
                            LocalTopLevelBottomBarVisibleSetter provides { showTopLevelBottomBar = it },
                            LocalTopLevelFabSetter provides { key, state ->
                                if (state == null) {
                                    topLevelFabStates.remove(key)
                                } else {
                                    topLevelFabStates[key] = state
                                }
                            },
                        ) {
                            NavDisplay(
                                entries = navState.rememberEntries(provider, navigationInsetsDecorator),
                                onBack = { navState.goBack() },
                                transitionSpec = { navState.navigationTransition(initialState, targetState) },
                                popTransitionSpec = { navState.navigationTransition(initialState, targetState) },
                                predictivePopTransitionSpec = { navState.navigationTransition(initialState, targetState) },
                            )
                        }
                    }

                    // 启动更新提醒：非模态 banner + 正式对话框，都由 UpdateManager 统一驱动；
                    // 检查在后台跑，UI 线程绝不等待 Cloudflare，慢网络超时也只是不弹，不卡程序。
                    val updateUiState by updateManager.uiState.collectAsStateWithLifecycle()
                    val updateDialogVisible by updateManager.dialogVisible.collectAsStateWithLifecycle()
                    val updateBannerVisible by updateManager.bannerVisible.collectAsStateWithLifecycle()

                    AnimatedVisibility(
                        visible = updateBannerVisible,
                        modifier = Modifier.align(Alignment.BottomCenter),
                        enter = fadeIn() + slideInVertically { it / 2 },
                        exit = fadeOut() + slideOutVertically { it / 2 },
                    ) {
                        (updateUiState as? UpdateUiState.Available)?.let { info ->
                            UpdateReminderBanner(
                                info = info.info,
                                onUpdate = updateManager::openDialog,
                                onLater = updateManager::dismissBanner,
                            )
                        }
                    }

                    AnimatedVisibility(
                        visible = updateDialogVisible && updateUiState !is UpdateUiState.Idle,
                        modifier = Modifier.align(Alignment.Center),
                    ) {
                        UpdateDialog(
                            state = updateUiState,
                            onConfirm = updateManager::confirmUpdate,
                            onDismiss = updateManager::closeDialog,
                            onOpenInstallSettings = updateManager::openInstallSettings,
                        )
                    }
                    }
                }
            }
        }
    }
}

@Composable
fun NavigationLayout(
    modifier: Modifier = Modifier,
    state: TopLevelNavState,
    fabStates: SnapshotStateMap<String, TopLevelFabState>,
    showBottomBar: Boolean,
    windowSizeClass: WindowSizeClass = currentWindowAdaptiveInfoV2().windowSizeClass,
    content: @Composable (PaddingValues) -> Unit,
) {
    val isTv = LocalContext.current.isTelevision
    val density = LocalDensity.current
    var railWidth by remember(density) { mutableStateOf(0.dp) }
    val contentFocusRequester = remember { FocusRequester() }
    val fabFocusRequester = remember { FocusRequester() }
    val showNavRail = windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
    val showNavigation = state.currentStack.lastOrNull()?.let { state.topLevelContentKeys.contains(it) } == true
    val selectedFabState = state.destinations[state.selectedIndex].fabKey?.let(fabStates::get)
    var displayedFabState by remember { mutableStateOf<TopLevelFabState?>(null) }
    LaunchedEffect(selectedFabState) {
        selectedFabState?.let { displayedFabState = it }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = modifier,
            bottomBar = {
                AnimatedVisibility(
                    visible = showNavigation && showBottomBar,
                    enter = fadeIn() + slideInVertically { it },
                    exit = fadeOut() + slideOutVertically { it },
                ) {
                    NextNavigationBar(
                        state = state,
                        fabState = displayedFabState,
                        contentFocusRequester = contentFocusRequester,
                        fabFocusRequester = fabFocusRequester,
                        showFabOnly = showNavRail,
                    )
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentWindowInsets = WindowInsets(0.dp),
            content = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .thenIf(isTv) {
                            // The FAB sits outside screen content, whose full-height focus groups
                            // overlap it. Enter the content explicitly instead of searching the rail.
                            focusRequester(contentFocusRequester)
                                .focusProperties {
                                    onExit = {
                                        if (requestedFocusDirection == FocusDirection.Down &&
                                            showNavigation && showBottomBar && displayedFabState != null
                                        ) {
                                            fabFocusRequester.requestFocus()
                                        }
                                    }
                                }
                                .focusGroup()
                        },
                ) {
                    content(
                        PaddingValues(
                            start = if (showNavRail) railWidth else 0.dp,
                            bottom = it.calculateBottomPadding(),
                        ),
                    )
                }
            },
        )
        AnimatedVisibility(
            visible = showNavRail && showNavigation,
            enter = fadeIn() + expandHorizontally(expandFrom = Alignment.Start),
            exit = fadeOut() + shrinkHorizontally(shrinkTowards = Alignment.Start),
        ) {
            NextNavigationRail(
                state = state,
                modifier = Modifier.onSizeChanged { railWidth = with(density) { it.width.toDp() } },
            )
        }
    }
}

/**
 * Returns `true` if dark theme should be used, as a function of the [state] and the
 * current system context.
 */
@Composable
fun shouldUseDarkTheme(
    state: MainActivityUiState,
): Boolean = when (state) {
    MainActivityUiState.Loading -> isSystemInDarkTheme()
    is MainActivityUiState.Success -> when (state.preferences.themeConfig) {
        ThemeConfig.SYSTEM -> isSystemInDarkTheme()
        ThemeConfig.OFF -> false
        ThemeConfig.ON -> true
    }
}

@Composable
fun shouldUseHighContrastDarkTheme(
    state: MainActivityUiState,
): Boolean = when (state) {
    MainActivityUiState.Loading -> false
    is MainActivityUiState.Success -> state.preferences.useHighContrastDarkTheme
}

/**
 * Returns `true` if the dynamic color is disabled, as a function of the [state].
 */
@Composable
fun shouldUseDynamicTheming(
    state: MainActivityUiState,
): Boolean = when (state) {
    MainActivityUiState.Loading -> false
    is MainActivityUiState.Success -> state.preferences.useDynamicColors
}

/**
 * 启动更新提醒 banner：非模态，浮在底部（不会盖住导航栏、不拦截其它点击）。
 * 仅提示「有更新」，用户点「立即更新」才打开正式对话框，点「稍后」本次启动不再弹。
 */
@Composable
private fun UpdateReminderBanner(
    info: UpdateInfo,
    onUpdate: () -> Unit,
    onLater: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mb = info.sizeBytes / 1024f / 1024f
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .navigationBarsPadding(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.update_available_title, info.versionName),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = stringResource(R.string.update_size, "%.1f".format(mb)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            TextButton(onClick = onLater) {
                Text(
                    text = stringResource(R.string.update_later),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Button(onClick = onUpdate) {
                Text(text = stringResource(R.string.update_now))
            }
        }
    }
}
