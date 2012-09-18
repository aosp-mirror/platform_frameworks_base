/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.updates;

import android.content.Context;
import android.content.Intent;
import android.test.AndroidTestCase;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.KeyFactory;
import java.util.HashSet;
import java.io.*;
import libcore.io.IoUtils;

/**
 * Tests for {@link com.android.server.CertPinInstallReceiver}
 */
public class CertPinInstallReceiverTest extends AndroidTestCase {

    private static final String TAG = "CertPinInstallReceiverTest";

    private static final String PINLIST_ROOT = System.getenv("ANDROID_DATA") + "/misc/keychain/";

    public static final String PINLIST_CONTENT_PATH = PINLIST_ROOT + "pins";
    public static final String PINLIST_METADATA_PATH = PINLIST_CONTENT_PATH + "metadata";

    public static final String PINLIST_CONTENT_URL_KEY = "pinlist_content_url";
    public static final String PINLIST_METADATA_URL_KEY = "pinlist_metadata_url";
    public static final String PINLIST_CERTIFICATE_KEY = "config_update_certificate";
    public static final String PINLIST_VERSION_KEY = "pinlist_version";

    private static final String EXTRA_CONTENT_PATH = "CONTENT_PATH";
    private static final String EXTRA_REQUIRED_HASH = "REQUIRED_HASH";
    private static final String EXTRA_SIGNATURE = "SIGNATURE";
    private static final String EXTRA_VERSION_NUMBER = "VERSION";

    public static final String TEST_CERT = "" +
                    "MIIDsjCCAxugAwIBAgIJAPLf2gS0zYGUMA0GCSqGSIb3DQEBBQUAMIGYMQswCQYDVQQGEwJVUzET" +
                    "MBEGA1UECBMKQ2FsaWZvcm5pYTEWMBQGA1UEBxMNTW91bnRhaW4gVmlldzEPMA0GA1UEChMGR29v" +
                    "Z2xlMRAwDgYDVQQLEwd0ZXN0aW5nMRYwFAYDVQQDEw1HZXJlbXkgQ29uZHJhMSEwHwYJKoZIhvcN" +
                    "AQkBFhJnY29uZHJhQGdvb2dsZS5jb20wHhcNMTIwNzE0MTc1MjIxWhcNMTIwODEzMTc1MjIxWjCB" +
                    "mDELMAkGA1UEBhMCVVMxEzARBgNVBAgTCkNhbGlmb3JuaWExFjAUBgNVBAcTDU1vdW50YWluIFZp" +
                    "ZXcxDzANBgNVBAoTBkdvb2dsZTEQMA4GA1UECxMHdGVzdGluZzEWMBQGA1UEAxMNR2VyZW15IENv" +
                    "bmRyYTEhMB8GCSqGSIb3DQEJARYSZ2NvbmRyYUBnb29nbGUuY29tMIGfMA0GCSqGSIb3DQEBAQUA" +
                    "A4GNADCBiQKBgQCjGGHATBYlmas+0sEECkno8LZ1KPglb/mfe6VpCT3GhSr+7br7NG/ZwGZnEhLq" +
                    "E7YIH4fxltHmQC3Tz+jM1YN+kMaQgRRjo/LBCJdOKaMwUbkVynAH6OYsKevjrOPk8lfM5SFQzJMG" +
                    "sA9+Tfopr5xg0BwZ1vA/+E3mE7Tr3M2UvwIDAQABo4IBADCB/TAdBgNVHQ4EFgQUhzkS9E6G+x8W" +
                    "L4EsmRjDxu28tHUwgc0GA1UdIwSBxTCBwoAUhzkS9E6G+x8WL4EsmRjDxu28tHWhgZ6kgZswgZgx" +
                    "CzAJBgNVBAYTAlVTMRMwEQYDVQQIEwpDYWxpZm9ybmlhMRYwFAYDVQQHEw1Nb3VudGFpbiBWaWV3" +
                    "MQ8wDQYDVQQKEwZHb29nbGUxEDAOBgNVBAsTB3Rlc3RpbmcxFjAUBgNVBAMTDUdlcmVteSBDb25k" +
                    "cmExITAfBgkqhkiG9w0BCQEWEmdjb25kcmFAZ29vZ2xlLmNvbYIJAPLf2gS0zYGUMAwGA1UdEwQF" +
                    "MAMBAf8wDQYJKoZIhvcNAQEFBQADgYEAYiugFDmbDOQ2U/+mqNt7o8ftlEo9SJrns6O8uTtK6AvR" +
                    "orDrR1AXTXkuxwLSbmVfedMGOZy7Awh7iZa8hw5x9XmUudfNxvmrKVEwGQY2DZ9PXbrnta/dwbhK" +
                    "mWfoepESVbo7CKIhJp8gRW0h1Z55ETXD57aGJRvQS4pxkP8ANhM=";


