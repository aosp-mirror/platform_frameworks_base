package com.android.server.locksettings.recoverablekeystore;

import static com.google.common.truth.Truth.assertThat;

import android.security.keystore.recovery.TrustedRootCertificates;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.widget.LockPatternUtils;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashMap;
import java.util.Map;

import javax.crypto.SecretKey;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class TestOnlyInsecureCertificateHelperTest {
    private final TestOnlyInsecureCertificateHelper mHelper
            = new TestOnlyInsecureCertificateHelper();

    @Test
    public void testDoesCredentailSupportInsecureMode_forNonWhitelistedPassword() throws Exception {
        assertThat(mHelper.doesCredentialSupportInsecureMode(
                LockPatternUtils.CREDENTIAL_TYPE_PASSWORD, "secret12345")).isFalse();
        assertThat(mHelper.doesCredentialSupportInsecureMode(
                LockPatternUtils.CREDENTIAL_TYPE_PASSWORD, "1234")).isFalse();
    }

    @Test
    public void testDoesCredentailSupportInsecureMode_forWhitelistedPassword() throws Exception {
        assertThat(mHelper.doesCredentialSupportInsecureMode(
                LockPatternUtils.CREDENTIAL_TYPE_PASSWORD,
                TrustedRootCertificates.INSECURE_PASSWORD_PREFIX)).isTrue();

        assertThat(mHelper.doesCredentialSupportInsecureMode(
                LockPatternUtils.CREDENTIAL_TYPE_PASSWORD,
                TrustedRootCertificates.INSECURE_PASSWORD_PREFIX + "12")).isTrue();
    }

    @Test
    public void testDoesCredentailSupportInsecureMode_Pattern() throws Exception {
        assertThat(mHelper.doesCredentialSupportInsecureMode(
                LockPatternUtils.CREDENTIAL_TYPE_PATTERN,
                TrustedRootCertificates.INSECURE_PASSWORD_PREFIX)).isFalse();
        assertThat(mHelper.doesCredentialSupportInsecureMode(
                LockPatternUtils.CREDENTIAL_TYPE_NONE,
                TrustedRootCertificates.INSECURE_PASSWORD_PREFIX)).isFalse();
    }

    @Test
    public void testIsTestOnlyCertificate() throws Exception {
        assertThat(mHelper.isTestOnlyCertificateAlias(
                TrustedRootCertificates.GOOGLE_CLOUD_KEY_VAULT_SERVICE_V1_ALIAS)).isFalse();
        assertThat(mHelper.isTestOnlyCertificateAlias(
                TrustedRootCertificates.TEST_ONLY_INSECURE_CERTIFICATE_ALIAS)).isTrue();
        assertThat(mHelper.isTestOnlyCertificateAlias(
                "UNKNOWN_ALIAS")).isFalse();
    }

    @Test
    public void testKeepOnlyWhitelistedInsecureKeys_emptyKeysList() throws Exception {
        Map<String, SecretKey> rawKeys = new HashMap<>();
        Map<String, SecretKey> expectedResult = new HashMap<>();

        Map<String, SecretKey> filteredKeys =
                mHelper.keepOnlyWhitelistedInsecureKeys(rawKeys);
        assertThat(filteredKeys.entrySet()).containsExactlyElementsIn(expectedResult.entrySet());
        assertThat(filteredKeys.entrySet()).containsAllIn(rawKeys.entrySet());
    }

    @Test
    public void testKeepOnlyWhitelistedInsecureKeys_singleNonWhitelistedKey() throws Exception {
        Map<String, SecretKey> rawKeys = new HashMap<>();
        Map<String, SecretKey> expectedResult = new HashMap<>();

        String alias = "secureAlias";
        rawKeys.put(alias, TestData.generateKey());

        Map<String, SecretKey> filteredKeys =
                mHelper.keepOnlyWhitelistedInsecureKeys(rawKeys);
        assertThat(filteredKeys.entrySet()).containsExactlyElementsIn(expectedResult.entrySet());
        assertThat(rawKeys.entrySet()).containsAllIn(filteredKeys.entrySet());
    }

    @Test
    public void testKeepOnlyWhitelistedInsecureKeys_singleWhitelistedKey() throws Exception {
        Map<String, SecretKey> rawKeys = new HashMap<>();
        Map<String, SecretKey> expectedResult = new HashMap<>();

        String alias = TrustedRootCertificates.INSECURE_KEY_ALIAS_PREFIX;
        rawKeys.put(alias, TestData.generateKey());
        expectedResult.put(alias, rawKeys.get(alias));

        Map<String, SecretKey> filteredKeys =
                mHelper.keepOnlyWhitelistedInsecureKeys(rawKeys);
        assertThat(filteredKeys.entrySet()).containsExactlyElementsIn(expectedResult.entrySet());
        assertThat(rawKeys.entrySet()).containsAllIn(filteredKeys.entrySet());
    }

    @Test
    public void testKeepOnlyWhitelistedInsecureKeys() throws Exception {
        Map<String, SecretKey> rawKeys = new HashMap<>();
        Map<String, SecretKey> expectedResult = new HashMap<>();

        String alias = "SECURE_ALIAS" + TrustedRootCertificates.INSECURE_KEY_ALIAS_PREFIX;
        rawKeys.put(alias, TestData.generateKey());

        alias = TrustedRootCertificates.INSECURE_KEY_ALIAS_PREFIX + "1";
        rawKeys.put(alias, TestData.generateKey());
        expectedResult.put(alias, rawKeys.get(alias));

        alias = TrustedRootCertificates.INSECURE_KEY_ALIAS_PREFIX + "2";
        rawKeys.put(alias, TestData.generateKey());
        expectedResult.put(alias, rawKeys.get(alias));

        Map<String, SecretKey> filteredKeys =
                mHelper.keepOnlyWhitelistedInsecureKeys(rawKeys);
        assertThat(filteredKeys.entrySet()).containsExactlyElementsIn(expectedResult.entrySet());
        assertThat(rawKeys.entrySet()).containsAllIn(filteredKeys.entrySet());
    }

    @Test
    public void testIsValidRootCertificateAlias_googleCertAlias() {
        assertThat(mHelper.isValidRootCertificateAlias(
                TrustedRootCertificates.GOOGLE_CLOUD_KEY_VAULT_SERVICE_V1_ALIAS)).isTrue();
    }

    @Test
    public void testIsValidRootCertificateAlias_testOnlyCertAlias() {
        assertThat(mHelper.isValidRootCertificateAlias(
                TrustedRootCertificates.TEST_ONLY_INSECURE_CERTIFICATE_ALIAS)).isTrue();
    }

    @Test
    public void testIsValidRootCertificateAlias_emptyCertAlias() {
        assertThat(mHelper.isValidRootCertificateAlias("")).isFalse();
    }

    @Test
    public void testIsValidRootCertificateAlias_nullCertAlias() {
        assertThat(mHelper.isValidRootCertificateAlias(null)).isFalse();
    }

    @Test
    public void testIsValidRootCertificateAlias_unknownCertAlias() {
        assertThat(mHelper.isValidRootCertificateAlias("unknown-root-certifiate-alias")).isFalse();
    }
}
