package com.android.server.security

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.security.attestationverification.AttestationVerificationManager.PARAM_CHALLENGE
import android.security.attestationverification.AttestationVerificationManager.PARAM_PUBLIC_KEY
import android.security.attestationverification.AttestationVerificationManager.RESULT_FAILURE
import android.security.attestationverification.AttestationVerificationManager.RESULT_SUCCESS
import android.security.attestationverification.AttestationVerificationManager.TYPE_CHALLENGE
import android.security.attestationverification.AttestationVerificationManager.TYPE_PUBLIC_KEY
import android.util.IndentingPrintWriter
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry
import com.android.server.security.AttestationVerificationManagerService.DumpLogger
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.time.LocalDate
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations


/** Test for Peer Device attestation verifier. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class AttestationVerificationPeerDeviceVerifierTest {
    private val certificateFactory = CertificateFactory.getInstance("X.509")
    @Mock private lateinit var context: Context
    private val dumpLogger = DumpLogger()
    private lateinit var trustAnchors: HashSet<TrustAnchor>

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)

        val rootCerts = TEST_ROOT_CERT_FILENAME.fromPEMFileToCerts()
        trustAnchors = HashSet<TrustAnchor>()
        rootCerts.forEach {
            trustAnchors.add(TrustAnchor(it as X509Certificate, null))
        }
    }

    @After
    fun dumpAndLog() {
        val dump = dumpLogger.getDump()
        Log.d(TAG, "$dump")
    }

    @Test
    fun verifyAttestation_returnsSuccessTypeChallenge() {
        val verifier = AttestationVerificationPeerDeviceVerifier(
            context, dumpLogger, trustAnchors, false, LocalDate.of(2022, 2, 1),
            LocalDate.of(2021, 8, 1)
        )
        val challengeRequirements = Bundle()
        challengeRequirements.putByteArray(PARAM_CHALLENGE, "player456".encodeToByteArray())

        val result = verifier.verifyAttestation(
            TYPE_CHALLENGE, challengeRequirements,
            TEST_ATTESTATION_WITH_ROOT_CERT_FILENAME.fromPEMFileToByteArray()
        )
        assertThat(result).isEqualTo(RESULT_SUCCESS)
    }

    @Test
    fun verifyAttestation_returnsSuccessLocalPatchOlderThanOneYear() {
        val verifier = AttestationVerificationPeerDeviceVerifier(
            context, dumpLogger, trustAnchors, false, LocalDate.of(2022, 2, 1),
            LocalDate.of(2021, 1, 1)
        )
        val challengeRequirements = Bundle()
        challengeRequirements.putByteArray(PARAM_CHALLENGE, "player456".encodeToByteArray())

        val result = verifier.verifyAttestation(
            TYPE_CHALLENGE, challengeRequirements,
            TEST_ATTESTATION_WITH_ROOT_CERT_FILENAME.fromPEMFileToByteArray()
        )
        assertThat(result).isEqualTo(RESULT_SUCCESS)
    }

    @Test
    fun verifyAttestation_returnsSuccessTypePublicKey() {
        val verifier = AttestationVerificationPeerDeviceVerifier(
            context, dumpLogger, trustAnchors, false, LocalDate.of(2022, 2, 1),
            LocalDate.of(2021, 8, 1)
        )

        val leafCert =
            (TEST_ATTESTATION_WITH_ROOT_CERT_FILENAME.fromPEMFileToCerts() as List)[0]
                    as X509Certificate
        val pkRequirements = Bundle()
        pkRequirements.putByteArray(PARAM_PUBLIC_KEY, leafCert.publicKey.encoded)

        val result = verifier.verifyAttestation(
            TYPE_PUBLIC_KEY, pkRequirements,
            TEST_ATTESTATION_WITH_ROOT_CERT_FILENAME.fromPEMFileToByteArray()
        )
        assertThat(result).isEqualTo(RESULT_SUCCESS)
    }

    @Test
    fun verifyAttestation_returnsSuccessOwnedBySystem() {
        val verifier = AttestationVerificationPeerDeviceVerifier(
            context, dumpLogger, trustAnchors, false, LocalDate.of(2022, 2, 1),
            LocalDate.of(2021, 1, 1)
        )
        val challengeRequirements = Bundle()
        challengeRequirements.putByteArray(PARAM_CHALLENGE, "activeUnlockValid".encodeToByteArray())
        challengeRequirements.putBoolean("android.key_owned_by_system", true)

        val result = verifier.verifyAttestation(
            TYPE_CHALLENGE, challengeRequirements,
            TEST_OWNED_BY_SYSTEM_FILENAME.fromPEMFileToByteArray()
        )

        assertThat(result).isEqualTo(RESULT_SUCCESS)
    }

    @Test
    fun verifyAttestation_returnsFailureOwnedBySystem() {
        val verifier = AttestationVerificationPeerDeviceVerifier(
            context, dumpLogger, trustAnchors, false, LocalDate.of(2022, 2, 1),
            LocalDate.of(2021, 1, 1)
        )
        val challengeRequirements = Bundle()
        challengeRequirements.putByteArray(PARAM_CHALLENGE, "player456".encodeToByteArray())
        challengeRequirements.putBoolean("android.key_owned_by_system", true)

        val result = verifier.verifyAttestation(
            TYPE_CHALLENGE, challengeRequirements,
            TEST_ATTESTATION_WITH_ROOT_CERT_FILENAME.fromPEMFileToByteArray()
        )
        assertThat(result).isEqualTo(RESULT_FAILURE)
    }

    @Test
    fun verifyAttestation_returnsFailurePatchDateNotWithinOneYearLocalPatch() {
        val verifier = AttestationVerificationPeerDeviceVerifier(
            context, dumpLogger, trustAnchors, false, LocalDate.of(2023, 3, 1),
            LocalDate.of(2023, 2, 1)
        )
        val challengeRequirements = Bundle()
        challengeRequirements.putByteArray(PARAM_CHALLENGE, "player456".encodeToByteArray())

        val result = verifier.verifyAttestation(
            TYPE_CHALLENGE, challengeRequirements,
            TEST_ATTESTATION_WITH_ROOT_CERT_FILENAME.fromPEMFileToByteArray()
        )
        assertThat(result).isEqualTo(RESULT_FAILURE)
    }

    @Test
    fun verifyAttestation_returnsFailureTrustedAnchorEmpty() {
        val verifier = AttestationVerificationPeerDeviceVerifier(
            context, dumpLogger, HashSet(), false, LocalDate.of(2022, 1, 1),
            LocalDate.of(2022, 1, 1)
        )
        val challengeRequirements = Bundle()
        challengeRequirements.putByteArray(PARAM_CHALLENGE, "player456".encodeToByteArray())

        val result = verifier.verifyAttestation(
            TYPE_CHALLENGE, challengeRequirements,
            TEST_ATTESTATION_WITH_ROOT_CERT_FILENAME.fromPEMFileToByteArray()
        )
        assertThat(result).isEqualTo(RESULT_FAILURE)
    }

    @Test
    fun verifyAttestation_returnsFailureTrustedAnchorMismatch() {
        val badTrustAnchorsCerts = TEST_ATTESTATION_CERT_FILENAME.fromPEMFileToCerts()
        val badTrustAnchors = HashSet<TrustAnchor>()
        badTrustAnchorsCerts.forEach {
            badTrustAnchors.add(TrustAnchor(it as X509Certificate, null))
        }

        val verifier = AttestationVerificationPeerDeviceVerifier(
            context, dumpLogger, badTrustAnchors, false, LocalDate.of(2022, 1, 1),
            LocalDate.of(2022, 1, 1)
        )
        val challengeRequirements = Bundle()
        challengeRequirements.putByteArray(PARAM_CHALLENGE, "player456".encodeToByteArray())

        val result = verifier.verifyAttestation(
            TYPE_CHALLENGE, challengeRequirements,
            TEST_ATTESTATION_WITH_ROOT_CERT_FILENAME.fromPEMFileToByteArray()
        )
        assertThat(result).isEqualTo(RESULT_FAILURE)
    }

    fun verifyAttestation_returnsFailureChallenge() {
        val verifier = AttestationVerificationPeerDeviceVerifier(
            context, dumpLogger, trustAnchors, false, LocalDate.of(2022, 1, 1),
            LocalDate.of(2022, 1, 1)
        )
        val challengeRequirements = Bundle()
        challengeRequirements.putByteArray(PARAM_CHALLENGE, "wrong".encodeToByteArray())

        val result = verifier.verifyAttestation(
            TYPE_CHALLENGE, challengeRequirements,
            TEST_ATTESTATION_WITH_ROOT_CERT_FILENAME.fromPEMFileToByteArray()
        )
        assertThat(result).isEqualTo(RESULT_FAILURE)
    }

    private fun String.fromPEMFileToCerts(): Collection<Certificate> {
        return certificateFactory.generateCertificates(
            InstrumentationRegistry.getInstrumentation().getContext().getResources().getAssets()
                .open(this)
        )
    }

    private fun String.fromPEMFileToByteArray(): ByteArray {
        val certs = this.fromPEMFileToCerts()
        val bos = ByteArrayOutputStream()
        certs.forEach {
            bos.write(it.encoded)
        }
        return bos.toByteArray()
    }

    private fun DumpLogger.getDump(): String {
        val sw = StringWriter()
        this.dumpTo(IndentingPrintWriter(PrintWriter(sw), " "))
        return sw.toString()
    }

    class TestActivity : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
        }
    }

    companion object {
        private const val TAG = "AVFTest"
        private const val TEST_ROOT_CERT_FILENAME = "test_root_certs.pem"
        private const val TEST_ATTESTATION_WITH_ROOT_CERT_FILENAME =
            "test_attestation_with_root_certs.pem"
        private const val TEST_ATTESTATION_CERT_FILENAME = "test_attestation_wrong_root_certs.pem"
        private const val TEST_OWNED_BY_SYSTEM_FILENAME = "test_owned_by_system_certs.pem"
    }
}
