package dev.anilbeesetti.nextplayer.feature.player.study

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.anilbeesetti.nextplayer.core.model.QuizQuestion
import dev.anilbeesetti.nextplayer.core.model.QuizQuestionType
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.designsystem.NextIcons

/**
 * 全屏答题浮层。
 *
 * 刻意不给关闭按钮、不响应返回键、不响应空白点击：
 * 这三条是「防止孩子跳过题目」的最低要求，少一条功能就形同虚设。
 *
 * 布局有两条路径：竖屏/平板走上下排布，横屏手机走左右分栏。
 * 横屏手机可视高度常常只有 360dp，竖排时四个选项加题干会溢出屏幕，
 * 所以这里同时用「分栏压缩高度 + 纵向滚动兜底」两层保护。
 */
@Composable
fun QuizOverlay(
    gate: QuizGate,
    modifier: Modifier = Modifier,
) {
    val state by gate.state.collectAsStateWithLifecycle()
    if (!state.visible) return

    // 结果页：题目已答完，这里只回答一个问题——「这次能看多久」。
    // 它是浮动解锁唯一可见的反馈，缺了它孩子学不到「认真答更划算」。
    val summary = state.summary
    if (summary != null) {
        QuizSummaryOverlay(
            summary = summary,
            onContinue = { gate.onSummaryConfirmed() },
            modifier = modifier,
        )
        return
    }

    val question = state.question ?: return

    BackHandler(enabled = true) { /* 锁定期间吞掉返回键 */ }

    val context = LocalContext.current
    val speaker = remember { QuizSpeaker(context) }
    DisposableEffect(speaker) {
        onDispose { speaker.shutdown() }
    }

    // 播放器暂停后 KEEP_SCREEN_ON 会被清掉，答题期间屏幕不能黑
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    /*
     * 设备念不出英文时，听力题必须换一种问法，否则这道题没有任何可作答的信息：
     * 四个选项都是英文拼写，孩子只能瞎点，点错三次还会被记成「没掌握」。
     *
     * 降级成「看中文选英文」是同一批词、同一个答案，只换提问方向——
     * 原本听力题给选项配的中文注脚此时必须撤掉，否则题干的中文和注脚一一对应，
     * 答案等于直接写着。判定只认 NO_ENGINE / NO_ENGLISH，初始化中不动，
     * 避免有引擎的设备闪一下再切回来；家长后装了引擎，状态变 READY 会自动切回听力题。
     */
    val listenFallback = question.type == QuizQuestionType.LISTEN_PICK_WORD && speaker.status.isUnavailable

    LaunchedEffect(question.statKey, listenFallback) {
        // 已经确定念不出来就别再请求朗读：QuizSpeaker 收到请求会再试一次绑定引擎，
        // 在没有 TTS 的设备上只是白白多打几次日志
        if (question.type == QuizQuestionType.LISTEN_PICK_WORD && !listenFallback) {
            speaker.speak(question.word)
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.94f)),
    ) {
        // 横屏手机：宽大于高且高度紧张，改左右分栏，避免选项被挤出屏幕
        val compact = maxHeight < 480.dp && maxWidth > maxHeight
        val twoColumns = maxWidth >= 560.dp
        val containerHeight = maxHeight
        val scrollState = rememberScrollState()

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState),
            contentAlignment = Alignment.Center,
        ) {
            // heightIn 保证内容不满一屏时仍然居中，超出时才滚动
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = containerHeight),
                contentAlignment = Alignment.Center,
            ) {
                if (compact) {
                    CompactQuizLayout(
                        state = state,
                        question = question,
                        gate = gate,
                        speaker = speaker,
                        listenFallback = listenFallback,
                    )
                } else {
                    StackedQuizLayout(
                        state = state,
                        question = question,
                        gate = gate,
                        speaker = speaker,
                        twoColumns = twoColumns,
                        listenFallback = listenFallback,
                    )
                }
            }
        }
    }
}

