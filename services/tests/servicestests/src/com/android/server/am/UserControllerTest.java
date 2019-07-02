/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.am;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.testing.DexmakerShareClassLoaderRule.runWithDexmakerShareClassLoader;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.android.server.am.UserController.CONTINUE_USER_SWITCH_MSG;
import static com.android.server.am.UserController.REPORT_LOCKED_BOOT_COMPLETE_MSG;
import static com.android.server.am.UserController.REPORT_USER_SWITCH_COMPLETE_MSG;
import static com.android.server.am.UserController.REPORT_USER_SWITCH_MSG;
import static com.android.server.am.UserController.SYSTEM_USER_CURRENT_MSG;
import static com.android.server.am.UserController.SYSTEM_USER_START_MSG;
import static com.android.server.am.UserController.USER_SWITCH_TIMEOUT_MSG;

import static com.google.android.collect.Lists.newArrayList;
import static com.google.android.collect.Sets.newHashSet;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.validateMockitoUsage;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.IUserSwitchObserver;
import android.content.Context;
import android.content.IIntentReceiver;
import android.content.Intent;
import android.content.pm.UserInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IRemoteCallback;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManagerInternal;
import android.os.storage.IStorageManager;
import android.platform.test.annotations.Presubmit;
import android.util.Log;


import androidx.test.filters.FlakyTest;
import androidx.test.filters.SmallTest;

import com.android.server.FgThread;
import com.android.server.pm.UserManagerService;
import com.android.server.wm.WindowManagerService;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Tests for {@link UserController}.
 *
 * Build/Install/Run:
 *  atest FrameworksServicesTests:UserControllerTest
 */
@SmallTest
@Presubmit

public class UserControllerTest {
    // Use big enough user id to avoid picking up already active user id.
    private static final int TEST_USER_ID = 100;
    private static final int TEST_USER_ID1 = 101;
    private static final int TEST_USER_ID2 = 102;
    private static final int NONEXIST_USER_ID = 2;
    private static final String TAG = UserControllerTest.class.getSimpleName();

    private static final long HANDLER_WAIT_TIME_MS = 100;

    private UserController mUserController;
    private TestInjector mInjector;
    private final HashMap<Integer, UserState> mUserStates = new HashMap<>();

    private static final List<String> START_FOREGROUND_USER_ACTIONS = newArrayList(
            Intent.ACTION_USER_STARTED,
            Intent.ACTION_USER_SWITCHED,
            Intent.ACTION_USER_STARTING);

    private static final List<String> START_BACKGROUND_USER_ACTIONS = newArrayList(
            Intent.ACTION_USER_STARTED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_USER_STARTING);

    private static final Set<Integer> START_FOREGROUND_USER_MESSAGE_CODES = newHashSet(
            REPORT_USER_SWITCH_MSG,
            USER_SWITCH_TIMEOUT_MSG,
            SYSTEM_USER_START_MSG,
            SYSTEM_USER_CURRENT_MSG);

    private static final Set<Integer> START_BACKGROUND_USER_MESSAGE_CODES = newHashSet(
            SYSTEM_USER_START_MSG,
            REPORT_LOCKED_BOOT_COMPLETE_MSG);

    @Before
    public void setUp() throws Exception {
        runWithDexmakerShareClassLoader(() -> {
            mInjector = spy(new TestInjector(getInstrumentation().getTargetContext()));
            doNothing().when(mInjector).clearAllLockedTasks(anyString());
            doNothing().when(mInjector).startHomeActivity(anyInt(), anyString());
            doReturn(false).when(mInjector).stackSupervisorSwitchUser(anyInt(), any());
            doNothing().when(mInjector).stackSupervisorResumeFocusedStackTopActivity();
            doNothing().when(mInjector).systemServiceManagerCleanupUser(anyInt());
            doNothing().when(mInjector).activityManagerForceStopPackage(anyInt(), anyString());
            doNothing().when(mInjector).activityManagerOnUserStopped(anyInt());
            doNothing().when(mInjector).clearBroadcastQueueForUser(anyInt());
            doNothing().when(mInjector).stackSupervisorRemoveUser(anyInt());
            mUserController = new UserController(mInjector);
            setUpUser(TEST_USER_ID, 0);
        });
    }

