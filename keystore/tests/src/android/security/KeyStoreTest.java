/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.security;

import android.app.Activity;
import android.os.Binder;
import android.os.IBinder;
import android.os.Process;
import android.security.keymaster.ExportResult;
import android.security.keymaster.KeyCharacteristics;
import android.security.keymaster.KeymasterArguments;
import android.security.keymaster.KeymasterBlob;
import android.security.keymaster.KeymasterDefs;
import android.security.keymaster.OperationResult;
import android.test.ActivityUnitTestCase;
import android.test.AssertionFailedError;
import android.test.MoreAsserts;
import android.test.suitebuilder.annotation.MediumTest;
import com.android.org.conscrypt.NativeConstants;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.security.spec.RSAKeyGenParameterSpec;

/**
 * Junit / Instrumentation test case for KeyStore class
 *
 * Running the test suite:
 *
 *  runtest keystore-unit
 *
 * Or this individual test case:
 *
 *  runtest --path frameworks/base/keystore/tests/src/android/security/KeyStoreTest.java
 */
@MediumTest
public class KeyStoreTest extends ActivityUnitTestCase<Activity> {
    private static final String TEST_PASSWD = "12345678";
    private static final String TEST_PASSWD2 = "87654321";
    private static final String TEST_KEYNAME = "test-key";
    private static final String TEST_KEYNAME1 = "test-key.1";
    private static final String TEST_KEYNAME2 = "test-key\02";
    private static final byte[] TEST_KEYVALUE = "test value".getBytes(StandardCharsets.UTF_8);

    // "Hello, World" in Chinese
    private static final String TEST_I18N_KEY = "\u4F60\u597D, \u4E16\u754C";
    private static final byte[] TEST_I18N_VALUE = TEST_I18N_KEY.getBytes(StandardCharsets.UTF_8);

    // Test vector data for signatures
    private static final int RSA_KEY_SIZE = 1024;
    private static final byte[] TEST_DATA =  new byte[RSA_KEY_SIZE / 8];
    static {
        for (int i = 0; i < TEST_DATA.length; i++) {
            TEST_DATA[i] = (byte) i;
        }
    }

    private KeyStore mKeyStore = null;

    public KeyStoreTest() {
        super(Activity.class);
    }

    private static final byte[] PRIVKEY_BYTES = hexToBytes(
            "308204BE020100300D06092A864886F70D0101010500048204A8308204A4020100028201" +
            "0100E0473E8AB8F2284FEB9E742FF9748FA118ED98633C92F52AEB7A2EBE0D3BE60329BE" +
            "766AD10EB6A515D0D2CFD9BEA7930F0C306537899F7958CD3E85B01F8818524D312584A9" +
            "4B251E3625B54141EDBFEE198808E1BB97FC7CB49B9EAAAF68E9C98D7D0EDC53BBC0FA00" +
            "34356D6305FBBCC3C7001405386ABBC873CB0F3EF7425F3D33DF7B315AE036D2A0B66AFD" +
            "47503B169BF36E3B5162515B715FDA83DEAF2C58AEB9ABFB3097C3CC9DD9DBE5EF296C17" +
            "6139028E8A671E63056D45F40188D2C4133490845DE52C2534E9C6B2478C07BDAE928823" +
            "B62D066C7770F9F63F3DBA247F530844747BE7AAA85D853B8BD244ACEC3DE3C89AB46453" +
            "AB4D24C3AC6902030100010282010037784776A5F17698F5AC960DFB83A1B67564E648BD" +
            "0597CF8AB8087186F2669C27A9ECBDD480F0197A80D07309E6C6A96F925331E57F8B4AC6" +
            "F4D45EDA45A23269C09FC428C07A4E6EDF738A15DEC97FABD2F2BB47A14F20EA72FCFE4C" +
            "36E01ADA77BD137CD8D4DA10BB162E94A4662971F175F985FA188F056CB97EE2816F43AB" +
            "9D3747612486CDA8C16196C30818A995EC85D38467791267B3BF21F273710A6925862576" +
            "841C5B6712C12D4BD20A2F3299ADB7C135DA5E9515ABDA76E7CAF2A3BE80551D073B78BF" +
            "1162C48AD2B7F4743A0238EE4D252F7D5E7E6533CCAE64CCB39360075A2FD1E034EC3AE5" +
            "CE9C408CCBF0E25E4114021687B3DD4754AE8102818100F541884BC3737B2922D4119EF4" +
            "5E2DEE2CD4CBB75F45505A157AA5009F99C73A2DF0724AC46024306332EA898177634546" +
            "5DC6DF1E0A6F140AFF3B7396E6A8994AC5DAA96873472FE37749D14EB3E075E629DBEB35" +
            "83338A6F3649D0A2654A7A42FD9AB6BFA4AC4D481D390BB229B064BDC311CC1BE1B63189" +
            "DA7C40CDECF2B102818100EA1A742DDB881CEDB7288C87E38D868DD7A409D15A43F445D5" +
            "377A0B5731DDBFCA2DAF28A8E13CD5C0AFCEC3347D74A39E235A3CD9633F274DE2B94F92" +
            "DF43833911D9E9F1CF58F27DE2E08FF45964C720D3EC2139DC7CAFC912953CDECB2F355A" +
            "2E2C35A50FAD754CB3B23166424BA3B6E3112A2B898C38C5C15EDB238693390281805182" +
            "8F1EC6FD996029901BAF1D7E337BA5F0AF27E984EAD895ACE62BD7DF4EE45A224089F2CC" +
            "151AF3CD173FCE0474BCB04F386A2CDCC0E0036BA2419F54579262D47100BE931984A3EF" +
            "A05BECF141574DC079B3A95C4A83E6C43F3214D6DF32D512DE198085E531E616B83FD7DD" +
            "9D1F4E2607C3333D07C55D107D1D3893587102818100DB4FB50F50DE8EDB53FF34C80931" +
            "88A0512867DA2CCA04897759E587C244010DAF8664D59E8083D16C164789301F67A9F078" +
            "060D834A2ADBD367575B68A8A842C2B02A89B3F31FCCEC8A22FE395795C5C6C7422B4E5D" +
            "74A1E9A8F30E7759B9FC2D639C1F15673E84E93A5EF1506F4315383C38D45CBD1B14048F" +
            "4721DC82326102818100D8114593AF415FB612DBF1923710D54D07486205A76A3B431949" +
            "68C0DFF1F11EF0F61A4A337D5FD3741BBC9640E447B8B6B6C47C3AC1204357D3B0C55BA9" +
            "286BDA73F629296F5FA9146D8976357D3C751E75148696A40B74685C82CE30902D639D72" +
            "4FF24D5E2E9407EE34EDED2E3B4DF65AA9BCFEB6DF28D07BA6903F165768");

    private static final byte[] AES256_BYTES = hexToBytes(
            "0CC175B9C0F1B6A831C399E269772661CEC520EA51EA0A47E87295FA3245A605");

