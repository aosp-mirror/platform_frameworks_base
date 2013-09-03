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
import android.os.Process;
import android.security.KeyStore;
import android.test.ActivityUnitTestCase;
import android.test.AssertionFailedError;
import android.test.suitebuilder.annotation.MediumTest;
import com.android.org.conscrypt.NativeCrypto;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;

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
    private static final byte[] TEST_DATA =  new byte[256];
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
        assertTrue(mKeyStore.password(TEST_PASSWD));
        assertEquals(KeyStore.State.UNLOCKED, mKeyStore.state());
    }

    public void testGet() throws Exception {
        assertNull(mKeyStore.get(TEST_KEYNAME));
        mKeyStore.password(TEST_PASSWD);
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
        mKeyStore.password(TEST_PASSWD);
        assertTrue(mKeyStore.put(TEST_KEYNAME, TEST_KEYVALUE, KeyStore.UID_SELF,
                KeyStore.FLAG_ENCRYPTED));
        assertTrue(Arrays.equals(TEST_KEYVALUE, mKeyStore.get(TEST_KEYNAME)));
    }

    public void testPut_grantedUid_Wifi() throws Exception {
        assertFalse(mKeyStore.contains(TEST_KEYNAME, Process.WIFI_UID));
        assertFalse(mKeyStore.put(TEST_KEYNAME, TEST_KEYVALUE, Process.WIFI_UID,
                KeyStore.FLAG_ENCRYPTED));
        assertFalse(mKeyStore.contains(TEST_KEYNAME, Process.WIFI_UID));
        mKeyStore.password(TEST_PASSWD);
        assertTrue(mKeyStore.put(TEST_KEYNAME, TEST_KEYVALUE, Process.WIFI_UID,
                KeyStore.FLAG_ENCRYPTED));
        assertTrue(mKeyStore.contains(TEST_KEYNAME, Process.WIFI_UID));
    }

    public void testPut_ungrantedUid_Bluetooth() throws Exception {
        assertFalse(mKeyStore.contains(TEST_KEYNAME, Process.BLUETOOTH_UID));
        assertFalse(mKeyStore.put(TEST_KEYNAME, TEST_KEYVALUE, Process.BLUETOOTH_UID,
                KeyStore.FLAG_ENCRYPTED));
        assertFalse(mKeyStore.contains(TEST_KEYNAME, Process.BLUETOOTH_UID));
        mKeyStore.password(TEST_PASSWD);
        assertFalse(mKeyStore.put(TEST_KEYNAME, TEST_KEYVALUE, Process.BLUETOOTH_UID,
                KeyStore.FLAG_ENCRYPTED));
        assertFalse(mKeyStore.contains(TEST_KEYNAME, Process.BLUETOOTH_UID));
    }

    public void testI18n() throws Exception {
        assertFalse(mKeyStore.put(TEST_I18N_KEY, TEST_I18N_VALUE, KeyStore.UID_SELF,
                KeyStore.FLAG_ENCRYPTED));
        assertFalse(mKeyStore.contains(TEST_I18N_KEY));
        mKeyStore.password(TEST_I18N_KEY);
        assertTrue(mKeyStore.put(TEST_I18N_KEY, TEST_I18N_VALUE, KeyStore.UID_SELF,
                KeyStore.FLAG_ENCRYPTED));
        assertTrue(mKeyStore.contains(TEST_I18N_KEY));
    }

    public void testDelete() throws Exception {
        assertFalse(mKeyStore.delete(TEST_KEYNAME));
        mKeyStore.password(TEST_PASSWD);
        assertFalse(mKeyStore.delete(TEST_KEYNAME));

        assertTrue(mKeyStore.put(TEST_KEYNAME, TEST_KEYVALUE, KeyStore.UID_SELF,
                KeyStore.FLAG_ENCRYPTED));
        assertTrue(Arrays.equals(TEST_KEYVALUE, mKeyStore.get(TEST_KEYNAME)));
        assertTrue(mKeyStore.delete(TEST_KEYNAME));
        assertNull(mKeyStore.get(TEST_KEYNAME));
    }

    public void testDelete_grantedUid_Wifi() throws Exception {
        assertFalse(mKeyStore.delete(TEST_KEYNAME, Process.WIFI_UID));
        mKeyStore.password(TEST_PASSWD);
        assertFalse(mKeyStore.delete(TEST_KEYNAME, Process.WIFI_UID));

        assertTrue(mKeyStore.put(TEST_KEYNAME, TEST_KEYVALUE, Process.WIFI_UID,
                KeyStore.FLAG_ENCRYPTED));
        assertTrue(mKeyStore.contains(TEST_KEYNAME, Process.WIFI_UID));
        assertTrue(mKeyStore.delete(TEST_KEYNAME, Process.WIFI_UID));
        assertFalse(mKeyStore.contains(TEST_KEYNAME, Process.WIFI_UID));
    }

    public void testDelete_ungrantedUid_Bluetooth() throws Exception {
        assertFalse(mKeyStore.delete(TEST_KEYNAME, Process.BLUETOOTH_UID));
        mKeyStore.password(TEST_PASSWD);
        assertFalse(mKeyStore.delete(TEST_KEYNAME, Process.BLUETOOTH_UID));

        assertFalse(mKeyStore.put(TEST_KEYNAME, TEST_KEYVALUE, Process.BLUETOOTH_UID,
                KeyStore.FLAG_ENCRYPTED));
        assertFalse(mKeyStore.contains(TEST_KEYNAME, Process.BLUETOOTH_UID));
        assertFalse(mKeyStore.delete(TEST_KEYNAME, Process.BLUETOOTH_UID));
        assertFalse(mKeyStore.contains(TEST_KEYNAME, Process.BLUETOOTH_UID));
    }

    public void testContains() throws Exception {
        assertFalse(mKeyStore.contains(TEST_KEYNAME));

        assertTrue(mKeyStore.password(TEST_PASSWD));
        assertFalse(mKeyStore.contains(TEST_KEYNAME));

        assertTrue(mKeyStore.put(TEST_KEYNAME, TEST_KEYVALUE, KeyStore.UID_SELF,
                KeyStore.FLAG_ENCRYPTED));
        assertTrue(mKeyStore.contains(TEST_KEYNAME));
    }

    public void testContains_grantedUid_Wifi() throws Exception {
        assertFalse(mKeyStore.contains(TEST_KEYNAME, Process.WIFI_UID));

        assertTrue(mKeyStore.password(TEST_PASSWD));
        assertFalse(mKeyStore.contains(TEST_KEYNAME, Process.WIFI_UID));

        assertTrue(mKeyStore.put(TEST_KEYNAME, TEST_KEYVALUE, Process.WIFI_UID,
                KeyStore.FLAG_ENCRYPTED));
        assertTrue(mKeyStore.contains(TEST_KEYNAME, Process.WIFI_UID));
    }

    public void testContains_grantedUid_Bluetooth() throws Exception {
        assertFalse(mKeyStore.contains(TEST_KEYNAME, Process.BLUETOOTH_UID));

        assertTrue(mKeyStore.password(TEST_PASSWD));
        assertFalse(mKeyStore.contains(TEST_KEYNAME, Process.BLUETOOTH_UID));

        assertFalse(mKeyStore.put(TEST_KEYNAME, TEST_KEYVALUE, Process.BLUETOOTH_UID,
                KeyStore.FLAG_ENCRYPTED));
        assertFalse(mKeyStore.contains(TEST_KEYNAME, Process.BLUETOOTH_UID));
    }

    public void testSaw() throws Exception {
        String[] emptyResult = mKeyStore.saw(TEST_KEYNAME);
        assertNotNull(emptyResult);
        assertEquals(0, emptyResult.length);

        mKeyStore.password(TEST_PASSWD);
        mKeyStore.put(TEST_KEYNAME1, TEST_KEYVALUE, KeyStore.UID_SELF, KeyStore.FLAG_ENCRYPTED);
        mKeyStore.put(TEST_KEYNAME2, TEST_KEYVALUE, KeyStore.UID_SELF, KeyStore.FLAG_ENCRYPTED);

        String[] results = mKeyStore.saw(TEST_KEYNAME);
        assertEquals(new HashSet(Arrays.asList(TEST_KEYNAME1.substring(TEST_KEYNAME.length()),
                                               TEST_KEYNAME2.substring(TEST_KEYNAME.length()))),
                     new HashSet(Arrays.asList(results)));
    }

    public void testSaw_ungrantedUid_Bluetooth() throws Exception {
        String[] results1 = mKeyStore.saw(TEST_KEYNAME, Process.BLUETOOTH_UID);
        assertNull(results1);

        mKeyStore.password(TEST_PASSWD);
        mKeyStore.put(TEST_KEYNAME1, TEST_KEYVALUE, KeyStore.UID_SELF, KeyStore.FLAG_ENCRYPTED);
        mKeyStore.put(TEST_KEYNAME2, TEST_KEYVALUE, KeyStore.UID_SELF, KeyStore.FLAG_ENCRYPTED);

        String[] results2 = mKeyStore.saw(TEST_KEYNAME, Process.BLUETOOTH_UID);
        assertNull(results2);
    }

    public void testSaw_grantedUid_Wifi() throws Exception {
        String[] results1 = mKeyStore.saw(TEST_KEYNAME, Process.WIFI_UID);
        assertNotNull(results1);
        assertEquals(0, results1.length);

        mKeyStore.password(TEST_PASSWD);
        mKeyStore.put(TEST_KEYNAME1, TEST_KEYVALUE, Process.WIFI_UID, KeyStore.FLAG_ENCRYPTED);
        mKeyStore.put(TEST_KEYNAME2, TEST_KEYVALUE, Process.WIFI_UID, KeyStore.FLAG_ENCRYPTED);

        String[] results2 = mKeyStore.saw(TEST_KEYNAME, Process.WIFI_UID);
        assertEquals(new HashSet(Arrays.asList(TEST_KEYNAME1.substring(TEST_KEYNAME.length()),
                                               TEST_KEYNAME2.substring(TEST_KEYNAME.length()))),
                     new HashSet(Arrays.asList(results2)));
    }

    public void testSaw_grantedUid_Vpn() throws Exception {
        String[] results1 = mKeyStore.saw(TEST_KEYNAME, Process.VPN_UID);
        assertNotNull(results1);
        assertEquals(0, results1.length);

        mKeyStore.password(TEST_PASSWD);
        mKeyStore.put(TEST_KEYNAME1, TEST_KEYVALUE, Process.VPN_UID, KeyStore.FLAG_ENCRYPTED);
        mKeyStore.put(TEST_KEYNAME2, TEST_KEYVALUE, Process.VPN_UID, KeyStore.FLAG_ENCRYPTED);

        String[] results2 = mKeyStore.saw(TEST_KEYNAME, Process.VPN_UID);
        assertEquals(new HashSet(Arrays.asList(TEST_KEYNAME1.substring(TEST_KEYNAME.length()),
                                               TEST_KEYNAME2.substring(TEST_KEYNAME.length()))),
                     new HashSet(Arrays.asList(results2)));
    }

    public void testLock() throws Exception {
        assertFalse(mKeyStore.lock());

        mKeyStore.password(TEST_PASSWD);
        assertEquals(KeyStore.State.UNLOCKED, mKeyStore.state());

        assertTrue(mKeyStore.lock());
        assertEquals(KeyStore.State.LOCKED, mKeyStore.state());
    }

    public void testUnlock() throws Exception {
        mKeyStore.password(TEST_PASSWD);
        assertEquals(KeyStore.State.UNLOCKED, mKeyStore.state());
        mKeyStore.lock();

        assertFalse(mKeyStore.unlock(TEST_PASSWD2));
        assertTrue(mKeyStore.unlock(TEST_PASSWD));
    }

    public void testIsEmpty() throws Exception {
        assertTrue(mKeyStore.isEmpty());
        mKeyStore.password(TEST_PASSWD);
        assertTrue(mKeyStore.isEmpty());
        mKeyStore.put(TEST_KEYNAME, TEST_KEYVALUE, KeyStore.UID_SELF, KeyStore.FLAG_ENCRYPTED);
        assertFalse(mKeyStore.isEmpty());
        mKeyStore.reset();
        assertTrue(mKeyStore.isEmpty());
    }

    public void testGenerate_NotInitialized_Fail() throws Exception {
        assertFalse("Should fail when keystore is not initialized",
                mKeyStore.generate(TEST_KEYNAME, KeyStore.UID_SELF, NativeCrypto.EVP_PKEY_RSA,
                        1024, KeyStore.FLAG_ENCRYPTED, null));
    }

    public void testGenerate_Locked_Fail() throws Exception {
        mKeyStore.password(TEST_PASSWD);
        mKeyStore.lock();
        assertFalse("Should fail when keystore is locked",
                mKeyStore.generate(TEST_KEYNAME, KeyStore.UID_SELF, NativeCrypto.EVP_PKEY_RSA,
                        1024, KeyStore.FLAG_ENCRYPTED, null));
    }

    public void testGenerate_Success() throws Exception {
        assertTrue(mKeyStore.password(TEST_PASSWD));

        assertTrue("Should be able to generate key when unlocked",
                mKeyStore.generate(TEST_KEYNAME, KeyStore.UID_SELF, NativeCrypto.EVP_PKEY_RSA,
                        1024, KeyStore.FLAG_ENCRYPTED, null));
        assertTrue(mKeyStore.contains(TEST_KEYNAME));
        assertFalse(mKeyStore.contains(TEST_KEYNAME, Process.WIFI_UID));
    }

    public void testGenerate_grantedUid_Wifi_Success() throws Exception {
        assertTrue(mKeyStore.password(TEST_PASSWD));

        assertTrue("Should be able to generate key when unlocked",
                mKeyStore.generate(TEST_KEYNAME, Process.WIFI_UID, NativeCrypto.EVP_PKEY_RSA,
                        1024, KeyStore.FLAG_ENCRYPTED, null));
        assertTrue(mKeyStore.contains(TEST_KEYNAME, Process.WIFI_UID));
        assertFalse(mKeyStore.contains(TEST_KEYNAME));
    }

    public void testGenerate_ungrantedUid_Bluetooth_Failure() throws Exception {
        assertTrue(mKeyStore.password(TEST_PASSWD));

        assertFalse(mKeyStore.generate(TEST_KEYNAME, Process.BLUETOOTH_UID,
                    NativeCrypto.EVP_PKEY_RSA, 1024, KeyStore.FLAG_ENCRYPTED, null));
        assertFalse(mKeyStore.contains(TEST_KEYNAME, Process.BLUETOOTH_UID));
        assertFalse(mKeyStore.contains(TEST_KEYNAME, Process.WIFI_UID));
        assertFalse(mKeyStore.contains(TEST_KEYNAME));
    }

    public void testImport_Success() throws Exception {
        assertTrue(mKeyStore.password(TEST_PASSWD));

        assertTrue("Should be able to import key when unlocked", mKeyStore.importKey(TEST_KEYNAME,
                PRIVKEY_BYTES, KeyStore.UID_SELF, KeyStore.FLAG_ENCRYPTED));
        assertTrue(mKeyStore.contains(TEST_KEYNAME));
        assertFalse(mKeyStore.contains(TEST_KEYNAME, Process.WIFI_UID));
    }

    public void testImport_grantedUid_Wifi_Success() throws Exception {
        assertTrue(mKeyStore.password(TEST_PASSWD));

        assertTrue("Should be able to import key when unlocked", mKeyStore.importKey(TEST_KEYNAME,
                PRIVKEY_BYTES, Process.WIFI_UID, KeyStore.FLAG_ENCRYPTED));
        assertTrue(mKeyStore.contains(TEST_KEYNAME, Process.WIFI_UID));
        assertFalse(mKeyStore.contains(TEST_KEYNAME));
    }

    public void testImport_ungrantedUid_Bluetooth_Failure() throws Exception {
        assertTrue(mKeyStore.password(TEST_PASSWD));

        assertFalse(mKeyStore.importKey(TEST_KEYNAME, PRIVKEY_BYTES, Process.BLUETOOTH_UID,
                KeyStore.FLAG_ENCRYPTED));
        assertFalse(mKeyStore.contains(TEST_KEYNAME, Process.BLUETOOTH_UID));
        assertFalse(mKeyStore.contains(TEST_KEYNAME, Process.WIFI_UID));
        assertFalse(mKeyStore.contains(TEST_KEYNAME));
    }

    public void testImport_Failure_BadEncoding() throws Exception {
        mKeyStore.password(TEST_PASSWD);

        assertFalse("Invalid DER-encoded key should not be imported", mKeyStore.importKey(
                TEST_KEYNAME, TEST_DATA, KeyStore.UID_SELF, KeyStore.FLAG_ENCRYPTED));
        assertFalse(mKeyStore.contains(TEST_KEYNAME));
        assertFalse(mKeyStore.contains(TEST_KEYNAME, Process.WIFI_UID));
    }

    public void testSign_Success() throws Exception {
        mKeyStore.password(TEST_PASSWD);

        assertTrue(mKeyStore.generate(TEST_KEYNAME, KeyStore.UID_SELF, NativeCrypto.EVP_PKEY_RSA,
                    1024, KeyStore.FLAG_ENCRYPTED, null));
        assertTrue(mKeyStore.contains(TEST_KEYNAME));
        final byte[] signature = mKeyStore.sign(TEST_KEYNAME, TEST_DATA);

        assertNotNull("Signature should not be null", signature);
    }

    public void testVerify_Success() throws Exception {
        mKeyStore.password(TEST_PASSWD);

        assertTrue(mKeyStore.generate(TEST_KEYNAME, KeyStore.UID_SELF, NativeCrypto.EVP_PKEY_RSA,
                    1024, KeyStore.FLAG_ENCRYPTED, null));
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
        mKeyStore.password(TEST_PASSWD);

        assertNull("Should not be able to sign without first generating keys",
                mKeyStore.sign(TEST_KEYNAME, TEST_DATA));
    }

    public void testGrant_Generated_Success() throws Exception {
        assertTrue("Password should work for keystore",
                mKeyStore.password(TEST_PASSWD));

        assertTrue("Should be able to generate key for testcase",
                mKeyStore.generate(TEST_KEYNAME, KeyStore.UID_SELF, NativeCrypto.EVP_PKEY_RSA,
                        1024, KeyStore.FLAG_ENCRYPTED, null));

        assertTrue("Should be able to grant key to other user",
                mKeyStore.grant(TEST_KEYNAME, 0));
    }

    public void testGrant_Imported_Success() throws Exception {
        assertTrue("Password should work for keystore", mKeyStore.password(TEST_PASSWD));

        assertTrue("Should be able to import key for testcase", mKeyStore.importKey(TEST_KEYNAME,
                PRIVKEY_BYTES, KeyStore.UID_SELF, KeyStore.FLAG_ENCRYPTED));

        assertTrue("Should be able to grant key to other user", mKeyStore.grant(TEST_KEYNAME, 0));
    }

    public void testGrant_NoKey_Failure() throws Exception {
        assertTrue("Should be able to unlock keystore for test",
                mKeyStore.password(TEST_PASSWD));

        assertFalse("Should not be able to grant without first initializing the keystore",
                mKeyStore.grant(TEST_KEYNAME, 0));
    }

    public void testGrant_NotInitialized_Failure() throws Exception {
        assertFalse("Should not be able to grant without first initializing the keystore",
                mKeyStore.grant(TEST_KEYNAME, 0));
    }

    public void testUngrant_Generated_Success() throws Exception {
        assertTrue("Password should work for keystore",
                mKeyStore.password(TEST_PASSWD));

        assertTrue("Should be able to generate key for testcase",
                mKeyStore.generate(TEST_KEYNAME, KeyStore.UID_SELF, NativeCrypto.EVP_PKEY_RSA,
                        1024, KeyStore.FLAG_ENCRYPTED, null));

        assertTrue("Should be able to grant key to other user",
                mKeyStore.grant(TEST_KEYNAME, 0));

        assertTrue("Should be able to ungrant key to other user",
                mKeyStore.ungrant(TEST_KEYNAME, 0));
    }

    public void testUngrant_Imported_Success() throws Exception {
        assertTrue("Password should work for keystore",
                mKeyStore.password(TEST_PASSWD));

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
                mKeyStore.password(TEST_PASSWD));

        assertTrue("Should be able to generate key for testcase",
                mKeyStore.generate(TEST_KEYNAME, KeyStore.UID_SELF, NativeCrypto.EVP_PKEY_RSA,
                        1024, KeyStore.FLAG_ENCRYPTED, null));

        assertFalse("Should not be able to revoke not existent grant",
                mKeyStore.ungrant(TEST_KEYNAME, 0));
    }

    public void testUngrant_DoubleUngrant_Failure() throws Exception {
        assertTrue("Password should work for keystore",
                mKeyStore.password(TEST_PASSWD));

        assertTrue("Should be able to generate key for testcase",
                mKeyStore.generate(TEST_KEYNAME, KeyStore.UID_SELF, NativeCrypto.EVP_PKEY_RSA,
                        1024, KeyStore.FLAG_ENCRYPTED, null));

        assertTrue("Should be able to grant key to other user",
                mKeyStore.grant(TEST_KEYNAME, 0));

        assertTrue("Should be able to ungrant key to other user",
                mKeyStore.ungrant(TEST_KEYNAME, 0));

        assertFalse("Should fail to ungrant key to other user second time",
                mKeyStore.ungrant(TEST_KEYNAME, 0));
    }

    public void testUngrant_DoubleGrantUngrant_Failure() throws Exception {
        assertTrue("Password should work for keystore",
                mKeyStore.password(TEST_PASSWD));

        assertTrue("Should be able to generate key for testcase",
                mKeyStore.generate(TEST_KEYNAME, KeyStore.UID_SELF, NativeCrypto.EVP_PKEY_RSA,
                        1024, KeyStore.FLAG_ENCRYPTED, null));

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
        assertTrue(mKeyStore.password(TEST_PASSWD));

        assertFalse(mKeyStore.contains(TEST_KEYNAME));

        assertTrue(mKeyStore.generate(TEST_KEYNAME, KeyStore.UID_SELF, NativeCrypto.EVP_PKEY_RSA,
                    1024, KeyStore.FLAG_ENCRYPTED, null));

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
        assertTrue(mKeyStore.password(TEST_PASSWD));

        assertFalse(mKeyStore.contains(TEST_KEYNAME));

        assertTrue(mKeyStore.generate(TEST_KEYNAME, KeyStore.UID_SELF, NativeCrypto.EVP_PKEY_RSA,
                    1024, KeyStore.FLAG_ENCRYPTED, null));

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
                mKeyStore.password(TEST_PASSWD));

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
                mKeyStore.password(TEST_PASSWD));

        assertTrue("Should be able to import key when unlocked", mKeyStore.importKey(TEST_KEYNAME,
                PRIVKEY_BYTES, KeyStore.UID_SELF, KeyStore.FLAG_ENCRYPTED));

        assertEquals("-1 should be returned for non-existent key",
                -1L, mKeyStore.getmtime(TEST_KEYNAME2));
    }
}
