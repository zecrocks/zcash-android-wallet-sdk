package cash.z.ecc.android.sdk.internal

import android.content.Context
import cash.z.ecc.android.sdk.internal.db.DatabaseCoordinator
import cash.z.ecc.android.sdk.internal.jni.MigrationRustBackend
import cash.z.ecc.android.sdk.model.ZcashNetwork
import kotlin.time.Clock

internal interface ChainTipEstimator {
    suspend fun estimatedTip(): Long

    /**
     * Measured average seconds per block over the recent scanned window (header timestamps),
     * expressed at the estimated chain tip and clamped to [MIN_SECONDS_PER_BLOCK,
     * MAX_SECONDS_PER_BLOCK]. Falls back to the protocol target ([PRE_NU7_SECONDS_PER_BLOCK] or
     * [POST_NU7_SECONDS_PER_BLOCK], depending on the height) when fewer than two blocks are
     * available. Testnet's minimum-difficulty rule makes real spacing bursty (seconds-per-block
     * during bursts) — a protocol constant there turns every height-to-time projection into an
     * overestimate.
     */
    suspend fun estimatedSecondsPerBlock(): Long
}

/** Protocol target block spacing from Blossom until NU7 activation (ZIP 208). */
internal const val PRE_NU7_SECONDS_PER_BLOCK = 75L

/** Protocol target block spacing from NU7 activation onward (ZIP 218). */
internal const val POST_NU7_SECONDS_PER_BLOCK = 25L

/** ZIP 218's `NU7PoWTargetSpacingRatio`: a pre-NU7 block spans the time of this many NU7 blocks. */
internal const val NU7_SPACING_RATIO = PRE_NU7_SECONDS_PER_BLOCK / POST_NU7_SECONDS_PER_BLOCK

/**
 * Protocol target spacing for the block at [height]. An unknown [height] (nothing scanned yet)
 * assumes NU7 is active, since that is the case for all but the first days of this SDK line; an
 * unknown [nu7ActivationHeight] (an unrecognized network) assumes it is not.
 */
internal fun protocolSecondsPerBlock(
    height: Long?,
    nu7ActivationHeight: Long?,
): Long =
    when {
        nu7ActivationHeight == null -> PRE_NU7_SECONDS_PER_BLOCK
        height == null || height >= nu7ActivationHeight -> POST_NU7_SECONDS_PER_BLOCK
        else -> PRE_NU7_SECONDS_PER_BLOCK
    }

/** Default when no estimator is wired: no estimate, protocol-constant rate. */
internal object NoOpChainTipEstimator : ChainTipEstimator {
    override suspend fun estimatedTip(): Long = -1L

    override suspend fun estimatedSecondsPerBlock(): Long = POST_NU7_SECONDS_PER_BLOCK
}

internal const val MIN_SECONDS_PER_BLOCK = 5L
internal const val MAX_SECONDS_PER_BLOCK = 150L

/** Blocks of history used for the rate measurement. */
internal const val RATE_WINDOW_BLOCKS = 100L

/**
 * Entries in a `blockRateSamples` array that carry a full `(height, time)` pair for both the newer
 * and the older sample: `[latestHeight, latestTime, olderHeight, olderTime]`.
 */
internal const val SAMPLE_PAIR_SIZE = 4

/**
 * Number of blocks in the height range `(fromExclusive, toInclusive]` that are mined under NU7
 * rules, i.e. at or above [nu7ActivationHeight].
 */
private fun postNu7BlocksIn(
    fromExclusive: Long,
    toInclusive: Long,
    nu7ActivationHeight: Long?,
): Long =
    if (nu7ActivationHeight == null) {
        0L
    } else {
        (toInclusive - maxOf(fromExclusive, nu7ActivationHeight - 1)).coerceIn(0L, toInclusive - fromExclusive)
    }

