/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.adb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.provider.Settings;
import android.util.Log;

import androidx.test.InstrumentationRegistry;

import com.android.server.FgThread;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.File;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(JUnit4.class)
public final class AdbDebuggingManagerTest {

    private static final String TAG = "AdbDebuggingManagerTest";

    // This component is passed to the AdbDebuggingManager to act as the activity that can confirm
    // unknown adb keys.  An overlay package was first attempted to override the
    // config_customAdbPublicKeyConfirmationComponent config, but the value from that package was
    // not being read.
    private static final String ADB_CONFIRM_COMPONENT =
            "com.android.frameworks.servicestests/"
                    + "com.android.server.adb.AdbDebuggingManagerTestActivity";

    // The base64 encoding of the values 'test key 1' and 'test key 2'.
    private static final String TEST_KEY_1 = "dGVzdCBrZXkgMQo=";
    private static final String TEST_KEY_2 = "dGVzdCBrZXkgMgo=";

    // This response is received from the AdbDebuggingHandler when the key is allowed to connect
    private static final String RESPONSE_KEY_ALLOWED = "OK";
    // This response is received from the AdbDebuggingHandler when the key is not allowed to connect
    private static final String RESPONSE_KEY_DENIED = "NO";

    // wait up to 5 seconds for any blocking queries
    private static final long TIMEOUT = 5000;
    private static final TimeUnit TIMEOUT_TIME_UNIT = TimeUnit.MILLISECONDS;

    private Context mContext;
    private AdbDebuggingManager mManager;
    private AdbDebuggingManager.AdbDebuggingThread mThread;
    private AdbDebuggingManager.AdbDebuggingHandler mHandler;
    private AdbDebuggingManager.AdbKeyStore mKeyStore;
    private BlockingQueue<TestResult> mBlockingQueue;
    private long mOriginalAllowedConnectionTime;
    private File mKeyFile;

    @Before
    public void setUp() throws Exception {
        mContext = InstrumentationRegistry.getContext();
        mManager = new AdbDebuggingManager(mContext, ADB_CONFIRM_COMPONENT);
        mKeyFile = new File(mContext.getFilesDir(), "test_adb_keys.xml");
        if (mKeyFile.exists()) {
            mKeyFile.delete();
        }
        mThread = new AdbDebuggingThreadTest();
        mKeyStore = mManager.new AdbKeyStore(mKeyFile);
        mHandler = mManager.new AdbDebuggingHandler(FgThread.get().getLooper(), mThread, mKeyStore);
        mOriginalAllowedConnectionTime = mKeyStore.getAllowedConnectionTime();
        mBlockingQueue = new ArrayBlockingQueue<>(1);

    }

    @After
    public void tearDown() throws Exception {
        mKeyStore.deleteKeyStore();
        setAllowedConnectionTime(mOriginalAllowedConnectionTime);
    }

    /**
     * Sets the allowed connection time within which a subsequent connection from a key for which
     * the user selected the 'Always allow' option will be allowed without user interaction.
     */
    private void setAllowedConnectionTime(long connectionTime) {
        Settings.Global.putLong(mContext.getContentResolver(),
                Settings.Global.ADB_ALLOWED_CONNECTION_TIME, connectionTime);
    };

    @Test
    public void testAllowNewKeyOnce() throws Exception {
        // Verifies the behavior when a new key first attempts to connect to a device. During the
        // first connection the ADB confirmation activity should be launched to prompt the user to
        // allow the connection with an option to always allow connections from this key.

        // Verify if the user allows the key but does not select the option to 'always
        // allow' that the connection is allowed but the key is not stored.
        runAdbTest(TEST_KEY_1, true, false, false);
    }

    @Test
    public void testDenyNewKey() throws Exception {
        // Verifies if the user does not allow the key then the connection is not allowed and the
        // key is not stored.
        runAdbTest(TEST_KEY_1, false, false, false);
    }

