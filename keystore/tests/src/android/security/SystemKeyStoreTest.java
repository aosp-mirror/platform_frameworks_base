/*
 * Copyright (C) 2010 The Android Open Source Project
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
import android.security.SystemKeyStore;
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
public class SystemKeyStoreTest extends ActivityUnitTestCase<Activity> {

    private static final String keyName = "TestKey";
    private static final String keyName2 = "TestKey2";
    private SystemKeyStore mSysKeyStore = null;

    public SystemKeyStoreTest() {
        super(Activity.class);
    }

    @Override
    protected void setUp() throws Exception {
        mSysKeyStore = SystemKeyStore.getInstance();
        try {
            mSysKeyStore.deleteKey(keyName);
            mSysKeyStore.deleteKey(keyName2);
        } catch (Exception e) { }
        super.setUp();
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            mSysKeyStore.deleteKey(keyName);
            mSysKeyStore.deleteKey(keyName2);
        } catch (Exception e) { }
        super.tearDown();
    }

    public void testBasicAccess() throws Exception {
        try {
            byte[] newKey = mSysKeyStore.generateNewKey(128, "AES", keyName);
            assertNotNull(newKey);
            byte[] recKey = mSysKeyStore.retrieveKey(keyName);
            assertEquals(newKey.length, recKey.length);
            for (int i = 0; i < newKey.length; i++) {
                assertEquals(newKey[i], recKey[i]);
            }
            mSysKeyStore.deleteKey(keyName);
            byte[] nullKey = mSysKeyStore.retrieveKey(keyName);
            assertNull(nullKey);

            String newKeyStr = mSysKeyStore.generateNewKeyHexString(128, "AES", keyName2);
            assertNotNull(newKeyStr);
            String recKeyStr = mSysKeyStore.retrieveKeyHexString(keyName2);
            assertEquals(newKeyStr, recKeyStr);

            mSysKeyStore.deleteKey(keyName2);
            String nullKey2 = mSysKeyStore.retrieveKeyHexString(keyName2);
            assertNull(nullKey2);
        } catch (Exception e) {
            fail();
        }
    }
}