    @After
    public void tearDown() throws Exception {
        mInjector.mHandlerThread.quit();
        validateMockitoUsage();
    }

    @Test
    public void testStartUser_foreground() {
        mUserController.startUser(TEST_USER_ID, true /* foreground */);
        verify(mInjector.getWindowManager()).startFreezingScreen(anyInt(), anyInt());
        verify(mInjector.getWindowManager(), never()).stopFreezingScreen();
        verify(mInjector.getWindowManager(), times(1)).setSwitchingUser(anyBoolean());
        verify(mInjector.getWindowManager()).setSwitchingUser(true);
        verify(mInjector).clearAllLockedTasks(anyString());
        startForegroundUserAssertions();
    }

    @FlakyTest(bugId = 118932054)
    @Test
    public void testStartUser_background() {
        mUserController.startUser(TEST_USER_ID, false /* foreground */);
        verify(mInjector.getWindowManager(), never()).startFreezingScreen(anyInt(), anyInt());
        verify(mInjector.getWindowManager(), never()).setSwitchingUser(anyBoolean());
        verify(mInjector, never()).clearAllLockedTasks(anyString());
        startBackgroundUserAssertions();
    }

    @Test
    public void testStartUserUIDisabled() {
        mUserController.mUserSwitchUiEnabled = false;
        mUserController.startUser(TEST_USER_ID, true /* foreground */);
        verify(mInjector.getWindowManager(), never()).startFreezingScreen(anyInt(), anyInt());
        verify(mInjector.getWindowManager(), never()).stopFreezingScreen();
        verify(mInjector.getWindowManager(), never()).setSwitchingUser(anyBoolean());
        startForegroundUserAssertions();
    }

    private void startUserAssertions(
            List<String> expectedActions, Set<Integer> expectedMessageCodes) {
        assertEquals(expectedActions, getActions(mInjector.mSentIntents));
        Set<Integer> actualCodes = mInjector.mHandler.getMessageCodes();
        assertEquals("Unexpected message sent", expectedMessageCodes, actualCodes);
    }

    private void startBackgroundUserAssertions() {
        startUserAssertions(START_BACKGROUND_USER_ACTIONS, START_BACKGROUND_USER_MESSAGE_CODES);
    }

    private void startForegroundUserAssertions() {
        startUserAssertions(START_FOREGROUND_USER_ACTIONS, START_FOREGROUND_USER_MESSAGE_CODES);
        Message reportMsg = mInjector.mHandler.getMessageForCode(REPORT_USER_SWITCH_MSG);
        assertNotNull(reportMsg);
        UserState userState = (UserState) reportMsg.obj;
        assertNotNull(userState);
        assertEquals(TEST_USER_ID, userState.mHandle.getIdentifier());
        assertEquals("User must be in STATE_BOOTING", UserState.STATE_BOOTING, userState.state);
        assertEquals("Unexpected old user id", 0, reportMsg.arg1);
        assertEquals("Unexpected new user id", TEST_USER_ID, reportMsg.arg2);
    }

    @Test
    public void testFailedStartUserInForeground() {
        mUserController.mUserSwitchUiEnabled = false;
        mUserController.startUserInForeground(NONEXIST_USER_ID);
        verify(mInjector.getWindowManager(), times(1)).setSwitchingUser(anyBoolean());
        verify(mInjector.getWindowManager()).setSwitchingUser(false);
    }

