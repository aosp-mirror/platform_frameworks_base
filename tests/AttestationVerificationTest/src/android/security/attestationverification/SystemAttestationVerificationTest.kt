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
import android.security.attestationverification.AttestationVerificationManager.PROFILE_SELF_TRUSTED
import android.security.attestationverification.AttestationVerificationManager.TYPE_PUBLIC_KEY
import android.security.attestationverification.AttestationVerificationManager.RESULT_UNKNOWN
import java.lang.IllegalArgumentException
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

    @Before
    fun setup() {
        rule.getScenario().onActivity {
            avm = it.getSystemService(AttestationVerificationManager::class.java)
            activity = it
        }
    }

    @Test
    fun verifyAttestation_returnsUnknown() {
        val future = CompletableFuture<Int>()
        val profile = AttestationProfile(PROFILE_SELF_TRUSTED)
        avm.verifyAttestation(profile, TYPE_PUBLIC_KEY, Bundle(), ByteArray(0),
                activity.mainExecutor) { result, _ ->
            future.complete(result)
        }

        assertThat(future.getSoon()).isEqualTo(RESULT_UNKNOWN)
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
}
