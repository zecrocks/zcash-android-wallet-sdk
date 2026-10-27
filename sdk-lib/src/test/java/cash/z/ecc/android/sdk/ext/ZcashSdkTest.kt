package cash.z.ecc.android.sdk.ext

import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.ZcashNetwork
import kotlin.test.Test
import kotlin.test.assertEquals

class ZcashSdkTest {
    private val nu7 = ZcashNetwork.Mainnet.nu7ActivationHeight!!

    @Test
    fun block_interval_changes_at_nu7() {
        assertEquals(75_000L, ZcashSdk.blockIntervalMillis(ZcashNetwork.Mainnet, nu7 - 1))
        assertEquals(25_000L, ZcashSdk.blockIntervalMillis(ZcashNetwork.Mainnet, nu7))
    }

    @Test
    fun default_expiry_delta_changes_at_nu7() {
        assertEquals(40, ZcashSdk.defaultTxExpiryDelta(ZcashNetwork.Mainnet, nu7 - 1))
        assertEquals(120, ZcashSdk.defaultTxExpiryDelta(ZcashNetwork.Mainnet, nu7))
    }

    @Test
    fun testnet_uses_its_own_activation_height() {
        assertEquals(
            75_000L,
            ZcashSdk.blockIntervalMillis(ZcashNetwork.Testnet, BlockHeight(4_465_025))
        )
        assertEquals(
            25_000L,
            ZcashSdk.blockIntervalMillis(ZcashNetwork.Testnet, BlockHeight(4_465_026))
        )
    }
}