    @Test
    public void testDispatchUserSwitch() throws RemoteException {
        // Prepare mock observer and register it
        IUserSwitchObserver observer = mock(IUserSwitchObserver.class);
        when(observer.asBinder()).thenReturn(new Binder());
        doAnswer(invocation -> {
            IRemoteCallback callback = (IRemoteCallback) invocation.getArguments()[1];
            callback.sendResult(null);
            return null;
        }).when(observer).onUserSwitching(anyInt(), any());
        mUserController.registerUserSwitchObserver(observer, "mock");
        // Start user -- this will update state of mUserController
        mUserController.startUser(TEST_USER_ID, true);
        Message reportMsg = mInjector.mHandler.getMessageForCode(REPORT_USER_SWITCH_MSG);
        assertNotNull(reportMsg);
        UserState userState = (UserState) reportMsg.obj;
        int oldUserId = reportMsg.arg1;
        int newUserId = reportMsg.arg2;
        // Call dispatchUserSwitch and verify that observer was called only once
        mInjector.mHandler.clearAllRecordedMessages();
        mUserController.dispatchUserSwitch(userState, oldUserId, newUserId);
        verify(observer, times(1)).onUserSwitching(eq(TEST_USER_ID), any());
        Set<Integer> expectedCodes = Collections.singleton(CONTINUE_USER_SWITCH_MSG);
        Set<Integer> actualCodes = mInjector.mHandler.getMessageCodes();
        assertEquals("Unexpected message sent", expectedCodes, actualCodes);
        Message conMsg = mInjector.mHandler.getMessageForCode(CONTINUE_USER_SWITCH_MSG);
        assertNotNull(conMsg);
        userState = (UserState) conMsg.obj;
        assertNotNull(userState);
        assertEquals(TEST_USER_ID, userState.mHandle.getIdentifier());
        assertEquals("User must be in STATE_BOOTING", UserState.STATE_BOOTING, userState.state);
        assertEquals("Unexpected old user id", 0, conMsg.arg1);
        assertEquals("Unexpected new user id", TEST_USER_ID, conMsg.arg2);
    }

    @Test
    public void testDispatchUserSwitchBadReceiver() throws RemoteException {
        // Prepare mock observer which doesn't notify the callback and register it
        IUserSwitchObserver observer = mock(IUserSwitchObserver.class);
        when(observer.asBinder()).thenReturn(new Binder());
        mUserController.registerUserSwitchObserver(observer, "mock");
        // Start user -- this will update state of mUserController
        mUserController.startUser(TEST_USER_ID, true);
        Message reportMsg = mInjector.mHandler.getMessageForCode(REPORT_USER_SWITCH_MSG);
        assertNotNull(reportMsg);
        UserState userState = (UserState) reportMsg.obj;
        int oldUserId = reportMsg.arg1;
        int newUserId = reportMsg.arg2;
        // Call dispatchUserSwitch and verify that observer was called only once
        mInjector.mHandler.clearAllRecordedMessages();
        mUserController.dispatchUserSwitch(userState, oldUserId, newUserId);
        verify(observer, times(1)).onUserSwitching(eq(TEST_USER_ID), any());
        // Verify that CONTINUE_USER_SWITCH_MSG is not sent (triggers timeout)
        Set<Integer> actualCodes = mInjector.mHandler.getMessageCodes();
        assertWithMessage("No messages should be sent").that(actualCodes).isEmpty();
    }

    @Test
    public void testContinueUserSwitch() throws RemoteException {
        // Start user -- this will update state of mUserController
        mUserController.startUser(TEST_USER_ID, true);
        Message reportMsg = mInjector.mHandler.getMessageForCode(REPORT_USER_SWITCH_MSG);
        assertNotNull(reportMsg);
        UserState userState = (UserState) reportMsg.obj;
        int oldUserId = reportMsg.arg1;
        int newUserId = reportMsg.arg2;
        mInjector.mHandler.clearAllRecordedMessages();
        // Verify that continueUserSwitch worked as expected
        mUserController.continueUserSwitch(userState, oldUserId, newUserId);
        verify(mInjector.getWindowManager(), times(1)).stopFreezingScreen();
        continueUserSwitchAssertions(TEST_USER_ID, false);
    }

    @Test
    public void testContinueUserSwitchUIDisabled() throws RemoteException {
        mUserController.mUserSwitchUiEnabled = false;
        // Start user -- this will update state of mUserController
        mUserController.startUser(TEST_USER_ID, true);
        Message reportMsg = mInjector.mHandler.getMessageForCode(REPORT_USER_SWITCH_MSG);
        assertNotNull(reportMsg);
        UserState userState = (UserState) reportMsg.obj;
        int oldUserId = reportMsg.arg1;
        int newUserId = reportMsg.arg2;
        mInjector.mHandler.clearAllRecordedMessages();
        // Verify that continueUserSwitch worked as expected
        mUserController.continueUserSwitch(userState, oldUserId, newUserId);
        verify(mInjector.getWindowManager(), never()).stopFreezingScreen();
        continueUserSwitchAssertions(TEST_USER_ID, false);
    }

