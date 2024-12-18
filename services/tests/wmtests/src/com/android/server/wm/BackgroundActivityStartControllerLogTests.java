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

package com.android.server.wm;

import static com.android.server.wm.BackgroundActivityStartController.BAL_ALLOW_SAW_PERMISSION;
import static com.android.server.wm.BackgroundActivityStartController.BAL_ALLOW_VISIBLE_WINDOW;
import static com.android.server.wm.BackgroundActivityStartControllerTests.setViaReflection;

import static com.google.common.truth.Truth.assertThat;

import android.app.ActivityOptions;
import android.app.BackgroundStartPrivileges;
import android.content.Intent;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import com.android.server.am.PendingIntentRecord;
import com.android.server.wm.BackgroundActivityStartController.BalVerdict;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.mockito.quality.Strictness;

/**
 * Tests for the {@link BackgroundActivityStartController} class.
 *
 * Build/Install/Run:
 * atest WmTests:BackgroundActivityStartControllerLogTests
 */
@SmallTest
@Presubmit
@RunWith(JUnit4.class)
public class BackgroundActivityStartControllerLogTests {

    private static final int SYSTEM_UID = 1000;
    private static final int APP1_UID = 10000;
    private static final int APP2_UID = 10001;
    private static final int APP1_PID = 10002;
    private static final int APP2_PID = 10003;

    public @Rule MockitoRule mMockitoRule = MockitoJUnit.rule().strictness(Strictness.LENIENT);

    @Mock
    ActivityTaskSupervisor mSupervisor;
    @Mock
    ActivityTaskManagerService mService;
    @Mock
    PendingIntentRecord mPendingIntentRecord;
    MirrorActiveUids mActiveUids = new MirrorActiveUids();
    BackgroundActivityStartController mController;
    BackgroundActivityStartController.BalState mState;

    @Before
    public void setup() {
        setViaReflection(mService, "mActiveUids", mActiveUids);
        mController = new BackgroundActivityStartController(mService,
                mSupervisor);
    }

    @Test
    public void intent_blocked_log() {
        useIntent();
        mState.setResultForCaller(BalVerdict.BLOCK);
        mState.setResultForRealCaller(BalVerdict.BLOCK);
        assertThat(mController.shouldLogStats(BalVerdict.BLOCK, mState)).isTrue();
    }

    @Test
    public void intent_visible_noLog() {
        useIntent();
        BalVerdict finalVerdict = new BalVerdict(BAL_ALLOW_VISIBLE_WINDOW, false, "visible");
        mState.setResultForCaller(finalVerdict);
        mState.setResultForRealCaller(BalVerdict.BLOCK);
        assertThat(mController.shouldLogStats(finalVerdict, mState)).isFalse();
    }

    @Test
    public void intent_saw_log() {
        useIntent();
        BalVerdict finalVerdict = new BalVerdict(BAL_ALLOW_SAW_PERMISSION, false, "SAW");
        mState.setResultForCaller(finalVerdict);
        mState.setResultForRealCaller(BalVerdict.BLOCK);
        assertThat(mController.shouldLogStats(finalVerdict, mState)).isTrue();
        assertThat(mController.shouldLogIntentActivity(finalVerdict, mState)).isFalse();
    }

    @Test
    public void pendingIntent_callerOnly_saw_log() {
        usePendingIntent();
        BalVerdict finalVerdict = new BalVerdict(BAL_ALLOW_SAW_PERMISSION, false, "SAW");
        mState.setResultForCaller(finalVerdict);
        mState.setResultForRealCaller(BalVerdict.BLOCK);
        assertThat(mController.shouldLogStats(finalVerdict, mState)).isTrue();
        assertThat(mController.shouldLogIntentActivity(finalVerdict, mState)).isFalse();
    }

    @Test
    public void pendingIntent_realCallerOnly_saw_log() {
        usePendingIntent();
        BalVerdict finalVerdict = new BalVerdict(BAL_ALLOW_SAW_PERMISSION, false, "SAW")
                .setBasedOnRealCaller();
        mState.setResultForCaller(BalVerdict.BLOCK);
        mState.setResultForRealCaller(finalVerdict);
        assertThat(mController.shouldLogStats(finalVerdict, mState)).isTrue();
        assertThat(mController.shouldLogIntentActivity(finalVerdict, mState)).isFalse();
    }

