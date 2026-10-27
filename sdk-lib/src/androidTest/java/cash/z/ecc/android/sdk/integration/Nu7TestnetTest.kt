package cash.z.ecc.android.sdk.integration

import android.util.Log
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import cash.z.ecc.android.bip39.Mnemonics
import cash.z.ecc.android.bip39.toSeed
import cash.z.ecc.android.sdk.CloseableSynchronizer
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.WalletInitMode
import cash.z.ecc.android.sdk.ext.ConsensusBranchId
import cash.z.ecc.android.sdk.internal.jni.RustBackend
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.AccountBalance
import cash.z.ecc.android.sdk.model.AccountCreateSetup
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.TransactionOverview
import cash.z.ecc.android.sdk.model.TransactionSubmitResult
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.android.sdk.test.ScopedTest
import cash.z.ecc.android.sdk.tool.CheckpointTool
import cash.z.ecc.android.sdk.tool.DerivationTool
import cash.z.ecc.android.sdk.type.ServerValidation
import co.electriccoin.lightwallet.client.LightWalletClient
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import co.electriccoin.lightwallet.client.model.Response
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.io.File
import java.util.Collections
import kotlin.test.assertNotNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Opt-in NU7 verification against the live Zcash testnet through `testnet.zec.rocks:443`.
 *
 * Skipped unless the instrumentation argument `nu7Testnet=true` is given, so the regular
 * instrumentation suite is unaffected. The wallet seed is never committed: pass it with
 * `nu7Seed` (a BIP 39 mnemonic whose words may be separated by `_`, `,` or `+`, since the
 * runner arguments cannot carry spaces) or `nu7SeedHex` (the hex-encoded BIP 39 entropy).
 *
 * Optional arguments: `nu7Birthday` (default 4460000), `nu7SyncTimeoutMinutes` (default 90) and `nu7MineTimeoutMinutes`
 * (default 10).
 *
 * Every wallet address and the key heights and branch IDs are logged under the logcat tag
 * [TAG], so a human can fund the wallet and rerun to exercise the spend tests.
 */
