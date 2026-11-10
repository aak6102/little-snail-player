package dev.anilbeesetti.nextplayer.feature.player.service

import dev.anilbeesetti.nextplayer.core.common.di.DiQualifiers
import dev.anilbeesetti.nextplayer.core.data.repository.FakeLicenseRepository
import dev.anilbeesetti.nextplayer.core.data.repository.FakeStudyRepository
import dev.anilbeesetti.nextplayer.core.data.repository.LicenseRepository
import dev.anilbeesetti.nextplayer.core.data.repository.QuizBankRepository
import dev.anilbeesetti.nextplayer.core.data.repository.StudyRepository
import dev.anilbeesetti.nextplayer.feature.player.study.QuizGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.robolectric.RuntimeEnvironment

/**
 * 学习答题锁依赖的测试替身。
 *
 * [PlayerService] 会注入 [QuizGate]，而 Robolectric 直接 new 出来的 Service
 * 只要走到 `onMediaItemTransition` / `onDestroy` 就会解析这个懒注入。
 * 测试容器里缺定义时抛的是 `NoDefinitionFoundException`，
 * 会以「看起来和答题无关的用例失败」的形式暴露（播放速度、播放列表、停止会话）。
 *
 * 题库用真实 [QuizBankRepository] 读 assets：测试 APK 里没有题库文件时会安全地返回空题库，
 * 出题端随之跳过拦截，正好符合「测试不该被答题锁干扰」的预期。
 */
internal fun studyTestModule(): Module = module {
    single<StudyRepository> { FakeStudyRepository() }
    // 默认给「已授权」：这些用例验证的是播放与答题逻辑，不该被付费墙干扰
    single<LicenseRepository> { FakeLicenseRepository(pro = true) }
    single { QuizBankRepository(RuntimeEnvironment.getApplication()) }
    single(named(DiQualifiers.APPLICATION_SCOPE)) { CoroutineScope(Dispatchers.Unconfined) }
    single { QuizGate(get(), get(), get(), get(named(DiQualifiers.APPLICATION_SCOPE))) }
}
