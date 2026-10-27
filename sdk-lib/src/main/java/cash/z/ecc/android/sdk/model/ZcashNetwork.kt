package cash.z.ecc.android.sdk.model

/**
 * The Zcash network.  Should be one of [ZcashNetwork.Testnet] or [ZcashNetwork.Mainnet].
 *
 * The constructor for the network is public to allow for certain test cases to use a custom "darkside" network.
 */
data class ZcashNetwork(
    val id: Int,
    val networkName: String,
    val saplingActivationHeight: BlockHeight,
    val orchardActivationHeight: BlockHeight
) {
    fun isMainnet() = id == ID_MAINNET

    fun isTestnet() = id == ID_TESTNET

    /**
     * The height at which NU7 activates on this network, or `null` if this SDK does not know it
     * (an unrecognized network id).
     *
     * NU7 lowers the target block spacing from 75 to 25 seconds (ZIP 208 / ZIP 218), so any
     * height-to-time conversion must check which side of this height it is on. These values
     * mirror `zcash_protocol`'s consensus parameters, which the native library uses for
     * consensus; the Kotlin copy exists so that time estimates do not need a JNI call.
     */
    val nu7ActivationHeight: BlockHeight?
        get() =
            when (id) {
                ID_MAINNET -> NU7_ACTIVATION_HEIGHT_MAINNET
                ID_TESTNET -> NU7_ACTIVATION_HEIGHT_TESTNET
                else -> null
            }

    /**
     * True if NU7 consensus rules (including its 25-second block target) apply at [height].
     */
    fun isNu7ActiveAt(height: BlockHeight): Boolean = nu7ActivationHeight?.let { height >= it } ?: false

    @Suppress("MagicNumber")
    companion object {
        const val ID_TESTNET = 0
        const val ID_MAINNET = 1

        private val NU7_ACTIVATION_HEIGHT_MAINNET = BlockHeight(3_543_405)
        private val NU7_ACTIVATION_HEIGHT_TESTNET = BlockHeight(4_465_026)

        // You may notice there are extra checkpoints bundled in the SDK that match the
        // sapling/orchard activation heights.

        val Testnet =
            ZcashNetwork(
                ID_TESTNET,
                "testnet",
                saplingActivationHeight = BlockHeight(280_000),
                orchardActivationHeight = BlockHeight(1_842_420)
            )

        val Mainnet =
            ZcashNetwork(
                ID_MAINNET,
                "mainnet",
                saplingActivationHeight = BlockHeight(419_200),
                orchardActivationHeight = BlockHeight(1_687_104)
            )

        fun from(id: Int) =
            when (id) {
                0 -> Testnet
                1 -> Mainnet
                else -> throw IllegalArgumentException("Unknown network id: $id")
            }
    }
}
