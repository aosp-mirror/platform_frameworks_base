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
 * limitations under the License
 */

package com.android.server.am;

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
import android.os.UserManagerInternal;
import android.platform.test.annotations.Presubmit;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Log;

import com.android.server.pm.UserManagerService;
import com.android.server.wm.WindowManagerService;

import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.testing.DexmakerShareClassLoaderRule.runWithDexmakerShareClassLoader;
import static com.android.server.am.UserController.CONTINUE_USER_SWITCH_MSG;
import static com.android.server.am.UserController.REPORT_LOCKED_BOOT_COMPLETE_MSG;
import static com.android.server.am.UserController.REPORT_USER_SWITCH_COMPLETE_MSG;
import static com.android.server.am.UserController.REPORT_USER_SWITCH_MSG;
import static com.android.server.am.UserController.SYSTEM_USER_CURRENT_MSG;
import static com.android.server.am.UserController.SYSTEM_USER_START_MSG;
import static com.android.server.am.UserController.USER_SWITCH_TIMEOUT_MSG;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

/**
 * Usage: bit FrameworksServicesTests:com.android.server.am.UserControllerTest
 */
@Presubmit
public class UserControllerTest extends AndroidTestCase {
    private static final int TEST_USER_ID = 10;
    private static final int NONEXIST_USER_ID = 2;
    private static String TAG = UserControllerTest.class.getSimpleName();
    private UserController mUserController;
    private TestInjector mInjector;

    private static final List<String> START_FOREGROUND_USER_ACTIONS =
            Arrays.asList(
                    Intent.ACTION_USER_STARTED,
                    Intent.ACTION_USER_SWITCHED,
                    Intent.ACTION_USER_STARTING);

    private static final List<String> START_BACKGROUND_USER_ACTIONS =
            Arrays.asList(
                    Intent.ACTION_USER_STARTED,
                    Intent.ACTION_LOCKED_BOOT_COMPLETED,
                    Intent.ACTION_USER_STARTING);

    private static final Set<Integer> START_FOREGROUND_USER_MESSAGE_CODES =
            new HashSet<>(Arrays.asList(REPORT_USER_SWITCH_MSG, USER_SWITCH_TIMEOUT_MSG,
                    SYSTEM_USER_START_MSG, SYSTEM_USER_CURRENT_MSG));

    private static final Set<Integer> START_BACKGROUND_USER_MESSAGE_CODES =
            new HashSet<>(Arrays.asList(SYSTEM_USER_START_MSG, REPORT_LOCKED_BOOT_COMPLETE_MSG));

    @Override
    public void setUp() throws Exception {
        super.setUp();
        runWithDexmakerShareClassLoader(() -> {
            mInjector = Mockito.spy(new TestInjector(getContext()));
            doNothing().when(mInjector).clearAllLockedTasks(anyString());
            doNothing().when(mInjector).startHomeActivity(anyInt(), anyString());
            doReturn(false).when(mInjector).stackSupervisorSwitchUser(anyInt(), any());
            doNothing().when(mInjector).stackSupervisorResumeFocusedStackTopActivity();
            mUserController = new UserController(mInjector);
            setUpUser(TEST_USER_ID, 0);
        });
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        mInjector.handlerThread.quit();
        Mockito.validateMockitoUsage();
    }

    @SmallTest
    public void testStartUser_foreground() throws RemoteException {
        mUserController.startUser(TEST_USER_ID, true /* foreground */);
        Mockito.verify(mInjector.getWindowManager()).startFreezingScreen(anyInt(), anyInt());
        Mockito.verify(mInjector.getWindowManager(), never()).stopFreezingScreen();
        Mockito.verify(mInjector.getWindowManager(), times(1)).setSwitchingUser(anyBoolean());
        Mockito.verify(mInjector.getWindowManager()).setSwitchingUser(true);
        Mockito.verify(mInjector).clearAllLockedTasks(anyString());
        startForegroundUserAssertions();
    }

