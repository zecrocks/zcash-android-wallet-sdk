package cash.z.ecc.android.sdk.ext

import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZcashNetwork

/**
 * Wrapper for all the constant values in the SDK. It is important that these values stay fixed for
 * all users of the SDK. Otherwise, if individual wallet makers are using different values, it
 * becomes easier to reduce privacy by segmenting the anonymity set of users, particularly as it
 * relates to network requests.
 */
object ZcashSdk {
    /**
     * Miner's fee in zatoshi.
     */
    @Suppress("MagicNumber")
    @Deprecated(
        message = "Upcoming SDK 2.1 will create multiple transactions at once for some recipients.",
        replaceWith =
            ReplaceWith(
                "proposeTransfer(usk.account, toAddress, amount, memo) OR " +
                    "proposeShielding(usk.account, shieldingThreshold, memo)"
            )
    )
    val MINERS_FEE = Zatoshi(10_000L)

    /**
     * The maximum length of a memo.
     */
    const val MAX_MEMO_SIZE = 512

    /**
     * The pre-Blossom default expiry delta. The native library has not used this value since
     * Blossom; see [defaultTxExpiryDelta].
     */
    @Deprecated(
        message =
            "The default expiry delta is 40 blocks before NU7 and 120 blocks from NU7 onward. " +
                "Use defaultTxExpiryDelta(network, targetHeight).",
        replaceWith = ReplaceWith("ZcashSdk.defaultTxExpiryDelta(network, targetHeight)")
    )
    const val EXPIRY_OFFSET = 20

    /**
     * Default number of blocks after the target height at which a transaction created by the
     * SDK expires, for transactions targeting a height before NU7 activation (ZIP 203).
     */
    const val PRE_NU7_DEFAULT_TX_EXPIRY_DELTA = 40

    /**
     * Default number of blocks after the target height at which a transaction created by the
     * SDK expires, for transactions targeting NU7 activation or later (ZIP 218). At the 25-second
     * NU7 block target this is the same ~50 minutes as [PRE_NU7_DEFAULT_TX_EXPIRY_DELTA].
     */
    const val POST_NU7_DEFAULT_TX_EXPIRY_DELTA = 120

    /**
     * The default expiry delta, in blocks, the native library applies to a transaction whose
     * target height is [targetHeight] on [network]. The value is chosen by librustzcash; this
     * mirrors it for display and scheduling purposes.
     */
    fun defaultTxExpiryDelta(
        network: ZcashNetwork,
        targetHeight: BlockHeight
    ): Int =
        if (network.isNu7ActiveAt(targetHeight)) {
            POST_NU7_DEFAULT_TX_EXPIRY_DELTA
        } else {
            PRE_NU7_DEFAULT_TX_EXPIRY_DELTA
        }

    /**
     * A short amount of time, in milliseconds, to poll for new blocks used typically when a block synchronization error
     * occurs.
     */
    const val POLL_INTERVAL_SHORT = 5_000L

    /**
     * Default amount of time, in milliseconds, to poll for new blocks. It stays below the 25-second NU7 target block
     * spacing, so a new block is normally noticed within one poll both before and after NU7.
     */
    const val POLL_INTERVAL = 20_000L

    /**
     * Estimate of the time between blocks before NU7.
     */
    @Deprecated(
        message =
            "NU7 lowers the target block spacing from 75 to 25 seconds, so no single constant is " +
                "correct. Use blockIntervalMillis(network, height).",
        replaceWith = ReplaceWith("ZcashSdk.blockIntervalMillis(network, height)")
    )
    const val BLOCK_INTERVAL_MILLIS = 75_000L

    /**
     * Target time between blocks, in milliseconds, from Blossom until NU7 activation.
     */
    const val PRE_NU7_BLOCK_INTERVAL_MILLIS = 75_000L

    /**
     * Target time between blocks, in milliseconds, from NU7 activation onward.
     */
    const val POST_NU7_BLOCK_INTERVAL_MILLIS = 25_000L

    /**
     * Target time between blocks, in milliseconds, for a block mined at [height] on [network]:
     * [POST_NU7_BLOCK_INTERVAL_MILLIS] once NU7 is active at [height], otherwise
     * [PRE_NU7_BLOCK_INTERVAL_MILLIS]. This is the protocol target, not a measurement; actual
     * spacing varies, especially on testnet.
     */
    fun blockIntervalMillis(
        network: ZcashNetwork,
        height: BlockHeight
    ): Long =
        if (network.isNu7ActiveAt(height)) {
            POST_NU7_BLOCK_INTERVAL_MILLIS
        } else {
            PRE_NU7_BLOCK_INTERVAL_MILLIS
        }

    /**
     * The default maximum amount of time to wait during retry backoff intervals. Failed loops will never wait longer
     * than this before retyring.
     */
    const val MAX_BACKOFF_INTERVAL = 600_000L

    /**
     * The default memo to use when shielding transparent funds.
     */
    const val DEFAULT_SHIELD_FUNDS_MEMO_PREFIX = "shielding:"

    /**
     * The default alias used as part of a file name for the preferences and databases. This
     * enables multiple wallets to exist on one device, which is also helpful for sweeping funds.
     */
    const val DEFAULT_ALIAS: String = "zcash_sdk"

    /**
     * The minimum alias length to be valid for our use.
     */
    const val ALIAS_MIN_LENGTH: Int = 1

    /**
     * The maximum alias length to be valid for our use.
     */
    const val ALIAS_MAX_LENGTH: Int = 99
}