    @Test
    public void testDisconnectAlwaysAllowKey() throws Exception {
        // When a key is disconnected from a device ADB should send a disconnect message; this
        // message should trigger an update of the last connection time for the currently connected
        // key.

        // Allow a connection from a new key with the 'Always allow' option selected.
        runAdbTest(TEST_KEY_1, true, true, false);

        // Get the last connection time for the currently connected key to verify that it is updated
        // after the disconnect.
        long lastConnectionTime = mKeyStore.getLastConnectionTime(TEST_KEY_1);

        // Sleep for a small amount of time to ensure a difference can be observed in the last
        // connection time after a disconnect.
        Thread.sleep(10);

        // Send the disconnect message for the currently connected key to trigger an update of the
        // last connection time.
        mHandler.obtainMessage(
                AdbDebuggingManager.AdbDebuggingHandler.MESSAGE_ADB_DISCONNECT).sendToTarget();

        // Use a latch to ensure the test does not exit untill the Runnable has been processed.
        CountDownLatch latch = new CountDownLatch(1);

        // Post a new Runnable to the handler to ensure it runs after the disconnect message is
        // processed.
        mHandler.post(() -> {
            assertNotEquals(
                    "The last connection time was not updated after the disconnect",
                    lastConnectionTime,
                    mKeyStore.getLastConnectionTime(TEST_KEY_1));
            latch.countDown();
        });
        if (!latch.await(TIMEOUT, TIMEOUT_TIME_UNIT)) {
            fail("The Runnable to verify the last connection time was updated did not complete "
                    + "within the timeout period");
        }
    }

    @Test
    public void testDisconnectAllowedOnceKey() throws Exception {
        // When a key is disconnected ADB should send a disconnect message; this message should
        // essentially result in a noop for keys that the user only allows once since the last
        // connection time is not maintained for these keys.

        // Allow a connection from a new key with the 'Always allow' option set to false
        runAdbTest(TEST_KEY_1, true, false, false);

        // Send the disconnect message for the currently connected key.
        mHandler.obtainMessage(
                AdbDebuggingManager.AdbDebuggingHandler.MESSAGE_ADB_DISCONNECT).sendToTarget();

        // Verify that the disconnected key is not automatically allowed on a subsequent connection.
        runAdbTest(TEST_KEY_1, true, false, false);
    }

    @Test
    public void testAlwaysAllowConnectionFromKey() throws Exception {
        // Verifies when the user selects the 'Always allow' option for the current key that
        // subsequent connection attempts from that key are allowed.

        // Allow a connection from a new key with the 'Always allow' option selected.
        runAdbTest(TEST_KEY_1, true, true, false);

        // Next attempt another connection with the same key and verify that the activity to prompt
        // the user to accept the key is not launched.
        runAdbTest(TEST_KEY_1, true, true, true);

        // Verify that a different key is not automatically allowed.
        runAdbTest(TEST_KEY_2, false, false, false);
    }

    @Test
    public void testOriginalAlwaysAllowBehavior() throws Exception {
        // If the Settings.Global.ADB_ALLOWED_CONNECTION_TIME setting is set to 0 then the original
        // behavior of 'Always allow' should be restored.

        // Accept the test key with the 'Always allow' option selected.
        runAdbTest(TEST_KEY_1, true, true, false);

        // Set the connection time to 0 to restore the original behavior.
        setAllowedConnectionTime(0);

        // Set the last connection time to the test key to a very small value to ensure it would
        // fail the new test but would be allowed with the original behavior.
        mKeyStore.setLastConnectionTime(TEST_KEY_1, 1);

        // Run the test with the key and verify that the connection is automatically allowed.
        runAdbTest(TEST_KEY_1, true, true, true);
    }

