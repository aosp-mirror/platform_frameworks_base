package android.security.attestationverification

import android.app.Activity
import android.os.Bundle
import android.security.attestationverification.AttestationVerificationManager.PARAM_CHALLENGE
import android.security.attestationverification.AttestationVerificationManager.PARAM_PUBLIC_KEY
import android.security.attestationverification.AttestationVerificationManager.PROFILE_PEER_DEVICE
import android.security.attestationverification.AttestationVerificationManager.RESULT_FAILURE
import android.security.attestationverification.AttestationVerificationManager.TYPE_CHALLENGE
import android.security.attestationverification.AttestationVerificationManager.TYPE_PUBLIC_KEY
import android.security.attestationverification.AttestationVerificationManager.TYPE_UNKNOWN
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.security.cert.CertificateFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Test for system-defined attestation verifiers. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class PeerDeviceSystemAttestationVerificationTest {

    @get:Rule
    val rule = ActivityScenarioRule(TestActivity::class.java)

    private val certifcateFactory = CertificateFactory.getInstance("X.509")
    private lateinit var activity: Activity
    private lateinit var avm: AttestationVerificationManager
    private lateinit var invalidAttestationByteArray: ByteArray

    @Before
    fun setup() {
        rule.getScenario().onActivity {
            avm = it.getSystemService(AttestationVerificationManager::class.java)
            activity = it
        }
        invalidAttestationByteArray = TEST_ATTESTATION_CERT_FILENAME.fromPEMFileToByteArray()
    }

    @Test
    fun verifyAttestation_returnsFailureWrongBindingType() {
        val future = CompletableFuture<Int>()
        val profile = AttestationProfile(PROFILE_PEER_DEVICE)
        avm.verifyAttestation(profile, TYPE_UNKNOWN, Bundle(),
            invalidAttestationByteArray, activity.mainExecutor) { result, _ ->
            future.complete(result)
        }

        assertThat(future.getSoon()).isEqualTo(RESULT_FAILURE)
    }

    @Test
    fun verifyAttestation_returnsFailureEmptyRequirements() {
        val future = CompletableFuture<Int>()
        val profile = AttestationProfile(PROFILE_PEER_DEVICE)
        avm.verifyAttestation(profile, TYPE_PUBLIC_KEY, Bundle(),
            invalidAttestationByteArray, activity.mainExecutor) { result, _ ->
            future.complete(result)
        }

        assertThat(future.getSoon()).isEqualTo(RESULT_FAILURE)
    }

    @Test
    fun verifyAttestation_returnsFailureMismatchBindingType() {
        val future = CompletableFuture<Int>()
        val profile = AttestationProfile(PROFILE_PEER_DEVICE)
        val publicKeyRequirements = Bundle()
        publicKeyRequirements.putByteArray(PARAM_PUBLIC_KEY, "publicKeyStr".encodeToByteArray())
        avm.verifyAttestation(profile, TYPE_CHALLENGE, publicKeyRequirements,
            invalidAttestationByteArray, activity.mainExecutor) { result, _ ->
            future.complete(result)
        }

        assertThat(future.getSoon()).isEqualTo(RESULT_FAILURE)

        val future2 = CompletableFuture<Int>()
        val challengeRequirements = Bundle()
        challengeRequirements.putByteArray(PARAM_CHALLENGE, "challengeStr".encodeToByteArray())
        avm.verifyAttestation(profile, TYPE_PUBLIC_KEY, challengeRequirements,
            invalidAttestationByteArray, activity.mainExecutor) { result, _ ->
            future2.complete(result)
        }

        assertThat(future2.getSoon()).isEqualTo(RESULT_FAILURE)
    }

    @Test
    fun verifyAttestation_returnsFailureWrongResourceKey() {
        val future = CompletableFuture<Int>()
        val profile = AttestationProfile(PROFILE_PEER_DEVICE)
        val wrongKeyRequirements = Bundle()
        wrongKeyRequirements.putByteArray("wrongReqKey", "publicKeyStr".encodeToByteArray())
        avm.verifyAttestation(profile, TYPE_PUBLIC_KEY, wrongKeyRequirements,
            invalidAttestationByteArray, activity.mainExecutor) { result, _ ->
            future.complete(result)
        }

        assertThat(future.getSoon()).isEqualTo(RESULT_FAILURE)
    }

    @Test
    fun verifyAttestation_returnsFailureEmptyAttestation() {
        val future = CompletableFuture<Int>()
        val profile = AttestationProfile(PROFILE_PEER_DEVICE)
        val requirements = Bundle()
        requirements.putByteArray(PARAM_PUBLIC_KEY, "publicKeyStr".encodeToByteArray())
        avm.verifyAttestation(profile, TYPE_PUBLIC_KEY, requirements, ByteArray(0),
            activity.mainExecutor) { result, _ ->
            future.complete(result)
        }

        assertThat(future.getSoon()).isEqualTo(RESULT_FAILURE)
    }

    @Test
    fun verifyAttestation_returnsFailureTrustAnchorMismatch() {
        val future = CompletableFuture<Int>()
        val profile = AttestationProfile(PROFILE_PEER_DEVICE)
        val challengeRequirements = Bundle()
        challengeRequirements.putByteArray(PARAM_CHALLENGE, "player456".encodeToByteArray())
        avm.verifyAttestation(profile, TYPE_CHALLENGE, challengeRequirements,
            invalidAttestationByteArray, activity.mainExecutor) { result, _ ->
            future.complete(result)
        }
        assertThat(future.getSoon()).isEqualTo(RESULT_FAILURE)
    }

    private fun <T> CompletableFuture<T>.getSoon(): T {
        return this.get(1, TimeUnit.SECONDS)
    }

    private fun String.fromPEMFileToByteArray(): ByteArray {
        val certs = certifcateFactory.generateCertificates(
            InstrumentationRegistry.getInstrumentation().getContext().getResources().getAssets()
                .open(this))
        val bos = ByteArrayOutputStream()
        certs.forEach {
            bos.write(it.encoded)
        }
        return bos.toByteArray()
    }

    class TestActivity : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
        }
    }

    companion object {
        private const val TEST_ATTESTATION_CERT_FILENAME = "test_attestation_wrong_root_certs.pem"
    }
}