    @SmallTest
    public void testStartUser_background() throws RemoteException {
        mUserController.startUser(TEST_USER_ID, false /* foreground */);
        Mockito.verify(
                mInjector.getWindowManager(), never()).startFreezingScreen(anyInt(), anyInt());
        Mockito.verify(mInjector.getWindowManager(), never()).setSwitchingUser(anyBoolean());
        Mockito.verify(mInjector, never()).clearAllLockedTasks(anyString());
        startBackgroundUserAssertions();
    }

    @SmallTest
    public void testStartUserUIDisabled() throws RemoteException {
        mUserController.mUserSwitchUiEnabled = false;
        mUserController.startUser(TEST_USER_ID, true /* foreground */);
        Mockito.verify(mInjector.getWindowManager(), never())
                .startFreezingScreen(anyInt(), anyInt());
        Mockito.verify(mInjector.getWindowManager(), never()).stopFreezingScreen();
        Mockito.verify(mInjector.getWindowManager(), never()).setSwitchingUser(anyBoolean());
        startForegroundUserAssertions();
    }

    private void startUserAssertions(
            List<String> expectedActions, Set<Integer> expectedMessageCodes)
            throws RemoteException {
        assertEquals(expectedActions, getActions(mInjector.sentIntents));
        Set<Integer> actualCodes = mInjector.handler.getMessageCodes();
        assertEquals("Unexpected message sent", expectedMessageCodes, actualCodes);
    }

    private void startBackgroundUserAssertions() throws RemoteException {
        startUserAssertions(START_BACKGROUND_USER_ACTIONS, START_BACKGROUND_USER_MESSAGE_CODES);
    }

    private void startForegroundUserAssertions() throws RemoteException {
        startUserAssertions(START_FOREGROUND_USER_ACTIONS, START_FOREGROUND_USER_MESSAGE_CODES);
        Message reportMsg = mInjector.handler.getMessageForCode(REPORT_USER_SWITCH_MSG);
        assertNotNull(reportMsg);
        UserState userState = (UserState) reportMsg.obj;
        assertNotNull(userState);
        assertEquals(TEST_USER_ID, userState.mHandle.getIdentifier());
        assertEquals("User must be in STATE_BOOTING", UserState.STATE_BOOTING, userState.state);
        assertEquals("Unexpected old user id", 0, reportMsg.arg1);
        assertEquals("Unexpected new user id", TEST_USER_ID, reportMsg.arg2);
    }

    @SmallTest
    public void testFailedStartUserInForeground() throws RemoteException {
        mUserController.mUserSwitchUiEnabled = false;
        mUserController.startUserInForeground(NONEXIST_USER_ID);
        Mockito.verify(mInjector.getWindowManager(), times(1)).setSwitchingUser(anyBoolean());
        Mockito.verify(mInjector.getWindowManager()).setSwitchingUser(false);
    }

