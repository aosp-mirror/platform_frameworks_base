package android.security.attestationverification

import android.os.Bundle
import android.app.Activity
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.google.common.truth.Truth.assertThat
import android.security.attestationverification.AttestationVerificationManager.PARAM_CHALLENGE
import android.security.attestationverification.AttestationVerificationManager.PROFILE_SELF_TRUSTED
import android.security.attestationverification.AttestationVerificationManager.PROFILE_UNKNOWN
import android.security.attestationverification.AttestationVerificationManager.RESULT_FAILURE
import android.security.attestationverification.AttestationVerificationManager.RESULT_SUCCESS
import android.security.attestationverification.AttestationVerificationManager.RESULT_UNKNOWN
import android.security.attestationverification.AttestationVerificationManager.TYPE_PUBLIC_KEY
import android.security.attestationverification.AttestationVerificationManager.TYPE_CHALLENGE
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.lang.IllegalArgumentException
import java.io.ByteArrayOutputStream
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Test for system-defined attestation verifiers. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class SystemAttestationVerificationTest {
    @get:Rule
    val rule = ActivityScenarioRule(TestActivity::class.java)

    private lateinit var activity: Activity
    private lateinit var avm: AttestationVerificationManager
    private lateinit var androidKeystore: KeyStore

    @Before
    fun setup() {
        rule.getScenario().onActivity {
            avm = it.getSystemService(AttestationVerificationManager::class.java)!!
            activity = it
            androidKeystore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        }
    }

    @Test
    fun verifyAttestation_returnsUnknown() {
        val future = CompletableFuture<Int>()
        val profile = AttestationProfile(PROFILE_UNKNOWN)
        avm.verifyAttestation(profile, TYPE_PUBLIC_KEY, Bundle(), ByteArray(0),
                activity.mainExecutor) { result, _ ->
            future.complete(result)
        }

        assertThat(future.getSoon()).isEqualTo(RESULT_UNKNOWN)
    }

    @Test
    fun verifyAttestation_returnsFailureWithEmptyAttestation() {
        val future = CompletableFuture<Int>()
        val profile = AttestationProfile(PROFILE_SELF_TRUSTED)
        avm.verifyAttestation(profile, TYPE_CHALLENGE, Bundle(), ByteArray(0),
            activity.mainExecutor) { result, _ ->
            future.complete(result)
        }

        assertThat(future.getSoon()).isEqualTo(RESULT_FAILURE)
    }

    @Test
    fun verifyAttestation_returnsFailureWithEmptyRequirements() {
        val future = CompletableFuture<Int>()
        val selfTrusted = TestSelfTrustedAttestation("test", "challengeStr")
        avm.verifyAttestation(selfTrusted.profile, selfTrusted.localBindingType,
            Bundle(), selfTrusted.attestation, activity.mainExecutor) { result, _ ->
            future.complete(result)
        }
        assertThat(future.getSoon()).isEqualTo(RESULT_FAILURE)
    }

    @Test
    fun verifyAttestation_returnsFailureWithWrongBindingType() {
        val future = CompletableFuture<Int>()
        val selfTrusted = TestSelfTrustedAttestation("test", "challengeStr")
        avm.verifyAttestation(selfTrusted.profile, TYPE_PUBLIC_KEY,
            selfTrusted.requirements, selfTrusted.attestation, activity.mainExecutor) { result, _ ->
            future.complete(result)
        }
        assertThat(future.getSoon()).isEqualTo(RESULT_FAILURE)
    }

    @Test
    fun verifyAttestation_returnsFailureWithWrongRequirements() {
        val future = CompletableFuture<Int>()
        val selfTrusted = TestSelfTrustedAttestation("test", "challengeStr")
        val wrongKeyRequirements = Bundle()
        wrongKeyRequirements.putByteArray(
            "wrongBindingKey", "challengeStr".encodeToByteArray())
        avm.verifyAttestation(selfTrusted.profile, selfTrusted.localBindingType,
            wrongKeyRequirements, selfTrusted.attestation, activity.mainExecutor) { result, _ ->
            future.complete(result)
        }
        assertThat(future.getSoon()).isEqualTo(RESULT_FAILURE)
    }

    @Test
    fun verifyAttestation_returnsFailureWithWrongChallenge() {
        val future = CompletableFuture<Int>()
        val selfTrusted = TestSelfTrustedAttestation("test", "challengeStr")
        val wrongChallengeRequirements = Bundle()
        wrongChallengeRequirements.putByteArray(PARAM_CHALLENGE, "wrong".encodeToByteArray())
        avm.verifyAttestation(selfTrusted.profile, selfTrusted.localBindingType,
            wrongChallengeRequirements, selfTrusted.attestation, activity.mainExecutor) {
                result, _ -> future.complete(result)
        }
        assertThat(future.getSoon()).isEqualTo(RESULT_FAILURE)
    }

    // TODO(b/216144791): Add more failure tests for PROFILE_SELF_TRUSTED.
    @Test
    fun verifyAttestation_returnsSuccess() {
        val future = CompletableFuture<Int>()
        val selfTrusted = TestSelfTrustedAttestation("test", "challengeStr")
        avm.verifyAttestation(selfTrusted.profile, selfTrusted.localBindingType,
            selfTrusted.requirements, selfTrusted.attestation, activity.mainExecutor) { result, _ ->
            future.complete(result)
        }
        assertThat(future.getSoon()).isEqualTo(RESULT_SUCCESS)
    }

    @Test
    fun verifyToken_returnsUnknown() {
        val future = CompletableFuture<Int>()
        val profile = AttestationProfile(PROFILE_SELF_TRUSTED)
        avm.verifyAttestation(profile, TYPE_PUBLIC_KEY, Bundle(), ByteArray(0),
                activity.mainExecutor) { _, token ->
            val result = avm.verifyToken(profile, TYPE_PUBLIC_KEY, Bundle(), token, null)
            future.complete(result)
        }

        assertThat(future.getSoon()).isEqualTo(RESULT_UNKNOWN)
    }

    @Test
    fun verifyToken_tooBigMaxAgeThrows() {
        val future = CompletableFuture<VerificationToken>()
        val profile = AttestationProfile(PROFILE_SELF_TRUSTED)
        avm.verifyAttestation(profile, TYPE_PUBLIC_KEY, Bundle(), ByteArray(0),
                activity.mainExecutor) { _, token ->
            future.complete(token)
        }

        assertThrows(IllegalArgumentException::class.java) {
            avm.verifyToken(profile, TYPE_PUBLIC_KEY, Bundle(), future.getSoon(),
                    Duration.ofSeconds(3601))
        }
    }

    private fun <T> CompletableFuture<T>.getSoon(): T {
        return this.get(1, TimeUnit.SECONDS)
    }

    class TestActivity : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
        }
    }

    inner class TestSelfTrustedAttestation(val alias: String, val challenge: String) {
        val profile = AttestationProfile(PROFILE_SELF_TRUSTED)
        val localBindingType = TYPE_CHALLENGE
        val requirements: Bundle
        val attestation: ByteArray

        init {
            val challengeByteArray = challenge.encodeToByteArray()
            generateAndStoreKey(alias, challengeByteArray)
            attestation = generateCertificatesByteArray(alias)
            requirements = Bundle()
            requirements.putByteArray(PARAM_CHALLENGE, challengeByteArray)
        }

        private fun generateAndStoreKey(alias: String, challenge: ByteArray) {
            val kpg: KeyPairGenerator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC,
                ANDROID_KEYSTORE
            )
            val parameterSpec: KeyGenParameterSpec = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            ).run {
                // a challenge results in a generated attestation
                setAttestationChallenge(challenge)
                setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                build()
            }
            kpg.initialize(parameterSpec)
            kpg.generateKeyPair()
        }

        private fun generateCertificatesByteArray(alias: String): ByteArray {
            val pkEntry = androidKeystore.getEntry(alias, null) as KeyStore.PrivateKeyEntry
            val certs = pkEntry.certificateChain
            val bos = ByteArrayOutputStream()
            certs.forEach {
                bos.write(it.encoded)
            }
            return bos.toByteArray()
        }
    }

    companion object {
        private const val TAG = "AVFTEST"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}
