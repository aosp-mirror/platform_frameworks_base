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

import static android.hardware.SensorPrivacyManager.Sensors.CAMERA;
import static android.hardware.SensorPrivacyManager.Sensors.MICROPHONE;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.TestCase.assertFalse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;
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

import com.android.internal.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.statusbar.policy.IndividualSensorPrivacyController;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class AppOpsControllerTest extends SysuiTestCase {
    private static final String TEST_PACKAGE_NAME = "test";
    private static final String TEST_ATTRIBUTION_NAME = "attribution";
    private static final String SYSTEM_PKG = "android";
    private static final int TEST_UID = UserHandle.getUid(0, 0);
    private static final int TEST_UID_OTHER = UserHandle.getUid(1, 0);
    private static final int TEST_UID_NON_USER_SENSITIVE = UserHandle.getUid(2, 0);
    private static final int TEST_CHAIN_ID = 1;

    @Mock
    private AppOpsManager mAppOpsManager;
    @Mock
    private AppOpsController.Callback mCallback;
    @Mock
    private AppOpsControllerImpl.H mMockHandler;
    @Mock
    private DumpManager mDumpManager;
    @Mock
    private PackageManager mPackageManager;
    @Mock
    private IndividualSensorPrivacyController mSensorPrivacyController;
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

    private String mExemptedRolePkgName;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mTestableLooper = TestableLooper.get(this);
        mExemptedRolePkgName = getContext().getString(R.string.config_systemUiIntelligence);

        getContext().addMockSystemService(AppOpsManager.class, mAppOpsManager);

        // All permissions of TEST_UID and TEST_UID_OTHER are user sensitive. None of
        // TEST_UID_NON_USER_SENSITIVE are user sensitive.
        getContext().setMockPackageManager(mPackageManager);

        doAnswer((invocation) -> mRecordingCallback = invocation.getArgument(0))
                .when(mAudioManager).registerAudioRecordingCallback(any(), any());
        when(mPausedMockRecording.getClientUid()).thenReturn(TEST_UID);
        when(mPausedMockRecording.isClientSilenced()).thenReturn(true);

        when(mAudioManager.getActiveRecordingConfigurations())
                .thenReturn(List.of(mPausedMockRecording));

        when(mSensorPrivacyController.isSensorBlocked(CAMERA))
                .thenReturn(false);
        when(mSensorPrivacyController.isSensorBlocked(CAMERA))
                .thenReturn(false);

        mController = new AppOpsControllerImpl(
                mContext,
                mTestableLooper.getLooper(),
                mDumpManager,
                mAudioManager,
                mSensorPrivacyController,
                mDispatcher,
                new FakeSystemClock()
        );
    }

    @Test
    public void testOnlyListenForFewOps() {
        mController.setListening(true);
        verify(mAppOpsManager, times(1)).startWatchingActive(AppOpsControllerImpl.OPS, mController);
        verify(mDispatcher, times(1)).registerReceiverWithHandler(eq(mController), any(), any());
        verify(mSensorPrivacyController, times(1)).addCallback(mController);
    }

    @Test
    public void testStopListening() {
        mController.setListening(false);
        verify(mAppOpsManager, times(1)).stopWatchingActive(mController);
        verify(mDispatcher, times(1)).unregisterReceiver(mController);
        verify(mSensorPrivacyController, times(1)).removeCallback(mController);
    }

    @Test
    public void startListening_fetchesCurrentActive_none() {
        when(mAppOpsManager.getPackagesForOps(AppOpsControllerImpl.OPS))
                .thenReturn(List.of());

        mController.setListening(true);

        assertThat(mController.getActiveAppOps()).isEmpty();
    }

    /** Regression test for b/294104969. */
    @Test
    public void startListening_fetchesCurrentActive_oneActive() {
        AppOpsManager.PackageOps packageOps = createPackageOp(
                "package.test",
                /* packageUid= */ 2,
                AppOpsManager.OPSTR_FINE_LOCATION,
                /* isRunning= */ true);
        when(mAppOpsManager.getPackagesForOps(AppOpsControllerImpl.OPS))
                .thenReturn(List.of(packageOps));

        // WHEN we start listening
        mController.setListening(true);

        // THEN the active list has the op
        List<AppOpItem> list = mController.getActiveAppOps();
        assertEquals(1, list.size());
        AppOpItem first = list.get(0);
        assertThat(first.getPackageName()).isEqualTo("package.test");
        assertThat(first.getUid()).isEqualTo(2);
        assertThat(first.getCode()).isEqualTo(AppOpsManager.OP_FINE_LOCATION);
    }

    @Test
    public void startListening_fetchesCurrentActive_multiplePackages() {
        AppOpsManager.PackageOps packageOps1 = createPackageOp(
                "package.one",
                /* packageUid= */ 1,
                AppOpsManager.OPSTR_FINE_LOCATION,
                /* isRunning= */ true);
        AppOpsManager.PackageOps packageOps2 = createPackageOp(
                "package.two",
                /* packageUid= */ 2,
                AppOpsManager.OPSTR_FINE_LOCATION,
                /* isRunning= */ false);
        AppOpsManager.PackageOps packageOps3 = createPackageOp(
                "package.three",
                /* packageUid= */ 3,
                AppOpsManager.OPSTR_FINE_LOCATION,
                /* isRunning= */ true);
        when(mAppOpsManager.getPackagesForOps(AppOpsControllerImpl.OPS))
                .thenReturn(List.of(packageOps1, packageOps2, packageOps3));

        // WHEN we start listening
        mController.setListening(true);

        // THEN the active list has the ops
        List<AppOpItem> list = mController.getActiveAppOps();
        assertEquals(2, list.size());

        AppOpItem item0 = list.get(0);
        assertThat(item0.getPackageName()).isEqualTo("package.one");
        assertThat(item0.getUid()).isEqualTo(1);
        assertThat(item0.getCode()).isEqualTo(AppOpsManager.OP_FINE_LOCATION);

        AppOpItem item1 = list.get(1);
        assertThat(item1.getPackageName()).isEqualTo("package.three");
        assertThat(item1.getUid()).isEqualTo(3);
        assertThat(item1.getCode()).isEqualTo(AppOpsManager.OP_FINE_LOCATION);
    }

    @Test
    public void startListening_fetchesCurrentActive_multipleEntries() {
        AppOpsManager.PackageOps packageOps = mock(AppOpsManager.PackageOps.class);
        when(packageOps.getUid()).thenReturn(1);
        when(packageOps.getPackageName()).thenReturn("package.one");

        // Entry 1
        AppOpsManager.OpEntry entry1 = mock(AppOpsManager.OpEntry.class);
        when(entry1.getOpStr()).thenReturn(AppOpsManager.OPSTR_PHONE_CALL_MICROPHONE);
        AppOpsManager.AttributedOpEntry attributed1 = mock(AppOpsManager.AttributedOpEntry.class);
        when(attributed1.isRunning()).thenReturn(true);
        when(entry1.getAttributedOpEntries()).thenReturn(Map.of("tag", attributed1));
        // Entry 2
        AppOpsManager.OpEntry entry2 = mock(AppOpsManager.OpEntry.class);
        when(entry2.getOpStr()).thenReturn(AppOpsManager.OPSTR_CAMERA);
        AppOpsManager.AttributedOpEntry attributed2 = mock(AppOpsManager.AttributedOpEntry.class);
        when(attributed2.isRunning()).thenReturn(true);
        when(entry2.getAttributedOpEntries()).thenReturn(Map.of("tag", attributed2));
        // Entry 3
        AppOpsManager.OpEntry entry3 = mock(AppOpsManager.OpEntry.class);
        when(entry3.getOpStr()).thenReturn(AppOpsManager.OPSTR_FINE_LOCATION);
        AppOpsManager.AttributedOpEntry attributed3 = mock(AppOpsManager.AttributedOpEntry.class);
        when(attributed3.isRunning()).thenReturn(false);
        when(entry3.getAttributedOpEntries()).thenReturn(Map.of("tag", attributed3));

        when(packageOps.getOps()).thenReturn(List.of(entry1, entry2, entry3));
        when(mAppOpsManager.getPackagesForOps(AppOpsControllerImpl.OPS))
                .thenReturn(List.of(packageOps));

        // WHEN we start listening
        mController.setListening(true);

        // THEN the active list has the ops
        List<AppOpItem> list = mController.getActiveAppOps();
        assertEquals(2, list.size());

        AppOpItem first = list.get(0);
        assertThat(first.getPackageName()).isEqualTo("package.one");
        assertThat(first.getUid()).isEqualTo(1);
        assertThat(first.getCode()).isEqualTo(AppOpsManager.OP_PHONE_CALL_MICROPHONE);

        AppOpItem second = list.get(1);
        assertThat(second.getPackageName()).isEqualTo("package.one");
        assertThat(second.getUid()).isEqualTo(1);
        assertThat(second.getCode()).isEqualTo(AppOpsManager.OP_CAMERA);
    }

    @Test
    public void startListening_fetchesCurrentActive_multipleAttributes() {
        AppOpsManager.PackageOps packageOps = mock(AppOpsManager.PackageOps.class);
        when(packageOps.getUid()).thenReturn(1);
        when(packageOps.getPackageName()).thenReturn("package.one");
        AppOpsManager.OpEntry entry = mock(AppOpsManager.OpEntry.class);
        when(entry.getOpStr()).thenReturn(AppOpsManager.OPSTR_RECORD_AUDIO);

        AppOpsManager.AttributedOpEntry attributed1 = mock(AppOpsManager.AttributedOpEntry.class);
        when(attributed1.isRunning()).thenReturn(false);
        AppOpsManager.AttributedOpEntry attributed2 = mock(AppOpsManager.AttributedOpEntry.class);
        when(attributed2.isRunning()).thenReturn(true);
        AppOpsManager.AttributedOpEntry attributed3 = mock(AppOpsManager.AttributedOpEntry.class);
        when(attributed3.isRunning()).thenReturn(true);
        when(entry.getAttributedOpEntries()).thenReturn(
                Map.of("attr1", attributed1, "attr2", attributed2, "attr3", attributed3));

        when(packageOps.getOps()).thenReturn(List.of(entry));
        when(mAppOpsManager.getPackagesForOps(AppOpsControllerImpl.OPS))
                .thenReturn(List.of(packageOps));

        // WHEN we start listening
        mController.setListening(true);

        // THEN the active list has the ops
        List<AppOpItem> list = mController.getActiveAppOps();
        // Multiple attributes get merged into one entry in the active ops
        assertEquals(1, list.size());

        AppOpItem first = list.get(0);
        assertThat(first.getPackageName()).isEqualTo("package.one");
        assertThat(first.getUid()).isEqualTo(1);
        assertThat(first.getCode()).isEqualTo(AppOpsManager.OP_RECORD_AUDIO);
    }

    /** Regression test for b/294104969. */
    @Test
    public void addCallback_existingCallbacksNotifiedOfCurrentActive() {
        AppOpsManager.PackageOps packageOps1 = createPackageOp(
                "package.one",
                /* packageUid= */ 1,
                AppOpsManager.OPSTR_FINE_LOCATION,
                /* isRunning= */ true);
        AppOpsManager.PackageOps packageOps2 = createPackageOp(
                "package.two",
                /* packageUid= */ 2,
                AppOpsManager.OPSTR_RECORD_AUDIO,
                /* isRunning= */ true);
        AppOpsManager.PackageOps packageOps3 = createPackageOp(
                "package.three",
                /* packageUid= */ 3,
                AppOpsManager.OPSTR_PHONE_CALL_MICROPHONE,
                /* isRunning= */ true);
        when(mAppOpsManager.getPackagesForOps(AppOpsControllerImpl.OPS))
                .thenReturn(List.of(packageOps1, packageOps2, packageOps3));

        // WHEN we start listening
        mController.addCallback(
                new int[]{AppOpsManager.OP_RECORD_AUDIO, AppOpsManager.OP_FINE_LOCATION},
                mCallback);
        mTestableLooper.processAllMessages();

        // THEN the callback is notified of the current active ops it cares about
        verify(mCallback).onActiveStateChanged(
                AppOpsManager.OP_FINE_LOCATION,
                /* uid= */ 1,
                "package.one",
                true);
        verify(mCallback).onActiveStateChanged(
                AppOpsManager.OP_RECORD_AUDIO,
                /* uid= */ 2,
                "package.two",
                true);
        verify(mCallback, never()).onActiveStateChanged(
                AppOpsManager.OP_PHONE_CALL_MICROPHONE,
                /* uid= */ 3,
                "package.three",
                true);
    }

    @Test
    public void addCallback_includedCode() {
        mController.addCallback(
                new int[]{AppOpsManager.OP_RECORD_AUDIO, AppOpsManager.OP_FINE_LOCATION},
                mCallback);
        mController.onOpActiveChanged(
                AppOpsManager.OPSTR_RECORD_AUDIO, TEST_UID, TEST_PACKAGE_NAME, true);
        mController.onOpNoted(AppOpsManager.OP_FINE_LOCATION, TEST_UID, TEST_PACKAGE_NAME,
                TEST_ATTRIBUTION_NAME, AppOpsManager.OP_FLAG_SELF, AppOpsManager.MODE_ALLOWED);
        mTestableLooper.processAllMessages();
        verify(mCallback).onActiveStateChanged(AppOpsManager.OP_RECORD_AUDIO,
                TEST_UID, TEST_PACKAGE_NAME, true);
    }

    @Test
    public void addCallback_notIncludedCode() {
        mController.addCallback(new int[]{AppOpsManager.OP_FINE_LOCATION}, mCallback);
        mController.onOpActiveChanged(
                AppOpsManager.OPSTR_RECORD_AUDIO, TEST_UID, TEST_PACKAGE_NAME, true);
        mTestableLooper.processAllMessages();
        verify(mCallback, never()).onActiveStateChanged(
                anyInt(), anyInt(), anyString(), anyBoolean());
    }

    @Test
    public void removeCallback_sameCode() {
        mController.addCallback(new int[]{AppOpsManager.OP_RECORD_AUDIO}, mCallback);
        mController.removeCallback(new int[]{AppOpsManager.OP_RECORD_AUDIO}, mCallback);
        mController.onOpActiveChanged(
                AppOpsManager.OPSTR_RECORD_AUDIO, TEST_UID, TEST_PACKAGE_NAME, true);
        mTestableLooper.processAllMessages();
        verify(mCallback, never()).onActiveStateChanged(
                anyInt(), anyInt(), anyString(), anyBoolean());
    }

    @Test
    public void addCallback_notSameCode() {
        mController.addCallback(new int[]{AppOpsManager.OP_RECORD_AUDIO}, mCallback);
        mController.removeCallback(new int[]{AppOpsManager.OP_CAMERA}, mCallback);
        mController.onOpActiveChanged(
                AppOpsManager.OPSTR_RECORD_AUDIO, TEST_UID, TEST_PACKAGE_NAME, true);
        mTestableLooper.processAllMessages();
        verify(mCallback).onActiveStateChanged(AppOpsManager.OP_RECORD_AUDIO,
                TEST_UID, TEST_PACKAGE_NAME, true);
    }

    @Test
    public void getActiveItems_sameDetails() {
        mController.onOpActiveChanged(AppOpsManager.OPSTR_RECORD_AUDIO,
                TEST_UID, TEST_PACKAGE_NAME, true);
        mController.onOpActiveChanged(AppOpsManager.OPSTR_RECORD_AUDIO,
                TEST_UID, TEST_PACKAGE_NAME, true);
        assertEquals(1, mController.getActiveAppOps().size());
    }

    @Test
    public void getActiveItems_differentDetails() {
        mController.onOpActiveChanged(AppOpsManager.OPSTR_RECORD_AUDIO,
                TEST_UID, TEST_PACKAGE_NAME, true);
        mController.onOpActiveChanged(AppOpsManager.OPSTR_CAMERA,
                TEST_UID, TEST_PACKAGE_NAME, true);
        mController.onOpNoted(AppOpsManager.OP_FINE_LOCATION,
                TEST_UID, TEST_PACKAGE_NAME, TEST_ATTRIBUTION_NAME,
                AppOpsManager.OP_FLAG_SELF, AppOpsManager.MODE_ALLOWED);
        assertEquals(3, mController.getActiveAppOps().size());
    }

    @Test
    public void getActiveItemsForUser() {
        mController.onOpActiveChanged(AppOpsManager.OPSTR_RECORD_AUDIO,
                TEST_UID, TEST_PACKAGE_NAME, true);
        mController.onOpActiveChanged(AppOpsManager.OPSTR_CAMERA,
                TEST_UID_OTHER, TEST_PACKAGE_NAME, true);
        mController.onOpNoted(AppOpsManager.OP_FINE_LOCATION,
                TEST_UID, TEST_PACKAGE_NAME, TEST_ATTRIBUTION_NAME,
                AppOpsManager.OP_FLAG_SELF, AppOpsManager.MODE_ALLOWED);
        assertEquals(2,
                mController.getActiveAppOpsForUser(UserHandle.getUserId(TEST_UID), false).size());
        assertEquals(1, mController.getActiveAppOpsForUser(UserHandle.getUserId(TEST_UID_OTHER),
                false).size());
    }

    @Test
    public void systemAndExemptedRolesAreIgnored() {
        assumeFalse(mExemptedRolePkgName == null || mExemptedRolePkgName.equals(""));

        mController.onOpActiveChanged(AppOpsManager.OPSTR_RECORD_AUDIO,
                TEST_UID_NON_USER_SENSITIVE, mExemptedRolePkgName, true);
        assertEquals(0, mController.getActiveAppOpsForUser(
                UserHandle.getUserId(TEST_UID_NON_USER_SENSITIVE), false).size());
        mController.onOpActiveChanged(AppOpsManager.OPSTR_RECORD_AUDIO,
                TEST_UID_NON_USER_SENSITIVE, SYSTEM_PKG, true);
        assertEquals(0, mController.getActiveAppOpsForUser(
                UserHandle.getUserId(TEST_UID_NON_USER_SENSITIVE), false).size());
    }

    @Test
    public void exemptedRoleNotNotified() {
        assumeFalse(mExemptedRolePkgName == null || mExemptedRolePkgName.equals(""));

        mController.addCallback(new int[]{AppOpsManager.OP_RECORD_AUDIO}, mCallback);
        mController.onOpActiveChanged(AppOpsManager.OPSTR_RECORD_AUDIO,
                TEST_UID_NON_USER_SENSITIVE, mExemptedRolePkgName, true);

        mTestableLooper.processAllMessages();

        verify(mCallback, never())
                .onActiveStateChanged(anyInt(), anyInt(), anyString(), anyBoolean());
    }

    @Test
    public void opNotedScheduledForRemoval() {
        mController.setBGHandler(mMockHandler);
        mController.onOpNoted(AppOpsManager.OP_FINE_LOCATION, TEST_UID, TEST_PACKAGE_NAME,
                TEST_ATTRIBUTION_NAME, AppOpsManager.OP_FLAG_SELF, AppOpsManager.MODE_ALLOWED);
        verify(mMockHandler).scheduleRemoval(any(AppOpItem.class), anyLong());
    }

    @Test
    public void noItemsAfterStopListening() {
        mController.setBGHandler(mMockHandler);

        mController.setListening(true);
        mController.onOpActiveChanged(AppOpsManager.OPSTR_FINE_LOCATION, TEST_UID,
                TEST_PACKAGE_NAME, true);
        mController.onOpNoted(AppOpsManager.OP_FINE_LOCATION, TEST_UID, TEST_PACKAGE_NAME,
                TEST_ATTRIBUTION_NAME, AppOpsManager.OP_FLAG_SELF, AppOpsManager.MODE_ALLOWED);
        assertFalse(mController.getActiveAppOps().isEmpty());

        mController.setListening(false);

        verify(mMockHandler).removeCallbacksAndMessages(null);
        assertTrue(mController.getActiveAppOps().isEmpty());
    }

    @Test
    public void noDoubleUpdateOnOpNoted() {
        mController.setBGHandler(mMockHandler);

        mController.onOpNoted(AppOpsManager.OP_FINE_LOCATION, TEST_UID, TEST_PACKAGE_NAME,
                TEST_ATTRIBUTION_NAME, AppOpsManager.OP_FLAG_SELF, AppOpsManager.MODE_ALLOWED);
        mController.onOpNoted(AppOpsManager.OP_FINE_LOCATION, TEST_UID, TEST_PACKAGE_NAME,
                TEST_ATTRIBUTION_NAME, AppOpsManager.OP_FLAG_SELF, AppOpsManager.MODE_ALLOWED);

        // Only one post to notify subscribers
        verify(mMockHandler, times(1)).post(any());

        List<AppOpItem> list = mController.getActiveAppOps();
        assertEquals(1, list.size());
    }

    @Test
    public void onDoubleOPNoted_scheduleTwiceForRemoval() {
        mController.setBGHandler(mMockHandler);

        mController.onOpNoted(AppOpsManager.OP_FINE_LOCATION, TEST_UID, TEST_PACKAGE_NAME,
                TEST_ATTRIBUTION_NAME, AppOpsManager.OP_FLAG_SELF, AppOpsManager.MODE_ALLOWED);
        mController.onOpNoted(AppOpsManager.OP_FINE_LOCATION, TEST_UID, TEST_PACKAGE_NAME,
                TEST_ATTRIBUTION_NAME, AppOpsManager.OP_FLAG_SELF, AppOpsManager.MODE_ALLOWED);

        // Only one post to notify subscribers
        verify(mMockHandler, times(2)).scheduleRemoval(any(), anyLong());
    }

    @Test
    public void testUntrustedChainUsagesDiscarded() {
        assertTrue(mController.getActiveAppOps().isEmpty());
        mController.setBGHandler(mMockHandler);

        //untrusted receiver access
        mController.onOpActiveChanged(AppOpsManager.OPSTR_RECORD_AUDIO, TEST_UID,
                TEST_PACKAGE_NAME, TEST_ATTRIBUTION_NAME, true,
                AppOpsManager.ATTRIBUTION_FLAG_RECEIVER, TEST_CHAIN_ID);
        //untrusted intermediary access
        mController.onOpActiveChanged(AppOpsManager.OPSTR_RECORD_AUDIO, TEST_UID,
                TEST_PACKAGE_NAME, TEST_ATTRIBUTION_NAME, true,
                AppOpsManager.ATTRIBUTION_FLAG_INTERMEDIARY, TEST_CHAIN_ID);
        assertTrue(mController.getActiveAppOps().isEmpty());
    }

    @Test
    public void testTrustedChainUsagesKept() {
        //untrusted accessor access
        mController.onOpActiveChanged(AppOpsManager.OPSTR_RECORD_AUDIO, TEST_UID,
                TEST_PACKAGE_NAME, TEST_ATTRIBUTION_NAME, true,
                AppOpsManager.ATTRIBUTION_FLAG_ACCESSOR, TEST_CHAIN_ID);
        //trusted access
        mController.onOpActiveChanged(AppOpsManager.OPSTR_CAMERA, TEST_UID,
                TEST_PACKAGE_NAME, TEST_ATTRIBUTION_NAME, true,
                AppOpsManager.ATTRIBUTION_FLAG_RECEIVER | AppOpsManager.ATTRIBUTION_FLAG_TRUSTED,
                TEST_CHAIN_ID);
        assertEquals(2, mController.getActiveAppOps().size());
    }

    @Test
    public void testActiveOpNotRemovedAfterNoted() throws InterruptedException {
        // Replaces the timeout delay with 5 ms
        TestHandler testHandler = new TestHandler(mTestableLooper.getLooper());

        mController.addCallback(new int[]{AppOpsManager.OP_FINE_LOCATION}, mCallback);
        mController.setBGHandler(testHandler);

        mController.onOpActiveChanged(
                AppOpsManager.OPSTR_FINE_LOCATION, TEST_UID, TEST_PACKAGE_NAME, true);

        mController.onOpNoted(AppOpsManager.OP_FINE_LOCATION, TEST_UID, TEST_PACKAGE_NAME,
                TEST_ATTRIBUTION_NAME, AppOpsManager.OP_FLAG_SELF, AppOpsManager.MODE_ALLOWED);

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
                TEST_ATTRIBUTION_NAME, AppOpsManager.OP_FLAG_SELF, AppOpsManager.MODE_ALLOWED);

        mController.onOpActiveChanged(
                AppOpsManager.OPSTR_FINE_LOCATION, TEST_UID, TEST_PACKAGE_NAME, true);

        mTestableLooper.processAllMessages();
        List<AppOpItem> list = mController.getActiveAppOps();
        verify(mCallback).onActiveStateChanged(
                AppOpsManager.OP_FINE_LOCATION, TEST_UID, TEST_PACKAGE_NAME, true);

        // Duplicates are not removed between active and noted
        assertEquals(2, list.size());

        mController.onOpActiveChanged(
                AppOpsManager.OPSTR_FINE_LOCATION, TEST_UID, TEST_PACKAGE_NAME, false);

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
                TEST_ATTRIBUTION_NAME, AppOpsManager.OP_FLAG_SELF, AppOpsManager.MODE_ALLOWED);

        mController.onOpActiveChanged(
                AppOpsManager.OPSTR_FINE_LOCATION, TEST_UID, TEST_PACKAGE_NAME, true);

        mTestableLooper.processAllMessages();
        verify(mCallback).onActiveStateChanged(
                AppOpsManager.OP_FINE_LOCATION, TEST_UID, TEST_PACKAGE_NAME, true);
    }

    @Test
    public void testActiveAndNotedOnlyOneCall() {
        mController.addCallback(new int[]{AppOpsManager.OP_FINE_LOCATION}, mCallback);

        mController.onOpActiveChanged(
                AppOpsManager.OPSTR_FINE_LOCATION, TEST_UID, TEST_PACKAGE_NAME, true);

        mController.onOpNoted(AppOpsManager.OP_FINE_LOCATION, TEST_UID, TEST_PACKAGE_NAME,
                TEST_ATTRIBUTION_NAME, AppOpsManager.OP_FLAG_SELF, AppOpsManager.MODE_ALLOWED);

        mTestableLooper.processAllMessages();
        verify(mCallback).onActiveStateChanged(
                AppOpsManager.OP_FINE_LOCATION, TEST_UID, TEST_PACKAGE_NAME, true);
    }

    @Test
    public void testPausedRecordingIsRetrievedOnCreation() {
        mController.addCallback(new int[]{AppOpsManager.OP_RECORD_AUDIO}, mCallback);
        mTestableLooper.processAllMessages();

        mController.onOpActiveChanged(
                AppOpsManager.OPSTR_RECORD_AUDIO, TEST_UID, TEST_PACKAGE_NAME, true);
        mTestableLooper.processAllMessages();

        verify(mCallback, never())
                .onActiveStateChanged(anyInt(), anyInt(), anyString(), anyBoolean());
    }

    @Test
    public void testPausedRecordingFilteredOut() {
        mController.addCallback(new int[]{AppOpsManager.OP_RECORD_AUDIO}, mCallback);
        mTestableLooper.processAllMessages();

        mController.onOpActiveChanged(
                AppOpsManager.OPSTR_RECORD_AUDIO, TEST_UID, TEST_PACKAGE_NAME, true);
        mTestableLooper.processAllMessages();

        assertTrue(mController.getActiveAppOps().isEmpty());
    }

    @Test
    public void testPausedPhoneCallMicrophoneFilteredOut() {
        mController.addCallback(new int[]{AppOpsManager.OP_PHONE_CALL_MICROPHONE}, mCallback);
        mTestableLooper.processAllMessages();

        mController.onOpActiveChanged(
                AppOpsManager.OPSTR_PHONE_CALL_MICROPHONE, TEST_UID, TEST_PACKAGE_NAME, true);
        mTestableLooper.processAllMessages();

        assertTrue(mController.getActiveAppOps().isEmpty());
    }

    @Test
    public void testOnlyRecordAudioPhoneCallMicrophonePaused() {
        mController.addCallback(new int[]{
                AppOpsManager.OP_RECORD_AUDIO,
                AppOpsManager.OP_CAMERA
        }, mCallback);
        mTestableLooper.processAllMessages();

        mController.onOpActiveChanged(
                AppOpsManager.OPSTR_CAMERA, TEST_UID, TEST_PACKAGE_NAME, true);
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
                AppOpsManager.OPSTR_RECORD_AUDIO, TEST_UID, TEST_PACKAGE_NAME, true);

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
                AppOpsManager.OPSTR_RECORD_AUDIO, TEST_UID_OTHER, TEST_PACKAGE_NAME, true);
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

    @Test
    public void testAudioFilteredWhenMicDisabled() {
        mController.addCallback(new int[]{AppOpsManager.OP_RECORD_AUDIO, AppOpsManager.OP_CAMERA},
                mCallback);
        mTestableLooper.processAllMessages();
        mController.onOpActiveChanged(
                AppOpsManager.OPSTR_RECORD_AUDIO, TEST_UID_OTHER, TEST_PACKAGE_NAME, true);
        mTestableLooper.processAllMessages();
        List<AppOpItem> list = mController.getActiveAppOps();
        assertEquals(1, list.size());
        assertEquals(AppOpsManager.OP_RECORD_AUDIO, list.get(0).getCode());
        assertFalse(list.get(0).isDisabled());

        // Add a camera op, and disable the microphone. The camera op should be the only op returned
        mController.onSensorBlockedChanged(MICROPHONE, true);
        mController.onOpActiveChanged(
                AppOpsManager.OPSTR_CAMERA, TEST_UID_OTHER, TEST_PACKAGE_NAME, true);
        mTestableLooper.processAllMessages();
        list = mController.getActiveAppOps();
        assertEquals(1, list.size());
        assertEquals(AppOpsManager.OP_CAMERA, list.get(0).getCode());


        // Re enable the microphone, and verify the op returns
        mController.onSensorBlockedChanged(MICROPHONE, false);
        mTestableLooper.processAllMessages();

        list = mController.getActiveAppOps();
        assertEquals(2, list.size());
        int micIdx = list.get(0).getCode() == AppOpsManager.OP_CAMERA ? 1 : 0;
        assertEquals(AppOpsManager.OP_RECORD_AUDIO, list.get(micIdx).getCode());
    }

    @Test
    public void testPhoneCallMicrophoneFilteredWhenMicDisabled() {
        mController.addCallback(
                new int[]{AppOpsManager.OP_PHONE_CALL_MICROPHONE, AppOpsManager.OP_CAMERA},
                mCallback);
        mTestableLooper.processAllMessages();
        mController.onOpActiveChanged(
                AppOpsManager.OPSTR_PHONE_CALL_MICROPHONE, TEST_UID_OTHER, TEST_PACKAGE_NAME, true);
        mTestableLooper.processAllMessages();
        List<AppOpItem> list = mController.getActiveAppOps();
        assertEquals(1, list.size());
        assertEquals(AppOpsManager.OP_PHONE_CALL_MICROPHONE, list.get(0).getCode());
        assertFalse(list.get(0).isDisabled());

        // Add a camera op, and disable the microphone. The camera op should be the only op returned
        mController.onSensorBlockedChanged(MICROPHONE, true);
        mController.onOpActiveChanged(
                AppOpsManager.OPSTR_CAMERA, TEST_UID_OTHER, TEST_PACKAGE_NAME, true);
        mTestableLooper.processAllMessages();
        list = mController.getActiveAppOps();
        assertEquals(1, list.size());
        assertEquals(AppOpsManager.OP_CAMERA, list.get(0).getCode());


        // Re enable the microphone, and verify the op returns
        mController.onSensorBlockedChanged(MICROPHONE, false);
        mTestableLooper.processAllMessages();

        list = mController.getActiveAppOps();
        assertEquals(2, list.size());
        int micIdx = list.get(0).getCode() == AppOpsManager.OP_CAMERA ? 1 : 0;
        assertEquals(AppOpsManager.OP_PHONE_CALL_MICROPHONE, list.get(micIdx).getCode());
    }

    @Test
    public void testCameraFilteredWhenCameraDisabled() {
        mController.addCallback(new int[]{AppOpsManager.OP_RECORD_AUDIO, AppOpsManager.OP_CAMERA},
                mCallback);
        mTestableLooper.processAllMessages();
        mController.onOpActiveChanged(
                AppOpsManager.OPSTR_CAMERA, TEST_UID_OTHER, TEST_PACKAGE_NAME, true);
        mTestableLooper.processAllMessages();
        List<AppOpItem> list = mController.getActiveAppOps();
        assertEquals(1, list.size());
        assertEquals(AppOpsManager.OP_CAMERA, list.get(0).getCode());
        assertFalse(list.get(0).isDisabled());

        // Add an audio op, and disable the camera. The audio op should be the only op returned
        mController.onSensorBlockedChanged(CAMERA, true);
        mController.onOpActiveChanged(
                AppOpsManager.OPSTR_RECORD_AUDIO, TEST_UID_OTHER, TEST_PACKAGE_NAME, true);
        mTestableLooper.processAllMessages();
        list = mController.getActiveAppOps();
        assertEquals(1, list.size());
        assertEquals(AppOpsManager.OP_RECORD_AUDIO, list.get(0).getCode());

        // Re enable the camera, and verify the op returns
        mController.onSensorBlockedChanged(CAMERA, false);
        mTestableLooper.processAllMessages();

        list = mController.getActiveAppOps();
        assertEquals(2, list.size());
        int cameraIdx = list.get(0).getCode() == AppOpsManager.OP_CAMERA ? 0 : 1;
        assertEquals(AppOpsManager.OP_CAMERA, list.get(cameraIdx).getCode());
    }

    @Test
    public void testPhoneCallCameraFilteredWhenCameraDisabled() {
        mController.addCallback(
                new int[]{AppOpsManager.OP_RECORD_AUDIO, AppOpsManager.OP_PHONE_CALL_CAMERA},
                mCallback);
        mTestableLooper.processAllMessages();
        mController.onOpActiveChanged(
                AppOpsManager.OPSTR_PHONE_CALL_CAMERA, TEST_UID_OTHER, TEST_PACKAGE_NAME, true);
        mTestableLooper.processAllMessages();
        List<AppOpItem> list = mController.getActiveAppOps();
        assertEquals(1, list.size());
        assertEquals(AppOpsManager.OP_PHONE_CALL_CAMERA, list.get(0).getCode());
        assertFalse(list.get(0).isDisabled());

        // Add an audio op, and disable the camera. The audio op should be the only op returned
        mController.onSensorBlockedChanged(CAMERA, true);
        mController.onOpActiveChanged(
                AppOpsManager.OPSTR_RECORD_AUDIO, TEST_UID_OTHER, TEST_PACKAGE_NAME, true);
        mTestableLooper.processAllMessages();
        list = mController.getActiveAppOps();
        assertEquals(1, list.size());
        assertEquals(AppOpsManager.OP_RECORD_AUDIO, list.get(0).getCode());

        // Re enable the camera, and verify the op returns
        mController.onSensorBlockedChanged(CAMERA, false);
        mTestableLooper.processAllMessages();

        list = mController.getActiveAppOps();
        assertEquals(2, list.size());
        int cameraIdx = list.get(0).getCode() == AppOpsManager.OP_PHONE_CALL_CAMERA ? 0 : 1;
        assertEquals(AppOpsManager.OP_PHONE_CALL_CAMERA, list.get(cameraIdx).getCode());
    }

    private AppOpsManager.PackageOps createPackageOp(
            String packageName, int packageUid, String opStr, boolean isRunning) {
        AppOpsManager.PackageOps packageOps = mock(AppOpsManager.PackageOps.class);
        when(packageOps.getPackageName()).thenReturn(packageName);
        when(packageOps.getUid()).thenReturn(packageUid);
        AppOpsManager.OpEntry entry = mock(AppOpsManager.OpEntry.class);
        when(entry.getOpStr()).thenReturn(opStr);
        AppOpsManager.AttributedOpEntry attributed = mock(AppOpsManager.AttributedOpEntry.class);
        when(attributed.isRunning()).thenReturn(isRunning);

        when(packageOps.getOps()).thenReturn(Collections.singletonList(entry));
        when(entry.getAttributedOpEntries()).thenReturn(Map.of("tag", attributed));

        return packageOps;
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