    public static final String TEST_KEY = "" +
                    "MIICdgIBADANBgkqhkiG9w0BAQEFAASCAmAwggJcAgEAAoGBAKMYYcBMFiWZqz7SwQQKSejwtnUo" +
                    "+CVv+Z97pWkJPcaFKv7tuvs0b9nAZmcSEuoTtggfh/GW0eZALdPP6MzVg36QxpCBFGOj8sEIl04p" +
                    "ozBRuRXKcAfo5iwp6+Os4+TyV8zlIVDMkwawD35N+imvnGDQHBnW8D/4TeYTtOvczZS/AgMBAAEC" +
                    "gYBxwFalNSwZK3WJipq+g6KLCiBn1JxGGDQlLKrweFaSuFyFky9fd3IvkIabirqQchD612sMb+GT" +
                    "0t1jptW6z4w2w6++IW0A3apDOCwoD+uvDBXrbFqI0VbyAWUNqHVdaFFIRk2IHGEE6463mGRdmILX" +
                    "IlCd/85RTHReg4rl/GFqWQJBANgLAIR4pWbl5Gm+DtY18wp6Q3pJAAMkmP/lISCBIidu1zcqYIKt" +
                    "PoDW4Knq9xnhxPbXrXKv4YzZWHBK8GkKhQ0CQQDBQnXufQcMew+PwiS0oJvS+eQ6YJwynuqG2ejg" +
                    "WE+T7489jKtscRATpUXpZUYmDLGg9bLt7L62hFvFSj2LO2X7AkBcdrD9AWnBFWlh/G77LVHczSEu" +
                    "KCoyLiqxcs5vy/TjLaQ8vw1ZQG580/qJnr+tOxyCjSJ18GK3VppsTRaBznfNAkB3nuCKNp9HTWCL" +
                    "dfrsRsFMrFpk++mSt6SoxXaMbn0LL2u1CD4PCEiQMGt+lK3/3TmRTKNs+23sYS7Ahjxj0udDAkEA" +
                    "p57Nj65WNaWeYiOfTwKXkLj8l29H5NbaGWxPT0XkWr4PvBOFZVH/wj0/qc3CMVGnv11+DyO+QUCN" +
                    "SqBB5aRe8g==";

    private void overrideSettings(String key, String value) throws Exception {
        assertTrue(Settings.Secure.putString(mContext.getContentResolver(), key, value));
        Thread.sleep(1000);
    }

    private void overrideCert(String value) throws Exception {
        overrideSettings(PINLIST_CERTIFICATE_KEY, value);
    }

    private String readPins() throws Exception {
        return IoUtils.readFileAsString(PINLIST_CONTENT_PATH);
    }

    private String readCurrentVersion() throws Exception {
        return IoUtils.readFileAsString("/data/misc/keychain/metadata/version");
    }

    private String getNextVersion() throws Exception {
        int currentVersion = Integer.parseInt(readCurrentVersion());
        return Integer.toString(currentVersion + 1);
    }

    private static String getCurrentHash(String content) throws Exception {
        if (content == null) {
            return "0";
        }
        MessageDigest dgst = MessageDigest.getInstance("SHA512");
        byte[] encoded = content.getBytes();
        byte[] fingerprint = dgst.digest(encoded);
        return IntegralToString.bytesToHexString(fingerprint, false);
    }

    private static String getHashOfCurrentContent() throws Exception {
        String content = IoUtils.readFileAsString("/data/misc/keychain/pins");
        return getCurrentHash(content);
    }

    private PrivateKey createKey() throws Exception {
        byte[] derKey = Base64.decode(TEST_KEY.getBytes(), Base64.DEFAULT);
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(derKey);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        return (PrivateKey) keyFactory.generatePrivate(keySpec);
    }

