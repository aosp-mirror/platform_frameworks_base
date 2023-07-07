/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.location.contexthub;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.when;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.hardware.location.ContextHubInfo;
import android.hardware.location.IContextHubClientCallback;
import android.os.Binder;
import android.os.Bundle;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
@Presubmit
public class ContextHubClientBrokerTest {
    private static final short HOST_ENDPOINT_ID = 123;
    private static final String ATTRIBUTE_TAG = "attribute_tag";
    private static final long NANOAPP_ID = 3210L;
    private ContextHubClientManager mClientManager;
    private Context mContext;
    @Mock private IContextHubWrapper mMockContextHubWrapper;
    @Mock private ContextHubInfo mMockContextHubInfo;
    @Mock private IContextHubClientCallback mMockCallback;
    @Rule public final MockitoRule mockito = MockitoJUnit.rule();

    @Before
    public void setUp() throws RemoteException {
        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        mClientManager = new ContextHubClientManager(mContext, mMockContextHubWrapper);
        when(mMockCallback.asBinder()).thenReturn(new Binder());
    }

    private ContextHubClientBroker createFromCallback() {
        ContextHubClientBroker broker =
                new ContextHubClientBroker(
                        mContext,
                        mMockContextHubWrapper,
                        mClientManager,
                        mMockContextHubInfo,
                        HOST_ENDPOINT_ID,
                        mMockCallback,
                        ATTRIBUTE_TAG,
                        new ContextHubTransactionManager(
                                mMockContextHubWrapper, mClientManager, new NanoAppStateManager()),
                        mContext.getPackageName());
        assertThat(broker.isWakelockUsable()).isTrue();
        assertThat(broker.getWakeLock().isHeld()).isFalse();
        return broker;
    }

    private ContextHubClientBroker createFromPendingIntent(PendingIntent pendingIntent) {
        ContextHubClientBroker broker =
                new ContextHubClientBroker(
                        mContext,
                        mMockContextHubWrapper,
                        mClientManager,
                        mMockContextHubInfo,
                        HOST_ENDPOINT_ID,
                        pendingIntent,
                        NANOAPP_ID,
                        ATTRIBUTE_TAG,
                        new ContextHubTransactionManager(
                                mMockContextHubWrapper, mClientManager, new NanoAppStateManager()));
        assertThat(broker.isWakelockUsable()).isTrue();
        assertThat(broker.getWakeLock().isHeld()).isFalse();
        return broker;
    }

    @Test
    // TODO(b/241016627): We should have similar tests for other public callbacks too.
    public void testWakeLock_callback_onNanoAppLoaded() {
        ContextHubClientBroker broker = createFromCallback();

        broker.onNanoAppLoaded(NANOAPP_ID);
        assertThat(broker.isWakelockUsable()).isTrue();
        assertThat(broker.getWakeLock().isHeld()).isTrue();

        broker.callbackFinished();
        assertThat(broker.isWakelockUsable()).isTrue();
        assertThat(broker.getWakeLock().isHeld()).isFalse();
    }

    @Test
    public void testWakeLock_callback_multiple() {
        ContextHubClientBroker broker = createFromCallback();

        broker.onNanoAppLoaded(NANOAPP_ID);
        broker.onNanoAppUnloaded(NANOAPP_ID);
        broker.onHubReset();

        broker.callbackFinished();
        assertThat(broker.isWakelockUsable()).isTrue();
        assertThat(broker.getWakeLock().isHeld()).isTrue();

        broker.callbackFinished();
        assertThat(broker.isWakelockUsable()).isTrue();
        assertThat(broker.getWakeLock().isHeld()).isTrue();

        broker.callbackFinished();
        assertThat(broker.isWakelockUsable()).isTrue();
        assertThat(broker.getWakeLock().isHeld()).isFalse();
    }