/**
 * Pure rate computation: clamped average spacing between two scanned blocks, expressed for the
 * block at [newerHeight]. When the window straddles NU7 activation, pre-NU7 blocks are weighted
 * as [NU7_SPACING_RATIO] post-NU7 blocks, so a window that has only partly crossed the boundary
 * does not report a blend of the two targets.
 */
internal fun measuredSecondsPerBlock(
    olderHeight: Long,
    olderTimeEpochSeconds: Long,
    newerHeight: Long,
    newerTimeEpochSeconds: Long,
    nu7ActivationHeight: Long? = null,
): Long {
    val blocks = newerHeight - olderHeight
    if (blocks <= 0) return protocolSecondsPerBlock(newerHeight, nu7ActivationHeight)
    val span = (newerTimeEpochSeconds - olderTimeEpochSeconds).coerceAtLeast(0)
    val postBlocks = postNu7BlocksIn(olderHeight, newerHeight, nu7ActivationHeight)
    val preBlocks = blocks - postBlocks
    // Span measured in units of post-NU7 blocks.
    val postNu7Rate = span / (preBlocks * NU7_SPACING_RATIO + postBlocks)
    val rate =
        if (postBlocks > 0) {
            postNu7Rate
        } else {
            span / blocks
        }
    return rate.coerceIn(MIN_SECONDS_PER_BLOCK, MAX_SECONDS_PER_BLOCK)
}

/**
 * Estimates the current chain tip from the latest scanned block plus elapsed wall-clock time at
 * [secondsPerBlock] (the spacing at [scannedHeight]). If the extrapolation crosses
 * [nu7ActivationHeight], blocks from activation onward are counted at a third of that spacing.
 * Returns -1 when no block has been scanned yet.
 */
internal fun estimateTip(
    scannedHeight: Long,
    scannedBlockTimeEpochSeconds: Long,
    nowEpochSeconds: Long,
    secondsPerBlock: Long = PRE_NU7_SECONDS_PER_BLOCK,
    nu7ActivationHeight: Long? = null,
): Long {
    val elapsed = (nowEpochSeconds - scannedBlockTimeEpochSeconds).coerceAtLeast(0L)
    val rate = secondsPerBlock.coerceAtLeast(1L)
    if (nu7ActivationHeight == null || scannedHeight >= nu7ActivationHeight) {
        return scannedHeight + elapsed / rate
    }
    val secondsToLastPreNu7Block = (nu7ActivationHeight - 1 - scannedHeight) * rate
    return if (elapsed < secondsToLastPreNu7Block) {
        scannedHeight + elapsed / rate
    } else {
        val postRate = (rate / NU7_SPACING_RATIO).coerceAtLeast(1L)
        nu7ActivationHeight - 1 + (elapsed - secondsToLastPreNu7Block) / postRate
    }
}

/**
 * Pure derivation of the measured rate from a `blockRateSamples` array
 * (`[latestHeight, latestTime, olderHeight, olderTime]`): the clamped average spacing between the
 * two samples, or the protocol target when the older sample is absent (array shorter than 4).
 * The result is for the block at the latest sample's height.
 */
internal fun secondsPerBlockFromSamples(
    samples: LongArray,
    nu7ActivationHeight: Long? = null,
): Long =
    if (samples.size >= SAMPLE_PAIR_SIZE) {
        measuredSecondsPerBlock(
            olderHeight = samples[2],
            olderTimeEpochSeconds = samples[3],
            newerHeight = samples[0],
            newerTimeEpochSeconds = samples[1],
            nu7ActivationHeight = nu7ActivationHeight,
        )
    } else {
        protocolSecondsPerBlock(samples.firstOrNull(), nu7ActivationHeight)
    }

/**
 * The block spacing to project from now: the rate measured at the latest scanned block, divided
 * by [NU7_SPACING_RATIO] if that block predates NU7 but the estimated tip has reached it.
 */
