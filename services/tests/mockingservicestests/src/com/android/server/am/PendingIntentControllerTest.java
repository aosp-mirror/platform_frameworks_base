/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.AppGlobals;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.IPackageManager;
import android.os.Looper;

import androidx.test.runner.AndroidJUnit4;

import com.android.server.AlarmManagerInternal;
import com.android.server.LocalServices;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

@RunWith(AndroidJUnit4.class)
public class PendingIntentControllerTest {
    private static final String TEST_PACKAGE_NAME = "test-package-1";
    private static final String TEST_FEATURE_ID = "test-feature-1";
    private static final int TEST_CALLING_UID = android.os.Process.myUid();
    private static final Intent[] TEST_INTENTS = new Intent[]{new Intent("com.test.intent")};

    @Mock
    private UserController mUserController;
    @Mock
    private AlarmManagerInternal mAlarmManagerInternal;
    @Mock
    private ActivityManagerInternal mActivityManagerInternal;
    @Mock
    private IPackageManager mIPackageManager;

    private MockitoSession mMockingSession;
    private PendingIntentController mPendingIntentController;

    @Before
    public void setUp() throws Exception {
        mMockingSession = mockitoSession()
                .initMocks(this)
                .mockStatic(LocalServices.class)
                .mockStatic(AppGlobals.class)
                .strictness(Strictness.LENIENT) // Needed to stub LocalServices.getService twice
                .startMocking();
        doReturn(mAlarmManagerInternal).when(
                () -> LocalServices.getService(AlarmManagerInternal.class));
        doReturn(mActivityManagerInternal).when(
                () -> LocalServices.getService(ActivityManagerInternal.class));
        doReturn(mIPackageManager).when(() -> AppGlobals.getPackageManager());
        when(mIPackageManager.getPackageUid(eq(TEST_PACKAGE_NAME), anyInt(), anyInt())).thenReturn(
                TEST_CALLING_UID);
        ActivityManagerConstants constants = mock(ActivityManagerConstants.class);
        constants.PENDINGINTENT_WARNING_THRESHOLD = 2000;
        mPendingIntentController = new PendingIntentController(Looper.getMainLooper(),
                mUserController, constants);
        mPendingIntentController.onActivityManagerInternalAdded();
    }

    private PendingIntentRecord createPendingIntentRecord(int flags) {
        return mPendingIntentController.getIntentSender(ActivityManager.INTENT_SENDER_BROADCAST,
                TEST_PACKAGE_NAME, TEST_FEATURE_ID, TEST_CALLING_UID, 0, null, null, 0,
                TEST_INTENTS, null, flags, null);
    }

    @Test
    public void alarmsRemovedOnCancel() {
        final PendingIntentRecord pir = createPendingIntentRecord(0);
        mPendingIntentController.cancelIntentSender(pir);
        final ArgumentCaptor<PendingIntent> piCaptor = ArgumentCaptor.forClass(PendingIntent.class);
        verify(mAlarmManagerInternal).remove(piCaptor.capture());
        assertEquals("Wrong target for pending intent passed to alarm manager", pir,
                piCaptor.getValue().getTarget());
    }

    @Test
    public void alarmsRemovedOnRecreateWithCancelCurrent() {
        final PendingIntentRecord pir = createPendingIntentRecord(0);
        createPendingIntentRecord(PendingIntent.FLAG_CANCEL_CURRENT);
        final ArgumentCaptor<PendingIntent> piCaptor = ArgumentCaptor.forClass(PendingIntent.class);
        verify(mAlarmManagerInternal).remove(piCaptor.capture());
        assertEquals("Wrong target for pending intent passed to alarm manager", pir,
                piCaptor.getValue().getTarget());
    }

    @Test
    public void alarmsRemovedOnSendingOneShot() {
        final PendingIntentRecord pir = createPendingIntentRecord(PendingIntent.FLAG_ONE_SHOT);
        pir.send(0, null, null, null, null, null, null);
        final ArgumentCaptor<PendingIntent> piCaptor = ArgumentCaptor.forClass(PendingIntent.class);
        verify(mAlarmManagerInternal).remove(piCaptor.capture());
        assertEquals("Wrong target for pending intent passed to alarm manager", pir,
                piCaptor.getValue().getTarget());
    }

    @After
    public void tearDown() {
        if (mMockingSession != null) {
            mMockingSession.finishMocking();
        }
    }
}
