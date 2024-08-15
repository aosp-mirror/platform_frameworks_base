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

package com.android.systemui.qs.tiles;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertTrue;

import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Dialog;
import android.os.Handler;
import android.service.quicksettings.Tile;
import android.testing.TestableLooper;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.animation.DialogTransitionAnimator;
import com.android.systemui.classifier.FalsingManagerFake;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.mediaprojection.MediaProjectionMetricsLogger;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QsEventLogger;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.pipeline.domain.interactor.PanelInteractor;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.res.R;
import com.android.systemui.screenrecord.RecordingController;
import com.android.systemui.settings.UserContextProvider;
import com.android.systemui.statusbar.phone.KeyguardDismissUtil;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@SmallTest
public class ScreenRecordTileTest extends SysuiTestCase {

    @Mock
    private RecordingController mController;
    @Mock
    private QSHost mHost;
    @Mock
    private KeyguardDismissUtil mKeyguardDismissUtil;
    @Mock
    private MetricsLogger mMetricsLogger;
    @Mock
    private StatusBarStateController mStatusBarStateController;
    @Mock
    private ActivityStarter mActivityStarter;
    @Mock
    private QSLogger mQSLogger;
    @Mock
    private FeatureFlags mFeatureFlags;
    @Mock
    private KeyguardStateController mKeyguardStateController;
    @Mock
    private DialogTransitionAnimator mDialogTransitionAnimator;
    @Mock
    private PanelInteractor mPanelInteractor;
    @Mock
    private QsEventLogger mUiEventLogger;
    @Mock
    private MediaProjectionMetricsLogger mMediaProjectionMetricsLogger;
    @Mock
    private Dialog mPermissionDialogPrompt;
    @Mock
    private UserContextProvider mUserContextProvider;

    private TestableLooper mTestableLooper;
    private ScreenRecordTile mTile;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mTestableLooper = TestableLooper.get(this);

        when(mUserContextProvider.getUserContext()).thenReturn(mContext);
        when(mHost.getContext()).thenReturn(mContext);

        mTile = new ScreenRecordTile(
                mHost,
                mUiEventLogger,
                mTestableLooper.getLooper(),
                new Handler(mTestableLooper.getLooper()),
                new FalsingManagerFake(),
                mMetricsLogger,
                mFeatureFlags,
                mStatusBarStateController,
                mActivityStarter,
                mQSLogger,
                mController,
                mKeyguardDismissUtil,
                mKeyguardStateController,
                mDialogTransitionAnimator,
                mPanelInteractor,
                mMediaProjectionMetricsLogger,
                mUserContextProvider
        );