/** 竖屏 / 大屏：题干在上，选项在下。 */
@Composable
private fun StackedQuizLayout(
    state: QuizUiState,
    question: QuizQuestion,
    gate: QuizGate,
    speaker: QuizSpeaker,
    twoColumns: Boolean,
    listenFallback: Boolean,
) {
    Column(
        modifier = Modifier
            .widthIn(max = 640.dp)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        QuizHead(
            state = state,
            question = question,
            gate = gate,
            compact = false,
            listenFallback = listenFallback,
        )
        Spacer(Modifier.height(18.dp))
        QuizPrompt(
            question = question,
            speaker = speaker,
            compact = false,
            listenFallback = listenFallback,
        )
        Spacer(Modifier.height(14.dp))
        HintText(state = state)
        Spacer(Modifier.height(10.dp))
        QuizOptions(
            state = state,
            gate = gate,
            twoColumns = twoColumns,
            compact = false,
            showCaptions = !listenFallback,
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.revealed && !state.mustPickAnswer) {
            Spacer(Modifier.height(4.dp))
            RevealConfirmButton(gate = gate, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** 横屏手机：左边题干，右边选项，把纵向高度压到一屏内。 */
@Composable
private fun CompactQuizLayout(
    state: QuizUiState,
    question: QuizQuestion,
    gate: QuizGate,
    speaker: QuizSpeaker,
    listenFallback: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            QuizHead(
                state = state,
                question = question,
                gate = gate,
                compact = true,
                listenFallback = listenFallback,
            )
            Spacer(Modifier.height(10.dp))
            QuizPrompt(
                question = question,
                speaker = speaker,
                compact = true,
                listenFallback = listenFallback,
            )
        }
        Column(
            modifier = Modifier.weight(1.15f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            QuizOptions(
                state = state,
                gate = gate,
                twoColumns = true,
                compact = true,
                showCaptions = !listenFallback,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            HintText(state = state)
            if (state.revealed && !state.mustPickAnswer) {
                Spacer(Modifier.height(8.dp))
                RevealConfirmButton(gate = gate, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun QuizHead(
    state: QuizUiState,
    question: QuizQuestion,
    gate: QuizGate,
    compact: Boolean,
    listenFallback: Boolean,
) {
        Text(
            text = stringResource(id = if (gate.crazyMode) R.string.quiz_title_crazy else R.string.quiz_title),
            color = Color(0xFFF5F5F5),
            fontSize = if (compact) 17.sp else 20.sp,
            fontWeight = FontWeight.Bold,
        )
    // 多题时给出进度，让孩子看见终点；只有一题时不显示，避免多余信息
    if (state.total > 1) {
        Spacer(Modifier.height(2.dp))
        Text(
            text = stringResource(id = R.string.quiz_progress, state.index, state.total),
            color = Color(0xFF8A8A8A),
            fontSize = if (compact) 11.sp else 12.sp,
        )
    }
    Spacer(Modifier.height(4.dp))
    Text(
        text = stringResource(
            id = when {
                // 降级之后问法与「看词选义」相反（中文 → 英文），提示也要跟着换，
                // 否则孩子按「听一听」的说明去找喇叭，屏幕上根本没有
                listenFallback -> R.string.quiz_hint_listen_fallback
                question.type == QuizQuestionType.WORD_TO_ZH -> R.string.quiz_hint_word_to_zh
                question.type == QuizQuestionType.LISTEN_PICK_WORD -> R.string.quiz_hint_listen
                else -> R.string.quiz_hint_emoji
            },
        ),
        color = Color(0xFFBDBDBD),
        fontSize = if (compact) 12.sp else 14.sp,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun QuizPrompt(
    question: QuizQuestion,
    speaker: QuizSpeaker,
    compact: Boolean,
    listenFallback: Boolean,
) {
    when (question.type) {
        QuizQuestionType.EMOJI_PICK_WORD -> Text(
            text = question.prompt,
            fontSize = if (compact) 44.sp else 64.sp,
        )

        QuizQuestionType.WORD_TO_ZH -> Text(
            text = question.prompt,
            color = Color(0xFFF5F5F5),
            fontSize = if (compact) 34.sp else 42.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )

        // 重听走慢速：第一遍用正常语速，听不清时再点就是「放慢重念」，才算真的给第二次机会
        QuizQuestionType.LISTEN_PICK_WORD -> if (listenFallback) {
            // 降级路径：这里不显示任何「听」的控件，只把中文摆出来。
            // 留一个点了没声音的喇叭，比不给按钮更糟——孩子会以为是自己没按对。
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = question.primaryMeaning.ifBlank { question.meaning },
                    color = Color(0xFFF5F5F5),
                    fontSize = if (compact) 34.sp else 42.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(id = R.string.quiz_listen_fallback_note),
                    color = Color(0xFF9A9A9A),
                    fontSize = if (compact) 12.sp else 13.sp,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                TextButton(onClick = { speaker.speak(question.word, slow = true) }) {
                    Icon(
                        imageVector = NextIcons.VolumeUp,
                        contentDescription = null,
                        tint = Color(0xFFF5F5F5),
                        modifier = Modifier.width(if (compact) 32.dp else 40.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(id = R.string.quiz_replay),
                        color = Color(0xFFF5F5F5),
                        fontSize = if (compact) 17.sp else 20.sp,
                    )
                }
                // 念不出来时必须说话：否则孩子只会以为喇叭坏了，一遍遍点到哭
                val hint = when (speaker.status) {
                    SpeakerStatus.NO_ENGINE -> R.string.quiz_speaker_no_engine
                    SpeakerStatus.NO_ENGLISH -> R.string.quiz_speaker_no_english
                    else -> 0
                }
                if (hint != 0) {
                    Text(
                        text = stringResource(id = hint),
                        color = Color(0xFFFFB4AB),
                        fontSize = if (compact) 12.sp else 14.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 6.dp, start = 8.dp, end = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun QuizOptions(
    state: QuizUiState,
    gate: QuizGate,
    twoColumns: Boolean,
    compact: Boolean,
    /** 听力题的中文注脚是否显示；降级成「看中文选单词」时必须关掉，否则等于泄题。 */
    showCaptions: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val options = state.question?.options ?: return
    val rows = if (twoColumns) options.chunked(2) else options.chunked(1)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 12.dp),
            ) {
                row.forEach { option ->
                    QuizOptionButton(
                        text = option,
                        // 听力题的选项补一行中文，让孩子能用「我听过这个词」确认，而不是瞎点
                        caption = if (showCaptions) {
                            state.question?.optionCaptions?.get(option)
                        } else {
                            null
                        },
                        visual = state.visualFor(option),
                        enabled = state.isOptionClickable(option),
                        compact = compact,
                        onClick = { gate.onOptionSelected(option) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun RevealConfirmButton(
    gate: QuizGate,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = { gate.onRevealConfirmed() },
        modifier = modifier,
    ) {
        Text(
            text = stringResource(id = R.string.quiz_reveal_confirm),
            fontSize = 17.sp,
        )
    }
}

@Composable
private fun HintText(
    state: QuizUiState,
    modifier: Modifier = Modifier,
) {
    val question = state.question ?: return
    // 关键点：答错时只说「划掉了几个」，绝不把答案或答案线索写在这里。
    // 早期实现答错两次就直接显示正确答案，等于把脚手架做成了送分。
    val text = when {
        // 欠账词：答案已经亮出来了，但必须自己点一下才算过。
        // 这一步的意义不是「刁难」，而是让过关动作里至少含一次真实的识别。
        state.mustPickAnswer -> stringResource(R.string.quiz_hint_pick_answer, question.answer)
        state.revealed -> stringResource(R.string.quiz_answer_revealed, question.answer)
        state.attempt > 0 -> stringResource(R.string.quiz_hint_eliminated, state.eliminated.size)
        else -> ""
    }
    val color = if (state.revealed) Color(0xFF5DCAA5) else Color(0xFFFAC775)
    Box(
        modifier = modifier.heightIn(min = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun QuizOptionButton(
    text: String,
    caption: String?,
    visual: OptionVisual,
    enabled: Boolean,
    compact: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = when (visual) {
        OptionVisual.DEFAULT -> Color(0xFF1C1C1C)
        OptionVisual.WRONG -> Color(0xFF4A1B1B)
        OptionVisual.CORRECT -> Color(0xFF10362B)
    }
    val borderColor = when (visual) {
        OptionVisual.DEFAULT -> Color(0xFF3A3A3A)
        OptionVisual.WRONG -> Color(0xFFA32D2D)
        OptionVisual.CORRECT -> Color(0xFF1D9E75)
    }
    val textColor = when (visual) {
        OptionVisual.WRONG -> Color(0xFFF09595)
        OptionVisual.CORRECT -> Color(0xFF9FE1CB)
        OptionVisual.DEFAULT -> Color(0xFFF5F5F5)
    }
    // 选错的选项划掉，视觉上明确「这条已经作废」，比变灰更直观
    val decoration = if (visual == OptionVisual.WRONG) TextDecoration.LineThrough else null

    Box(
        modifier = modifier
            .background(container, RoundedCornerShape(14.dp))
            .border(1.dp, borderColor, RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(
                horizontal = 16.dp,
                vertical = if (compact) 12.dp else 16.dp,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = text,
                color = textColor,
                fontSize = if (compact) 17.sp else 19.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                textDecoration = decoration,
            )
            // 中文注脚比英文小一号、低对比度：它是辅助确认，不能盖过要考的拼写本身
            if (!caption.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = caption,
                    color = if (visual == OptionVisual.DEFAULT) {
                        Color(0xFF9A9A9A)
                    } else {
                        textColor.copy(alpha = 0.75f)
                    },
                    fontSize = if (compact) 11.sp else 12.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * 结果页：告诉孩子这一轮换来了多少分钟。
 *
 * 不做倒计时、不显示「下次要更努力」之类的说教，只报事实：
 * 全对 → 看满设定时长；有错 → 只有 5 分钟。后果比训话有效。
 */
@Composable
private fun QuizSummaryOverlay(
    summary: QuizSummary,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(enabled = true) { /* 结果页也不许返回键逃逸 */ }

    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.94f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(
                    // 「全对」和「都过了但用了排除法」是两件事：后者不该拿到「全部答对」四个字。
                    // 家长会翻这张结果页，说不清楚反而伤信任。
                    when {
                        summary.allCorrect && summary.eliminatedCount == 0 ->
                            R.string.quiz_summary_perfect_title

                        summary.allCorrect -> R.string.quiz_summary_eliminated_title
                        else -> R.string.quiz_summary_retry_title
                    },
                ),
                color = if (summary.allCorrect) Color(0xFF9FE1CB) else Color(0xFFFAC775),
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = when {
                    summary.allCorrect && summary.eliminatedCount == 0 ->
                        stringResource(R.string.quiz_summary_perfect_desc, summary.nextMinutes)

                    summary.allCorrect -> stringResource(
                        R.string.quiz_summary_eliminated_desc,
                        summary.eliminatedCount,
                        summary.nextMinutes,
                    )

                    else -> stringResource(R.string.quiz_summary_retry_desc, summary.nextMinutes)
                },
                color = Color(0xFFEDEDED),
                fontSize = 17.sp,
                textAlign = TextAlign.Center,
            )
            if (summary.total > 1) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(
                        R.string.quiz_summary_score,
                        summary.correctCount,
                        summary.total,
                    ),
                    color = Color(0xFF9A9A9A),
                    fontSize = 13.sp,
                )
            }
            if (summary.downgraded) {
                Spacer(Modifier.height(10.dp))
                // 只说「先练简单的」，不报档位：报了就变成规则说明书，
                // 孩子会去研究怎么触发降档，而不是把词记住
                Text(
                    text = stringResource(R.string.quiz_summary_easier_hint),
                    color = Color(0xFFB9C4CE),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(22.dp))
            Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.quiz_summary_continue), fontSize = 17.sp)
            }
        }
    }
}

enum class OptionVisual { DEFAULT, WRONG, CORRECT }

/**
 * 选项是否可点。
 *
 * 揭示答案后的分支很关键：普通词一律禁用（只能点「我记住了」）；
 * 欠账词只留正确答案一个可点——此时另外三个已被划掉，
 * 所以孩子面前实际上只剩一个可点的选项，不会形成新的卡死风险。
 */
private fun QuizUiState.isOptionClickable(option: String): Boolean {
    if (option in eliminated) return false
    if (!revealed) return true
    return mustPickAnswer && question?.answer == option
}

private fun QuizUiState.visualFor(option: String): OptionVisual = when {
    revealed && question?.answer == option -> OptionVisual.CORRECT
    option in eliminated -> OptionVisual.WRONG
    else -> OptionVisual.DEFAULT
}