    @Test
    public void testWakeLock_callback_binderDied() {
        ContextHubClientBroker broker = createFromCallback();

        broker.binderDied();

        assertThat(broker.isWakelockUsable()).isFalse();
        assertThat(broker.getWakeLock().isHeld()).isFalse();
    }

    @Test
    public void testWakeLock_pendingIntent() throws InterruptedException {
        PendingIntent pendingIntent = PendingIntent.getBroadcast(mContext, 0, new Intent(), 0);
        ContextHubClientBroker broker = createFromPendingIntent(pendingIntent);
        CountDownLatch latch = new CountDownLatch(1);
        PendingIntent.OnFinished onFinishedCallback =
                (PendingIntent unusedPendingIntent,
                        Intent unusedIntent,
                        int resultCode,
                        String resultData,
                        Bundle resultExtras) -> {
                    // verify that the wakelock is held before calling the OnFinished callback.
                    assertThat(broker.isWakelockUsable()).isTrue();
                    assertThat(broker.getWakeLock().isHeld()).isTrue();
                    broker.onSendFinished(
                            unusedPendingIntent,
                            unusedIntent,
                            resultCode,
                            resultData,
                            resultExtras);
                    latch.countDown();
                };

        broker.doSendPendingIntent(pendingIntent, new Intent(), onFinishedCallback);

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(broker.isWakelockUsable()).isTrue();
        assertThat(broker.getWakeLock().isHeld()).isFalse();
    }

    @Test
    public void testWakeLock_pendingIntent_multipleTimes() throws InterruptedException {
        PendingIntent pendingIntent = PendingIntent.getBroadcast(mContext, 0, new Intent(), 0);
        ContextHubClientBroker broker = createFromPendingIntent(pendingIntent);
        CountDownLatch latch = new CountDownLatch(3);
        PendingIntent.OnFinished onFinishedCallback =
                (PendingIntent unusedPendingIntent,
                        Intent unusedIntent,
                        int resultCode,
                        String resultData,
                        Bundle resultExtras) -> {
                    // verify that the wakelock is held before calling the OnFinished callback.
                    assertThat(broker.isWakelockUsable()).isTrue();
                    assertThat(broker.getWakeLock().isHeld()).isTrue();
                    broker.onSendFinished(
                            unusedPendingIntent,
                            unusedIntent,
                            resultCode,
                            resultData,
                            resultExtras);
                    latch.countDown();
                };

        broker.doSendPendingIntent(pendingIntent, new Intent(), onFinishedCallback);
        broker.doSendPendingIntent(pendingIntent, new Intent(), onFinishedCallback);
        broker.doSendPendingIntent(pendingIntent, new Intent(), onFinishedCallback);

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(broker.isWakelockUsable()).isTrue();
        assertThat(broker.getWakeLock().isHeld()).isFalse();
    }

    @Test
    public void testWakeLock_pendingIntent_binderDied() {
        PendingIntent pendingIntent = PendingIntent.getBroadcast(mContext, 0, new Intent(), 0);
        ContextHubClientBroker broker = createFromPendingIntent(pendingIntent);

        broker.binderDied();

        // The wakelock should still be usable because a pending intent exists.
        assertThat(broker.isWakelockUsable()).isTrue();
        assertThat(broker.getWakeLock().isHeld()).isFalse();
    }

    @Test
    public void testWakeLock_close_pendingIntent() {
        ContextHubClientBroker broker = createFromCallback();

        broker.close();

        // The wakelock should be unusable because broker.close() clears out the pending intent.
        assertThat(broker.isWakelockUsable()).isFalse();
        assertThat(broker.getWakeLock().isHeld()).isFalse();
    }

    @Test
    public void testWakeLock_onNanoAppUnloaded_closeBeforeCallback() {
        ContextHubClientBroker broker = createFromCallback();

        broker.onNanoAppUnloaded(NANOAPP_ID);
        broker.close();

        assertThat(broker.isWakelockUsable()).isFalse();
        assertThat(broker.getWakeLock().isHeld()).isFalse();
    }
}