    private void continueUserSwitchAssertions(int expectedUserId, boolean backgroundUserStopping)
            throws RemoteException {
        Set<Integer> expectedCodes = new LinkedHashSet<>();
        expectedCodes.add(REPORT_USER_SWITCH_COMPLETE_MSG);
        if (backgroundUserStopping) {
            expectedCodes.add(0); // this is for directly posting in stopping.
        }
        Set<Integer> actualCodes = mInjector.mHandler.getMessageCodes();
        assertEquals("Unexpected message sent", expectedCodes, actualCodes);
        Message msg = mInjector.mHandler.getMessageForCode(REPORT_USER_SWITCH_COMPLETE_MSG);
        assertNotNull(msg);
        assertEquals("Unexpected userId", expectedUserId, msg.arg1);
    }

    @Test
    public void testDispatchUserSwitchComplete() throws RemoteException {
        // Prepare mock observer and register it
        IUserSwitchObserver observer = mock(IUserSwitchObserver.class);
        when(observer.asBinder()).thenReturn(new Binder());
        mUserController.registerUserSwitchObserver(observer, "mock");
        // Start user -- this will update state of mUserController
        mUserController.startUser(TEST_USER_ID, true);
        Message reportMsg = mInjector.mHandler.getMessageForCode(REPORT_USER_SWITCH_MSG);
        assertNotNull(reportMsg);
        int newUserId = reportMsg.arg2;
        mInjector.mHandler.clearAllRecordedMessages();
        // Mockito can't reset only interactions, so just verify that this hasn't been
        // called with 'false' until after dispatchUserSwitchComplete.
        verify(mInjector.getWindowManager(), never()).setSwitchingUser(false);
        // Call dispatchUserSwitchComplete
        mUserController.dispatchUserSwitchComplete(newUserId);
        verify(observer, times(1)).onUserSwitchComplete(anyInt());
        verify(observer).onUserSwitchComplete(TEST_USER_ID);
        verify(mInjector.getWindowManager(), times(1)).setSwitchingUser(false);
    }

    @Test
    public void testExplicitSystenUserStartInBackground() {
        setUpUser(UserHandle.USER_SYSTEM, 0);
        assertFalse(mUserController.isSystemUserStarted());
        assertTrue(mUserController.startUser(UserHandle.USER_SYSTEM, false, null));
        assertTrue(mUserController.isSystemUserStarted());
    }

    /**
     * Test stopping of user from max running users limit.
     */
    @Test
    public void testUserStoppingForMultipleUsersNormalMode()
            throws InterruptedException, RemoteException {
        setUpUser(TEST_USER_ID1, 0);
        setUpUser(TEST_USER_ID2, 0);
        mUserController.mMaxRunningUsers = 3;
        int numerOfUserSwitches = 1;
        addForegroundUserAndContinueUserSwitch(TEST_USER_ID, UserHandle.USER_SYSTEM,
                numerOfUserSwitches, false);
        // running: user 0, USER_ID
        assertTrue(mUserController.canStartMoreUsers());
        assertEquals(Arrays.asList(new Integer[] {0, TEST_USER_ID}),
                mUserController.getRunningUsersLU());

        numerOfUserSwitches++;
        addForegroundUserAndContinueUserSwitch(TEST_USER_ID1, TEST_USER_ID,
                numerOfUserSwitches, false);
        // running: user 0, USER_ID, USER_ID1
        assertFalse(mUserController.canStartMoreUsers());
        assertEquals(Arrays.asList(new Integer[] {0, TEST_USER_ID, TEST_USER_ID1}),
                mUserController.getRunningUsersLU());

        numerOfUserSwitches++;
        addForegroundUserAndContinueUserSwitch(TEST_USER_ID2, TEST_USER_ID1,
                numerOfUserSwitches, false);
        UserState ussUser2 = mUserStates.get(TEST_USER_ID2);
        // skip middle step and call this directly.
        mUserController.finishUserSwitch(ussUser2);
        waitForHandlerToComplete(mInjector.mHandler, HANDLER_WAIT_TIME_MS);
        // running: user 0, USER_ID1, USER_ID2
        // USER_ID should be stopped as it is least recently used non user0.
        assertFalse(mUserController.canStartMoreUsers());
        assertEquals(Arrays.asList(new Integer[] {0, TEST_USER_ID1, TEST_USER_ID2}),
                mUserController.getRunningUsersLU());
    }