internal fun secondsPerBlockAtTip(
    samples: LongArray,
    nowEpochSeconds: Long,
    nu7ActivationHeight: Long?,
): Long {
    val rate = secondsPerBlockFromSamples(samples, nu7ActivationHeight)
    if (samples.size < 2 || nu7ActivationHeight == null || samples[0] >= nu7ActivationHeight) {
        return rate
    }
    val tip =
        estimateTip(
            scannedHeight = samples[0],
            scannedBlockTimeEpochSeconds = samples[1],
            nowEpochSeconds = nowEpochSeconds,
            secondsPerBlock = rate,
            nu7ActivationHeight = nu7ActivationHeight,
        )
    return if (tip >= nu7ActivationHeight) {
        (rate / NU7_SPACING_RATIO).coerceIn(MIN_SECONDS_PER_BLOCK, MAX_SECONDS_PER_BLOCK)
    } else {
        rate
    }
}

/**
 * A [ChainTipEstimator] that reads its block samples through the engine's BUNDLED SQLite (a JNI
 * call), never through a second Android-framework SQLite instance on the engine-owned
 * `data.sqlite3`.
 *
 * This deliberately does NOT use `ReadOnlySupportSqliteOpenHelper`/`BlockTable` (framework
 * `android.database.sqlite.SQLiteDatabase`): running a second SQLite library instance against the
 * engine's DB in one process makes a framework `close()` drop the engine's fcntl/WAL locks and
 * truncate the `-shm` index under the engine's live mmap → deterministic SIGBUS (Milan's
 * dual-SQLite-instance incident; the production host reads moved to bundled rusqlite for exactly
 * this reason). An earlier version of this class opened framework SQLite here and both leaked the
 * handle (StrictMode `LeakedClosableViolation`) and reintroduced that hazard.
 */
internal class LazyDataDbChainTipEstimator(
    private val context: Context,
    private val network: ZcashNetwork,
    private val alias: String,
    private val clock: Clock = Clock.System,
    private val rustBackendProvider: suspend () -> MigrationRustBackend = { MigrationRustBackend.new() },
) : ChainTipEstimator {
    private val nu7ActivationHeight: Long? = network.nu7ActivationHeight?.value

    private suspend fun samples(): LongArray {
        val dbFile = DatabaseCoordinator.getInstance(context).dataDbFile(network, alias)
        // blockRateSamples dispatches its own JNI read onto SdkDispatchers.DATABASE_IO.
        return rustBackendProvider().blockRateSamples(dbFile.absolutePath, RATE_WINDOW_BLOCKS)
    }

    // Deliberately broad: the estimate is advisory, and every failure mode of the JNI read (a
    // missing or half-migrated database, a JNI error, an I/O fault) must degrade to the sentinel
    // rather than propagate into a caller that only wanted a hint about the tip.
    @Suppress("TooGenericExceptionCaught")
    override suspend fun estimatedTip(): Long =
        try {
            val s = samples()
            if (s.size < 2) {
                -1L
            } else {
                estimateTip(
                    scannedHeight = s[0],
                    scannedBlockTimeEpochSeconds = s[1],
                    nowEpochSeconds = clock.now().epochSeconds,
                    secondsPerBlock = secondsPerBlockFromSamples(s, nu7ActivationHeight),
                    nu7ActivationHeight = nu7ActivationHeight,
                )
            }
        } catch (e: Exception) {
            Twig.warn(e) { "ChainTipEstimator: could not read latest block — returning -1" }
            -1L
        }

    // Deliberately broad, for the same reason as [estimatedTip]: an unmeasurable rate falls back to
    // the protocol target instead of failing the caller.
    @Suppress("TooGenericExceptionCaught")
    override suspend fun estimatedSecondsPerBlock(): Long =
        try {
            secondsPerBlockAtTip(samples(), clock.now().epochSeconds, nu7ActivationHeight)
        } catch (e: Exception) {
            Twig.warn(e) { "ChainTipEstimator: could not measure block rate — falling back" }
            protocolSecondsPerBlock(height = null, nu7ActivationHeight = nu7ActivationHeight)
        }
}
