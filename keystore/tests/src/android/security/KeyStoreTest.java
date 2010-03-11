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
    private static final String TEST_EMPTY_PASSWD = "";
    private static final String TEST_SHORT_PASSWD = "short";
    private static final String TEST_PASSWD2 = "87654321";
    private static final String TEST_KEYNAME = "testkey";
    private static final String TEST_KEYNAME1 = "testkey1";
    private static final String TEST_KEYNAME2 = "testkey2";
    private static final String TEST_KEYVALUE = "test value";

    // "Hello, World" in Chinese
    private static final String TEST_I18N = "\u4F60\u597D, \u4E16\u754C";

    private KeyStore mKeyStore = null;

    public KeyStoreTest() {
        super(Activity.class);
    }

    @Override
    protected void setUp() throws Exception {
        mKeyStore = KeyStore.getInstance();
        if (mKeyStore.test() != KeyStore.UNINITIALIZED) mKeyStore.reset();
        assertEquals(KeyStore.UNINITIALIZED, mKeyStore.test());
        super.setUp();
    }

    @Override
    protected void tearDown() throws Exception {
        mKeyStore.reset();
        super.tearDown();
    }

    public void testTest() throws Exception {
        assertEquals(KeyStore.UNINITIALIZED, mKeyStore.test());
    }

    public void testPassword() throws Exception {
        //assertFalse(mKeyStore.password(TEST_EMPTY_PASSWD));
        //assertFalse(mKeyStore.password(TEST_SHORT_PASSWD));

        assertTrue(mKeyStore.password(TEST_PASSWD));
        assertEquals(KeyStore.NO_ERROR, mKeyStore.test());

        assertFalse(mKeyStore.password(TEST_PASSWD2, TEST_PASSWD2));
        //assertFalse(mKeyStore.password(TEST_PASSWD, TEST_SHORT_PASSWD));

        assertTrue(mKeyStore.password(TEST_PASSWD, TEST_PASSWD2));
    }

    public void testPut() throws Exception {
        assertFalse(mKeyStore.put(TEST_KEYNAME, TEST_KEYVALUE));
        assertFalse(mKeyStore.contains(TEST_KEYNAME));
        mKeyStore.password(TEST_PASSWD);
        assertTrue(mKeyStore.put(TEST_KEYNAME, TEST_KEYVALUE));
    }

    public void testI18n() throws Exception {
        assertFalse(mKeyStore.put(TEST_I18N, TEST_I18N));
        assertFalse(mKeyStore.contains(TEST_I18N));
        mKeyStore.password(TEST_I18N);
        assertTrue(mKeyStore.put(TEST_I18N, TEST_I18N));
        assertTrue(mKeyStore.contains(TEST_I18N));
    }

    public void testDelete() throws Exception {
        assertTrue(mKeyStore.delete(TEST_KEYNAME));
        mKeyStore.password(TEST_PASSWD);
        assertTrue(mKeyStore.delete(TEST_KEYNAME));

        mKeyStore.put(TEST_KEYNAME, TEST_KEYVALUE);
        assertTrue(mKeyStore.delete(TEST_KEYNAME));
    }

    public void testContains() throws Exception {
        assertFalse(mKeyStore.contains(TEST_KEYNAME));

        mKeyStore.password(TEST_PASSWD);
        assertFalse(mKeyStore.contains(TEST_KEYNAME));

        mKeyStore.put(TEST_KEYNAME, TEST_KEYVALUE);
        assertTrue(mKeyStore.contains(TEST_KEYNAME));
    }

    public void testSaw() throws Exception {
        String[] results = mKeyStore.saw(TEST_KEYNAME);
        assertEquals(0, results.length);

        mKeyStore.password(TEST_PASSWD);
        mKeyStore.put(TEST_KEYNAME1, TEST_KEYVALUE);
        mKeyStore.put(TEST_KEYNAME2, TEST_KEYVALUE);

        results = mKeyStore.saw(TEST_KEYNAME);
        assertEquals(2, results.length);
    }

    public void testLock() throws Exception {
        assertFalse(mKeyStore.lock());

        mKeyStore.password(TEST_PASSWD);
        assertEquals(KeyStore.NO_ERROR, mKeyStore.test());

        assertTrue(mKeyStore.lock());
        assertEquals(KeyStore.LOCKED, mKeyStore.test());
    }

    public void testUnlock() throws Exception {
        mKeyStore.password(TEST_PASSWD);
        assertEquals(KeyStore.NO_ERROR, mKeyStore.test());
        mKeyStore.lock();

        assertFalse(mKeyStore.unlock(TEST_PASSWD2));
        assertTrue(mKeyStore.unlock(TEST_PASSWD));
    }
}
