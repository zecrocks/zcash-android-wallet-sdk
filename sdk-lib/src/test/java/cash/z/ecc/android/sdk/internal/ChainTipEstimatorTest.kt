package cash.z.ecc.android.sdk.internal

import org.junit.Test
import kotlin.test.assertEquals

class ChainTipEstimatorTest {
    @Test
    fun `estimateTip floors elapsed over 75s`() {
        // 74*5 = 370 seconds elapsed; 370/75 = 4 (floor); 1000 + 4 = 1004
        assertEquals(1004, estimateTip(1000, 0, 74 * 5))
    }

    @Test
    fun `estimateTip clamps negative elapsed`() {
        // nowEpoch < scannedBlockTime: negative elapsed clamped to 0
        assertEquals(1000, estimateTip(1000, 500, 400))
    }

    @Test
    fun `estimateTip switches to the NU7 spacing at activation`() {
        // Blocks 1001..1009 are pre-NU7 (9 * 75 = 675 s); the remaining 250 s are 10 NU7 blocks.
        assertEquals(1019, estimateTip(1000, 0, 925, secondsPerBlock = 75, nu7ActivationHeight = 1010))
    }

    @Test
    fun `estimateTip before reaching activation is unaffected`() {
        assertEquals(1005, estimateTip(1000, 0, 5 * 75, secondsPerBlock = 75, nu7ActivationHeight = 1010))
    }

    @Test
    fun `estimateTip after activation uses the given rate`() {
        assertEquals(2010, estimateTip(2000, 0, 250, secondsPerBlock = 25, nu7ActivationHeight = 1010))
    }

    @Test
    fun `protocolSecondsPerBlock follows activation height`() {
        assertEquals(PRE_NU7_SECONDS_PER_BLOCK, protocolSecondsPerBlock(1009, 1010))
        assertEquals(POST_NU7_SECONDS_PER_BLOCK, protocolSecondsPerBlock(1010, 1010))
        assertEquals(POST_NU7_SECONDS_PER_BLOCK, protocolSecondsPerBlock(null, 1010))
        assertEquals(PRE_NU7_SECONDS_PER_BLOCK, protocolSecondsPerBlock(5000, null))
    }

    @Test
    fun `measuredSecondsPerBlock reports the NU7 rate across the boundary`() {
        // 10 pre-NU7 blocks at 75 s and 10 NU7 blocks at 25 s.
        val span = 10 * 75L + 10 * 25L
        assertEquals(25, measuredSecondsPerBlock(1000, 0, 1020, span, nu7ActivationHeight = 1011))
        // Entirely pre-NU7.
        assertEquals(75, measuredSecondsPerBlock(1000, 0, 1010, 750, nu7ActivationHeight = 1011))
    }

    @Test
    fun `secondsPerBlockFromSamples falls back to the target for the latest height`() {
        assertEquals(POST_NU7_SECONDS_PER_BLOCK, secondsPerBlockFromSamples(longArrayOf(2000, 0), 1010))
        assertEquals(PRE_NU7_SECONDS_PER_BLOCK, secondsPerBlockFromSamples(longArrayOf(1000, 0), 1010))
    }

    @Test
    fun `secondsPerBlockAtTip divides the pre-NU7 rate once the tip passes activation`() {
        val samples = longArrayOf(1000, 7500, 900, 0)
        assertEquals(75, secondsPerBlockAtTip(samples, nowEpochSeconds = 7500 + 75, nu7ActivationHeight = 1010))
        assertEquals(25, secondsPerBlockAtTip(samples, nowEpochSeconds = 7500 + 2000, nu7ActivationHeight = 1010))
    }
}
