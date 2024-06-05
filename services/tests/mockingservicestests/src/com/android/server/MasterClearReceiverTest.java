/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.Looper;
import android.os.RecoverySystem;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.storage.StorageManager;
import android.platform.test.annotations.Presubmit;
import android.util.Log;

import androidx.test.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Run it as {@code FrameworksMockingServicesTests:MasterClearReceiverTest}.
 */
@Presubmit
public final class MasterClearReceiverTest {

    private static final String TAG = MasterClearReceiverTest.class.getSimpleName();

    private MockitoSession mSession;

    // Cannot @Mock context because MasterClearReceiver shows an AlertDialog, which relies
    // on resources - we'd need to mock them as well.
    private final Context mContext = new ContextWrapper(
            InstrumentationRegistry.getInstrumentation().getTargetContext()) {

        @Override
        public Object getSystemService(String name) {
            Log.v(TAG, "getSystemService(): " + name);
            if (name.equals(Context.STORAGE_SERVICE)) {
                return mSm;
            }
            if (name.equals(Context.USER_SERVICE)) {
                return mUserManager;
            }
            return super.getSystemService(name);
        }
    };

    private final MasterClearReceiver mReceiver = new MasterClearReceiver();

    // Used to make sure that wipeAdoptableDisks() is called before rebootWipeUserData()
    private boolean mWipeExternalDataCalled;

    // Uset to block test until rebootWipeUserData() is called, as it might be asynchronous called
    // in a different thread
    private final CountDownLatch mRebootWipeUserDataLatch = new CountDownLatch(1);

    @Mock
    private StorageManager mSm;

    @Mock
    private UserManager mUserManager;

    @Before
    public void startSession() {
        mSession = mockitoSession()
                .initMocks(this)
                .mockStatic(RecoverySystem.class)
                .mockStatic(UserManager.class)
                .strictness(Strictness.LENIENT)
                .startMocking();
        setPendingResultForUser(UserHandle.myUserId());
    }

    @After
    public void finishSession() {
        if (mSession == null) {
            Log.w(TAG, "finishSession(): no session");
            return;
        }
        mSession.finishMocking();
    }

    @Test
    public void testNoExtras() throws Exception {
        expectNoWipeExternalData();
        expectRebootWipeUserData();

        Intent intent = new Intent(Intent.ACTION_FACTORY_RESET);
        mReceiver.onReceive(mContext, intent);

        verifyRebootWipeUserData();
        verifyNoWipeExternalData();
    }

    @Test
    public void testWipeExternalDirectory() throws Exception {
        expectWipeExternalData();
        expectRebootWipeUserData();

        Intent intent = new Intent(Intent.ACTION_FACTORY_RESET);
        intent.putExtra(Intent.EXTRA_WIPE_EXTERNAL_STORAGE, true);
        mReceiver.onReceive(mContext, intent);

        verifyRebootWipeUserData();
        verifyWipeExternalData();
    }

    @Test
    public void testAllExtras() throws Exception {
        expectWipeExternalData();
        expectRebootWipeUserData();

        Intent intent = new Intent(Intent.ACTION_FACTORY_RESET);
        intent.putExtra(Intent.EXTRA_WIPE_EXTERNAL_STORAGE, true);
        intent.putExtra("shutdown", true);
        intent.putExtra(Intent.EXTRA_REASON, "Self destruct");
        intent.putExtra(Intent.EXTRA_FORCE_FACTORY_RESET, true);
        intent.putExtra(Intent.EXTRA_WIPE_ESIMS, true);
        intent.putExtra("keep_memtag_mode", true);
        mReceiver.onReceive(mContext, intent);

        verifyRebootWipeUserData(/* shutdown= */ true, /* reason= */ "Self destruct",
                /* force= */ true, /* wipeEuicc= */ true, /* keepMemtagMode= */ true);
        verifyWipeExternalData();
    }

    @Test
    public void testNonSystemUser() throws Exception {
        expectWipeNonSystemUser();

        Intent intent = new Intent(Intent.ACTION_FACTORY_RESET);
        setPendingResultForUser(/* userId= */ 10);
        mReceiver.onReceive(mContext, intent);

        verifyNoRebootWipeUserData();
        verifyNoWipeExternalData();
        verifyWipeNonSystemUser();
    }

