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

package android.security.tests;

import android.app.Activity;
import android.security.KeyStore;
import android.test.ActivityUnitTestCase;
import android.test.suitebuilder.annotation.MediumTest;
import java.nio.charset.Charsets;
import java.util.Arrays;
import java.util.HashSet;

/**
 * Junit / Instrumentation test case for KeyStore class
 *
 * Running the test suite:
 *
 *  adb shell am instrument -w android.security.tests/.KeyStoreTestRunner
 */
@MediumTest
public class KeyStoreTest extends ActivityUnitTestCase<Activity> {
    private static final String TEST_PASSWD = "12345678";
    private static final String TEST_PASSWD2 = "87654321";
    private static final String TEST_KEYNAME = "testkey";
    private static final String TEST_KEYNAME1 = "testkey1";
    private static final String TEST_KEYNAME2 = "testkey2";
    private static final byte[] TEST_KEYVALUE = "test value".getBytes(Charsets.UTF_8);

    // "Hello, World" in Chinese
    private static final String TEST_I18N_KEY = "\u4F60\u597D, \u4E16\u754C";
    private static final byte[] TEST_I18N_VALUE = TEST_I18N_KEY.getBytes(Charsets.UTF_8);

    private KeyStore mKeyStore = null;

    public KeyStoreTest() {
        super(Activity.class);
    }

    @Override
    protected void setUp() throws Exception {
        mKeyStore = KeyStore.getInstance();
        if (mKeyStore.state() != KeyStore.State.UNINITIALIZED) {
            mKeyStore.reset();
        }
        assertEquals(KeyStore.State.UNINITIALIZED, mKeyStore.state());
        super.setUp();
    }

    @Override
    protected void tearDown() throws Exception {
        mKeyStore.reset();
        super.tearDown();
    }

    public void teststate() throws Exception {
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
        assertTrue(mKeyStore.put(TEST_KEYNAME, TEST_KEYVALUE));
        assertTrue(Arrays.equals(TEST_KEYVALUE, mKeyStore.get(TEST_KEYNAME)));
    }

    public void testPut() throws Exception {
        assertNull(mKeyStore.get(TEST_KEYNAME));
        assertFalse(mKeyStore.put(TEST_KEYNAME, TEST_KEYVALUE));
        assertFalse(mKeyStore.contains(TEST_KEYNAME));
        mKeyStore.password(TEST_PASSWD);
        assertTrue(mKeyStore.put(TEST_KEYNAME, TEST_KEYVALUE));
        assertTrue(Arrays.equals(TEST_KEYVALUE, mKeyStore.get(TEST_KEYNAME)));
    }

    public void testI18n() throws Exception {
        assertFalse(mKeyStore.put(TEST_I18N_KEY, TEST_I18N_VALUE));
        assertFalse(mKeyStore.contains(TEST_I18N_KEY));
        mKeyStore.password(TEST_I18N_KEY);
        assertTrue(mKeyStore.put(TEST_I18N_KEY, TEST_I18N_VALUE));
        assertTrue(mKeyStore.contains(TEST_I18N_KEY));
    }

    public void testDelete() throws Exception {
        assertTrue(mKeyStore.delete(TEST_KEYNAME));
        mKeyStore.password(TEST_PASSWD);
        assertTrue(mKeyStore.delete(TEST_KEYNAME));

        mKeyStore.put(TEST_KEYNAME, TEST_KEYVALUE);
        assertTrue(Arrays.equals(TEST_KEYVALUE, mKeyStore.get(TEST_KEYNAME)));
        assertTrue(mKeyStore.delete(TEST_KEYNAME));
        assertNull(mKeyStore.get(TEST_KEYNAME));
    }

    public void testContains() throws Exception {
        assertFalse(mKeyStore.contains(TEST_KEYNAME));

        mKeyStore.password(TEST_PASSWD);
        assertFalse(mKeyStore.contains(TEST_KEYNAME));

        mKeyStore.put(TEST_KEYNAME, TEST_KEYVALUE);
        assertTrue(mKeyStore.contains(TEST_KEYNAME));
    }

    public void testSaw() throws Exception {
        String[] emptyResult = mKeyStore.saw(TEST_KEYNAME);
        assertNotNull(emptyResult);
        assertEquals(0, emptyResult.length);

        mKeyStore.password(TEST_PASSWD);
        mKeyStore.put(TEST_KEYNAME1, TEST_KEYVALUE);
        mKeyStore.put(TEST_KEYNAME2, TEST_KEYVALUE);

        String[] results = mKeyStore.saw(TEST_KEYNAME);
        assertEquals(new HashSet(Arrays.asList(TEST_KEYNAME1.substring(TEST_KEYNAME.length()),
                                               TEST_KEYNAME2.substring(TEST_KEYNAME.length()))),
                     new HashSet(Arrays.asList(results)));
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
        mKeyStore.put(TEST_KEYNAME, TEST_KEYVALUE);
        assertFalse(mKeyStore.isEmpty());
        mKeyStore.reset();
        assertTrue(mKeyStore.isEmpty());
    }
}