    @Test
    public void testLastConnectionTimeUpdatedByScheduledJob() throws Exception {
        // If a development device is left connected to a system beyond the allowed connection time
        // a reboot of the device while connected could make it appear as though the last connection
        // time is beyond the allowed window. A scheduled job runs daily while a key is connected
        // to update the last connection time to the current time; this ensures if the device is
        // rebooted while connected to a system the last connection time should be within 24 hours.

        // Allow the key to connect with the 'Always allow' option selected
        runAdbTest(TEST_KEY_1, true, true, false);

        // Get the current last connection time for comparison after the scheduled job is run
        long lastConnectionTime = mKeyStore.getLastConnectionTime(TEST_KEY_1);

        // Sleep a small amount of time to ensure that the updated connection time changes
        Thread.sleep(10);

        // Send a message to the handler to update the last connection time for the active key
        mHandler.obtainMessage(
                AdbDebuggingManager.AdbDebuggingHandler.MESSAGE_ADB_UPDATE_KEY_CONNECTION_TIME)
                .sendToTarget();

        // Post a Runnable to the handler to ensure it runs after the update key connection time
        // message is processed.
        CountDownLatch latch = new CountDownLatch(1);
        mHandler.post(() -> {
            assertNotEquals(
                    "The last connection time of the key was not updated after the update key "
                            + "connection time message",
                    lastConnectionTime, mKeyStore.getLastConnectionTime(TEST_KEY_1));
            latch.countDown();
        });
        if (!latch.await(TIMEOUT, TIMEOUT_TIME_UNIT)) {
            fail("The Runnable to verify the last connection time was updated did not complete "
                    + "within the timeout period");
        }
    }

    @Test
    public void testKeystorePersisted() throws Exception {
        // After any updates are made to the key store a message should be sent to persist the
        // key store. This test verifies that a key that is always allowed is persisted in the key
        // store along with its last connection time.

        // Allow the key to connect with the 'Always allow' option selected
        runAdbTest(TEST_KEY_1, true, true, false);

        // Send a message to the handler to persist the updated keystore.
        mHandler.obtainMessage(
                AdbDebuggingManager.AdbDebuggingHandler.MESSAGE_ADB_PERSIST_KEY_STORE)
                .sendToTarget();

        // Post a Runnable to the handler to ensure the persist key store message has been processed
        // using a new AdbKeyStore backed by the key file.
        mHandler.post(() -> assertTrue(
                "The key with the 'Always allow' option selected was not persisted in the keystore",
                mManager.new AdbKeyStore(mKeyFile).isKeyAuthorized(TEST_KEY_1)));

        // Get the current last connection time to ensure it is updated in the persisted keystore.
        long lastConnectionTime = mKeyStore.getLastConnectionTime(TEST_KEY_1);

        // Sleep a small amount of time to ensure the last connection time is updated.
        Thread.sleep(10);

        // Send a message to the handler to update the last connection time for the active key.
        mHandler.obtainMessage(
                AdbDebuggingManager.AdbDebuggingHandler.MESSAGE_ADB_UPDATE_KEY_CONNECTION_TIME)
                .sendToTarget();

        // Persist the updated last connection time.
        mHandler.obtainMessage(
                AdbDebuggingManager.AdbDebuggingHandler.MESSAGE_ADB_PERSIST_KEY_STORE)
                .sendToTarget();

        // Post a Runnable with a new key store backed by the key file to verify that the last
        // connection time obtained above is different from the persisted updated value.
        CountDownLatch latch = new CountDownLatch(1);
        mHandler.post(() -> {
            assertNotEquals(
                    "The last connection time in the key file was not updated after the update "
                            + "connection time message", lastConnectionTime,
                    mManager.new AdbKeyStore(mKeyFile).getLastConnectionTime(TEST_KEY_1));
            latch.countDown();
        });
        if (!latch.await(TIMEOUT, TIMEOUT_TIME_UNIT)) {
            fail("The Runnable to verify the last connection time was updated did not complete "
                    + "within the timeout period");
        }
    }