    /**
     * This test tests delayed locking mode using 4 users. As core logic of delayed locking is
     * happening in finishUserStopped call, the test also calls finishUserStopped while skipping
     * all middle steps which takes too much work to mock.
     */
    @Test
    public void testUserStoppingForMultipleUsersDelayedLockingMode()
            throws InterruptedException, RemoteException {
        setUpUser(TEST_USER_ID1, 0);
        setUpUser(TEST_USER_ID2, 0);
        mUserController.mMaxRunningUsers = 3;
        mUserController.mDelayUserDataLocking = true;
        int numerOfUserSwitches = 1;
        addForegroundUserAndContinueUserSwitch(TEST_USER_ID, UserHandle.USER_SYSTEM,
                numerOfUserSwitches, false);
        // running: user 0, USER_ID
        assertTrue(mUserController.canStartMoreUsers());
        assertEquals(Arrays.asList(new Integer[] {0, TEST_USER_ID}),
                mUserController.getRunningUsersLU());
        numerOfUserSwitches++;

        addForegroundUserAndContinueUserSwitch(TEST_USER_ID1, TEST_USER_ID,
                numerOfUserSwitches, true);
        // running: user 0, USER_ID1
        // stopped + unlocked: USER_ID
        numerOfUserSwitches++;
        assertTrue(mUserController.canStartMoreUsers());
        assertEquals(Arrays.asList(new Integer[] {0, TEST_USER_ID1}),
                mUserController.getRunningUsersLU());
        // Skip all other steps and test unlock delaying only
        UserState uss = mUserStates.get(TEST_USER_ID);
        uss.setState(UserState.STATE_SHUTDOWN); // necessary state change from skipped part
        mUserController.finishUserStopped(uss);
        // Cannot mock FgThread handler, so confirm that there is no posted message left before
        // checking.
        waitForHandlerToComplete(FgThread.getHandler(), HANDLER_WAIT_TIME_MS);
        verify(mInjector.mStorageManagerMock, times(0))
                .lockUserKey(anyInt());

        addForegroundUserAndContinueUserSwitch(TEST_USER_ID2, TEST_USER_ID1,
                numerOfUserSwitches, true);
        // running: user 0, USER_ID2
        // stopped + unlocked: USER_ID1
        // stopped + locked: USER_ID
        assertTrue(mUserController.canStartMoreUsers());
        assertEquals(Arrays.asList(new Integer[] {0, TEST_USER_ID2}),
                mUserController.getRunningUsersLU());
        UserState ussUser1 = mUserStates.get(TEST_USER_ID1);
        ussUser1.setState(UserState.STATE_SHUTDOWN);
        mUserController.finishUserStopped(ussUser1);
        waitForHandlerToComplete(FgThread.getHandler(), HANDLER_WAIT_TIME_MS);
        verify(mInjector.mStorageManagerMock, times(1))
                .lockUserKey(TEST_USER_ID);
    }

    private void addForegroundUserAndContinueUserSwitch(int newUserId, int expectedOldUserId,
            int expectedNumberOfCalls, boolean expectOldUserStopping)
            throws RemoteException {
        // Start user -- this will update state of mUserController
        mUserController.startUser(newUserId, true);
        Message reportMsg = mInjector.mHandler.getMessageForCode(REPORT_USER_SWITCH_MSG);
        assertNotNull(reportMsg);
        UserState userState = (UserState) reportMsg.obj;
        int oldUserId = reportMsg.arg1;
        assertEquals(expectedOldUserId, oldUserId);
        assertEquals(newUserId, reportMsg.arg2);
        mUserStates.put(newUserId, userState);
        mInjector.mHandler.clearAllRecordedMessages();
        // Verify that continueUserSwitch worked as expected
        mUserController.continueUserSwitch(userState, oldUserId, newUserId);
        verify(mInjector.getWindowManager(), times(expectedNumberOfCalls))
                .stopFreezingScreen();
        continueUserSwitchAssertions(newUserId, expectOldUserStopping);
    }

    private void setUpUser(int userId, int flags) {
        UserInfo userInfo = new UserInfo(userId, "User" + userId, flags);
        when(mInjector.mUserManagerMock.getUserInfo(eq(userId))).thenReturn(userInfo);
    }

