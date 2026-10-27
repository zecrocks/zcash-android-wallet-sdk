package cash.z.ecc.android.sdk.ext

import cash.z.ecc.android.sdk.type.ConsensusMatchType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConsensusBranchIdTest {
    @Test
    fun ids_match_zcash_protocol() {
        // Values from zcash_protocol 0.11 consensus::BranchId.
        assertEquals(0xc2d6_d0b4, ConsensusBranchId.NU5.id)
        assertEquals(0xc8e7_1055, ConsensusBranchId.NU6.id)
        assertEquals(0x4dec_4df0, ConsensusBranchId.NU6_1.id)
        assertEquals(0x5437_f330, ConsensusBranchId.NU6_2.id)
        assertEquals(0x37a5_165b, ConsensusBranchId.NU6_3.id)
        assertEquals(0x7719_0ad9, ConsensusBranchId.NU7.id)
    }

    @Test
    fun hex_ids_match_numeric_ids() {
        ConsensusBranchId.entries.forEach {
            assertEquals("%x".format(it.id), it.hexId, "hexId of $it")
        }
    }

    @Test
    fun nu7_resolves_from_server_hex_and_sdk_id() {
        assertEquals(ConsensusBranchId.NU7, ConsensusBranchId.fromHex("77190ad9"))
        assertEquals(ConsensusBranchId.NU7, ConsensusBranchId.fromHex("0x7719_0AD9"))
        assertEquals(ConsensusBranchId.NU7, ConsensusBranchId.fromId(0x7719_0ad9))
    }

    @Test
    fun server_on_nu7_is_newer_than_sdk_on_nu6_3() {
        val match = ConsensusMatchType(ConsensusBranchId.NU6_3, ConsensusBranchId.fromHex("77190ad9"))
        assertTrue(match.hasBoth)
        assertTrue(match.isServerNewer)
    }
}
