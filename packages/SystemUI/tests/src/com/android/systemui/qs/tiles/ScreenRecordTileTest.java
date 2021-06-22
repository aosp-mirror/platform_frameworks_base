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
import static junit.framework.Assert.assertTrue;

import static org.junit.Assert.assertFalse;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.os.Handler;
import android.service.quicksettings.Tile;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.classifier.FalsingManagerFake;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSTileHost;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.screenrecord.RecordingController;
import com.android.systemui.statusbar.phone.KeyguardDismissUtil;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@SmallTest
public class ScreenRecordTileTest extends SysuiTestCase {

    @Mock
    private RecordingController mController;
    @Mock
    private QSTileHost mHost;
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

    private TestableLooper mTestableLooper;
    private ScreenRecordTile mTile;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mTestableLooper = TestableLooper.get(this);

        when(mHost.getContext()).thenReturn(mContext);

        mTile = new ScreenRecordTile(
                mHost,
                mTestableLooper.getLooper(),
                new Handler(mTestableLooper.getLooper()),
                new FalsingManagerFake(),
                mMetricsLogger,
                mStatusBarStateController,
                mActivityStarter,
                mQSLogger,
                mController,
                mKeyguardDismissUtil
        );

        mTile.initialize();
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
        verify(mController, times(1)).getPromptIntent();
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
}
