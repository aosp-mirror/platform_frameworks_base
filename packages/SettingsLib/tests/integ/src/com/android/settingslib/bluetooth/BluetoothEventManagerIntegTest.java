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

package com.android.settingslib.bluetooth;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;

import static java.util.concurrent.TimeUnit.SECONDS;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.UserInfo;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;

/**
 * Test that verifies that BluetoothEventManager can receive broadcasts for non-current users for
 * all bluetooth events.
 *
 * <p>Creation and deletion of users takes a long time, so marking this as a LargeTest.
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class BluetoothEventManagerIntegTest {
    private static final int LATCH_TIMEOUT = 4;

    private Context mContext;
    private UserManager mUserManager;
    private BluetoothEventManager mBluetoothEventManager;

    private UserInfo mOtherUser;
    private final Intent mTestIntent = new Intent("Test intent");

    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getTargetContext();
        mUserManager = UserManager.get(mContext);

        mBluetoothEventManager =
                new BluetoothEventManager(
                        mock(LocalBluetoothAdapter.class),
                        mock(LocalBluetoothManager.class),
                        mock(CachedBluetoothDeviceManager.class),
                        mContext,
                        /* handler= */ null,
                        UserHandle.ALL);

        // Create and start another user in the background.
        mOtherUser = mUserManager.createUser("TestUser", /* flags= */ 0);
        try {
            ActivityManager.getService().startUserInBackground(mOtherUser.id);
        } catch (RemoteException e) {
            fail("Count't create an additional user.");
        }
    }

    @After
    public void tearDown() {
        if (mOtherUser != null) {
            mUserManager.removeUser(mOtherUser.id);
        }
    }

    /**
     * Verify that MultiUserAwareBluetoothEventManager's adapter receiver handles events coming from
     * users other than current user.
     */
    @Test
    public void registerAdapterReceiver_ifIntentFromAnotherUser_broadcastIsReceived()
            throws Exception {
        // Create a latch to listen for the intent.
        final CountDownLatch broadcastLatch = new CountDownLatch(1);

        // Register adapter receiver.
        mBluetoothEventManager.addHandler(mTestIntent.getAction(),
                (context, intent, device) -> broadcastLatch.countDown());
        mBluetoothEventManager.registerAdapterIntentReceiver();

        // Send broadcast from another user.
        mContext.sendBroadcastAsUser(mTestIntent, mOtherUser.getUserHandle());

        // Wait to receive it.
        assertTrue(broadcastLatch.await(LATCH_TIMEOUT, SECONDS));
    }

    /**
     * Verify that MultiUserAwareBluetoothEventManager's profile receiver handles events coming from
     * users other than current user.
     */
    @Test
    public void registerProfileReceiver_ifIntentFromAnotherUser_broadcastIsReceived()
            throws Exception {
        // Create a latch to listen for the intent.
        final CountDownLatch broadcastLatch = new CountDownLatch(1);

        // Register profile receiver.
        mBluetoothEventManager.addProfileHandler(mTestIntent.getAction(),
                (context, intent, device) -> broadcastLatch.countDown());
        mBluetoothEventManager.registerProfileIntentReceiver();

        // Send broadcast from another user.
        mContext.sendBroadcastAsUser(mTestIntent, mOtherUser.getUserHandle());

        // Wait to receive it.
        assertTrue(broadcastLatch.await(LATCH_TIMEOUT, SECONDS));
    }
}