@LargeTest
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class Nu7TestnetTest : ScopedTest() {
    @Before
    fun requireOptIn() {
        assumeTrue("NU7 testnet test is opt-in: pass nu7Testnet=true", isEnabled)
    }

    @Test
    fun test1_serverIsOnNu7() =
        runBlocking {
            val serverInfo =
                LightWalletClient.new(appContext, ENDPOINT).let { client ->
                    try {
                        client.getServerInfo().successOrFail("getServerInfo")
                    } finally {
                        client.dispose()
                    }
                }
            val serverTip = serverInfo.blockHeightUnsafe.value
            val serverBranch = ConsensusBranchId.fromHex(serverInfo.consensusBranchId)
            log(
                "server chain=${serverInfo.chainName} tip=$serverTip " +
                    "estimatedHeight=${serverInfo.estimatedHeight} branchId=0x${serverInfo.consensusBranchId} " +
                    "($serverBranch)"
            )

            assertTrue("server must be testnet", serverInfo.matchingNetwork(ZcashNetwork.Testnet.networkName))
            assertTrue("server tip $serverTip must be past NU7 activation", serverTip > NU7_ACTIVATION)
            assertEquals(NU7_BRANCH_ID, serverInfo.consensusBranchId.toLong(HEX_RADIX))
            assertEquals(ConsensusBranchId.NU7, serverBranch)

            // The SDK's own view of the consensus rules, independent of any server.
            val backend = branchOnlyBackend()
            val sdkBranchAtActivation = backend.getBranchIdForHeight(NU7_ACTIVATION)
            val sdkBranchBeforeActivation = backend.getBranchIdForHeight(NU7_ACTIVATION - 1)
            val sdkBranchAtTip = backend.getBranchIdForHeight(serverTip)
            log(
                "sdk branchId at ${NU7_ACTIVATION - 1}=0x${sdkBranchBeforeActivation.hex()} " +
                    "at $NU7_ACTIVATION=0x${sdkBranchAtActivation.hex()} at tip $serverTip=0x${sdkBranchAtTip.hex()}"
            )
            assertEquals(NU7_BRANCH_ID, sdkBranchAtActivation)
            assertEquals(NU7_BRANCH_ID, sdkBranchAtTip)
            assertTrue("pre-activation height must not be NU7", sdkBranchBeforeActivation != NU7_BRANCH_ID)

            val synchronizer = synchronizer()
            val validation = synchronizer.validateServerEndpoint(appContext, ENDPOINT)
            log("validateServerEndpoint(${ENDPOINT.host}:${ENDPOINT.port}) = ${validation.describe()}")
            assertEquals(ServerValidation.Valid, validation)
        }

    @Test
    fun test2_syncAcrossNu7Activation() =
        runBlocking {
            val synchronizer = synchronizer()
            logAddresses(synchronizer, account(synchronizer))

            val scanned = syncToTip(synchronizer)
            val tip = synchronizer.networkHeight.value
            log("synced: fullyScannedHeight=$scanned networkHeight=$tip")
            assertNotNull(tip)
            assertTrue(
                "fullyScannedHeight $scanned must be within $SYNC_MARGIN of tip $tip",
                scanned.value >= tip.value - SYNC_MARGIN
            )
            assertTrue("scan must cross NU7 activation", scanned.value > NU7_ACTIVATION)
            assertNull("initializationError", synchronizer.initializationError)
            assertTrue("critical errors during sync: ${criticalErrors.size}", criticalErrors.isEmpty())

            val match = synchronizer.validateConsensusBranch()
            log("validateConsensusBranch: sdk=${match.sdkBranch} server=${match.serverBranch} valid=${match.isValid}")
            assertTrue("consensus branch mismatch: ${match.errorMessage}", match.isValid)
            assertEquals(ConsensusBranchId.NU7, match.sdkBranch)

            val balance = balanceOf(synchronizer)
            log("balance after sync: ${balance?.describe()}")
            logAddresses(synchronizer, account(synchronizer))
        }

    @Test
    fun test3_sendUnderNu7() =
        runBlocking {
            val synchronizer = synchronizer()
            val account = account(synchronizer)
            syncToTip(synchronizer)

            val spendable = balanceOf(synchronizer)?.spendableShielded() ?: Zatoshi(0)
            log("spendable shielded balance: ${spendable.value} zats")
            if (spendable.value < MIN_SPENDABLE.value) {
                logAddresses(synchronizer, account)
            }
            assumeTrue(
                "needs >= ${MIN_SPENDABLE.value} spendable shielded zats, have ${spendable.value}; fund the " +
                    "addresses logged under $TAG and rerun",
                spendable.value >= MIN_SPENDABLE.value
            )

            val ua = synchronizer.getUnifiedAddress(account)
            val creationTip = assertNotNull(synchronizer.networkHeight.value)
            val proposal = synchronizer.proposeTransfer(account, ua, SEND_AMOUNT, "NU7 testnet self-transfer")
            log("proposal: ${proposal.toPrettyString()} creationTip=$creationTip")

            val overview = createAndAwaitMined(synchronizer, account, proposal)
            val expiry = assertNotNull(overview.expiryHeight).value
            val mined = assertNotNull(overview.minedHeight).value
            log(
                "send mined: minedHeight=$mined expiryHeight=$expiry creationTip=${creationTip.value} " +
                    "expiry-creationTip=${expiry - creationTip.value} " +
                    "fee=${overview.feePaid?.value}"
            )
            assertTrue("mined height $mined must be past NU7 activation", mined > NU7_ACTIVATION)
            assertTrue(
                "expiry delta ${expiry - creationTip.value} must be ~$NU7_EXPIRY_DELTA",
                expiry - creationTip.value in EXPIRY_DELTA_RANGE
            )
        }

    @Test
    fun test4_shieldUnderNu7() =
        runBlocking {
            val synchronizer = synchronizer()
            val account = account(synchronizer)
            syncToTip(synchronizer)

            val transparent = balanceOf(synchronizer)?.unshielded ?: Zatoshi(0)
            log("transparent balance: ${transparent.value} zats")
            assumeTrue(
                "no transparent funds to shield; send some to the transparent address logged under $TAG",
                transparent.value > 0
            )

            val creationTip = assertNotNull(synchronizer.networkHeight.value)
            val proposal = synchronizer.proposeShielding(account, SHIELDING_THRESHOLD)
            assumeTrue(
                "transparent balance ${transparent.value} is below the shielding threshold " +
                    "${SHIELDING_THRESHOLD.value}",
                proposal != null
            )
            log("shielding proposal: ${proposal!!.toPrettyString()} creationTip=$creationTip")

            val overview = createAndAwaitMined(synchronizer, account, proposal)
            val expiry = assertNotNull(overview.expiryHeight).value
            val mined = assertNotNull(overview.minedHeight).value
            log(
                "shield mined: minedHeight=$mined expiryHeight=$expiry expiry-creationTip=" +
                    "${expiry - creationTip.value} isShielding=${overview.isShielding}"
            )
            assertTrue("mined height $mined must be past NU7 activation", mined > NU7_ACTIVATION)
            assertTrue(overview.isShielding)
            assertTrue(
                "expiry delta ${expiry - creationTip.value} must be ~$NU7_EXPIRY_DELTA",
                expiry - creationTip.value in EXPIRY_DELTA_RANGE
            )
        }

    private suspend fun createAndAwaitMined(
        synchronizer: Synchronizer,
        account: Account,
        proposal: Proposal
    ): TransactionOverview {
        val usk =
            DerivationTool.getInstance().deriveUnifiedSpendingKey(
                seed,
                ZcashNetwork.Testnet,
                assertNotNull(account.hdAccountIndex)
            )
        val results = synchronizer.createProposedTransactions(proposal, usk).toList()
        results.forEach { log("submit result: ${it.describe()}") }
        assertEquals(proposal.transactionCount(), results.size)
        results.forEach { assertTrue("submission failed: ${it.describe()}", it is TransactionSubmitResult.Success) }

        val txId = results.last().txId
        return withTimeout(mineTimeout) {
            synchronizer.allTransactions.first { transactions ->
                transactions.any { it.txId.value == txId && it.minedHeight != null }
            }
        }.first { it.txId.value == txId }
    }

    private fun TransactionSubmitResult.describe() =
        when (this) {
            is TransactionSubmitResult.Success -> "Success(${txIdString()})"
            is TransactionSubmitResult.Failure -> "Failure(${txIdString()}, grpc=$grpcError, code=$code, $description)"
            is TransactionSubmitResult.NotAttempted -> "NotAttempted(${txIdString()})"
        }

    private fun ServerValidation.describe() =
        when (this) {
            is ServerValidation.InValid -> "InValid(${reason.javaClass.simpleName}: ${reason.message})"
            else -> toString()
        }

    @Suppress("TooManyFunctions")
    companion object {
        private const val TAG = "NU7TEST"
        private const val NU7_ACTIVATION = 4_465_026L
        private const val NU7_BRANCH_ID = 0x7719_0AD9L
        private const val NU7_EXPIRY_DELTA = 120

        // The builder targets the block after the chain tip, so expiry lands at tip + 121.
        private val EXPIRY_DELTA_RANGE = 118L..122L
        private const val HEX_RADIX = 16

        // Before NU7 activation, so the scan crosses the upgrade boundary.
        private const val DEFAULT_BIRTHDAY = 4_460_000L
        private const val SYNC_MARGIN = 10L
        private val MIN_SPENDABLE = Zatoshi(50_000)
        private val SEND_AMOUNT = Zatoshi(10_000)
        private val SHIELDING_THRESHOLD = Zatoshi(10_000)
        private val POLL_INTERVAL = 5.seconds
        private const val PROGRESS_LOG_EVERY = 6

        private val ENDPOINT = LightWalletEndpoint(host = "testnet.zec.rocks", port = 443, isSecure = true)

        private val appContext = InstrumentationRegistry.getInstrumentation().context
        private val args = InstrumentationRegistry.getArguments()

        private val isEnabled = args.getString("nu7Testnet").toBoolean()

        // Optional `nu7Birthday` restores from an older height to exercise long historic scans.
        private val birthday = args.getString("nu7Birthday")?.toLongOrNull() ?: DEFAULT_BIRTHDAY
        private val syncTimeout = minutesArg("nu7SyncTimeoutMinutes", 90)
        private val mineTimeout = minutesArg("nu7MineTimeoutMinutes", 10)

        // Unique per run so every run is a fresh restore from the birthday.
        private val alias = "nu7test_${System.currentTimeMillis()}"

        private val seed: ByteArray by lazy {
            val hex = args.getString("nu7SeedHex")
            val words = args.getString("nu7Seed")
            val code =
                when {
                    !hex.isNullOrBlank() -> Mnemonics.MnemonicCode(hex.hexToByteArray())
                    !words.isNullOrBlank() -> Mnemonics.MnemonicCode(words.split(SEPARATORS).joinToString(" "))
                    else -> throw AssertionError("nu7Testnet=true requires nu7Seed or nu7SeedHex")
                }
            code.validate()
            code.toSeed()
        }
        private val SEPARATORS = Regex("[\\s_,+]+")

        private val criticalErrors: MutableList<Throwable?> = Collections.synchronizedList(mutableListOf())
        private val lock = Mutex()
        private var closeable: CloseableSynchronizer? = null

        private fun minutesArg(
            name: String,
            default: Long
        ): Duration = (args.getString(name)?.toLongOrNull() ?: default).minutes

        private fun log(message: String) {
            Log.i(TAG, message)
        }

        private fun Long.hex() = toString(HEX_RADIX)

        private fun String.hexToByteArray(): ByteArray =
            chunked(2).map { it.toInt(HEX_RADIX).toByte() }.toByteArray()

        private suspend fun branchOnlyBackend(): RustBackend {
            // getBranchIdForHeight is pure consensus logic; the paths are never opened.
            val dir = File(appContext.cacheDir, "nu7-branch-only").apply { mkdirs() }
            return RustBackend.new(
                fsBlockDbRoot = File(dir, "fs"),
                dataDbFile = File(dir, "data.db"),
                saplingSpendFile = File(dir, "spend.params"),
                saplingOutputFile = File(dir, "output.params"),
                zcashNetworkId = ZcashNetwork.Testnet.id
            )
        }

        private suspend fun synchronizer(): Synchronizer =
            lock.withLock {
                closeable ?: run {
                    val checkpoint =
                        CheckpointTool.loadNearest(appContext, ZcashNetwork.Testnet, BlockHeight.new(birthday))
                    log("creating wallet alias=$alias birthday=$birthday nearestCheckpoint=${checkpoint.height}")
                    Synchronizer
                        .new(
                            alias = alias,
                            birthday = BlockHeight.new(birthday),
                            context = appContext,
                            lightWalletEndpoint = ENDPOINT,
                            setup = AccountCreateSetup("NU7 test", null, FirstClassByteArray(seed)),
                            walletInitMode = WalletInitMode.RestoreWallet,
                            zcashNetwork = ZcashNetwork.Testnet,
                            isTorEnabled = false,
                            isExchangeRateEnabled = false
                        ).also { synchronizer ->
                            synchronizer.onCriticalErrorHandler = { error ->
                                log("critical error: ${error?.javaClass?.name}")
                                criticalErrors += error
                                false
                            }
                            synchronizer.onProcessorErrorHandler = { error ->
                                log("processor error (retrying): ${error?.javaClass?.name}")
                                true
                            }
                            closeable = synchronizer
                        }
                }
            }

        private suspend fun account(synchronizer: Synchronizer): Account = synchronizer.getAccounts().single()

        private suspend fun logAddresses(
            synchronizer: Synchronizer,
            account: Account
        ) {
            val ua = synchronizer.getUnifiedAddress(account)
            val taddr = synchronizer.getTransparentAddress(account)
            log("==================== FUND THIS TESTNET WALLET ====================")
            log("unified address:     $ua")
            log("transparent address: $taddr")
            log("==================================================================")
        }

        /**
         * Waits until the synchronizer reports SYNCED with its fully scanned height at the
         * network tip, returning that height.
         */
        private suspend fun syncToTip(synchronizer: Synchronizer): BlockHeight =
            withTimeout(syncTimeout) {
                var polls = 0
                var syncedHeight: BlockHeight? = null
                while (syncedHeight == null) {
                    val status = synchronizer.status.first()
                    val scanned = synchronizer.fullyScannedHeight.value
                    val tip = synchronizer.networkHeight.value
                    if (polls++ % PROGRESS_LOG_EVERY == 0) {
                        log(
                            "sync status=$status fullyScanned=$scanned networkHeight=$tip " +
                                "progress=${synchronizer.progress.first().toPercentage()}%"
                        )
                    }
                    assertTrue("critical error while syncing", criticalErrors.isEmpty())
                    val atTip = scanned != null && tip != null && scanned.value >= tip.value - SYNC_MARGIN
                    if (status == Synchronizer.Status.SYNCED && atTip) {
                        syncedHeight = scanned
                    } else {
                        delay(POLL_INTERVAL)
                    }
                }
                checkNotNull(syncedHeight)
            }

        private fun balanceOf(synchronizer: Synchronizer): AccountBalance? =
            synchronizer.walletBalances.value
                ?.values
                ?.singleOrNull()

        private fun AccountBalance.spendableShielded(): Zatoshi =
            sapling.available + orchard.available + ironwood.available

        private fun AccountBalance.describe() =
            "sapling=${sapling.available.value}/${sapling.total.value} orchard=${orchard.available.value}/" +
                "${orchard.total.value} ironwood=${ironwood.available.value}/${ironwood.total.value} " +
                "transparent=${unshielded.value} (available/total zats)"

        private fun <T> Response<T>.successOrFail(what: String): T =
            when (this) {
                is Response.Success -> result
                is Response.Failure -> throw AssertionError("$what failed: ${toThrowable().message}")
            }

        @JvmStatic
        @AfterClass
        fun closeAndEraseWallet() =
            runBlocking {
                closeable?.let {
                    it.close()
                    // Give the close a moment to release the databases before erasing.
                    delay(2.seconds)
                    Synchronizer.erase(appContext, ZcashNetwork.Testnet, alias)
                }
                closeable = null
            }
    }
}
