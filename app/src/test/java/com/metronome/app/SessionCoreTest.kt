package com.metronome.app

import com.metronome.app.core.SessionStateMachine
import com.metronome.app.core.TrainingConfig
import com.metronome.app.core.TrainingSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 会话状态机回归：暂停冻结准备/训练/段进度、恢复不丢进度、
 * 结束只发生一次、FINISHED 后再次启动是新会话、暂停与停止不同。
 */
class SessionCoreTest {

    private var now = 0L
    private lateinit var session: SessionStateMachine

    private val plan = TrainingConfig(
        timerEnabled = true,
        totalDurationSec = 300,
        prepareCountdownSec = 5,
        segments = listOf(
            TrainingSegment("A", 60, 160),
            TrainingSegment("B", 90, 180),
        ),
        loop = null,
    )

    @Before
    fun setUp() {
        now = 0L
        session = SessionStateMachine { now }
    }

    private fun tickEvery(ms: Long, step: Long = 250) {
        var advanced = 0L
        while (advanced < ms) {
            now += step
            advanced += step
            session.tick()
        }
    }

    @Test
    fun `prepare countdown does not count into training time`() {
        assertTrue(session.start(plan))
        assertEquals(SessionStateMachine.State.PREPARING, session.state)
        tickEvery(5_000)
        assertEquals(SessionStateMachine.State.RUNNING, session.state)
        val pos = session.position()!!
        assertEquals("准备时间不计入训练时长", 0L, pos.trainingElapsedMs)
    }

    @Test
    fun `pause freezes prepare countdown and resume restores it`() {
        session.start(plan)
        tickEvery(2_000)
        assertEquals(SessionStateMachine.State.PREPARING, session.state)
        val remainBefore = session.prepareRemainingMs()
        assertTrue(remainBefore in 2_800..3_100)
        session.pause()
        assertEquals(SessionStateMachine.State.PAUSED, session.state)
        now += 60_000   // 暂停很久
        assertEquals("暂停期间倒计时不推进", remainBefore, session.prepareRemainingMs())
        session.resume()
        assertEquals(SessionStateMachine.State.PREPARING, session.state)
        tickEvery(3_100)
        assertEquals(SessionStateMachine.State.RUNNING, session.state)
    }

    @Test
    fun `pause freezes training progress and resume keeps it`() {
        session.start(TrainingConfig(segments = listOf(TrainingSegment("A", 60, 160))))
        tickEvery(10_000)
        val elapsed = session.position()!!.trainingElapsedMs
        assertTrue(elapsed in 9_800..10_200)
        session.pause()
        now += 120_000
        assertEquals("暂停不销毁进度", elapsed, session.position()!!.trainingElapsedMs)
        session.resume()
        tickEvery(2_000)
        val resumed = session.position()!!.trainingElapsedMs
        assertTrue(resumed in elapsed + 1_700..elapsed + 2_300)
    }

    @Test
    fun `pause and resume are idempotent`() {
        session.start(TrainingConfig(segments = listOf(TrainingSegment("A", 60, 160))))
        assertTrue(session.pause())
        assertFalse(session.pause())
        assertTrue(session.resume())
        assertFalse(session.resume())
    }

    @Test
    fun `timer end finishes exactly once`() {
        session.start(plan.copy(prepareCountdownSec = 0))
        tickEvery(300_000)
        assertEquals(SessionStateMachine.State.FINISHED, session.state)
        val transition = session.tick()
        assertFalse("结束只发生一次", transition.changed)
        assertEquals(SessionStateMachine.State.FINISHED, session.state)
    }

    @Test
    fun `finish by cap keeps last segment info and elapsed`() {
        // 段总长 450s > 上限 300s → 必然由上限截断（而非自然完成）
        session.start(
            plan.copy(
                prepareCountdownSec = 0,
                segments = listOf(
                    TrainingSegment("A", 200, 160),
                    TrainingSegment("B", 250, 180),
                ),
            )
        )
        tickEvery(300_000)
        assertTrue(session.finishedByCap)
        assertEquals(300_000, session.finishElapsedMs)
    }

    @Test
    fun `restart after finish is a new session not a paused resume`() {
        session.start(plan.copy(prepareCountdownSec = 0))
        tickEvery(300_000)
        assertEquals(SessionStateMachine.State.FINISHED, session.state)
        now += 60_000
        val plan2 = TrainingConfig(segments = listOf(TrainingSegment("B", 30, 150)))
        assertTrue(session.start(plan2))
        assertEquals(SessionStateMachine.State.RUNNING, session.state)
        assertEquals(0L, session.position()!!.trainingElapsedMs)
        assertFalse(session.finishedByCap)
    }

    @Test
    fun `cancel clears everything`() {
        session.start(plan.copy(prepareCountdownSec = 0))
        tickEvery(10_000)
        session.cancel()
        assertEquals(SessionStateMachine.State.IDLE, session.state)
        assertEquals(null, session.position())
        tickEvery(5_000)
        assertEquals(SessionStateMachine.State.IDLE, session.state)
    }

    @Test
    fun `long unscheduled gap still locates correct segment`() {
        session.start(plan.copy(prepareCountdownSec = 0))
        // 模拟控制线程 2 分钟没有被调度（超过段 A 总时长）
        now += 120_000
        val transition = session.tick()
        assertEquals(SessionStateMachine.State.RUNNING, transition.to)
        val pos = session.position()!!
        assertEquals("一次评估跨多段：应正确落在段 B", 1, pos.segmentIndex)
        assertEquals(60_000, pos.segmentElapsedMs)
    }

    @Test
    fun `segment spm comes from current segment`() {
        session.start(plan.copy(prepareCountdownSec = 0))
        assertEquals(160, session.position()!!.segmentTargetSpm)
        tickEvery(60_000)
        assertEquals(180, session.position()!!.segmentTargetSpm)
    }

    @Test
    fun `invalid config rejected`() {
        assertFalse(
            session.start(TrainingConfig(timerEnabled = true, totalDurationSec = 0))
        )
        assertEquals(SessionStateMachine.State.IDLE, session.state)
    }
}
