package cash.z.ecc.android.sdk.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ZcashNetworkTest {
    @Test
    fun is_mainnet_succeed_test() {
        assertTrue { ZcashNetwork.Mainnet.isMainnet() }
    }

    @Test
    fun is_mainnet_fail_test() {
        assertFalse { ZcashNetwork.Testnet.isMainnet() }
    }

    @Test
    fun is_testnet_succeed_test() {
        assertTrue { ZcashNetwork.Testnet.isTestnet() }
    }

    @Test
    fun is_testnet_fail_test() {
        assertFalse { ZcashNetwork.Mainnet.isTestnet() }
    }

    @Test
    fun nu7_activation_heights_match_zcash_protocol() {
        assertEquals(BlockHeight(3_543_405), ZcashNetwork.Mainnet.nu7ActivationHeight)
        assertEquals(BlockHeight(4_465_026), ZcashNetwork.Testnet.nu7ActivationHeight)
    }

    @Test
    fun nu7_activation_height_unknown_for_custom_network() {
        val custom = ZcashNetwork(42, "custom", BlockHeight(1), BlockHeight(1))
        assertNull(custom.nu7ActivationHeight)
        assertFalse { custom.isNu7ActiveAt(BlockHeight(10_000_000)) }
    }

    @Test
    fun is_nu7_active_at_boundary() {
        assertFalse { ZcashNetwork.Mainnet.isNu7ActiveAt(BlockHeight(3_543_404)) }
        assertTrue { ZcashNetwork.Mainnet.isNu7ActiveAt(BlockHeight(3_543_405)) }
    }
}