    private static byte[] hexToBytes(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4) + Character.digit(
                    s.charAt(i + 1), 16));
        }
        return data;
    }

    @Override
    protected void setUp() throws Exception {
        mKeyStore = KeyStore.getInstance();
        if (mKeyStore.state() != KeyStore.State.UNINITIALIZED) {
            mKeyStore.reset();
        }
        assertEquals("KeyStore should be in an uninitialized state",
                KeyStore.State.UNINITIALIZED, mKeyStore.state());
        super.setUp();
    }

    @Override
    protected void tearDown() throws Exception {
        mKeyStore.reset();
        super.tearDown();
    }

    public void testState() throws Exception {
        assertEquals(KeyStore.State.UNINITIALIZED, mKeyStore.state());
    }

    public void testPassword() throws Exception {
        assertTrue(mKeyStore.onUserPasswordChanged(TEST_PASSWD));
        assertEquals(KeyStore.State.UNLOCKED, mKeyStore.state());
    }

    public void testGet() throws Exception {
        assertNull(mKeyStore.get(TEST_KEYNAME));
        mKeyStore.onUserPasswordChanged(TEST_PASSWD);
        assertNull(mKeyStore.get(TEST_KEYNAME));
        assertTrue(mKeyStore.put(TEST_KEYNAME, TEST_KEYVALUE, KeyStore.UID_SELF,
                KeyStore.FLAG_ENCRYPTED));
        assertTrue(Arrays.equals(TEST_KEYVALUE, mKeyStore.get(TEST_KEYNAME)));
    }

    public void testPut() throws Exception {
        assertNull(mKeyStore.get(TEST_KEYNAME));
        assertFalse(mKeyStore.put(TEST_KEYNAME, TEST_KEYVALUE, KeyStore.UID_SELF,
                KeyStore.FLAG_ENCRYPTED));
        assertFalse(mKeyStore.contains(TEST_KEYNAME));
        mKeyStore.onUserPasswordChanged(TEST_PASSWD);
        assertTrue(mKeyStore.put(TEST_KEYNAME, TEST_KEYVALUE, KeyStore.UID_SELF,
                KeyStore.FLAG_ENCRYPTED));
        assertTrue(Arrays.equals(TEST_KEYVALUE, mKeyStore.get(TEST_KEYNAME)));
    }

    public void testPut_grantedUid_Wifi() throws Exception {
        assertFalse(mKeyStore.contains(TEST_KEYNAME, Process.WIFI_UID));
        assertFalse(mKeyStore.put(TEST_KEYNAME, TEST_KEYVALUE, Process.WIFI_UID,
                KeyStore.FLAG_ENCRYPTED));
        assertFalse(mKeyStore.contains(TEST_KEYNAME, Process.WIFI_UID));
        mKeyStore.onUserPasswordChanged(TEST_PASSWD);
        assertTrue(mKeyStore.put(TEST_KEYNAME, TEST_KEYVALUE, Process.WIFI_UID,
                KeyStore.FLAG_ENCRYPTED));
        assertTrue(mKeyStore.contains(TEST_KEYNAME, Process.WIFI_UID));
    }

    public void testPut_ungrantedUid_Bluetooth() throws Exception {
        assertFalse(mKeyStore.contains(TEST_KEYNAME, Process.BLUETOOTH_UID));
        assertFalse(mKeyStore.put(TEST_KEYNAME, TEST_KEYVALUE, Process.BLUETOOTH_UID,
                KeyStore.FLAG_ENCRYPTED));
        assertFalse(mKeyStore.contains(TEST_KEYNAME, Process.BLUETOOTH_UID));
        mKeyStore.onUserPasswordChanged(TEST_PASSWD);
        assertFalse(mKeyStore.put(TEST_KEYNAME, TEST_KEYVALUE, Process.BLUETOOTH_UID,
                KeyStore.FLAG_ENCRYPTED));
        assertFalse(mKeyStore.contains(TEST_KEYNAME, Process.BLUETOOTH_UID));
    }

    public void testI18n() throws Exception {
        assertFalse(mKeyStore.put(TEST_I18N_KEY, TEST_I18N_VALUE, KeyStore.UID_SELF,
                KeyStore.FLAG_ENCRYPTED));
        assertFalse(mKeyStore.contains(TEST_I18N_KEY));
        mKeyStore.onUserPasswordChanged(TEST_I18N_KEY);
        assertTrue(mKeyStore.put(TEST_I18N_KEY, TEST_I18N_VALUE, KeyStore.UID_SELF,
                KeyStore.FLAG_ENCRYPTED));
        assertTrue(mKeyStore.contains(TEST_I18N_KEY));
    }

    public void testDelete() throws Exception {
        assertFalse(mKeyStore.delete(TEST_KEYNAME));
        mKeyStore.onUserPasswordChanged(TEST_PASSWD);
        assertFalse(mKeyStore.delete(TEST_KEYNAME));

        assertTrue(mKeyStore.put(TEST_KEYNAME, TEST_KEYVALUE, KeyStore.UID_SELF,
                KeyStore.FLAG_ENCRYPTED));
        assertTrue(Arrays.equals(TEST_KEYVALUE, mKeyStore.get(TEST_KEYNAME)));
        assertTrue(mKeyStore.delete(TEST_KEYNAME));
        assertNull(mKeyStore.get(TEST_KEYNAME));
    }

    public void testDelete_grantedUid_Wifi() throws Exception {
        assertFalse(mKeyStore.delete(TEST_KEYNAME, Process.WIFI_UID));
        mKeyStore.onUserPasswordChanged(TEST_PASSWD);
        assertFalse(mKeyStore.delete(TEST_KEYNAME, Process.WIFI_UID));

        assertTrue(mKeyStore.put(TEST_KEYNAME, TEST_KEYVALUE, Process.WIFI_UID,
                KeyStore.FLAG_ENCRYPTED));
        assertTrue(mKeyStore.contains(TEST_KEYNAME, Process.WIFI_UID));
        assertTrue(mKeyStore.delete(TEST_KEYNAME, Process.WIFI_UID));
        assertFalse(mKeyStore.contains(TEST_KEYNAME, Process.WIFI_UID));
    }

    public void testDelete_ungrantedUid_Bluetooth() throws Exception {
        assertFalse(mKeyStore.delete(TEST_KEYNAME, Process.BLUETOOTH_UID));
        mKeyStore.onUserPasswordChanged(TEST_PASSWD);
        assertFalse(mKeyStore.delete(TEST_KEYNAME, Process.BLUETOOTH_UID));

        assertFalse(mKeyStore.put(TEST_KEYNAME, TEST_KEYVALUE, Process.BLUETOOTH_UID,
                KeyStore.FLAG_ENCRYPTED));
        assertFalse(mKeyStore.contains(TEST_KEYNAME, Process.BLUETOOTH_UID));
        assertFalse(mKeyStore.delete(TEST_KEYNAME, Process.BLUETOOTH_UID));
        assertFalse(mKeyStore.contains(TEST_KEYNAME, Process.BLUETOOTH_UID));
    }

    public void testContains() throws Exception {
        assertFalse(mKeyStore.contains(TEST_KEYNAME));

        assertTrue(mKeyStore.onUserPasswordChanged(TEST_PASSWD));
        assertFalse(mKeyStore.contains(TEST_KEYNAME));

        assertTrue(mKeyStore.put(TEST_KEYNAME, TEST_KEYVALUE, KeyStore.UID_SELF,
                KeyStore.FLAG_ENCRYPTED));
        assertTrue(mKeyStore.contains(TEST_KEYNAME));
    }

    public void testContains_grantedUid_Wifi() throws Exception {
        assertFalse(mKeyStore.contains(TEST_KEYNAME, Process.WIFI_UID));

        assertTrue(mKeyStore.onUserPasswordChanged(TEST_PASSWD));
        assertFalse(mKeyStore.contains(TEST_KEYNAME, Process.WIFI_UID));

        assertTrue(mKeyStore.put(TEST_KEYNAME, TEST_KEYVALUE, Process.WIFI_UID,
                KeyStore.FLAG_ENCRYPTED));
        assertTrue(mKeyStore.contains(TEST_KEYNAME, Process.WIFI_UID));
    }

    public void testContains_grantedUid_Bluetooth() throws Exception {
        assertFalse(mKeyStore.contains(TEST_KEYNAME, Process.BLUETOOTH_UID));

        assertTrue(mKeyStore.onUserPasswordChanged(TEST_PASSWD));
        assertFalse(mKeyStore.contains(TEST_KEYNAME, Process.BLUETOOTH_UID));

        assertFalse(mKeyStore.put(TEST_KEYNAME, TEST_KEYVALUE, Process.BLUETOOTH_UID,
                KeyStore.FLAG_ENCRYPTED));
        assertFalse(mKeyStore.contains(TEST_KEYNAME, Process.BLUETOOTH_UID));
    }

    public void testList() throws Exception {
        String[] emptyResult = mKeyStore.list(TEST_KEYNAME);
        assertNotNull(emptyResult);
        assertEquals(0, emptyResult.length);

        mKeyStore.onUserPasswordChanged(TEST_PASSWD);
        mKeyStore.put(TEST_KEYNAME1, TEST_KEYVALUE, KeyStore.UID_SELF, KeyStore.FLAG_ENCRYPTED);
        mKeyStore.put(TEST_KEYNAME2, TEST_KEYVALUE, KeyStore.UID_SELF, KeyStore.FLAG_ENCRYPTED);

        String[] results = mKeyStore.list(TEST_KEYNAME);
        assertEquals(new HashSet(Arrays.asList(TEST_KEYNAME1.substring(TEST_KEYNAME.length()),
                                               TEST_KEYNAME2.substring(TEST_KEYNAME.length()))),
                     new HashSet(Arrays.asList(results)));
    }

    public void testList_ungrantedUid_Bluetooth() throws Exception {
        String[] results1 = mKeyStore.list(TEST_KEYNAME, Process.BLUETOOTH_UID);
        assertEquals(0, results1.length);

        mKeyStore.onUserPasswordChanged(TEST_PASSWD);
        mKeyStore.put(TEST_KEYNAME1, TEST_KEYVALUE, KeyStore.UID_SELF, KeyStore.FLAG_ENCRYPTED);
        mKeyStore.put(TEST_KEYNAME2, TEST_KEYVALUE, KeyStore.UID_SELF, KeyStore.FLAG_ENCRYPTED);

        String[] results2 = mKeyStore.list(TEST_KEYNAME, Process.BLUETOOTH_UID);
        assertEquals(0, results2.length);
    }

    public void testList_grantedUid_Wifi() throws Exception {
        String[] results1 = mKeyStore.list(TEST_KEYNAME, Process.WIFI_UID);
        assertNotNull(results1);
        assertEquals(0, results1.length);

        mKeyStore.onUserPasswordChanged(TEST_PASSWD);
        mKeyStore.put(TEST_KEYNAME1, TEST_KEYVALUE, Process.WIFI_UID, KeyStore.FLAG_ENCRYPTED);
        mKeyStore.put(TEST_KEYNAME2, TEST_KEYVALUE, Process.WIFI_UID, KeyStore.FLAG_ENCRYPTED);

        String[] results2 = mKeyStore.list(TEST_KEYNAME, Process.WIFI_UID);
        assertEquals(new HashSet(Arrays.asList(TEST_KEYNAME1.substring(TEST_KEYNAME.length()),
                                               TEST_KEYNAME2.substring(TEST_KEYNAME.length()))),
                     new HashSet(Arrays.asList(results2)));
    }

    public void testList_grantedUid_Vpn() throws Exception {
        String[] results1 = mKeyStore.list(TEST_KEYNAME, Process.VPN_UID);
        assertNotNull(results1);
        assertEquals(0, results1.length);

        mKeyStore.onUserPasswordChanged(TEST_PASSWD);
        mKeyStore.put(TEST_KEYNAME1, TEST_KEYVALUE, Process.VPN_UID, KeyStore.FLAG_ENCRYPTED);
        mKeyStore.put(TEST_KEYNAME2, TEST_KEYVALUE, Process.VPN_UID, KeyStore.FLAG_ENCRYPTED);

        String[] results2 = mKeyStore.list(TEST_KEYNAME, Process.VPN_UID);
        assertEquals(new HashSet(Arrays.asList(TEST_KEYNAME1.substring(TEST_KEYNAME.length()),
                                               TEST_KEYNAME2.substring(TEST_KEYNAME.length()))),
                     new HashSet(Arrays.asList(results2)));
    }

    public void testLock() throws Exception {
        assertFalse(mKeyStore.lock());

        mKeyStore.onUserPasswordChanged(TEST_PASSWD);
        assertEquals(KeyStore.State.UNLOCKED, mKeyStore.state());

        assertTrue(mKeyStore.lock());
        assertEquals(KeyStore.State.LOCKED, mKeyStore.state());
    }

    public void testUnlock() throws Exception {
        mKeyStore.onUserPasswordChanged(TEST_PASSWD);
        assertEquals(KeyStore.State.UNLOCKED, mKeyStore.state());
        mKeyStore.lock();

        assertFalse(mKeyStore.unlock(TEST_PASSWD2));
        assertTrue(mKeyStore.unlock(TEST_PASSWD));
    }

    public void testIsEmpty() throws Exception {
        assertTrue(mKeyStore.isEmpty());
        mKeyStore.onUserPasswordChanged(TEST_PASSWD);
        assertTrue(mKeyStore.isEmpty());
        mKeyStore.put(TEST_KEYNAME, TEST_KEYVALUE, KeyStore.UID_SELF, KeyStore.FLAG_ENCRYPTED);
        assertFalse(mKeyStore.isEmpty());
        mKeyStore.reset();
        assertTrue(mKeyStore.isEmpty());
    }

    public void testGenerate_NotInitialized_Fail() throws Exception {
        assertFalse("Should fail when keystore is not initialized",
                mKeyStore.generate(TEST_KEYNAME, KeyStore.UID_SELF, NativeConstants.EVP_PKEY_RSA,
                        RSA_KEY_SIZE, KeyStore.FLAG_ENCRYPTED, null));
    }

    public void testGenerate_Locked_Fail() throws Exception {
        mKeyStore.onUserPasswordChanged(TEST_PASSWD);
        mKeyStore.lock();
        assertFalse("Should fail when keystore is locked",
                mKeyStore.generate(TEST_KEYNAME, KeyStore.UID_SELF, NativeConstants.EVP_PKEY_RSA,
                        RSA_KEY_SIZE, KeyStore.FLAG_ENCRYPTED, null));
    }

    public void testGenerate_Success() throws Exception {
        assertTrue(mKeyStore.onUserPasswordChanged(TEST_PASSWD));

        assertTrue("Should be able to generate key when unlocked",
                mKeyStore.generate(TEST_KEYNAME, KeyStore.UID_SELF, NativeConstants.EVP_PKEY_RSA,
                        RSA_KEY_SIZE, KeyStore.FLAG_ENCRYPTED, null));
        assertTrue(mKeyStore.contains(TEST_KEYNAME));
        assertFalse(mKeyStore.contains(TEST_KEYNAME, Process.WIFI_UID));
    }

    public void testGenerate_grantedUid_Wifi_Success() throws Exception {
        assertTrue(mKeyStore.onUserPasswordChanged(TEST_PASSWD));

        assertTrue("Should be able to generate key when unlocked",
                mKeyStore.generate(TEST_KEYNAME, Process.WIFI_UID, NativeConstants.EVP_PKEY_RSA,
                        RSA_KEY_SIZE, KeyStore.FLAG_ENCRYPTED, null));
        assertTrue(mKeyStore.contains(TEST_KEYNAME, Process.WIFI_UID));
        assertFalse(mKeyStore.contains(TEST_KEYNAME));
    }

    public void testGenerate_ungrantedUid_Bluetooth_Failure() throws Exception {
        assertTrue(mKeyStore.onUserPasswordChanged(TEST_PASSWD));

        assertFalse(mKeyStore.generate(TEST_KEYNAME, Process.BLUETOOTH_UID,
                    NativeConstants.EVP_PKEY_RSA, RSA_KEY_SIZE, KeyStore.FLAG_ENCRYPTED, null));
        assertFalse(mKeyStore.contains(TEST_KEYNAME, Process.BLUETOOTH_UID));
        assertFalse(mKeyStore.contains(TEST_KEYNAME, Process.WIFI_UID));
        assertFalse(mKeyStore.contains(TEST_KEYNAME));
    }

    public void testImport_Success() throws Exception {
        assertTrue(mKeyStore.onUserPasswordChanged(TEST_PASSWD));

        assertTrue("Should be able to import key when unlocked", mKeyStore.importKey(TEST_KEYNAME,
                PRIVKEY_BYTES, KeyStore.UID_SELF, KeyStore.FLAG_ENCRYPTED));
        assertTrue(mKeyStore.contains(TEST_KEYNAME));
        assertFalse(mKeyStore.contains(TEST_KEYNAME, Process.WIFI_UID));
    }

    public void testImport_grantedUid_Wifi_Success() throws Exception {
        assertTrue(mKeyStore.onUserPasswordChanged(TEST_PASSWD));

        assertTrue("Should be able to import key when unlocked", mKeyStore.importKey(TEST_KEYNAME,
                PRIVKEY_BYTES, Process.WIFI_UID, KeyStore.FLAG_ENCRYPTED));
        assertTrue(mKeyStore.contains(TEST_KEYNAME, Process.WIFI_UID));
        assertFalse(mKeyStore.contains(TEST_KEYNAME));
    }

    public void testImport_ungrantedUid_Bluetooth_Failure() throws Exception {
        assertTrue(mKeyStore.onUserPasswordChanged(TEST_PASSWD));

        assertFalse(mKeyStore.importKey(TEST_KEYNAME, PRIVKEY_BYTES, Process.BLUETOOTH_UID,
                KeyStore.FLAG_ENCRYPTED));
        assertFalse(mKeyStore.contains(TEST_KEYNAME, Process.BLUETOOTH_UID));
        assertFalse(mKeyStore.contains(TEST_KEYNAME, Process.WIFI_UID));
        assertFalse(mKeyStore.contains(TEST_KEYNAME));
    }

    public void testImport_Failure_BadEncoding() throws Exception {
        mKeyStore.onUserPasswordChanged(TEST_PASSWD);

        assertFalse("Invalid DER-encoded key should not be imported", mKeyStore.importKey(
                TEST_KEYNAME, TEST_DATA, KeyStore.UID_SELF, KeyStore.FLAG_ENCRYPTED));
        assertFalse(mKeyStore.contains(TEST_KEYNAME));
        assertFalse(mKeyStore.contains(TEST_KEYNAME, Process.WIFI_UID));
    }

    public void testSign_Success() throws Exception {
        mKeyStore.onUserPasswordChanged(TEST_PASSWD);

        assertTrue(mKeyStore.generate(TEST_KEYNAME, KeyStore.UID_SELF, NativeConstants.EVP_PKEY_RSA,
                    RSA_KEY_SIZE, KeyStore.FLAG_ENCRYPTED, null));
        assertTrue(mKeyStore.contains(TEST_KEYNAME));
        final byte[] signature = mKeyStore.sign(TEST_KEYNAME, TEST_DATA);

        assertNotNull("Signature should not be null", signature);
    }

    public void testVerify_Success() throws Exception {
        mKeyStore.onUserPasswordChanged(TEST_PASSWD);

        assertTrue(mKeyStore.generate(TEST_KEYNAME, KeyStore.UID_SELF, NativeConstants.EVP_PKEY_RSA,
                    RSA_KEY_SIZE, KeyStore.FLAG_ENCRYPTED, null));
        assertTrue(mKeyStore.contains(TEST_KEYNAME));
        final byte[] signature = mKeyStore.sign(TEST_KEYNAME, TEST_DATA);

        assertNotNull("Signature should not be null", signature);

        assertTrue("Signature should verify with same data",
                mKeyStore.verify(TEST_KEYNAME, TEST_DATA, signature));
    }

    public void testSign_NotInitialized_Failure() throws Exception {
        assertNull("Should not be able to sign without first initializing the keystore",
                mKeyStore.sign(TEST_KEYNAME, TEST_DATA));
    }

    public void testSign_NotGenerated_Failure() throws Exception {
        mKeyStore.onUserPasswordChanged(TEST_PASSWD);

        assertNull("Should not be able to sign without first generating keys",
                mKeyStore.sign(TEST_KEYNAME, TEST_DATA));
    }

    public void testGrant_Generated_Success() throws Exception {
        assertTrue("Password should work for keystore",
                mKeyStore.onUserPasswordChanged(TEST_PASSWD));

        assertTrue("Should be able to generate key for testcase",
                mKeyStore.generate(TEST_KEYNAME, KeyStore.UID_SELF, NativeConstants.EVP_PKEY_RSA,
                        RSA_KEY_SIZE, KeyStore.FLAG_ENCRYPTED, null));

        assertTrue("Should be able to grant key to other user",
                mKeyStore.grant(TEST_KEYNAME, 0));
    }

    public void testGrant_Imported_Success() throws Exception {
        assertTrue("Password should work for keystore", mKeyStore.onUserPasswordChanged(TEST_PASSWD));

        assertTrue("Should be able to import key for testcase", mKeyStore.importKey(TEST_KEYNAME,
                PRIVKEY_BYTES, KeyStore.UID_SELF, KeyStore.FLAG_ENCRYPTED));

        assertTrue("Should be able to grant key to other user", mKeyStore.grant(TEST_KEYNAME, 0));
    }

    public void testGrant_NoKey_Failure() throws Exception {
        assertTrue("Should be able to unlock keystore for test",
                mKeyStore.onUserPasswordChanged(TEST_PASSWD));

        assertFalse("Should not be able to grant without first initializing the keystore",
                mKeyStore.grant(TEST_KEYNAME, 0));
    }

    public void testGrant_NotInitialized_Failure() throws Exception {
        assertFalse("Should not be able to grant without first initializing the keystore",
                mKeyStore.grant(TEST_KEYNAME, 0));
    }

    public void testUngrant_Generated_Success() throws Exception {
        assertTrue("Password should work for keystore",
                mKeyStore.onUserPasswordChanged(TEST_PASSWD));

        assertTrue("Should be able to generate key for testcase",
                mKeyStore.generate(TEST_KEYNAME, KeyStore.UID_SELF, NativeConstants.EVP_PKEY_RSA,
                        RSA_KEY_SIZE, KeyStore.FLAG_ENCRYPTED, null));

        assertTrue("Should be able to grant key to other user",
                mKeyStore.grant(TEST_KEYNAME, 0));

        assertTrue("Should be able to ungrant key to other user",
                mKeyStore.ungrant(TEST_KEYNAME, 0));
    }

    public void testUngrant_Imported_Success() throws Exception {
        assertTrue("Password should work for keystore",
                mKeyStore.onUserPasswordChanged(TEST_PASSWD));

        assertTrue("Should be able to import key for testcase", mKeyStore.importKey(TEST_KEYNAME,
                PRIVKEY_BYTES, KeyStore.UID_SELF, KeyStore.FLAG_ENCRYPTED));

        assertTrue("Should be able to grant key to other user",
                mKeyStore.grant(TEST_KEYNAME, 0));

        assertTrue("Should be able to ungrant key to other user",
                mKeyStore.ungrant(TEST_KEYNAME, 0));
    }

    public void testUngrant_NotInitialized_Failure() throws Exception {
        assertFalse("Should fail to ungrant key when keystore not initialized",
                mKeyStore.ungrant(TEST_KEYNAME, 0));
    }

    public void testUngrant_NoGrant_Failure() throws Exception {
        assertTrue("Password should work for keystore",
                mKeyStore.onUserPasswordChanged(TEST_PASSWD));

        assertTrue("Should be able to generate key for testcase",
                mKeyStore.generate(TEST_KEYNAME, KeyStore.UID_SELF, NativeConstants.EVP_PKEY_RSA,
                        RSA_KEY_SIZE, KeyStore.FLAG_ENCRYPTED, null));

        assertFalse("Should not be able to revoke not existent grant",
                mKeyStore.ungrant(TEST_KEYNAME, 0));
    }

    public void testUngrant_DoubleUngrant_Failure() throws Exception {
        assertTrue("Password should work for keystore",
                mKeyStore.onUserPasswordChanged(TEST_PASSWD));

        assertTrue("Should be able to generate key for testcase",
                mKeyStore.generate(TEST_KEYNAME, KeyStore.UID_SELF, NativeConstants.EVP_PKEY_RSA,
                        RSA_KEY_SIZE, KeyStore.FLAG_ENCRYPTED, null));

        assertTrue("Should be able to grant key to other user",
                mKeyStore.grant(TEST_KEYNAME, 0));

        assertTrue("Should be able to ungrant key to other user",
                mKeyStore.ungrant(TEST_KEYNAME, 0));

        assertFalse("Should fail to ungrant key to other user second time",
                mKeyStore.ungrant(TEST_KEYNAME, 0));
    }

    public void testUngrant_DoubleGrantUngrant_Failure() throws Exception {
        assertTrue("Password should work for keystore",
                mKeyStore.onUserPasswordChanged(TEST_PASSWD));

        assertTrue("Should be able to generate key for testcase",
                mKeyStore.generate(TEST_KEYNAME, KeyStore.UID_SELF, NativeConstants.EVP_PKEY_RSA,
                        RSA_KEY_SIZE, KeyStore.FLAG_ENCRYPTED, null));

        assertTrue("Should be able to grant key to other user",
                mKeyStore.grant(TEST_KEYNAME, 0));

        assertTrue("Should be able to grant key to other user a second time",
                mKeyStore.grant(TEST_KEYNAME, 0));

        assertTrue("Should be able to ungrant key to other user",
                mKeyStore.ungrant(TEST_KEYNAME, 0));

        assertFalse("Should fail to ungrant key to other user second time",
                mKeyStore.ungrant(TEST_KEYNAME, 0));
    }

    public void testDuplicate_grantedUid_Wifi_Success() throws Exception {
        assertTrue(mKeyStore.onUserPasswordChanged(TEST_PASSWD));

        assertFalse(mKeyStore.contains(TEST_KEYNAME));

        assertTrue(mKeyStore.generate(TEST_KEYNAME, KeyStore.UID_SELF, NativeConstants.EVP_PKEY_RSA,
                    RSA_KEY_SIZE, KeyStore.FLAG_ENCRYPTED, null));

        assertTrue(mKeyStore.contains(TEST_KEYNAME));
        assertFalse(mKeyStore.contains(TEST_KEYNAME, Process.WIFI_UID));

        // source doesn't exist
        assertFalse(mKeyStore.duplicate(TEST_KEYNAME1, -1, TEST_KEYNAME1, Process.WIFI_UID));
        assertFalse(mKeyStore.contains(TEST_KEYNAME1, Process.WIFI_UID));

        // Copy from current UID to granted UID
        assertTrue(mKeyStore.duplicate(TEST_KEYNAME, -1, TEST_KEYNAME1, Process.WIFI_UID));
        assertTrue(mKeyStore.contains(TEST_KEYNAME));
        assertFalse(mKeyStore.contains(TEST_KEYNAME1));
        assertFalse(mKeyStore.contains(TEST_KEYNAME, Process.WIFI_UID));
        assertTrue(mKeyStore.contains(TEST_KEYNAME1, Process.WIFI_UID));
        assertFalse(mKeyStore.duplicate(TEST_KEYNAME, -1, TEST_KEYNAME1, Process.WIFI_UID));

        // Copy from granted UID to same granted UID
        assertTrue(mKeyStore.duplicate(TEST_KEYNAME1, Process.WIFI_UID, TEST_KEYNAME2,
                Process.WIFI_UID));
        assertFalse(mKeyStore.contains(TEST_KEYNAME, Process.WIFI_UID));
        assertTrue(mKeyStore.contains(TEST_KEYNAME1, Process.WIFI_UID));
        assertTrue(mKeyStore.contains(TEST_KEYNAME2, Process.WIFI_UID));
        assertFalse(mKeyStore.duplicate(TEST_KEYNAME1, Process.WIFI_UID, TEST_KEYNAME2,
                Process.WIFI_UID));

        assertTrue(mKeyStore.duplicate(TEST_KEYNAME, -1, TEST_KEYNAME2, -1));
        assertTrue(mKeyStore.contains(TEST_KEYNAME));
        assertFalse(mKeyStore.contains(TEST_KEYNAME1));
        assertTrue(mKeyStore.contains(TEST_KEYNAME2));
        assertFalse(mKeyStore.duplicate(TEST_KEYNAME, -1, TEST_KEYNAME2, -1));
    }

    public void testDuplicate_ungrantedUid_Bluetooth_Failure() throws Exception {
        assertTrue(mKeyStore.onUserPasswordChanged(TEST_PASSWD));

        assertFalse(mKeyStore.contains(TEST_KEYNAME));

        assertTrue(mKeyStore.generate(TEST_KEYNAME, KeyStore.UID_SELF, NativeConstants.EVP_PKEY_RSA,
                    RSA_KEY_SIZE, KeyStore.FLAG_ENCRYPTED, null));

        assertTrue(mKeyStore.contains(TEST_KEYNAME));
        assertFalse(mKeyStore.contains(TEST_KEYNAME, Process.BLUETOOTH_UID));

        assertFalse(mKeyStore.duplicate(TEST_KEYNAME, -1, TEST_KEYNAME2, Process.BLUETOOTH_UID));
        assertFalse(mKeyStore.duplicate(TEST_KEYNAME, Process.BLUETOOTH_UID, TEST_KEYNAME2,
                Process.BLUETOOTH_UID));

        assertTrue(mKeyStore.contains(TEST_KEYNAME));
        assertFalse(mKeyStore.contains(TEST_KEYNAME, Process.BLUETOOTH_UID));
    }

    /**
     * The amount of time to allow before and after expected time for variance
     * in timing tests.
     */
    private static final long SLOP_TIME_MILLIS = 15000L;

    public void testGetmtime_Success() throws Exception {
        assertTrue("Password should work for keystore",
                mKeyStore.onUserPasswordChanged(TEST_PASSWD));

        assertTrue("Should be able to import key when unlocked", mKeyStore.importKey(TEST_KEYNAME,
                PRIVKEY_BYTES, KeyStore.UID_SELF, KeyStore.FLAG_ENCRYPTED));

        long now = System.currentTimeMillis();
        long actual = mKeyStore.getmtime(TEST_KEYNAME);

        long expectedAfter = now - SLOP_TIME_MILLIS;
        long expectedBefore = now + SLOP_TIME_MILLIS;

        assertLessThan("Time should be close to current time", expectedBefore, actual);
        assertGreaterThan("Time should be close to current time", expectedAfter, actual);
    }

    private static void assertLessThan(String explanation, long expectedBefore, long actual) {
        if (actual >= expectedBefore) {
            throw new AssertionFailedError(explanation + ": actual=" + actual
                    + ", expected before: " + expectedBefore);
        }
    }

    private static void assertGreaterThan(String explanation, long expectedAfter, long actual) {
        if (actual <= expectedAfter) {
            throw new AssertionFailedError(explanation + ": actual=" + actual
                    + ", expected after: " + expectedAfter);
        }
    }

    public void testGetmtime_NonExist_Failure() throws Exception {
        assertTrue("Password should work for keystore",
                mKeyStore.onUserPasswordChanged(TEST_PASSWD));

        assertTrue("Should be able to import key when unlocked", mKeyStore.importKey(TEST_KEYNAME,
                PRIVKEY_BYTES, KeyStore.UID_SELF, KeyStore.FLAG_ENCRYPTED));

        assertEquals("-1 should be returned for non-existent key",
                -1L, mKeyStore.getmtime(TEST_KEYNAME2));
    }

    private KeyCharacteristics generateRsaKey(String name) throws Exception {
        KeymasterArguments args = new KeymasterArguments();
        args.addEnum(KeymasterDefs.KM_TAG_PURPOSE, KeymasterDefs.KM_PURPOSE_ENCRYPT);
        args.addEnum(KeymasterDefs.KM_TAG_PURPOSE, KeymasterDefs.KM_PURPOSE_DECRYPT);
        args.addEnum(KeymasterDefs.KM_TAG_ALGORITHM, KeymasterDefs.KM_ALGORITHM_RSA);
        args.addEnum(KeymasterDefs.KM_TAG_PADDING, KeymasterDefs.KM_PAD_NONE);
        args.addBoolean(KeymasterDefs.KM_TAG_NO_AUTH_REQUIRED);
        args.addUnsignedInt(KeymasterDefs.KM_TAG_KEY_SIZE, 2048);
        args.addUnsignedLong(KeymasterDefs.KM_TAG_RSA_PUBLIC_EXPONENT, RSAKeyGenParameterSpec.F4);

        KeyCharacteristics outCharacteristics = new KeyCharacteristics();
        int result = mKeyStore.generateKey(name, args, null, 0, outCharacteristics);
        assertEquals("generateRsaKey should succeed", KeyStore.NO_ERROR, result);
        return outCharacteristics;
    }

    public void testGenerateKey() throws Exception {
        generateRsaKey("test");
        mKeyStore.delete("test");
    }

    public void testGenerateRsaWithEntropy() throws Exception {
        byte[] entropy = new byte[] {1,2,3,4,5};
        String name = "test";
        KeymasterArguments args = new KeymasterArguments();
        args.addEnum(KeymasterDefs.KM_TAG_PURPOSE, KeymasterDefs.KM_PURPOSE_ENCRYPT);
        args.addEnum(KeymasterDefs.KM_TAG_PURPOSE, KeymasterDefs.KM_PURPOSE_DECRYPT);
        args.addEnum(KeymasterDefs.KM_TAG_ALGORITHM, KeymasterDefs.KM_ALGORITHM_RSA);
        args.addEnum(KeymasterDefs.KM_TAG_PADDING, KeymasterDefs.KM_PAD_NONE);
        args.addBoolean(KeymasterDefs.KM_TAG_NO_AUTH_REQUIRED);
        args.addUnsignedInt(KeymasterDefs.KM_TAG_KEY_SIZE, 2048);
        args.addUnsignedLong(KeymasterDefs.KM_TAG_RSA_PUBLIC_EXPONENT, RSAKeyGenParameterSpec.F4);

        KeyCharacteristics outCharacteristics = new KeyCharacteristics();
        int result = mKeyStore.generateKey(name, args, entropy, 0, outCharacteristics);
        assertEquals("generateKey should succeed", KeyStore.NO_ERROR, result);
    }

    public void testGenerateAndDelete() throws Exception {
        generateRsaKey("test");
        assertTrue("delete should succeed", mKeyStore.delete("test"));
    }

    public void testGetKeyCharacteristicsSuccess() throws Exception {
        mKeyStore.onUserPasswordChanged(TEST_PASSWD);
        String name = "test";
        KeyCharacteristics gen = generateRsaKey(name);
        KeyCharacteristics call = new KeyCharacteristics();
        int result = mKeyStore.getKeyCharacteristics(name, null, null, call);
        assertEquals("getKeyCharacteristics should succeed", KeyStore.NO_ERROR, result);
        mKeyStore.delete("test");
    }

    public void testAppId() throws Exception {
        String name = "test";
        byte[] id = new byte[] {0x01, 0x02, 0x03};
        KeymasterArguments args = new KeymasterArguments();
        args.addEnum(KeymasterDefs.KM_TAG_PURPOSE, KeymasterDefs.KM_PURPOSE_ENCRYPT);
        args.addEnum(KeymasterDefs.KM_TAG_PURPOSE, KeymasterDefs.KM_PURPOSE_DECRYPT);
        args.addEnum(KeymasterDefs.KM_TAG_ALGORITHM, KeymasterDefs.KM_ALGORITHM_RSA);
        args.addEnum(KeymasterDefs.KM_TAG_PADDING, KeymasterDefs.KM_PAD_NONE);
        args.addUnsignedInt(KeymasterDefs.KM_TAG_KEY_SIZE, 2048);
        args.addEnum(KeymasterDefs.KM_TAG_BLOCK_MODE, KeymasterDefs.KM_MODE_ECB);
        args.addBoolean(KeymasterDefs.KM_TAG_NO_AUTH_REQUIRED);
        args.addBytes(KeymasterDefs.KM_TAG_APPLICATION_ID, id);
        args.addUnsignedLong(KeymasterDefs.KM_TAG_RSA_PUBLIC_EXPONENT, RSAKeyGenParameterSpec.F4);

        KeyCharacteristics outCharacteristics = new KeyCharacteristics();
        int result = mKeyStore.generateKey(name, args, null, 0, outCharacteristics);
        assertEquals("generateRsaKey should succeed", KeyStore.NO_ERROR, result);
        assertEquals("getKeyCharacteristics should fail without application ID",
                KeymasterDefs.KM_ERROR_INVALID_KEY_BLOB,
                mKeyStore.getKeyCharacteristics(name, null, null, outCharacteristics));
        assertEquals("getKeyCharacteristics should succeed with application ID",
                KeyStore.NO_ERROR,
                mKeyStore.getKeyCharacteristics(name, new KeymasterBlob(id), null,
                    outCharacteristics));
    }


    public void testExportRsa() throws Exception {
        String name = "test";
        generateRsaKey(name);
        ExportResult result = mKeyStore.exportKey(name, KeymasterDefs.KM_KEY_FORMAT_X509, null,
                null);
        assertEquals("Export success", KeyStore.NO_ERROR, result.resultCode);
        // TODO: Verify we have an RSA public key that's well formed.
    }

    public void testAesGcmEncryptSuccess() throws Exception {
        String name = "test";
        KeymasterArguments args = new KeymasterArguments();
        args.addEnum(KeymasterDefs.KM_TAG_PURPOSE, KeymasterDefs.KM_PURPOSE_ENCRYPT);
        args.addEnum(KeymasterDefs.KM_TAG_PURPOSE, KeymasterDefs.KM_PURPOSE_DECRYPT);
        args.addEnum(KeymasterDefs.KM_TAG_ALGORITHM, KeymasterDefs.KM_ALGORITHM_AES);
        args.addEnum(KeymasterDefs.KM_TAG_PADDING, KeymasterDefs.KM_PAD_NONE);
        args.addUnsignedInt(KeymasterDefs.KM_TAG_KEY_SIZE, 256);
        args.addEnum(KeymasterDefs.KM_TAG_BLOCK_MODE, KeymasterDefs.KM_MODE_GCM);
        args.addBoolean(KeymasterDefs.KM_TAG_NO_AUTH_REQUIRED);

        KeyCharacteristics outCharacteristics = new KeyCharacteristics();
        int rc = mKeyStore.generateKey(name, args, null, 0, outCharacteristics);
        assertEquals("Generate should succeed", KeyStore.NO_ERROR, rc);

        args = new KeymasterArguments();
        args.addEnum(KeymasterDefs.KM_TAG_ALGORITHM, KeymasterDefs.KM_ALGORITHM_AES);
        args.addEnum(KeymasterDefs.KM_TAG_BLOCK_MODE, KeymasterDefs.KM_MODE_GCM);
        args.addEnum(KeymasterDefs.KM_TAG_PADDING, KeymasterDefs.KM_PAD_NONE);
        args.addUnsignedInt(KeymasterDefs.KM_TAG_MAC_LENGTH, 128);
        OperationResult result = mKeyStore.begin(name, KeymasterDefs.KM_PURPOSE_ENCRYPT,
                true, args, null);
        IBinder token = result.token;
        assertEquals("Begin should succeed", KeyStore.NO_ERROR, result.resultCode);
        result = mKeyStore.update(token, null, new byte[] {0x01, 0x02, 0x03, 0x04});
        assertEquals("Update should succeed", KeyStore.NO_ERROR, result.resultCode);
        assertEquals("Finish should succeed", KeyStore.NO_ERROR,
                mKeyStore.finish(token, null, null).resultCode);
        // TODO: Assert that an AEAD tag was returned by finish
    }

    public void testBadToken() throws Exception {
        IBinder token = new Binder();
        OperationResult result = mKeyStore.update(token, null, new byte[] {0x01});
        assertEquals("Update with invalid token should fail",
                KeymasterDefs.KM_ERROR_INVALID_OPERATION_HANDLE, result.resultCode);
    }

    private int importAesKey(String name, byte[] key, int size, int mode) {
        KeymasterArguments args = new KeymasterArguments();
        args.addEnum(KeymasterDefs.KM_TAG_PURPOSE, KeymasterDefs.KM_PURPOSE_ENCRYPT);
        args.addEnum(KeymasterDefs.KM_TAG_PURPOSE, KeymasterDefs.KM_PURPOSE_DECRYPT);
        args.addEnum(KeymasterDefs.KM_TAG_ALGORITHM, KeymasterDefs.KM_ALGORITHM_AES);
        args.addEnum(KeymasterDefs.KM_TAG_PADDING, KeymasterDefs.KM_PAD_NONE);
        args.addEnum(KeymasterDefs.KM_TAG_BLOCK_MODE, mode);
        args.addUnsignedInt(KeymasterDefs.KM_TAG_KEY_SIZE, size);
        args.addBoolean(KeymasterDefs.KM_TAG_NO_AUTH_REQUIRED);
        return mKeyStore.importKey(name, args, KeymasterDefs.KM_KEY_FORMAT_RAW, key, 0,
                new KeyCharacteristics());
    }
    private byte[] doOperation(String name, int purpose, byte[] in, KeymasterArguments beginArgs) {
        OperationResult result = mKeyStore.begin(name, purpose,
                true, beginArgs, null);
        assertEquals("Begin should succeed", KeyStore.NO_ERROR, result.resultCode);
        IBinder token = result.token;
        result = mKeyStore.update(token, null, in);
        assertEquals("Update should succeed", KeyStore.NO_ERROR, result.resultCode);
        assertEquals("All data should be consumed", in.length, result.inputConsumed);
        assertEquals("Finish should succeed", KeyStore.NO_ERROR,
                mKeyStore.finish(token, null, null).resultCode);
        return result.output;
    }

    public void testImportAes() throws Exception {
        int result = importAesKey("aes", AES256_BYTES, 256, KeymasterDefs.KM_MODE_ECB);
        assertEquals("import should succeed", KeyStore.NO_ERROR, result);
        mKeyStore.delete("aes");
    }

    public void testAes256Ecb() throws Exception {
        byte[] key =
                hexToBytes("603deb1015ca71be2b73aef0857d77811f352c073b6108d72d9810a30914dff4");
        String name = "aes";
        assertEquals(KeyStore.NO_ERROR, importAesKey(name, key, 256, KeymasterDefs.KM_MODE_ECB));
        byte[][] testVectors = new byte[][] {
            hexToBytes("6bc1bee22e409f96e93d7e117393172a"),
            hexToBytes("ae2d8a571e03ac9c9eb76fac45af8e51"),
            hexToBytes("30c81c46a35ce411e5fbc1191a0a52ef"),
            hexToBytes("f69f2445df4f9b17ad2b417be66c3710")};
        byte[][] cipherVectors = new byte[][] {
            hexToBytes("f3eed1bdb5d2a03c064b5a7e3db181f8"),
            hexToBytes("591ccb10d410ed26dc5ba74a31362870"),
            hexToBytes("b6ed21b99ca6f4f9f153e7b1beafed1d"),
            hexToBytes("23304b7a39f9f3ff067d8d8f9e24ecc7")};
        KeymasterArguments beginArgs = new KeymasterArguments();
        beginArgs.addEnum(KeymasterDefs.KM_TAG_ALGORITHM, KeymasterDefs.KM_ALGORITHM_AES);
        beginArgs.addEnum(KeymasterDefs.KM_TAG_BLOCK_MODE, KeymasterDefs.KM_MODE_ECB);
        beginArgs.addEnum(KeymasterDefs.KM_TAG_PADDING, KeymasterDefs.KM_PAD_NONE);
        for (int i = 0; i < testVectors.length; i++) {
            byte[] cipherText = doOperation(name, KeymasterDefs.KM_PURPOSE_ENCRYPT, testVectors[i],
                    beginArgs);
            MoreAsserts.assertEquals(cipherVectors[i], cipherText);
        }
        for (int i = 0; i < testVectors.length; i++) {
            byte[] plainText = doOperation(name, KeymasterDefs.KM_PURPOSE_DECRYPT,
                    cipherVectors[i], beginArgs);
            MoreAsserts.assertEquals(testVectors[i], plainText);
        }
    }

    // This is a very implementation specific test and should be thrown out eventually, however it
    // is nice for now to test that keystore is properly pruning operations.
    public void testOperationPruning() throws Exception {
        String name = "test";
        KeymasterArguments args = new KeymasterArguments();
        args.addEnum(KeymasterDefs.KM_TAG_PURPOSE, KeymasterDefs.KM_PURPOSE_ENCRYPT);
        args.addEnum(KeymasterDefs.KM_TAG_PURPOSE, KeymasterDefs.KM_PURPOSE_DECRYPT);
        args.addEnum(KeymasterDefs.KM_TAG_ALGORITHM, KeymasterDefs.KM_ALGORITHM_AES);
        args.addEnum(KeymasterDefs.KM_TAG_PADDING, KeymasterDefs.KM_PAD_NONE);
        args.addUnsignedInt(KeymasterDefs.KM_TAG_KEY_SIZE, 256);
        args.addEnum(KeymasterDefs.KM_TAG_BLOCK_MODE, KeymasterDefs.KM_MODE_CTR);
        args.addBoolean(KeymasterDefs.KM_TAG_NO_AUTH_REQUIRED);

        KeyCharacteristics outCharacteristics = new KeyCharacteristics();
        int rc = mKeyStore.generateKey(name, args, null, 0, outCharacteristics);
        assertEquals("Generate should succeed", KeyStore.NO_ERROR, rc);

        args = new KeymasterArguments();
        args.addEnum(KeymasterDefs.KM_TAG_ALGORITHM, KeymasterDefs.KM_ALGORITHM_AES);
        args.addEnum(KeymasterDefs.KM_TAG_BLOCK_MODE, KeymasterDefs.KM_MODE_CTR);
        args.addEnum(KeymasterDefs.KM_TAG_PADDING, KeymasterDefs.KM_PAD_NONE);
        OperationResult result = mKeyStore.begin(name, KeymasterDefs.KM_PURPOSE_ENCRYPT,
                true, args, null);
        assertEquals("Begin should succeed", KeyStore.NO_ERROR, result.resultCode);
        IBinder first = result.token;
        // Implementation detail: softkeymaster supports 16 concurrent operations
        for (int i = 0; i < 16; i++) {
            result = mKeyStore.begin(name, KeymasterDefs.KM_PURPOSE_ENCRYPT, true, args, null);
            assertEquals("Begin should succeed", KeyStore.NO_ERROR, result.resultCode);
        }
        // At this point the first operation should be pruned.
        assertEquals("Operation should be pruned", KeymasterDefs.KM_ERROR_INVALID_OPERATION_HANDLE,
                mKeyStore.update(first, null, new byte[] {0x01}).resultCode);
    }

    public void testAuthNeeded() throws Exception {
        String name = "test";
        KeymasterArguments args = new KeymasterArguments();
        args.addEnum(KeymasterDefs.KM_TAG_PURPOSE, KeymasterDefs.KM_PURPOSE_ENCRYPT);
        args.addEnum(KeymasterDefs.KM_TAG_PURPOSE, KeymasterDefs.KM_PURPOSE_DECRYPT);
        args.addEnum(KeymasterDefs.KM_TAG_ALGORITHM, KeymasterDefs.KM_ALGORITHM_AES);
        args.addEnum(KeymasterDefs.KM_TAG_PADDING, KeymasterDefs.KM_PAD_PKCS7);
        args.addUnsignedInt(KeymasterDefs.KM_TAG_KEY_SIZE, 256);
        args.addEnum(KeymasterDefs.KM_TAG_BLOCK_MODE, KeymasterDefs.KM_MODE_ECB);
        args.addEnum(KeymasterDefs.KM_TAG_USER_AUTH_TYPE, 1);

        KeyCharacteristics outCharacteristics = new KeyCharacteristics();
        int rc = mKeyStore.generateKey(name, args, null, 0, outCharacteristics);
        assertEquals("Generate should succeed", KeyStore.NO_ERROR, rc);
        OperationResult result = mKeyStore.begin(name, KeymasterDefs.KM_PURPOSE_ENCRYPT,
                true, args, null);
        assertEquals("Begin should expect authorization", KeyStore.OP_AUTH_NEEDED,
                result.resultCode);
        IBinder token = result.token;
        result = mKeyStore.update(token, null, new byte[] {0x01, 0x02, 0x03, 0x04});
        assertEquals("Update should require authorization",
                KeymasterDefs.KM_ERROR_KEY_USER_NOT_AUTHENTICATED, result.resultCode);
    }

    public void testPasswordRemovalEncryptedEntry() throws Exception {
        mKeyStore.onUserPasswordChanged("test");
        assertTrue(mKeyStore.put(TEST_KEYNAME, TEST_KEYVALUE, KeyStore.UID_SELF,
                KeyStore.FLAG_ENCRYPTED));
        assertTrue(mKeyStore.contains(TEST_KEYNAME));
        assertTrue(Arrays.equals(TEST_KEYVALUE, mKeyStore.get(TEST_KEYNAME)));
        mKeyStore.onUserPasswordChanged("");
        // Removing the password should have deleted all entries using FLAG_ENCRYPTED
        assertNull(mKeyStore.get(TEST_KEYNAME));
        assertFalse(mKeyStore.contains(TEST_KEYNAME));
    }

    public void testPasswordRemovalUnencryptedEntry() throws Exception {
        mKeyStore.onUserPasswordChanged("test");
        assertTrue(mKeyStore.put(TEST_KEYNAME, TEST_KEYVALUE, KeyStore.UID_SELF,
                KeyStore.FLAG_NONE));
        assertTrue(mKeyStore.contains(TEST_KEYNAME));
        assertTrue(Arrays.equals(TEST_KEYVALUE, mKeyStore.get(TEST_KEYNAME)));
        mKeyStore.onUserPasswordChanged("");
        // Removing the password should not delete unencrypted entries.
        assertTrue(mKeyStore.contains(TEST_KEYNAME));
        assertTrue(Arrays.equals(TEST_KEYVALUE, mKeyStore.get(TEST_KEYNAME)));
    }
}
