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

import android.content.Context;
import android.content.IIntentReceiver;
import android.content.Intent;
import android.content.pm.UserInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.RemoteException;
import android.os.UserManagerInternal;
import android.test.AndroidTestCase;
import android.util.Log;

import com.android.server.pm.UserManagerService;
import com.android.server.wm.WindowManagerService;

import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

public class UserControllerTest extends AndroidTestCase {
    private static String TAG = UserControllerTest.class.getSimpleName();
    private UserController mUserController;
    private TestInjector mInjector;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mInjector = new TestInjector(getContext());
        mUserController = new UserController(mInjector);
        setUpUser(10, 0);
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        mInjector.handlerThread.quit();

    }

    public void testStartUser() throws RemoteException {
        mUserController.startUser(10, true);

        Mockito.verify(mInjector.getWindowManager()).startFreezingScreen(anyInt(), anyInt());
        Mockito.verify(mInjector.getWindowManager(), never()).stopFreezingScreen();
        List<String> expectedActions = Arrays.asList(Intent.ACTION_USER_STARTED,
                Intent.ACTION_USER_SWITCHED, Intent.ACTION_USER_STARTING);
        assertEquals(expectedActions, getActions(mInjector.sentIntents));
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

    private static class TestInjector extends UserController.Injector {
        final Object lock = new Object();
        Handler handler;
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
            handler = new Handler(handlerThread.getLooper());
            userManagerMock = mock(UserManagerService.class);
            userManagerInternalMock = mock(UserManagerInternal.class);
            windowManagerMock = mock(WindowManagerService.class);
        }

        @Override
        protected Object getLock() {
            return lock;
        }

        @Override
        protected Handler getHandler() {
            return handler;
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
        void stackSupervisorSetLockTaskModeLocked(TaskRecord task, int lockTaskModeState,
                String reason, boolean andResume) {
            Log.i(TAG, "stackSupervisorSetLockTaskModeLocked");
        }

        @Override
        WindowManagerService getWindowManager() {
            return windowManagerMock;
        }

        @Override
        void updateUserConfigurationLocked() {
            Log.i(TAG, "updateUserConfigurationLocked");
        }

        @Override
        protected int broadcastIntentLocked(Intent intent, String resolvedType,
                IIntentReceiver resultTo, int resultCode, String resultData, Bundle resultExtras,
                String[] requiredPermissions, int appOp, Bundle bOptions, boolean ordered,
                boolean sticky, int callingPid, int callingUid, int userId) {
            Log.i(TAG, "broadcastIntentLocked " + intent);
            sentIntents.add(intent);
            return 0;
        }

        @Override
        boolean stackSupervisorSwitchUserLocked(int userId, UserState uss) {
            Log.i(TAG, "stackSupervisorSwitchUserLocked " + userId);
            return true;
        }

        @Override
        void startHomeActivityLocked(int userId, String reason) {
            Log.i(TAG, "startHomeActivityLocked " + userId);
        }
   }
}