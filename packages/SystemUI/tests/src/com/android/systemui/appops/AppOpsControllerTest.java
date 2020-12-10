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

package com.android.systemui.appops;

import static junit.framework.TestCase.assertFalse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.AppOpsManager;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.AudioRecordingConfiguration;
import android.os.Looper;
import android.os.UserHandle;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dump.DumpManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Collections;
import java.util.List;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class AppOpsControllerTest extends SysuiTestCase {
    private static final String TEST_PACKAGE_NAME = "test";
    private static final int TEST_UID = UserHandle.getUid(0, 0);
    private static final int TEST_UID_OTHER = UserHandle.getUid(1, 0);
    private static final int TEST_UID_NON_USER_SENSITIVE = UserHandle.getUid(2, 0);

    @Mock
    private AppOpsManager mAppOpsManager;
    @Mock
    private AppOpsController.Callback mCallback;
    @Mock
    private AppOpsControllerImpl.H mMockHandler;
    @Mock
    private DumpManager mDumpManager;
    @Mock
    private PermissionFlagsCache mFlagsCache;
    @Mock
    private PackageManager mPackageManager;
    @Mock(stubOnly = true)
    private AudioManager mAudioManager;
    @Mock()
    private BroadcastDispatcher mDispatcher;
    @Mock(stubOnly = true)
    private AudioManager.AudioRecordingCallback mRecordingCallback;
    @Mock(stubOnly = true)
    private AudioRecordingConfiguration mPausedMockRecording;

    private AppOpsControllerImpl mController;
    private TestableLooper mTestableLooper;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mTestableLooper = TestableLooper.get(this);

        getContext().addMockSystemService(AppOpsManager.class, mAppOpsManager);

        // All permissions of TEST_UID and TEST_UID_OTHER are user sensitive. None of
        // TEST_UID_NON_USER_SENSITIVE are user sensitive.
        getContext().setMockPackageManager(mPackageManager);
        when(mFlagsCache.getPermissionFlags(anyString(), anyString(), eq(TEST_UID))).thenReturn(
                PackageManager.FLAG_PERMISSION_USER_SENSITIVE_WHEN_GRANTED);
        when(mFlagsCache.getPermissionFlags(anyString(), anyString(), eq(TEST_UID_OTHER)))
                .thenReturn(PackageManager.FLAG_PERMISSION_USER_SENSITIVE_WHEN_GRANTED);
        when(mFlagsCache.getPermissionFlags(anyString(), anyString(),
                eq(TEST_UID_NON_USER_SENSITIVE))).thenReturn(0);

        doAnswer((invocation) -> mRecordingCallback = invocation.getArgument(0))
                .when(mAudioManager).registerAudioRecordingCallback(any(), any());
        when(mPausedMockRecording.getClientUid()).thenReturn(TEST_UID);
        when(mPausedMockRecording.isClientSilenced()).thenReturn(true);

        when(mAudioManager.getActiveRecordingConfigurations())
                .thenReturn(List.of(mPausedMockRecording));

        mController = new AppOpsControllerImpl(
                mContext,
                mTestableLooper.getLooper(),
                mDumpManager,
                mFlagsCache,
                mAudioManager,
                mDispatcher
        );
    }

    @Test
    public void testOnlyListenForFewOps() {
        mController.setListening(true);
        verify(mAppOpsManager, times(1)).startWatchingActive(AppOpsControllerImpl.OPS, mController);
        verify(mDispatcher, times(1)).registerReceiverWithHandler(eq(mController), any(), any());
    }

    @Test
    public void testStopListening() {
        mController.setListening(false);
        verify(mAppOpsManager, times(1)).stopWatchingActive(mController);
        verify(mDispatcher, times(1)).unregisterReceiver(mController);
    }

    @Test
    public void addCallback_includedCode() {
        mController.addCallback(
                new int[]{AppOpsManager.OP_RECORD_AUDIO, AppOpsManager.OP_FINE_LOCATION},
                mCallback);
        mController.onOpActiveChanged(
                AppOpsManager.OP_RECORD_AUDIO, TEST_UID, TEST_PACKAGE_NAME, true);
        mController.onOpNoted(AppOpsManager.OP_FINE_LOCATION, TEST_UID, TEST_PACKAGE_NAME,
                AppOpsManager.MODE_ALLOWED);
        mTestableLooper.processAllMessages();
        verify(mCallback).onActiveStateChanged(AppOpsManager.OP_RECORD_AUDIO,
                TEST_UID, TEST_PACKAGE_NAME, true);
    }

    @Test
    public void addCallback_notIncludedCode() {
        mController.addCallback(new int[]{AppOpsManager.OP_FINE_LOCATION}, mCallback);
        mController.onOpActiveChanged(
                AppOpsManager.OP_RECORD_AUDIO, TEST_UID, TEST_PACKAGE_NAME, true);
        mTestableLooper.processAllMessages();
        verify(mCallback, never()).onActiveStateChanged(
                anyInt(), anyInt(), anyString(), anyBoolean());
    }

    @Test
    public void removeCallback_sameCode() {
        mController.addCallback(new int[]{AppOpsManager.OP_RECORD_AUDIO}, mCallback);
        mController.removeCallback(new int[]{AppOpsManager.OP_RECORD_AUDIO}, mCallback);
        mController.onOpActiveChanged(
                AppOpsManager.OP_RECORD_AUDIO, TEST_UID, TEST_PACKAGE_NAME, true);
        mTestableLooper.processAllMessages();
        verify(mCallback, never()).onActiveStateChanged(
                anyInt(), anyInt(), anyString(), anyBoolean());
    }

    @Test
    public void addCallback_notSameCode() {
        mController.addCallback(new int[]{AppOpsManager.OP_RECORD_AUDIO}, mCallback);
        mController.removeCallback(new int[]{AppOpsManager.OP_CAMERA}, mCallback);
        mController.onOpActiveChanged(
                AppOpsManager.OP_RECORD_AUDIO, TEST_UID, TEST_PACKAGE_NAME, true);
        mTestableLooper.processAllMessages();
        verify(mCallback).onActiveStateChanged(AppOpsManager.OP_RECORD_AUDIO,
                TEST_UID, TEST_PACKAGE_NAME, true);
    }

    @Test
    public void getActiveItems_sameDetails() {
        mController.onOpActiveChanged(AppOpsManager.OP_RECORD_AUDIO,
                TEST_UID, TEST_PACKAGE_NAME, true);
        mController.onOpActiveChanged(AppOpsManager.OP_RECORD_AUDIO,
                TEST_UID, TEST_PACKAGE_NAME, true);
        assertEquals(1, mController.getActiveAppOps().size());
    }

    @Test
    public void getActiveItems_differentDetails() {
        mController.onOpActiveChanged(AppOpsManager.OP_RECORD_AUDIO,
                TEST_UID, TEST_PACKAGE_NAME, true);
        mController.onOpActiveChanged(AppOpsManager.OP_CAMERA,
                TEST_UID, TEST_PACKAGE_NAME, true);
        mController.onOpNoted(AppOpsManager.OP_FINE_LOCATION,
                TEST_UID, TEST_PACKAGE_NAME, AppOpsManager.MODE_ALLOWED);
        assertEquals(3, mController.getActiveAppOps().size());
    }

    @Test
    public void getActiveItemsForUser() {
        mController.onOpActiveChanged(AppOpsManager.OP_RECORD_AUDIO,
                TEST_UID, TEST_PACKAGE_NAME, true);
        mController.onOpActiveChanged(AppOpsManager.OP_CAMERA,
                TEST_UID_OTHER, TEST_PACKAGE_NAME, true);
        mController.onOpNoted(AppOpsManager.OP_FINE_LOCATION,
                TEST_UID, TEST_PACKAGE_NAME, AppOpsManager.MODE_ALLOWED);
        assertEquals(2,
                mController.getActiveAppOpsForUser(UserHandle.getUserId(TEST_UID)).size());
        assertEquals(1,
                mController.getActiveAppOpsForUser(UserHandle.getUserId(TEST_UID_OTHER)).size());
    }

    @Test
    public void nonUserSensitiveOpsAreIgnored() {
        mController.onOpActiveChanged(AppOpsManager.OP_RECORD_AUDIO,
                TEST_UID_NON_USER_SENSITIVE, TEST_PACKAGE_NAME, true);
        assertEquals(0, mController.getActiveAppOpsForUser(
                UserHandle.getUserId(TEST_UID_NON_USER_SENSITIVE)).size());
    }

    @Test
    public void nonUserSensitiveOpsNotNotified() {
        mController.addCallback(new int[]{AppOpsManager.OP_RECORD_AUDIO}, mCallback);
        mController.onOpActiveChanged(AppOpsManager.OP_RECORD_AUDIO,
                TEST_UID_NON_USER_SENSITIVE, TEST_PACKAGE_NAME, true);

        mTestableLooper.processAllMessages();

        verify(mCallback, never())
                .onActiveStateChanged(anyInt(), anyInt(), anyString(), anyBoolean());
    }

    @Test
    public void opNotedScheduledForRemoval() {
        mController.setBGHandler(mMockHandler);
        mController.onOpNoted(AppOpsManager.OP_FINE_LOCATION, TEST_UID, TEST_PACKAGE_NAME,
                AppOpsManager.MODE_ALLOWED);
        verify(mMockHandler).scheduleRemoval(any(AppOpItem.class), anyLong());
    }

    @Test
    public void noItemsAfterStopListening() {
        mController.setBGHandler(mMockHandler);

        mController.setListening(true);
        mController.onOpActiveChanged(AppOpsManager.OP_FINE_LOCATION, TEST_UID, TEST_PACKAGE_NAME,
                true);
        mController.onOpNoted(AppOpsManager.OP_FINE_LOCATION, TEST_UID, TEST_PACKAGE_NAME,
                AppOpsManager.MODE_ALLOWED);
        assertFalse(mController.getActiveAppOps().isEmpty());

        mController.setListening(false);

        verify(mMockHandler).removeCallbacksAndMessages(null);
        assertTrue(mController.getActiveAppOps().isEmpty());
    }

    @Test
    public void noDoubleUpdateOnOpNoted() {
        mController.setBGHandler(mMockHandler);

        mController.onOpNoted(AppOpsManager.OP_FINE_LOCATION, TEST_UID, TEST_PACKAGE_NAME,
                AppOpsManager.MODE_ALLOWED);
        mController.onOpNoted(AppOpsManager.OP_FINE_LOCATION, TEST_UID, TEST_PACKAGE_NAME,
                AppOpsManager.MODE_ALLOWED);

        // Only one post to notify subscribers
        verify(mMockHandler, times(1)).post(any());

        List<AppOpItem> list = mController.getActiveAppOps();
        assertEquals(1, list.size());
    }

    @Test
    public void onDoubleOPNoted_scheduleTwiceForRemoval() {
        mController.setBGHandler(mMockHandler);

        mController.onOpNoted(AppOpsManager.OP_FINE_LOCATION, TEST_UID, TEST_PACKAGE_NAME,
                AppOpsManager.MODE_ALLOWED);
        mController.onOpNoted(AppOpsManager.OP_FINE_LOCATION, TEST_UID, TEST_PACKAGE_NAME,
                AppOpsManager.MODE_ALLOWED);

        // Only one post to notify subscribers
        verify(mMockHandler, times(2)).scheduleRemoval(any(), anyLong());
    }

    @Test
    public void testActiveOpNotRemovedAfterNoted() throws InterruptedException {
        // Replaces the timeout delay with 5 ms
        TestHandler testHandler = new TestHandler(mTestableLooper.getLooper());

        mController.addCallback(new int[]{AppOpsManager.OP_FINE_LOCATION}, mCallback);
        mController.setBGHandler(testHandler);

        mController.onOpActiveChanged(
                AppOpsManager.OP_FINE_LOCATION, TEST_UID, TEST_PACKAGE_NAME, true);

        mController.onOpNoted(AppOpsManager.OP_FINE_LOCATION, TEST_UID, TEST_PACKAGE_NAME,
                AppOpsManager.MODE_ALLOWED);

        // Check that we "scheduled" the removal. Don't actually schedule until we are ready to
        // process messages at a later time.
        assertNotNull(testHandler.mDelayScheduled);

        mTestableLooper.processAllMessages();
        List<AppOpItem> list = mController.getActiveAppOps();
        verify(mCallback).onActiveStateChanged(
                AppOpsManager.OP_FINE_LOCATION, TEST_UID, TEST_PACKAGE_NAME, true);

        // Duplicates are not removed between active and noted
        assertEquals(2, list.size());

        // Now is later, so we can schedule delayed messages.
        testHandler.scheduleDelayed();
        mTestableLooper.processAllMessages();

        verify(mCallback, never()).onActiveStateChanged(
                AppOpsManager.OP_FINE_LOCATION, TEST_UID, TEST_PACKAGE_NAME, false);
        list = mController.getActiveAppOps();
        assertEquals(1, list.size());
    }

    @Test
    public void testNotedNotRemovedAfterActive() {
        mController.addCallback(new int[]{AppOpsManager.OP_FINE_LOCATION}, mCallback);

        mController.onOpNoted(AppOpsManager.OP_FINE_LOCATION, TEST_UID, TEST_PACKAGE_NAME,
                AppOpsManager.MODE_ALLOWED);

        mController.onOpActiveChanged(
                AppOpsManager.OP_FINE_LOCATION, TEST_UID, TEST_PACKAGE_NAME, true);

        mTestableLooper.processAllMessages();
        List<AppOpItem> list = mController.getActiveAppOps();
        verify(mCallback).onActiveStateChanged(
                AppOpsManager.OP_FINE_LOCATION, TEST_UID, TEST_PACKAGE_NAME, true);

        // Duplicates are not removed between active and noted
        assertEquals(2, list.size());

        mController.onOpActiveChanged(
                AppOpsManager.OP_FINE_LOCATION, TEST_UID, TEST_PACKAGE_NAME, false);

        mTestableLooper.processAllMessages();

        verify(mCallback, never()).onActiveStateChanged(
                AppOpsManager.OP_FINE_LOCATION, TEST_UID, TEST_PACKAGE_NAME, false);
        list = mController.getActiveAppOps();
        assertEquals(1, list.size());
    }

    @Test
    public void testNotedAndActiveOnlyOneCall() {
        mController.addCallback(new int[]{AppOpsManager.OP_FINE_LOCATION}, mCallback);

        mController.onOpNoted(AppOpsManager.OP_FINE_LOCATION, TEST_UID, TEST_PACKAGE_NAME,
                AppOpsManager.MODE_ALLOWED);

        mController.onOpActiveChanged(
                AppOpsManager.OP_FINE_LOCATION, TEST_UID, TEST_PACKAGE_NAME, true);

        mTestableLooper.processAllMessages();
        verify(mCallback).onActiveStateChanged(
                AppOpsManager.OP_FINE_LOCATION, TEST_UID, TEST_PACKAGE_NAME, true);
    }

    @Test
    public void testActiveAndNotedOnlyOneCall() {
        mController.addCallback(new int[]{AppOpsManager.OP_FINE_LOCATION}, mCallback);

        mController.onOpActiveChanged(
                AppOpsManager.OP_FINE_LOCATION, TEST_UID, TEST_PACKAGE_NAME, true);

        mController.onOpNoted(AppOpsManager.OP_FINE_LOCATION, TEST_UID, TEST_PACKAGE_NAME,
                AppOpsManager.MODE_ALLOWED);

        mTestableLooper.processAllMessages();
        verify(mCallback).onActiveStateChanged(
                AppOpsManager.OP_FINE_LOCATION, TEST_UID, TEST_PACKAGE_NAME, true);
    }

    @Test
    public void testPausedRecordingIsRetrievedOnCreation() {
        mController.addCallback(new int[]{AppOpsManager.OP_RECORD_AUDIO}, mCallback);
        mTestableLooper.processAllMessages();

        mController.onOpActiveChanged(
                AppOpsManager.OP_RECORD_AUDIO, TEST_UID, TEST_PACKAGE_NAME, true);
        mTestableLooper.processAllMessages();

        verify(mCallback, never())
                .onActiveStateChanged(anyInt(), anyInt(), anyString(), anyBoolean());
    }

    @Test
    public void testPausedRecordingFilteredOut() {
        mController.addCallback(new int[]{AppOpsManager.OP_RECORD_AUDIO}, mCallback);
        mTestableLooper.processAllMessages();

        mController.onOpActiveChanged(
                AppOpsManager.OP_RECORD_AUDIO, TEST_UID, TEST_PACKAGE_NAME, true);
        mTestableLooper.processAllMessages();

        assertTrue(mController.getActiveAppOps().isEmpty());
    }

    @Test
    public void testOnlyRecordAudioPaused() {
        mController.addCallback(new int[]{
                AppOpsManager.OP_RECORD_AUDIO,
                AppOpsManager.OP_CAMERA
        }, mCallback);
        mTestableLooper.processAllMessages();

        mController.onOpActiveChanged(
                AppOpsManager.OP_CAMERA, TEST_UID, TEST_PACKAGE_NAME, true);
        mTestableLooper.processAllMessages();

        verify(mCallback).onActiveStateChanged(
                AppOpsManager.OP_CAMERA, TEST_UID, TEST_PACKAGE_NAME, true);
        List<AppOpItem> list = mController.getActiveAppOps();

        assertEquals(1, list.size());
        assertEquals(AppOpsManager.OP_CAMERA, list.get(0).getCode());
    }

    @Test
    public void testUnpausedRecordingSentActive() {
        mController.addCallback(new int[]{AppOpsManager.OP_RECORD_AUDIO}, mCallback);
        mTestableLooper.processAllMessages();
        mController.onOpActiveChanged(
                AppOpsManager.OP_RECORD_AUDIO, TEST_UID, TEST_PACKAGE_NAME, true);

        mTestableLooper.processAllMessages();
        mRecordingCallback.onRecordingConfigChanged(Collections.emptyList());

        mTestableLooper.processAllMessages();

        verify(mCallback).onActiveStateChanged(
                AppOpsManager.OP_RECORD_AUDIO, TEST_UID, TEST_PACKAGE_NAME, true);
    }

    @Test
    public void testAudioPausedSentInactive() {
        mController.addCallback(new int[]{AppOpsManager.OP_RECORD_AUDIO}, mCallback);
        mTestableLooper.processAllMessages();
        mController.onOpActiveChanged(
                AppOpsManager.OP_RECORD_AUDIO, TEST_UID_OTHER, TEST_PACKAGE_NAME, true);
        mTestableLooper.processAllMessages();

        AudioRecordingConfiguration mockARC = mock(AudioRecordingConfiguration.class);
        when(mockARC.getClientUid()).thenReturn(TEST_UID_OTHER);
        when(mockARC.isClientSilenced()).thenReturn(true);

        mRecordingCallback.onRecordingConfigChanged(List.of(mockARC));
        mTestableLooper.processAllMessages();

        InOrder inOrder = inOrder(mCallback);
        inOrder.verify(mCallback).onActiveStateChanged(
                AppOpsManager.OP_RECORD_AUDIO, TEST_UID_OTHER, TEST_PACKAGE_NAME, true);
        inOrder.verify(mCallback).onActiveStateChanged(
                AppOpsManager.OP_RECORD_AUDIO, TEST_UID_OTHER, TEST_PACKAGE_NAME, false);
    }

    private class TestHandler extends AppOpsControllerImpl.H {
        TestHandler(Looper looper) {
            mController.super(looper);
        }

        Runnable mDelayScheduled;

        void scheduleDelayed() {
            if (mDelayScheduled != null) {
                mDelayScheduled.run();
                mDelayScheduled = null;
            }
        }

        @Override
        public void scheduleRemoval(AppOpItem item, long timeToRemoval) {
            mDelayScheduled = () -> super.scheduleRemoval(item, 0L);
        }
    }
}