        mTile.initialize();
        mTestableLooper.processAllMessages();
    }

    @After
    public void tearDown() {
        mTile.destroy();
        mTestableLooper.processAllMessages();
    }

    // Test that the tile is inactive and labeled correctly when the controller is neither starting
    // or recording, and that clicking on the tile in this state brings up the record prompt
    @Test
    public void testNotActive() {
        when(mController.isStarting()).thenReturn(false);
        when(mController.isRecording()).thenReturn(false);

        mTile.refreshState();
        mTestableLooper.processAllMessages();

        assertEquals(Tile.STATE_INACTIVE, mTile.getState().state);
        assertTrue(mTile.getState().secondaryLabel.toString().equals(
                mContext.getString(R.string.quick_settings_screen_record_start)));

        mTile.handleClick(null /* view */);
        mTestableLooper.processAllMessages();

        ArgumentCaptor<Runnable> onStartRecordingClicked = ArgumentCaptor.forClass(Runnable.class);
        verify(mController).createScreenRecordDialog(any(), eq(mFeatureFlags),
                eq(mDialogTransitionAnimator), eq(mActivityStarter),
                onStartRecordingClicked.capture());

        // When starting the recording, we collapse the shade and disable the dialog animation.
        assertNotNull(onStartRecordingClicked.getValue());
        onStartRecordingClicked.getValue().run();
        verify(mDialogTransitionAnimator).disableAllCurrentDialogsExitAnimations();
        verify(mPanelInteractor).collapsePanels();
    }

    // Test that the tile is active and labeled correctly when the controller is starting
    @Test
    public void testIsStarting() {
        when(mController.isStarting()).thenReturn(true);
        when(mController.isRecording()).thenReturn(false);

        mTile.refreshState();
        mTestableLooper.processAllMessages();

        assertEquals(Tile.STATE_ACTIVE, mTile.getState().state);
        assertTrue(mTile.getState().secondaryLabel.toString().endsWith("..."));
    }

    // Test that the tile cancels countdown if it is clicked when the controller is starting
    @Test
    public void testCancelRecording() {
        when(mController.isStarting()).thenReturn(true);
        when(mController.isRecording()).thenReturn(false);

        mTile.handleClick(null /* view */);

        verify(mController, times(1)).cancelCountdown();
    }

    // Test that the tile is active and labeled correctly when the controller is recording
    @Test
    public void testIsRecording() {
        when(mController.isStarting()).thenReturn(false);
        when(mController.isRecording()).thenReturn(true);

        mTile.refreshState();
        mTestableLooper.processAllMessages();

        assertEquals(Tile.STATE_ACTIVE, mTile.getState().state);
        assertTrue(mTile.getState().secondaryLabel.toString().equals(
                mContext.getString(R.string.quick_settings_screen_record_stop)));
    }

    // Test that the tile stops the recording if it is clicked when the controller is recording
    @Test
    public void testStopRecording() {
        when(mController.isStarting()).thenReturn(false);
        when(mController.isRecording()).thenReturn(true);

        mTile.handleClick(null /* view */);

        verify(mController, times(1)).stopRecording();
    }

    @Test
    public void testContentDescriptionHasTileName() {
        mTile.refreshState();
        mTestableLooper.processAllMessages();

        assertTrue(mTile.getState().contentDescription.toString().contains(mTile.getState().label));
    }

    @Test
    public void testForceExpandIcon_notRecordingNotStarting() {
        when(mController.isStarting()).thenReturn(false);
        when(mController.isRecording()).thenReturn(false);

        mTile.refreshState();
        mTestableLooper.processAllMessages();

        assertTrue(mTile.getState().forceExpandIcon);
    }

    @Test
    public void testForceExpandIcon_recordingNotStarting() {
        when(mController.isStarting()).thenReturn(false);
        when(mController.isRecording()).thenReturn(true);

        mTile.refreshState();
        mTestableLooper.processAllMessages();

        assertFalse(mTile.getState().forceExpandIcon);
    }

    @Test
    public void testForceExpandIcon_startingNotRecording() {
        when(mController.isStarting()).thenReturn(true);
        when(mController.isRecording()).thenReturn(false);

        mTile.refreshState();
        mTestableLooper.processAllMessages();

        assertFalse(mTile.getState().forceExpandIcon);
    }

    @Test
    public void testIcon_whenRecording_isOnState() {
        when(mController.isStarting()).thenReturn(false);
        when(mController.isRecording()).thenReturn(true);
        QSTile.BooleanState state = new QSTile.BooleanState();

        mTile.handleUpdateState(state, /* arg= */ null);

        assertEquals(state.icon, QSTileImpl.ResourceIcon.get(R.drawable.qs_screen_record_icon_on));
    }

    @Test
    public void testIcon_whenStarting_isOnState() {
        when(mController.isStarting()).thenReturn(true);
        when(mController.isRecording()).thenReturn(false);
        QSTile.BooleanState state = new QSTile.BooleanState();

        mTile.handleUpdateState(state, /* arg= */ null);

        assertEquals(state.icon, QSTileImpl.ResourceIcon.get(R.drawable.qs_screen_record_icon_on));
    }

    @Test
    public void testIcon_whenRecordingOff_isOffState() {
        when(mController.isStarting()).thenReturn(false);
        when(mController.isRecording()).thenReturn(false);
        QSTile.BooleanState state = new QSTile.BooleanState();

        mTile.handleUpdateState(state, /* arg= */ null);

        assertEquals(state.icon, QSTileImpl.ResourceIcon.get(R.drawable.qs_screen_record_icon_off));
    }

    @Test
    public void showingDialogPrompt_logsMediaProjectionPermissionRequested() {
        when(mController.isStarting()).thenReturn(false);
        when(mController.isRecording()).thenReturn(false);
        when(mController.createScreenRecordDialog(any(), any(), any(), any(), any()))
                .thenReturn(mPermissionDialogPrompt);

        mTile.handleClick(null /* view */);
        mTestableLooper.processAllMessages();

        verify(mController).createScreenRecordDialog(any(), eq(mFeatureFlags),
                eq(mDialogTransitionAnimator), eq(mActivityStarter), any());
        var onDismissAction = ArgumentCaptor.forClass(ActivityStarter.OnDismissAction.class);
        verify(mKeyguardDismissUtil).executeWhenUnlocked(
                onDismissAction.capture(), anyBoolean(), anyBoolean());
        assertNotNull(onDismissAction.getValue());

        onDismissAction.getValue().onDismiss();

        verify(mPermissionDialogPrompt).show();
        verify(mMediaProjectionMetricsLogger)
                .notifyPermissionRequestDisplayed(mContext.getUserId());
    }

}