    private X509Certificate createCertificate() throws Exception {
        byte[] derCert = Base64.decode(TEST_CERT.getBytes(), Base64.DEFAULT);
        InputStream istream = new ByteArrayInputStream(derCert);
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        return (X509Certificate) cf.generateCertificate(istream);
    }

    private String makeTemporaryContentFile(String content) throws Exception {
        FileOutputStream fw = mContext.openFileOutput("content.txt", mContext.MODE_WORLD_READABLE);
        fw.write(content.getBytes(), 0, content.length());
        fw.close();
        return mContext.getFilesDir() + "/content.txt";
    }

    private String createSignature(String content, String version, String requiredHash)
                                   throws Exception {
        Signature signer = Signature.getInstance("SHA512withRSA");
        signer.initSign(createKey());
        signer.update(content.trim().getBytes());
        signer.update(version.trim().getBytes());
        signer.update(requiredHash.getBytes());
        String sig = new String(Base64.encode(signer.sign(), Base64.DEFAULT));
        assertEquals(true,
                     verifySignature(content, version, requiredHash, sig, createCertificate()));
        return sig;
    }

    public boolean verifySignature(String content, String version, String requiredPrevious,
                                   String signature, X509Certificate cert) throws Exception {
        Signature signer = Signature.getInstance("SHA512withRSA");
        signer.initVerify(cert);
        signer.update(content.trim().getBytes());
        signer.update(version.trim().getBytes());
        signer.update(requiredPrevious.trim().getBytes());
        return signer.verify(Base64.decode(signature.getBytes(), Base64.DEFAULT));
    }

    private void sendIntent(String contentPath, String version, String required, String sig) {
        Intent i = new Intent();
        i.setAction("android.intent.action.UPDATE_PINS");
        i.putExtra(EXTRA_CONTENT_PATH, contentPath);
        i.putExtra(EXTRA_VERSION_NUMBER, version);
        i.putExtra(EXTRA_REQUIRED_HASH, required);
        i.putExtra(EXTRA_SIGNATURE, sig);
        mContext.sendBroadcast(i);
    }

    private String runTest(String cert, String content, String version, String required, String sig)
                           throws Exception {
        Log.e(TAG, "started test");
        overrideCert(cert);
        String contentPath = makeTemporaryContentFile(content);
        sendIntent(contentPath, version, required, sig);
        Thread.sleep(1000);
        return readPins();
    }

    private String runTestWithoutSig(String cert, String content, String version, String required)
                                     throws Exception {
        String sig = createSignature(content, version, required);
        return runTest(cert, content, version, required, sig);
    }

    public void testOverwritePinlist() throws Exception {
        Log.e(TAG, "started testOverwritePinList");
        assertEquals("abcde", runTestWithoutSig(TEST_CERT, "abcde", getNextVersion(), getHashOfCurrentContent()));
        Log.e(TAG, "started testOverwritePinList");
    }

   public void testBadSignatureFails() throws Exception {
        Log.e(TAG, "started testOverwritePinList");
        String text = "blahblah";
        runTestWithoutSig(TEST_CERT, text, getNextVersion(), getHashOfCurrentContent());
        assertEquals(text, runTest(TEST_CERT, "bcdef", getNextVersion(), getCurrentHash(text), ""));
        Log.e(TAG, "started testOverwritePinList");
    }

    public void testBadRequiredHashFails() throws Exception {
        runTestWithoutSig(TEST_CERT, "blahblahblah", getNextVersion(), getHashOfCurrentContent());
        assertEquals("blahblahblah", runTestWithoutSig(TEST_CERT, "cdefg", getNextVersion(), "0"));
        Log.e(TAG, "started testOverwritePinList");
    }

    public void testBadVersionFails() throws Exception {
        String text = "blahblahblahblah";
        String version = getNextVersion();
        runTestWithoutSig(TEST_CERT, text, version, getHashOfCurrentContent());
        assertEquals(text, runTestWithoutSig(TEST_CERT, "defgh", version, getCurrentHash(text)));
        Log.e(TAG, "started testOverwritePinList");
    }

    public void testOverrideRequiredHash() throws Exception {
        runTestWithoutSig(TEST_CERT, "blahblahblah", getNextVersion(), getHashOfCurrentContent());
        assertEquals("blahblahblah", runTestWithoutSig(TEST_CERT, "cdefg", "NONE", "0"));
        Log.e(TAG, "started testOverwritePinList");
    }

}
