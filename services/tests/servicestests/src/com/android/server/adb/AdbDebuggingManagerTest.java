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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.debug.AdbManager;
import android.debug.IAdbManager;
import android.os.ServiceManager;
import android.provider.Settings;
import android.util.Log;

import androidx.test.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
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
    private static final String TEST_KEY_1 = "dGVzdCBrZXkgMQo= test@android.com";
    private static final String TEST_KEY_2 = "dGVzdCBrZXkgMgo= test@android.com";

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
    private File mAdbKeyXmlFile;
    private File mAdbKeyFile;
    private FakeTicker mFakeTicker;

    @Before
    public void setUp() throws Exception {
        mContext = InstrumentationRegistry.getContext();
        mAdbKeyFile = new File(mContext.getFilesDir(), "adb_keys");
        if (mAdbKeyFile.exists()) {
            mAdbKeyFile.delete();
        }
        mAdbKeyXmlFile = new File(mContext.getFilesDir(), "test_adb_keys.xml");
        if (mAdbKeyXmlFile.exists()) {
            mAdbKeyXmlFile.delete();
        }

        mFakeTicker = new FakeTicker();
        // Set the ticker time to October 22, 2008 (the day the T-Mobile G1 was released)
        mFakeTicker.advance(1224658800L);

        mThread = new AdbDebuggingThreadTest();
        mManager = new AdbDebuggingManager(
                mContext, ADB_CONFIRM_COMPONENT, mAdbKeyFile, mAdbKeyXmlFile, mThread, mFakeTicker);

        mHandler = mManager.mHandler;
        mThread.setHandler(mHandler);

        mHandler.initKeyStore();
        mKeyStore = mHandler.mAdbKeyStore;

        mOriginalAllowedConnectionTime = mKeyStore.getAllowedConnectionTime();
        mBlockingQueue = new ArrayBlockingQueue<>(1);
    }

    @After
    public void tearDown() throws Exception {
        mKeyStore.deleteKeyStore();
        setAllowedConnectionTime(mOriginalAllowedConnectionTime);
        dropShellPermissionIdentity();
    }

    /**
     * Sets the allowed connection time within which a subsequent connection from a key for which
     * the user selected the 'Always allow' option will be allowed without user interaction.
     */
    private void setAllowedConnectionTime(long connectionTime) {
        Settings.Global.putLong(mContext.getContentResolver(),
                Settings.Global.ADB_ALLOWED_CONNECTION_TIME, connectionTime);
    }

    @Test
    public void testAllowNewKeyOnce() throws Exception {
        // Verifies the behavior when a new key first attempts to connect to a device. During the
        // first connection the ADB confirmation activity should be launched to prompt the user to
        // allow the connection with an option to always allow connections from this key.

        // Verify if the user allows the key but does not select the option to 'always
        // allow' that the connection is allowed but the key is not stored.
        runAdbTest(TEST_KEY_1, true, false, false);

        // Persist the keystore to ensure that the key is not written to the adb_keys file.
        persistKeyStore();
        assertFalse(
                "A key for which the 'always allow' option is not selected must not be written "
                        + "to the adb_keys file",
                isKeyInFile(TEST_KEY_1, mAdbKeyFile));
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

        // Advance the clock by 10ms to ensure there's a difference
        mFakeTicker.advance(10 * 1_000_000);

        // Send the disconnect message for the currently connected key to trigger an update of the
        // last connection time.
        disconnectKey(TEST_KEY_1);
        assertEquals(
                "The last connection time was not updated after the disconnect",
                mFakeTicker.currentTimeMillis(),
                mKeyStore.getLastConnectionTime(TEST_KEY_1));
    }

    @Test
    public void testDisconnectAllowedOnceKey() throws Exception {
        // When a key is disconnected ADB should send a disconnect message; this message should
        // essentially result in a noop for keys that the user only allows once since the last
        // connection time is not maintained for these keys.

        // Allow a connection from a new key with the 'Always allow' option set to false
        runAdbTest(TEST_KEY_1, true, false, false);

        // Send the disconnect message for the currently connected key.
        disconnectKey(TEST_KEY_1);

        // Verify that the disconnected key is not automatically allowed on a subsequent connection.
        runAdbTest(TEST_KEY_1, true, false, false);
    }

    @Test
    public void testAlwaysAllowConnectionFromKey() throws Exception {
        // Verifies when the user selects the 'Always allow' option for the current key that
        // subsequent connection attempts from that key are allowed.

        // Allow a connection from a new key with the 'Always allow' option selected.
        runAdbTest(TEST_KEY_1, true, true, false);

        // Send a persist keystore message to force the key to be written to the adb_keys file
        persistKeyStore();

        // Verify the key is in the adb_keys file to ensure subsequent connections are allowed by
        // adbd.
        assertTrue("The key was not in the adb_keys file after persisting the keystore",
                isKeyInFile(TEST_KEY_1, mAdbKeyFile));
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

        // Verify that the key is in the adb_keys file to ensure subsequent connections are
        // automatically allowed by adbd.
        persistKeyStore();
        assertTrue("The key was not in the adb_keys file after persisting the keystore",
                isKeyInFile(TEST_KEY_1, mAdbKeyFile));
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

        // Advance a small amount of time to ensure that the updated connection time changes
        mFakeTicker.advance(10);

        // Send a message to the handler to update the last connection time for the active key
        updateKeyStore();
        assertNotEquals(
                "The last connection time of the key was not updated after the update key "
                        + "connection time message",
                lastConnectionTime, mKeyStore.getLastConnectionTime(TEST_KEY_1));
    }

    @Test
    public void testKeystorePersisted() throws Exception {
        // After any updates are made to the key store a message should be sent to persist the
        // key store. This test verifies that a key that is always allowed is persisted in the key
        // store along with its last connection time.

        // Allow the key to connect with the 'Always allow' option selected
        runAdbTest(TEST_KEY_1, true, true, false);

        // Send a message to the handler to persist the updated keystore and verify a new key store
        // backed by the XML file contains the key.
        persistKeyStore();
        assertTrue(
                "The key with the 'Always allow' option selected was not persisted in the keystore",
                mManager.new AdbKeyStore().isKeyAuthorized(TEST_KEY_1));

        // Get the current last connection time to ensure it is updated in the persisted keystore.
        long lastConnectionTime = mKeyStore.getLastConnectionTime(TEST_KEY_1);

        // Advance a small amount of time to ensure the last connection time is updated.
        mFakeTicker.advance(10);

        // Send a message to the handler to update the last connection time for the active key.
        updateKeyStore();

        // Persist the updated last connection time and verify a new key store backed by the XML
        // file contains the updated connection time.
        persistKeyStore();
        assertNotEquals(
                "The last connection time in the key file was not updated after the update "
                        + "connection time message", lastConnectionTime,
                mManager.new AdbKeyStore().getLastConnectionTime(TEST_KEY_1));
        // Verify that the key is in the adb_keys file
        assertTrue("The key was not in the adb_keys file after persisting the keystore",
                isKeyInFile(TEST_KEY_1, mAdbKeyFile));
    }

    @Test
    public void testAdbClearRemovesActiveKey() throws Exception {
        // If the user selects the option to 'Revoke USB debugging authorizations' while an 'Always
        // allow' key is connected that key should be deleted as well.

        // Allow the key to connect with the 'Always allow' option selected
        runAdbTest(TEST_KEY_1, true, true, false);

        // Send a message to the handler to clear the adb authorizations.
        clearKeyStore();

        // Send a message to disconnect the currently connected key
        disconnectKey(TEST_KEY_1);
        assertFalse(
                "The currently connected 'always allow' key must not be authorized after an adb"
                        + " clear message.",
                mKeyStore.isKeyAuthorized(TEST_KEY_1));

        // The key should not be in the adb_keys file after clearing the authorizations.
        assertFalse("The key must not be in the adb_keys file after clearing authorizations",
                isKeyInFile(TEST_KEY_1, mAdbKeyFile));
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

        // Advance a small amount of time to exceed the allowed window.
        mFakeTicker.advance(10);

        // The AdbKeyStore has a method to get the time of the next key expiration to ensure the
        // scheduled job runs at the time of the next expiration or after 24 hours, whichever occurs
        // first.
        assertEquals("The time of the next key expiration must be 0.", 0,
                mKeyStore.getNextExpirationTime());

        // Persist the key store and verify that the key is no longer in the adb_keys file.
        persistKeyStore();
        assertFalse(
                "The key must not be in the adb_keys file after the allowed time has elapsed.",
                isKeyInFile(TEST_KEY_1, mAdbKeyFile));
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
                "The last connection time in the adb key store must not be set to a value less "
                        + "than the previous connection time",
                lastConnectionTime, mKeyStore.getLastConnectionTime(TEST_KEY_1));

        // Attempt to set the last connection time just beyond the allowed window.
        mKeyStore.setLastConnectionTime(TEST_KEY_1,
                Math.max(0, lastConnectionTime - (mKeyStore.getAllowedConnectionTime() + 1)));
        assertEquals(
                "The last connection time in the adb key store must not be set to a value less "
                        + "than the previous connection time",
                lastConnectionTime, mKeyStore.getLastConnectionTime(TEST_KEY_1));
    }

    @Test
    public void testAdbKeyRemovedByScheduledJob() throws Exception {
        // When a key is automatically allowed it should be stored in the adb_keys file. A job is
        // then scheduled daily to update the connection time of the currently connected key, and if
        // no connected key exists the key store is updated to purge expired keys. This test
        // verifies that after a key's expiration time has been reached that it is no longer
        // in the key store nor the adb_keys file

        // Set the allowed time to the default to ensure that any modification to this value do not
        // impact this test.
        setAllowedConnectionTime(Settings.Global.DEFAULT_ADB_ALLOWED_CONNECTION_TIME);

        // Allow both test keys to connect with the 'always allow' option selected.
        runAdbTest(TEST_KEY_1, true, true, false);
        runAdbTest(TEST_KEY_2, true, true, false);
        disconnectKey(TEST_KEY_1);
        disconnectKey(TEST_KEY_2);

        // Persist the key store and verify that both keys are in the key store and adb_keys file.
        persistKeyStore();
        assertTrue(
                "Test key 1 must be in the adb_keys file after selecting the 'always allow' "
                        + "option",
                isKeyInFile(TEST_KEY_1, mAdbKeyFile));
        assertTrue(
                "Test key 1 must be in the adb key store after selecting the 'always allow' "
                        + "option",
                mKeyStore.isKeyAuthorized(TEST_KEY_1));
        assertTrue(
                "Test key 2 must be in the adb_keys file after selecting the 'always allow' "
                        + "option",
                isKeyInFile(TEST_KEY_2, mAdbKeyFile));
        assertTrue(
                "Test key 2 must be in the adb key store after selecting the 'always allow' option",
                mKeyStore.isKeyAuthorized(TEST_KEY_2));

        // Set test key 1's last connection time to a small value and persist the keystore to ensure
        // it is cleared out after the next key store update.
        mKeyStore.setLastConnectionTime(TEST_KEY_1, 1, true);
        updateKeyStore();
        assertFalse(
                "Test key 1 must no longer be in the adb_keys file after its timeout period is "
                        + "reached",
                isKeyInFile(TEST_KEY_1, mAdbKeyFile));
        assertFalse(
                "Test key 1 must no longer be in the adb key store after its timeout period is "
                        + "reached",
                mKeyStore.isKeyAuthorized(TEST_KEY_1));
        assertTrue(
                "Test key 2 must still be in the adb_keys file after test key 1's timeout "
                        + "period is reached",
                isKeyInFile(TEST_KEY_2, mAdbKeyFile));
        assertTrue(
                "Test key 2 must still be in the adb key store after test key 1's timeout period "
                        + "is reached",
                mKeyStore.isKeyAuthorized(TEST_KEY_2));
    }

    @Test
    public void testKeystoreExpirationTimes() throws Exception {
        // When one or more keys are always allowed a daily job is scheduled to update the
        // connection time of the connected key and to purge any expired keys. The keystore provides
        // a method to obtain the expiration time of the next key to expire to ensure that a
        // scheduled job can run at the time of the next expiration if it is before the daily job
        // would run. This test verifies that this method returns the expected values depending on
        // when the key should expire and also verifies that the method to schedule the next job to
        // update the keystore is the expected value based on the time of the next expiration.

        final long epsilon = 5000;

        // Ensure the allowed time is set to the default.
        setAllowedConnectionTime(Settings.Global.DEFAULT_ADB_ALLOWED_CONNECTION_TIME);

        // If there are no keys in the keystore the expiration time should be -1.
        assertEquals("The expiration time must be -1 when there are no keys in the keystore", -1,
                mKeyStore.getNextExpirationTime());

        // Allow the test key to connect with the 'always allow' option.
        runAdbTest(TEST_KEY_1, true, true, false);

        // Verify that the current expiration time is within a small value of the default time.
        long expirationTime = mKeyStore.getNextExpirationTime();
        if (Math.abs(expirationTime - Settings.Global.DEFAULT_ADB_ALLOWED_CONNECTION_TIME)
                > epsilon) {
            fail("The expiration time for a new key, " + expirationTime
                    + ", is outside the expected value of "
                    + Settings.Global.DEFAULT_ADB_ALLOWED_CONNECTION_TIME);
        }
        // The delay until the next job should be the lesser of the default expiration time and the
        // AdbDebuggingHandler's job interval.
        long expectedValue = Math.min(
                AdbDebuggingManager.AdbDebuggingHandler.UPDATE_KEYSTORE_JOB_INTERVAL,
                Settings.Global.DEFAULT_ADB_ALLOWED_CONNECTION_TIME);
        long delay = mHandler.scheduleJobToUpdateAdbKeyStore();
        if (Math.abs(delay - expectedValue) > epsilon) {
            fail("The delay before the next scheduled job, " + delay
                    + ", is outside the expected value of " + expectedValue);
        }

        // Set the current expiration time to a minute from expiration and verify this new value is
        // returned.
        final long newExpirationTime = 60000;
        mKeyStore.setLastConnectionTime(
                TEST_KEY_1,
                mFakeTicker.currentTimeMillis()
                        - Settings.Global.DEFAULT_ADB_ALLOWED_CONNECTION_TIME
                        + newExpirationTime,
                true);
        expirationTime = mKeyStore.getNextExpirationTime();
        if (Math.abs(expirationTime - newExpirationTime) > epsilon) {
            fail("The expiration time for a key about to expire, " + expirationTime
                    + ", is outside the expected value of " + newExpirationTime);
        }
        delay = mHandler.scheduleJobToUpdateAdbKeyStore();
        if (Math.abs(delay - newExpirationTime) > epsilon) {
            fail("The delay before the next scheduled job, " + delay
                    + ", is outside the expected value of " + newExpirationTime);
        }

        // If a key is already expired the expiration time and delay before the next job runs should
        // be 0.
        mKeyStore.setLastConnectionTime(TEST_KEY_1, 1, true);
        assertEquals("The expiration time for a key that is already expired must be 0", 0,
                mKeyStore.getNextExpirationTime());
        assertEquals(
                "The delay before the next scheduled job for a key that is already expired must"
                        + " be 0", 0, mHandler.scheduleJobToUpdateAdbKeyStore());

        // If the previous behavior of never removing old keys is set then the expiration time
        // should be -1 to indicate the job does not need to run.
        setAllowedConnectionTime(0);
        assertEquals("The expiration time must be -1 when the keys are set to never expire", -1,
                mKeyStore.getNextExpirationTime());
    }

    @Test
    public void testConnectionTimeUpdatedWithConnectedKeyMessage() throws Exception {
        // When a system successfully passes the SIGNATURE challenge adbd sends a connected key
        // message to the framework to notify of the newly connected key. This message should
        // trigger the AdbDebuggingManager to update the last connection time for this key and mark
        // it as the currently connected key so that its time can be updated during subsequent
        // keystore update jobs as well as when the disconnected message is received.

        // Allow the test key to connect with the 'always allow' option selected.
        runAdbTest(TEST_KEY_1, true, true, false);

        // Simulate disconnecting the key before a subsequent connection without user interaction.
        disconnectKey(TEST_KEY_1);

        // Get the last connection time for the key to verify that it is updated when the connected
        // key message is sent.
        long connectionTime = mKeyStore.getLastConnectionTime(TEST_KEY_1);
        mFakeTicker.advance(10);
        mHandler.obtainMessage(AdbDebuggingManager.AdbDebuggingHandler.MESSAGE_ADB_CONNECTED_KEY,
                TEST_KEY_1).sendToTarget();
        flushHandlerQueue();
        assertNotEquals(
                "The connection time for the key must be updated when the connected key message "
                        + "is received",
                connectionTime, mKeyStore.getLastConnectionTime(TEST_KEY_1));

        // Verify that the scheduled job updates the connection time of the key.
        connectionTime = mKeyStore.getLastConnectionTime(TEST_KEY_1);
        mFakeTicker.advance(10);
        updateKeyStore();
        assertNotEquals(
                "The connection time for the key must be updated when the update keystore message"
                        + " is sent",
                connectionTime, mKeyStore.getLastConnectionTime(TEST_KEY_1));

        // Verify that the connection time is updated when the key is disconnected.
        connectionTime = mKeyStore.getLastConnectionTime(TEST_KEY_1);
        mFakeTicker.advance(10);
        disconnectKey(TEST_KEY_1);
        assertNotEquals(
                "The connection time for the key must be updated when the disconnected message is"
                        + " received",
                connectionTime, mKeyStore.getLastConnectionTime(TEST_KEY_1));
    }

    @Test
    public void testClearAuthorizations() throws Exception {
        // When the user selects the 'Revoke USB debugging authorizations' all previously 'always
        // allow' keys should be deleted.

        // Set the allowed connection time to the default value to ensure tests do not fail due to
        // a small value.
        setAllowedConnectionTime(Settings.Global.DEFAULT_ADB_ALLOWED_CONNECTION_TIME);

        // Allow the test key to connect with the 'always allow' option selected.
        runAdbTest(TEST_KEY_1, true, true, false);
        persistKeyStore();

        // Verify that the key is authorized and in the adb_keys file
        assertTrue(
                "The test key must be in the keystore after the 'always allow' option is selected",
                mKeyStore.isKeyAuthorized(TEST_KEY_1));
        assertTrue(
                "The test key must be in the adb_keys file after the 'always allow option is "
                        + "selected",
                isKeyInFile(TEST_KEY_1, mAdbKeyFile));

        // Send the message to clear the adb authorizations and verify that the keys are no longer
        // authorized.
        clearKeyStore();
        assertFalse(
                "The test key must not be in the keystore after clearing the authorizations",
                mKeyStore.isKeyAuthorized(TEST_KEY_1));
        assertFalse(
                "The test key must not be in the adb_keys file after clearing the authorizations",
                isKeyInFile(TEST_KEY_1, mAdbKeyFile));
    }

    @Test
    public void testClearKeystoreAfterDisablingAdb() throws Exception {
        // When the user disables adb they should still be able to clear the authorized keys.

        // Allow the test key to connect with the 'always allow' option selected and persist the
        // keystore.
        runAdbTest(TEST_KEY_1, true, true, false);
        persistKeyStore();

        // Disable adb and verify that the keystore can be cleared without throwing an exception.
        disableAdb();
        clearKeyStore();
        assertFalse(
                "The test key must not be in the adb_keys file after clearing the authorizations",
                isKeyInFile(TEST_KEY_1, mAdbKeyFile));
    }

    @Test
    public void testUntrackedUserKeysAddedToKeystore() throws Exception {
        // When a device is first updated to a build that tracks the connection time of adb keys
        // the keys in the user key file will not have a connection time. To prevent immediately
        // deleting keys that the user is actively using these untracked keys should be added to the
        // keystore with the current system time; this gives the user time to reconnect
        // automatically with an active key while inactive keys are deleted after the expiration
        // time.

        final long epsilon = 5000;
        final String[] testKeys = {TEST_KEY_1, TEST_KEY_2};

        // Add the test keys to the user key file.
        FileOutputStream fo = new FileOutputStream(mAdbKeyFile);
        for (String key : testKeys) {
            fo.write(key.getBytes());
            fo.write('\n');
        }
        fo.close();

        // Set the expiration time to the default and use this value to verify the expiration time
        // of the previously untracked keys.
        setAllowedConnectionTime(Settings.Global.DEFAULT_ADB_ALLOWED_CONNECTION_TIME);

        // The untracked keys should be added to the keystore as part of the constructor.
        AdbDebuggingManager.AdbKeyStore adbKeyStore = mManager.new AdbKeyStore();

        // Verify that the connection time for each test key is within a small value of the current
        // time.
        long time = mFakeTicker.currentTimeMillis();
        for (String key : testKeys) {
            long connectionTime = adbKeyStore.getLastConnectionTime(key);
            if (Math.abs(time - connectionTime) > epsilon) {
                fail("The connection time for a previously untracked key, " + connectionTime
                        + ", is beyond the current time of " + time);
            }
        }
    }

    @Test
    public void testConnectionTimeUpdatedForMultipleConnectedKeys() throws Exception {
        // Since ADB supports multiple simultaneous connections verify that the connection time of
        // each key is updated by the scheduled job as long as it is connected.

        // Allow both test keys to connect with the 'always allow' option selected.
        runAdbTest(TEST_KEY_1, true, true, false);
        runAdbTest(TEST_KEY_2, true, true, false);

        // Advance a small amount of time to ensure the connection time is updated by the scheduled
        // job.
        long connectionTime1 = mKeyStore.getLastConnectionTime(TEST_KEY_1);
        long connectionTime2 = mKeyStore.getLastConnectionTime(TEST_KEY_2);
        mFakeTicker.advance(10);
        updateKeyStore();
        assertNotEquals(
                "The connection time for test key 1 must be updated after the scheduled job runs",
                connectionTime1, mKeyStore.getLastConnectionTime(TEST_KEY_1));
        assertNotEquals(
                "The connection time for test key 2 must be updated after the scheduled job runs",
                connectionTime2, mKeyStore.getLastConnectionTime(TEST_KEY_2));

        // Disconnect the second test key and verify that the last connection time of the first key
        // is the only one updated.
        disconnectKey(TEST_KEY_2);
        connectionTime1 = mKeyStore.getLastConnectionTime(TEST_KEY_1);
        connectionTime2 = mKeyStore.getLastConnectionTime(TEST_KEY_2);
        mFakeTicker.advance(10);
        updateKeyStore();
        assertNotEquals(
                "The connection time for test key 1 must be updated after another key is "
                        + "disconnected and the scheduled job runs",
                connectionTime1, mKeyStore.getLastConnectionTime(TEST_KEY_1));
        assertEquals(
                "The connection time for test key 2 must not be updated after it is disconnected",
                connectionTime2, mKeyStore.getLastConnectionTime(TEST_KEY_2));
    }

    @Test
    public void testClearAuthorizationsBeforeAdbEnabled() throws Exception {
        // The adb key store is not instantiated until adb is enabled; however if the user attempts
        // to clear the adb authorizations when adb is disabled after a boot a NullPointerException
        // was thrown as deleteKeyStore is invoked against the key store. This test ensures the
        // key store can be successfully cleared when adb is disabled.
        clearKeyStore();
    }

    @Test
    public void testClearAuthorizationsDeletesKeyFiles() throws Exception {
        mAdbKeyFile.createNewFile();
        mAdbKeyXmlFile.createNewFile();

        clearKeyStore();

        assertFalse("The adb key file should have been deleted after revocation of the grants",
                mAdbKeyFile.exists());
        assertFalse("The adb xml key file should have been deleted after revocation of the grants",
                mAdbKeyXmlFile.exists());
    }

    @Test
    public void testAdbKeyStore_removeKey() throws Exception {
        // Accept the test key with the 'Always allow' option selected.
        runAdbTest(TEST_KEY_1, true, true, false);
        runAdbTest(TEST_KEY_2, true, true, false);

        // Set the connection time to 0 to restore the original behavior.
        setAllowedConnectionTime(0);

        // Verify that the key is in the adb_keys file to ensure subsequent connections are
        // automatically allowed by adbd.
        persistKeyStore();
        assertTrue("The key was not in the adb_keys file after persisting the keystore",
                isKeyInFile(TEST_KEY_1, mAdbKeyFile));
        assertTrue("The key was not in the adb_keys file after persisting the keystore",
                isKeyInFile(TEST_KEY_2, mAdbKeyFile));

        // Now remove one of the keys and make sure the other key is still there
        mKeyStore.removeKey(TEST_KEY_1);
        // Wait for the handler queue to receive the MESSAGE_ADB_PERSIST_KEYSTORE
        flushHandlerQueue();

        assertFalse("The key was still in the adb_keys file after removing the key",
                isKeyInFile(TEST_KEY_1, mAdbKeyFile));
        assertTrue("The key was not in the adb_keys file after removing a different key",
                isKeyInFile(TEST_KEY_2, mAdbKeyFile));
    }

    @Test
    public void testAdbKeyStore_addDuplicateKey_doesNotAddDuplicateToAdbKeyFile() throws Exception {
        setAllowedConnectionTime(0);

        runAdbTest(TEST_KEY_1, true, true, false);
        persistKeyStore();
        runAdbTest(TEST_KEY_1, true, true, false);
        persistKeyStore();

        assertEquals("adb_keys contains duplicate keys", 1, adbKeyFileKeys(mAdbKeyFile).size());
    }

    @Test
    public void testAdbKeyStore_adbTempKeysFile_readsLastConnectionTimeFromXml() throws Exception {
        long insertTime = mFakeTicker.currentTimeMillis();
        runAdbTest(TEST_KEY_1, true, true, false);
        persistKeyStore();

        mFakeTicker.advance(10);
        AdbDebuggingManager.AdbKeyStore newKeyStore = mManager.new AdbKeyStore();

        assertEquals(
                "KeyStore not populated from the XML file.",
                insertTime,
                newKeyStore.getLastConnectionTime(TEST_KEY_1));
    }

    @Test
    public void test_notifyKeyFilesUpdated_filesDeletedRemovesPreviouslyAddedKey()
            throws Exception {
        runAdbTest(TEST_KEY_1, true, true, false);
        persistKeyStore();

        Files.delete(mAdbKeyXmlFile.toPath());
        Files.delete(mAdbKeyFile.toPath());

        mManager.notifyKeyFilesUpdated();
        flushHandlerQueue();

        assertFalse(
                "Key is authorized after reloading deleted key files. Was state preserved?",
                mKeyStore.isKeyAuthorized(TEST_KEY_1));
    }

    @Test
    public void test_notifyKeyFilesUpdated_newKeyIsAuthorized() throws Exception {
        runAdbTest(TEST_KEY_1, true, true, false);
        persistKeyStore();

        // Back up the existing key files
        Path tempXmlFile = Files.createTempFile("adbKeyXmlFile", ".tmp");
        Path tempAdbKeysFile = Files.createTempFile("adb_keys", ".tmp");
        Files.copy(mAdbKeyXmlFile.toPath(), tempXmlFile, StandardCopyOption.REPLACE_EXISTING);
        Files.copy(mAdbKeyFile.toPath(), tempAdbKeysFile, StandardCopyOption.REPLACE_EXISTING);

        // Delete the existing key files
        Files.delete(mAdbKeyXmlFile.toPath());
        Files.delete(mAdbKeyFile.toPath());

        // Notify the manager that adb key files have changed.
        mManager.notifyKeyFilesUpdated();
        flushHandlerQueue();

        // Copy the files back
        Files.copy(tempXmlFile, mAdbKeyXmlFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(tempAdbKeysFile, mAdbKeyFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

        // Tell the manager that the key files have changed.
        mManager.notifyKeyFilesUpdated();
        flushHandlerQueue();

        assertTrue(
                "Key is not authorized after reloading key files.",
                mKeyStore.isKeyAuthorized(TEST_KEY_1));
    }

    @Test
    public void testAdbKeyStore_adbWifiConnect_storesBssidWhenAlwaysAllow() throws Exception {
        String trustedNetwork = "My Network";
        mKeyStore.addTrustedNetwork(trustedNetwork);
        persistKeyStore();

        AdbDebuggingManager.AdbKeyStore newKeyStore = mManager.new AdbKeyStore();

        assertTrue(
                "Persisted trusted network not found in new keystore instance.",
                newKeyStore.isTrustedNetwork(trustedNetwork));
    }

    @Test
    public void testIsValidMdnsServiceName() {
        // Longer than 15 characters
        assertFalse(isValidMdnsServiceName("abcd1234abcd1234"));

        // Contains invalid characters
        assertFalse(isValidMdnsServiceName("a*a"));
        assertFalse(isValidMdnsServiceName("a_a"));
        assertFalse(isValidMdnsServiceName("_a"));

        // Does not begin or end with letter or digit
        assertFalse(isValidMdnsServiceName(""));
        assertFalse(isValidMdnsServiceName("-"));
        assertFalse(isValidMdnsServiceName("-a"));
        assertFalse(isValidMdnsServiceName("-1"));
        assertFalse(isValidMdnsServiceName("a-"));
        assertFalse(isValidMdnsServiceName("1-"));

        // Contains consecutive hyphens
        assertFalse(isValidMdnsServiceName("a--a"));

        // Does not contain at least one letter
        assertFalse(isValidMdnsServiceName("1"));
        assertFalse(isValidMdnsServiceName("12"));
        assertFalse(isValidMdnsServiceName("1-2"));

        // letter not within [a-zA-Z]
        assertFalse(isValidMdnsServiceName("aés"));

        // Some valid names
        assertTrue(isValidMdnsServiceName("a"));
        assertTrue(isValidMdnsServiceName("a1"));
        assertTrue(isValidMdnsServiceName("1A"));
        assertTrue(isValidMdnsServiceName("aZ"));
        assertTrue(isValidMdnsServiceName("a-Z"));
        assertTrue(isValidMdnsServiceName("a-b-Z"));
        assertTrue(isValidMdnsServiceName("abc-def-123-456"));
    }

    @Test
    public void testPairingThread_MdnsServiceName_RFC6335() {
        assertTrue(isValidMdnsServiceName(AdbDebuggingManager.PairingThread.SERVICE_PROTOCOL));
    }

    private boolean isValidMdnsServiceName(String name) {
        // The rules for Service Names [RFC6335] state that they may be no more
        // than fifteen characters long (not counting the mandatory underscore),
        // consisting of only letters, digits, and hyphens, must begin and end
        // with a letter or digit, must not contain consecutive hyphens, and
        // must contain at least one letter.
        // No more than 15 characters long
        final int len = name.length();
        if (name.isEmpty() || len > 15) {
            return false;
        }

        boolean hasAtLeastOneLetter = false;
        boolean sawHyphen = false;
        for (int i = 0; i < len; ++i) {
            // Must contain at least one letter
            // Only contains letters, digits and hyphens
            char c = name.charAt(i);
            if (c == '-') {
                // Cannot be at beginning or end
                if (i == 0 || i == len - 1) {
                    return false;
                }
                if (sawHyphen) {
                    // Consecutive hyphen found
                    return false;
                }
                sawHyphen = true;
                continue;
            }

            sawHyphen = false;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                hasAtLeastOneLetter = true;
                continue;
            }

            if (c >= '0' && c <= '9') {
                continue;
            }

            // Invalid character
            return false;
        }

        return hasAtLeastOneLetter;
    }

    CountDownLatch mAdbActionLatch = new CountDownLatch(1);
    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Log.i(TAG, "Received intent action=" + action);
            if (AdbManager.WIRELESS_DEBUG_PAIRED_DEVICES_ACTION.equals(action)) {
                assertEquals("Received broadcast without MANAGE_DEBUGGING permission.",
                        context.checkSelfPermission(android.Manifest.permission.MANAGE_DEBUGGING),
                        PackageManager.PERMISSION_GRANTED);
                Log.i(TAG, "action=" + action + " paired_device=" + intent.getSerializableExtra(
                        AdbManager.WIRELESS_DEVICES_EXTRA).toString());
                mAdbActionLatch.countDown();
            } else if (AdbManager.WIRELESS_DEBUG_STATE_CHANGED_ACTION.equals(action)) {
                assertEquals("Received broadcast without MANAGE_DEBUGGING permission.",
                        context.checkSelfPermission(android.Manifest.permission.MANAGE_DEBUGGING),
                        PackageManager.PERMISSION_GRANTED);
                int status = intent.getIntExtra(AdbManager.WIRELESS_STATUS_EXTRA,
                        AdbManager.WIRELESS_STATUS_DISCONNECTED);
                Log.i(TAG, "action=" + action + " status=" + status);
                mAdbActionLatch.countDown();
            } else if (AdbManager.WIRELESS_DEBUG_PAIRING_RESULT_ACTION.equals(action)) {
                assertEquals("Received broadcast without MANAGE_DEBUGGING permission.",
                        context.checkSelfPermission(android.Manifest.permission.MANAGE_DEBUGGING),
                        PackageManager.PERMISSION_GRANTED);
                Integer res = intent.getIntExtra(
                        AdbManager.WIRELESS_STATUS_EXTRA,
                        AdbManager.WIRELESS_STATUS_FAIL);
                Log.i(TAG, "action=" + action + " result=" + res);

                if (res.equals(AdbManager.WIRELESS_STATUS_PAIRING_CODE)) {
                    String pairingCode = intent.getStringExtra(
                                AdbManager.WIRELESS_PAIRING_CODE_EXTRA);
                    Log.i(TAG, "pairingCode=" + pairingCode);
                } else if (res.equals(AdbManager.WIRELESS_STATUS_CONNECTED)) {
                    int port = intent.getIntExtra(AdbManager.WIRELESS_DEBUG_PORT_EXTRA, 0);
                    Log.i(TAG, "port=" + port);
                }
                mAdbActionLatch.countDown();
            }
        }
    };

    private void adoptShellPermissionIdentity() {
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
            .adoptShellPermissionIdentity(android.Manifest.permission.MANAGE_DEBUGGING);
    }

    private void dropShellPermissionIdentity() {
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
            .dropShellPermissionIdentity();
    }

    @Test
    public void testBroadcastReceiverWithPermissions() throws Exception {
        adoptShellPermissionIdentity();
        final IAdbManager mAdbManager = IAdbManager.Stub.asInterface(
                ServiceManager.getService(Context.ADB_SERVICE));
        IntentFilter intentFilter =
                new IntentFilter(AdbManager.WIRELESS_DEBUG_PAIRED_DEVICES_ACTION);
        intentFilter.addAction(AdbManager.WIRELESS_DEBUG_STATE_CHANGED_ACTION);
        intentFilter.addAction(AdbManager.WIRELESS_DEBUG_PAIRING_RESULT_ACTION);
        assertEquals("Context does not have MANAGE_DEBUGGING permission.",
                mContext.checkSelfPermission(android.Manifest.permission.MANAGE_DEBUGGING),
                PackageManager.PERMISSION_GRANTED);
        try {
            mContext.registerReceiver(mReceiver, intentFilter);
            mAdbManager.enablePairingByPairingCode();
            if (!mAdbActionLatch.await(TIMEOUT, TIMEOUT_TIME_UNIT)) {
                fail("Receiver did not receive adb intent action within the timeout duration");
            }
        } finally {
            mContext.unregisterReceiver(mReceiver);
        }
    }

    @Test
    public void testBroadcastReceiverWithoutPermissions() throws Exception {
        adoptShellPermissionIdentity();
        final IAdbManager mAdbManager = IAdbManager.Stub.asInterface(
                ServiceManager.getService(Context.ADB_SERVICE));
        IntentFilter intentFilter =
                new IntentFilter(AdbManager.WIRELESS_DEBUG_PAIRED_DEVICES_ACTION);
        intentFilter.addAction(AdbManager.WIRELESS_DEBUG_STATE_CHANGED_ACTION);
        intentFilter.addAction(AdbManager.WIRELESS_DEBUG_PAIRING_RESULT_ACTION);
        mAdbManager.enablePairingByPairingCode();

        dropShellPermissionIdentity();
        assertEquals("Context has MANAGE_DEBUGGING permission.",
                mContext.checkSelfPermission(android.Manifest.permission.MANAGE_DEBUGGING),
                PackageManager.PERMISSION_DENIED);
        try {
            mContext.registerReceiver(mReceiver, intentFilter);

            if (mAdbActionLatch.await(TIMEOUT, TIMEOUT_TIME_UNIT)) {
                fail("Broadcast receiver received adb action intent without debug permissions");
            }
        } finally {
            mContext.unregisterReceiver(mReceiver);
        }
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
            assertFalse("The key must not be authorized in the key store",
                    mKeyStore.isKeyAuthorized(key));
            assertFalse(
                    "The key must not be stored in the adb_keys file",
                    isKeyInFile(key, mAdbKeyFile));
        }
        flushHandlerQueue();
    }

    private void persistKeyStore() throws Exception {
        // Send a message to the handler to persist the key store.
        mHandler.obtainMessage(
                AdbDebuggingManager.AdbDebuggingHandler.MESSAGE_ADB_PERSIST_KEYSTORE)
                    .sendToTarget();
        flushHandlerQueue();
    }

    private void disconnectKey(String key) throws Exception {
        // Send a message to the handler to disconnect the currently connected key.
        mHandler.obtainMessage(AdbDebuggingManager.AdbDebuggingHandler.MESSAGE_ADB_DISCONNECT,
                key).sendToTarget();
        flushHandlerQueue();
    }

    private void updateKeyStore() throws Exception {
        // Send a message to the handler to run the update keystore job.
        mHandler.obtainMessage(
                AdbDebuggingManager.AdbDebuggingHandler.MESSAGE_ADB_UPDATE_KEYSTORE).sendToTarget();
        flushHandlerQueue();
    }

    private void clearKeyStore() throws Exception {
        // Send a message to the handler to clear all previously authorized keys.
        mHandler.obtainMessage(
                AdbDebuggingManager.AdbDebuggingHandler.MESSAGE_ADB_CLEAR).sendToTarget();
        flushHandlerQueue();
    }

    private void disableAdb() throws Exception {
        // Send a message to the handler to disable adb.
        mHandler.obtainMessage(
                AdbDebuggingManager.AdbDebuggingHandler.MESSAGE_ADB_DISABLED).sendToTarget();
        flushHandlerQueue();
    }

    private void flushHandlerQueue() throws Exception {
        // Post a Runnable to ensure that all of the current messages in the queue are flushed.
        CountDownLatch latch = new CountDownLatch(1);
        mHandler.post(() -> {
            latch.countDown();
        });
        if (!latch.await(TIMEOUT, TIMEOUT_TIME_UNIT)) {
            fail("The Runnable to flush the handler's queue did not complete within the timeout "
                    + "period");
        }
    }

    private boolean isKeyInFile(String key, File keyFile) throws Exception {
        if (key == null) {
            return false;
        }
        return adbKeyFileKeys(keyFile).contains(key);
    }

    private static List<String> adbKeyFileKeys(File keyFile) throws Exception {
        List<String> keys = new ArrayList<>();
        if (keyFile.exists()) {
            try (BufferedReader in = new BufferedReader(new FileReader(keyFile))) {
                String currKey;
                while ((currKey = in.readLine()) != null) {
                    keys.add(currKey);
                }
            }
        }
        return keys;
    }

    /**
     * Helper class that extends AdbDebuggingThread to receive the response from AdbDebuggingManager
     * indicating whether the key should be allowed to  connect.
     */
    private class AdbDebuggingThreadTest extends AdbDebuggingManager.AdbDebuggingThread {
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

    private static class FakeTicker implements AdbDebuggingManager.Ticker {
        private long mCurrentTime;

        private void advance(long milliseconds) {
            mCurrentTime += milliseconds;
        }

        @Override
        public long currentTimeMillis() {
            return mCurrentTime;
        }
    }
}