    @Test
    public void testAdbClearRemovesActiveKey() throws Exception {
        // If the user selects the option to 'Revoke USB debugging authorizations' while an 'Always
        // allow' key is connected that key should be deleted as well.

        // Allow the key to connect with the 'Always allow' option selected
        runAdbTest(TEST_KEY_1, true, true, false);

        // Send a message to the handler to clear the adb authorizations.
        mHandler.obtainMessage(
                AdbDebuggingManager.AdbDebuggingHandler.MESSAGE_ADB_CLEAR).sendToTarget();

        // Send a message to disconnect the currently connected key
        mHandler.obtainMessage(
                AdbDebuggingManager.AdbDebuggingHandler.MESSAGE_ADB_DISCONNECT).sendToTarget();

        // Post a Runnable to ensure the disconnect has completed to verify the 'Always allow' key
        // that was connected when the clear was sent requires authorization.
        CountDownLatch latch = new CountDownLatch(1);
        mHandler.post(() -> {
            assertFalse(
                    "The currently connected 'always allow' key should not be authorized after an"
                            + " adb"
                            + " clear message.",
                    mKeyStore.isKeyAuthorized(TEST_KEY_1));
            latch.countDown();
        });
        if (!latch.await(TIMEOUT, TIMEOUT_TIME_UNIT)) {
            fail("The Runnable to verify the key is not authorized did not complete within the "
                    + "timeout period");
        }
    }

    @Test
    public void testAdbGrantRevokedIfLastConnectionBeyondAllowedTime() throws Exception {
        // If the user selects the 'Always allow' option then subsequent connections from the key
        // will be allowed as long as the connection is within the allowed window. Once the last
        // connection time is beyond this window the user should be prompted to allow the key again.

        // Allow the key to connect with the 'Always allow' option selected
        runAdbTest(TEST_KEY_1, true, true, false);

        // Set the allowed window to a small value to ensure the time is beyond the allowed window.
        setAllowedConnectionTime(1);

        // Sleep for a small amount of time to exceed the allowed window.
        Thread.sleep(10);

        // A new connection from this key should prompt the user again.
        runAdbTest(TEST_KEY_1, true, true, false);
    }

    @Test
    public void testLastConnectionTimeCannotBeSetBack() throws Exception {
        // When a device is first booted there is a possibility that the system time will be set to
        // the build time of the system image. If a device is connected to a system during a reboot
        // this could cause the connection time to be set in the past; if the device time is not
        // corrected before the device is disconnected then a subsequent connection with the time
        // corrected would appear as though the last connection time was beyond the allowed window,
        // and the user would be required to authorize the connection again. This test verifies that
        // the AdbKeyStore does not update the last connection time if it is less than the
        // previously written connection time.

        // Allow the key to connect with the 'Always allow' option selected
        runAdbTest(TEST_KEY_1, true, true, false);

        // Get the last connection time that was written to the key store.
        long lastConnectionTime = mKeyStore.getLastConnectionTime(TEST_KEY_1);

        // Attempt to set the last connection time to 1970
        mKeyStore.setLastConnectionTime(TEST_KEY_1, 0);
        assertEquals(
                "The last connection time in the adb key store should not be set to a value less "
                        + "than the previous connection time",
                lastConnectionTime, mKeyStore.getLastConnectionTime(TEST_KEY_1));

        // Attempt to set the last connection time just beyond the allowed window.
        mKeyStore.setLastConnectionTime(TEST_KEY_1,
                Math.max(0, lastConnectionTime - (mKeyStore.getAllowedConnectionTime() + 1)));
        assertEquals(
                "The last connection time in the adb key store should not be set to a value less "
                        + "than the previous connection time",
                lastConnectionTime, mKeyStore.getLastConnectionTime(TEST_KEY_1));
    }