    @Test
    public void intent_shouldLogIntentActivity() {
        BalVerdict finalVerdict = new BalVerdict(BAL_ALLOW_SAW_PERMISSION, false, "SAW");
        useIntent(APP1_UID);
        assertThat(mController.shouldLogIntentActivity(finalVerdict, mState)).isFalse();
        useIntent(SYSTEM_UID);
        assertThat(mController.shouldLogIntentActivity(finalVerdict, mState)).isTrue();
    }

    @Test
    public void pendingIntent_shouldLogIntentActivityForCaller() {
        BalVerdict finalVerdict = new BalVerdict(BAL_ALLOW_SAW_PERMISSION, false, "SAW");
        usePendingIntent(APP1_UID, APP2_UID);
        assertThat(mController.shouldLogIntentActivity(finalVerdict, mState)).isFalse();
        usePendingIntent(SYSTEM_UID, SYSTEM_UID);
        assertThat(mController.shouldLogIntentActivity(finalVerdict, mState)).isTrue();
        usePendingIntent(SYSTEM_UID, APP2_UID);
        assertThat(mController.shouldLogIntentActivity(finalVerdict, mState)).isTrue();
        usePendingIntent(APP1_UID, SYSTEM_UID);
        assertThat(mController.shouldLogIntentActivity(finalVerdict, mState)).isFalse();
    }

    @Test
    public void pendingIntent_shouldLogIntentActivityForRealCaller() {
        BalVerdict finalVerdict = new BalVerdict(BAL_ALLOW_SAW_PERMISSION, false,
                "SAW").setBasedOnRealCaller();
        usePendingIntent(APP1_UID, APP2_UID);
        assertThat(mController.shouldLogIntentActivity(finalVerdict, mState)).isFalse();
        usePendingIntent(SYSTEM_UID, SYSTEM_UID);
        assertThat(mController.shouldLogIntentActivity(finalVerdict, mState)).isTrue();
        usePendingIntent(SYSTEM_UID, APP2_UID);
        assertThat(mController.shouldLogIntentActivity(finalVerdict, mState)).isFalse();
        usePendingIntent(APP1_UID, SYSTEM_UID);
        assertThat(mController.shouldLogIntentActivity(finalVerdict, mState)).isTrue();
    }

    @Test
    public void pendingIntent_realCallerOnly_visible_noLog() {
        usePendingIntent();
        BalVerdict finalVerdict = new BalVerdict(BAL_ALLOW_VISIBLE_WINDOW, false,
                "visible").setBasedOnRealCaller();
        mState.setResultForCaller(BalVerdict.BLOCK);
        mState.setResultForRealCaller(finalVerdict);
        assertThat(mController.shouldLogStats(finalVerdict, mState)).isFalse();
    }

    @Test
    public void pendingIntent_callerOnly_visible_noLog() {
        usePendingIntent();
        BalVerdict finalVerdict = new BalVerdict(BAL_ALLOW_VISIBLE_WINDOW, false, "visible");
        mState.setResultForCaller(finalVerdict);
        mState.setResultForRealCaller(BalVerdict.BLOCK);
        assertThat(mController.shouldLogStats(finalVerdict, mState)).isTrue();
        assertThat(mController.shouldLogIntentActivity(finalVerdict, mState)).isFalse();
    }

    private void useIntent() {
        useIntent(APP1_UID);
    }

    private void useIntent(int uid) {
        mState = mController.new BalState(uid, APP1_PID,
                "calling.package", uid, APP1_PID, null,
                null, BackgroundStartPrivileges.NONE, null, new Intent(),
                ActivityOptions.makeBasic());
    }

    private void usePendingIntent() {
        usePendingIntent(APP1_UID, APP2_UID);
    }

    private void usePendingIntent(int callerUid, int realCallerUid) {
        mState = mController.new BalState(callerUid, APP1_PID,
                "calling.package", realCallerUid, APP2_PID, null,
                mPendingIntentRecord, BackgroundStartPrivileges.NONE, null, new Intent(),
                ActivityOptions.makeBasic());
    }
}