    @Test
    public void testHeadlessSystemUser() throws Exception {
        expectNoWipeExternalData();
        expectRebootWipeUserData();
        expectHeadlessSystemUserMode();

        Intent intent = new Intent(Intent.ACTION_FACTORY_RESET);
        setPendingResultForUser(/* userId= */ 10);
        mReceiver.onReceive(mContext, intent);

        verifyRebootWipeUserData();
        verifyNoWipeExternalData();
    }

    private void expectNoWipeExternalData() {
        // This is a trick to simplify how the order of methods are called: as wipeAdoptableDisks()
        // should be called before rebootWipeUserData(), expectRebootWipeUserData() throws an
        // exception if it's not called, so this method "emulates" a call when it's not neeeded.
        //
        // A more robust solution would be using internal counters for expected and actual mocked
        // calls, so the expectXXX() methods would increment expected counter and the Answer
        // implementations would increment the actual counter and check if they match, but that
        // would be an overkill (and make the test logic more complicated).
        mWipeExternalDataCalled = true;
    }

    private void expectRebootWipeUserData() {
        doAnswer((inv) -> {
            Log.i(TAG, inv.toString());
            if (!mWipeExternalDataCalled) {
                String error = "rebootWipeUserData() called before wipeAdoptableDisks()";
                Log.e(TAG, error);
                throw new IllegalStateException(error);
            }
            mRebootWipeUserDataLatch.countDown();
            return null;
        }).when(() -> RecoverySystem
                .rebootWipeUserData(any(), anyBoolean(), any(), anyBoolean(), anyBoolean(), anyBoolean()));
    }

    private void expectWipeExternalData() {
        Looper.prepare(); // needed by Dialog

        doAnswer((inv) -> {
            Log.i(TAG, inv.toString());
            mWipeExternalDataCalled = true;
            return null;
        }).when(mSm).wipeAdoptableDisks();
    }

    private void expectWipeNonSystemUser() {
        when(mUserManager.removeUserWhenPossible(any(), anyBoolean()))
                .thenReturn(UserManager.REMOVE_RESULT_REMOVED);
    }

    private void expectHeadlessSystemUserMode() {
        doAnswer((inv) -> {
            Log.i(TAG, inv.toString());
            return true;
        }).when(() -> UserManager.isHeadlessSystemUserMode());
    }

    private void verifyRebootWipeUserData() throws Exception  {
        verifyRebootWipeUserData(/* shutdown= */ false, /* reason= */ null, /* force= */ false,
                /* wipeEuicc= */ false);

    }

    private void verifyRebootWipeUserData(boolean shutdown, String reason, boolean force,
            boolean wipeEuicc) throws Exception {
        verifyRebootWipeUserData(shutdown, reason, force, wipeEuicc, /* keepMemtagMode= */ false);
    }

    private void verifyRebootWipeUserData(boolean shutdown, String reason, boolean force,
            boolean wipeEuicc, boolean keepMemtagMode) throws Exception {
        boolean called = mRebootWipeUserDataLatch.await(5, TimeUnit.SECONDS);
        assertWithMessage("rebootWipeUserData not called in 5s").that(called).isTrue();

        verify(()-> RecoverySystem.rebootWipeUserData(same(mContext), eq(shutdown), eq(reason),
                eq(force), eq(wipeEuicc), eq(keepMemtagMode)));
    }

    private void verifyNoRebootWipeUserData() {
        verify(()-> RecoverySystem.rebootWipeUserData(
                any(), anyBoolean(), anyString(), anyBoolean(), anyBoolean()), never());
    }

    private void verifyWipeExternalData() {
        verify(mSm).wipeAdoptableDisks();
    }

    private void verifyNoWipeExternalData() {
        verify(mSm, never()).wipeAdoptableDisks();
    }

    private void verifyWipeNonSystemUser() {
        verify(mUserManager).removeUserWhenPossible(any(), anyBoolean());
    }

    private void setPendingResultForUser(int userId) {
        mReceiver.setPendingResult(new BroadcastReceiver.PendingResult(
                Activity.RESULT_OK,
                "resultData",
                /* resultExtras= */ null,
                BroadcastReceiver.PendingResult.TYPE_UNREGISTERED,
                /* ordered= */ true,
                /* sticky= */ false,
                /* token= */ null,
                userId,
                /* flags= */ 0));
    }
}