    @SmallTest
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
        Message reportMsg = mInjector.handler.getMessageForCode(REPORT_USER_SWITCH_MSG);
        assertNotNull(reportMsg);
        UserState userState = (UserState) reportMsg.obj;
        int oldUserId = reportMsg.arg1;
        int newUserId = reportMsg.arg2;
        // Call dispatchUserSwitch and verify that observer was called only once
        mInjector.handler.clearAllRecordedMessages();
        mUserController.dispatchUserSwitch(userState, oldUserId, newUserId);
        Mockito.verify(observer, times(1)).onUserSwitching(eq(TEST_USER_ID), any());
        Set<Integer> expectedCodes = Collections.singleton(CONTINUE_USER_SWITCH_MSG);
        Set<Integer> actualCodes = mInjector.handler.getMessageCodes();
        assertEquals("Unexpected message sent", expectedCodes, actualCodes);
        Message conMsg = mInjector.handler.getMessageForCode(CONTINUE_USER_SWITCH_MSG);
        assertNotNull(conMsg);
        userState = (UserState) conMsg.obj;
        assertNotNull(userState);
        assertEquals(TEST_USER_ID, userState.mHandle.getIdentifier());
        assertEquals("User must be in STATE_BOOTING", UserState.STATE_BOOTING, userState.state);
        assertEquals("Unexpected old user id", 0, conMsg.arg1);
        assertEquals("Unexpected new user id", TEST_USER_ID, conMsg.arg2);
    }

    @SmallTest
    public void testDispatchUserSwitchBadReceiver() throws RemoteException {
        // Prepare mock observer which doesn't notify the callback and register it
        IUserSwitchObserver observer = mock(IUserSwitchObserver.class);
        when(observer.asBinder()).thenReturn(new Binder());
        mUserController.registerUserSwitchObserver(observer, "mock");
        // Start user -- this will update state of mUserController
        mUserController.startUser(TEST_USER_ID, true);
        Message reportMsg = mInjector.handler.getMessageForCode(REPORT_USER_SWITCH_MSG);
        assertNotNull(reportMsg);
        UserState userState = (UserState) reportMsg.obj;
        int oldUserId = reportMsg.arg1;
        int newUserId = reportMsg.arg2;
        // Call dispatchUserSwitch and verify that observer was called only once
        mInjector.handler.clearAllRecordedMessages();
        mUserController.dispatchUserSwitch(userState, oldUserId, newUserId);
        Mockito.verify(observer, times(1)).onUserSwitching(eq(TEST_USER_ID), any());
        // Verify that CONTINUE_USER_SWITCH_MSG is not sent (triggers timeout)
        Set<Integer> actualCodes = mInjector.handler.getMessageCodes();
        assertTrue("No messages should be sent", actualCodes.isEmpty());
    }

    @SmallTest
    public void testContinueUserSwitch() throws RemoteException {
        // Start user -- this will update state of mUserController
        mUserController.startUser(TEST_USER_ID, true);
        Message reportMsg = mInjector.handler.getMessageForCode(REPORT_USER_SWITCH_MSG);
        assertNotNull(reportMsg);
        UserState userState = (UserState) reportMsg.obj;
        int oldUserId = reportMsg.arg1;
        int newUserId = reportMsg.arg2;
        mInjector.handler.clearAllRecordedMessages();
        // Verify that continueUserSwitch worked as expected
        mUserController.continueUserSwitch(userState, oldUserId, newUserId);
        Mockito.verify(mInjector.getWindowManager(), times(1)).stopFreezingScreen();
        continueUserSwitchAssertions();
    }

    @SmallTest
    public void testContinueUserSwitchUIDisabled() throws RemoteException {
        mUserController.mUserSwitchUiEnabled = false;
        // Start user -- this will update state of mUserController
        mUserController.startUser(TEST_USER_ID, true);
        Message reportMsg = mInjector.handler.getMessageForCode(REPORT_USER_SWITCH_MSG);
        assertNotNull(reportMsg);
        UserState userState = (UserState) reportMsg.obj;
        int oldUserId = reportMsg.arg1;
        int newUserId = reportMsg.arg2;
        mInjector.handler.clearAllRecordedMessages();
        // Verify that continueUserSwitch worked as expected
        mUserController.continueUserSwitch(userState, oldUserId, newUserId);
        Mockito.verify(mInjector.getWindowManager(), never()).stopFreezingScreen();
        continueUserSwitchAssertions();
    }

    private void continueUserSwitchAssertions() throws RemoteException {
        Set<Integer> expectedCodes = Collections.singleton(REPORT_USER_SWITCH_COMPLETE_MSG);
        Set<Integer> actualCodes = mInjector.handler.getMessageCodes();
        assertEquals("Unexpected message sent", expectedCodes, actualCodes);
        Message msg = mInjector.handler.getMessageForCode(REPORT_USER_SWITCH_COMPLETE_MSG);
        assertNotNull(msg);
        assertEquals("Unexpected userId", TEST_USER_ID, msg.arg1);
    }

    @SmallTest
    public void testDispatchUserSwitchComplete() throws RemoteException {
        // Prepare mock observer and register it
        IUserSwitchObserver observer = mock(IUserSwitchObserver.class);
        when(observer.asBinder()).thenReturn(new Binder());
        mUserController.registerUserSwitchObserver(observer, "mock");
        // Start user -- this will update state of mUserController
        mUserController.startUser(TEST_USER_ID, true);
        Message reportMsg = mInjector.handler.getMessageForCode(REPORT_USER_SWITCH_MSG);
        assertNotNull(reportMsg);
        int newUserId = reportMsg.arg2;
        mInjector.handler.clearAllRecordedMessages();
        // Mockito can't reset only interactions, so just verify that this hasn't been
        // called with 'false' until after dispatchUserSwitchComplete.
        Mockito.verify(mInjector.getWindowManager(), never()).setSwitchingUser(false);
        // Call dispatchUserSwitchComplete
        mUserController.dispatchUserSwitchComplete(newUserId);
        Mockito.verify(observer, times(1)).onUserSwitchComplete(anyInt());
        Mockito.verify(observer).onUserSwitchComplete(TEST_USER_ID);
        Mockito.verify(mInjector.getWindowManager(), times(1)).setSwitchingUser(false);
    }

    private void setUpUser(int userId, int flags) {
        UserInfo userInfo = new UserInfo(userId, "User" + userId, flags);
        when(mInjector.userManagerMock.getUserInfo(eq(userId))).thenReturn(userInfo);
    }

    private static List<String> getActions(List<Intent> intents) {
        List<String> result = new ArrayList<>();
        for (Intent intent : intents) {
            result.add(intent.getAction());
        }
        return result;
    }

    // Should be public to allow mocking
    public static class TestInjector extends UserController.Injector {
        TestHandler handler;
        TestHandler uiHandler;
        HandlerThread handlerThread;
        UserManagerService userManagerMock;
        UserManagerInternal userManagerInternalMock;
        WindowManagerService windowManagerMock;
        private Context mCtx;
        List<Intent> sentIntents = new ArrayList<>();

        TestInjector(Context ctx) {
            super(null);
            mCtx = ctx;
            handlerThread = new HandlerThread(TAG);
            handlerThread.start();
            handler = new TestHandler(handlerThread.getLooper());
            uiHandler = new TestHandler(handlerThread.getLooper());
            userManagerMock = mock(UserManagerService.class);
            userManagerInternalMock = mock(UserManagerInternal.class);
            windowManagerMock = mock(WindowManagerService.class);
        }

        @Override
        protected Handler getHandler(Handler.Callback callback) {
            return handler;
        }

        @Override
        protected Handler getUiHandler(Handler.Callback callback) {
            return uiHandler;
        }

        @Override
        protected UserManagerService getUserManager() {
            return userManagerMock;
        }

        @Override
        UserManagerInternal getUserManagerInternal() {
            return userManagerInternalMock;
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
            return windowManagerMock;
        }

        @Override
        void updateUserConfiguration() {
            Log.i(TAG, "updateUserConfiguration");
        }

        @Override
        protected int broadcastIntent(Intent intent, String resolvedType,
                IIntentReceiver resultTo, int resultCode, String resultData, Bundle resultExtras,
                String[] requiredPermissions, int appOp, Bundle bOptions, boolean ordered,
                boolean sticky, int callingPid, int callingUid, int userId) {
            Log.i(TAG, "broadcastIntentLocked " + intent);
            sentIntents.add(intent);
            return 0;
        }

        @Override
        void reportGlobalUsageEventLocked(int event) {
        }

        @Override
        void reportCurWakefulnessUsageEvent() {
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