    /**
     * Runs an adb test with the provided configuration.
     *
     * @param key The base64 encoding of the key to be used during the test.
     * @param allowKey boolean indicating whether the key should be allowed to connect.
     * @param alwaysAllow boolean indicating whether the 'Always allow' option should be selected.
     * @param autoAllowExpected boolean indicating whether the key is expected to be automatically
     *                          allowed without user interaction.
     */
    private void runAdbTest(String key, boolean allowKey, boolean alwaysAllow,
            boolean autoAllowExpected) throws Exception {
        // if the key should not be automatically allowed then set up the activity
        if (!autoAllowExpected) {
            new AdbDebuggingManagerTestActivity.Configurator()
                    .setExpectedKey(key)
                    .setAllowKey(allowKey)
                    .setAlwaysAllow(alwaysAllow)
                    .setHandler(mHandler)
                    .setBlockingQueue(mBlockingQueue);
        }
        // send the message indicating a new key is attempting to connect
        mHandler.obtainMessage(AdbDebuggingManager.AdbDebuggingHandler.MESSAGE_ADB_CONFIRM,
                key).sendToTarget();
        // if the key should not be automatically allowed then the ADB public key confirmation
        // activity should be launched
        if (!autoAllowExpected) {
            TestResult activityResult = mBlockingQueue.poll(TIMEOUT, TIMEOUT_TIME_UNIT);
            assertNotNull(
                    "The ADB public key confirmation activity did not complete within the timeout"
                            + " period", activityResult);
            assertEquals("The ADB public key activity failed with result: " + activityResult,
                    TestResult.RESULT_ACTIVITY_LAUNCHED, activityResult.mReturnCode);
        }
        // If the activity was launched it should send a response back to the manager that would
        // trigger a response to the thread, or if the key is a known valid key then a response
        // should be sent back without requiring interaction with the activity.
        TestResult threadResult = mBlockingQueue.poll(TIMEOUT, TIMEOUT_TIME_UNIT);
        assertNotNull("A response was not sent to the thread within the timeout period",
                threadResult);
        // verify that the result is an expected message from the thread
        assertEquals("An unexpected result was received: " + threadResult,
                TestResult.RESULT_RESPONSE_RECEIVED, threadResult.mReturnCode);
        assertEquals("The manager did not send the proper response for allowKey = " + allowKey,
                allowKey ? RESPONSE_KEY_ALLOWED : RESPONSE_KEY_DENIED, threadResult.mMessage);
        // if the key is not allowed or not always allowed verify it is not in the key store
        if (!allowKey || !alwaysAllow) {
            assertFalse(
                    "The key should not be allowed automatically on subsequent connection attempts",
                    mKeyStore.isKeyAuthorized(key));
        }
    }

    /**
     * Helper class that extends AdbDebuggingThread to receive the response from AdbDebuggingManager
     * indicating whether the key should be allowed to  connect.
     */
    class AdbDebuggingThreadTest extends AdbDebuggingManager.AdbDebuggingThread {
        AdbDebuggingThreadTest() {
            mManager.super();
        }

        @Override
        public void sendResponse(String msg) {
            TestResult result = new TestResult(TestResult.RESULT_RESPONSE_RECEIVED, msg);
            try {
                mBlockingQueue.put(result);
            } catch (InterruptedException e) {
                Log.e(TAG,
                        "Caught an InterruptedException putting the result in the queue: " + result,
                        e);
            }
        }
    }

    /**
     * Contains the result for the current portion of the test along with any corresponding
     * messages.
     */
    public static class TestResult {
        public int mReturnCode;
        public String mMessage;

        public static final int RESULT_ACTIVITY_LAUNCHED = 1;
        public static final int RESULT_UNEXPECTED_KEY = 2;
        public static final int RESULT_RESPONSE_RECEIVED = 3;

        public TestResult(int returnCode) {
            this(returnCode, null);
        }

        public TestResult(int returnCode, String message) {
            mReturnCode = returnCode;
            mMessage = message;
        }

        @Override
        public String toString() {
            return "{mReturnCode = " + mReturnCode + ", mMessage = " + mMessage + "}";
        }
    }
}