    private static List<String> getActions(List<Intent> intents) {
        List<String> result = new ArrayList<>();
        for (Intent intent : intents) {
            result.add(intent.getAction());
        }
        return result;
    }

    private void waitForHandlerToComplete(Handler handler, long waitTimeMs)
            throws InterruptedException {
        final Object lock = new Object();
        synchronized (lock) {
            handler.post(() -> {
                synchronized (lock) {
                    lock.notify();
                }
            });
            lock.wait(waitTimeMs);
        }
    }

    // Should be public to allow mocking
    private static class TestInjector extends UserController.Injector {
        public final TestHandler mHandler;
        public final HandlerThread mHandlerThread;
        public final UserManagerService mUserManagerMock;
        public final List<Intent> mSentIntents = new ArrayList<>();

        private final TestHandler mUiHandler;

        private final IStorageManager mStorageManagerMock;
        private final UserManagerInternal mUserManagerInternalMock;
        private final WindowManagerService mWindowManagerMock;

        private final Context mCtx;

        TestInjector(Context ctx) {
            super(null);
            mCtx = ctx;
            mHandlerThread = new HandlerThread(TAG);
            mHandlerThread.start();
            mHandler = new TestHandler(mHandlerThread.getLooper());
            mUiHandler = new TestHandler(mHandlerThread.getLooper());
            mUserManagerMock = mock(UserManagerService.class);
            mUserManagerInternalMock = mock(UserManagerInternal.class);
            mWindowManagerMock = mock(WindowManagerService.class);
            mStorageManagerMock = mock(IStorageManager.class);
        }

        @Override
        protected Handler getHandler(Handler.Callback callback) {
            return mHandler;
        }

        @Override
        protected Handler getUiHandler(Handler.Callback callback) {
            return mUiHandler;
        }

        @Override
        protected UserManagerService getUserManager() {
            return mUserManagerMock;
        }

        @Override
        UserManagerInternal getUserManagerInternal() {
            return mUserManagerInternalMock;
        }

        @Override
        protected Context getContext() {
            return mCtx;
        }

        @Override
        int checkCallingPermission(String permission) {
            Log.i(TAG, "checkCallingPermission " + permission);
            return PERMISSION_GRANTED;
        }

        @Override
        WindowManagerService getWindowManager() {
            return mWindowManagerMock;
        }

        @Override
        void updateUserConfiguration() {
            Log.i(TAG, "updateUserConfiguration");
        }

        @Override
        protected int broadcastIntent(Intent intent, String resolvedType,
                IIntentReceiver resultTo, int resultCode, String resultData, Bundle resultExtras,
                String[] requiredPermissions, int appOp, Bundle bOptions, boolean ordered,
                boolean sticky, int callingPid, int callingUid, int realCallingUid,
                int realCallingPid, int userId) {
            Log.i(TAG, "broadcastIntentLocked " + intent);
            mSentIntents.add(intent);
            return 0;
        }

        @Override
        void reportGlobalUsageEventLocked(int event) {
        }

        @Override
        void reportCurWakefulnessUsageEvent() {
        }

        @Override
        boolean isRuntimeRestarted() {
            // to pass all metrics related calls
            return true;
        }

        @Override
        protected IStorageManager getStorageManager() {
            return mStorageManagerMock;
        }
    }

    private static class TestHandler extends Handler {
        private final List<Message> mMessages = new ArrayList<>();

        TestHandler(Looper looper) {
            super(looper);
        }

        Set<Integer> getMessageCodes() {
            Set<Integer> result = new LinkedHashSet<>();
            for (Message msg : mMessages) {
                result.add(msg.what);
            }
            return result;
        }

        Message getMessageForCode(int what) {
            for (Message msg : mMessages) {
                if (msg.what == what) {
                    return msg;
                }
            }
            return null;
        }

        void clearAllRecordedMessages() {
            mMessages.clear();
        }

        @Override
        public boolean sendMessageAtTime(Message msg, long uptimeMillis) {
            Message copy = new Message();
            copy.copyFrom(msg);
            mMessages.add(copy);
            return super.sendMessageAtTime(msg, uptimeMillis);
        }
    }
}
