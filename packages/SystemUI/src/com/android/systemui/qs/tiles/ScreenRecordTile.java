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

import android.content.Intent;
import android.service.quicksettings.Tile;
import android.util.Log;

import com.android.systemui.R;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.screenrecord.RecordingController;

import javax.inject.Inject;

/**
 * Quick settings tile for screen recording
 */
public class ScreenRecordTile extends QSTileImpl<QSTile.BooleanState> {
    private static final String TAG = "ScreenRecordTile";
    private RecordingController mController;
    private long mMillisUntilFinished = 0;

    @Inject
    public ScreenRecordTile(QSHost host, RecordingController controller) {
        super(host);
        mController = controller;
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    protected void handleClick() {
        if (mController.isStarting()) {
            cancelCountdown();
        } else if (mController.isRecording()) {
            stopRecording();
        } else {
            startCountdown();
        }
        refreshState();
    }

    /**
     * Refresh tile state
     * @param millisUntilFinished Time until countdown completes, or 0 if not counting down
     */
    public void refreshState(long millisUntilFinished) {
        mMillisUntilFinished = millisUntilFinished;
        refreshState();
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        boolean isStarting = mController.isStarting();
        boolean isRecording = mController.isRecording();

        state.label = mContext.getString(R.string.quick_settings_screen_record_label);
        state.value = isRecording || isStarting;
        state.state = (isRecording || isStarting) ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;
        state.handlesLongClick = false;

        if (isRecording) {
            state.icon = ResourceIcon.get(R.drawable.ic_qs_screenrecord);
            state.secondaryLabel = mContext.getString(R.string.quick_settings_screen_record_stop);
        } else if (isStarting) {
            // round, since the timer isn't exact
            int countdown = (int) Math.floorDiv(mMillisUntilFinished + 500, 1000);
            // TODO update icon
            state.icon = ResourceIcon.get(R.drawable.ic_qs_screenrecord);
            state.secondaryLabel = String.format("%d...", countdown);
        } else {
            // TODO update icon
            state.icon = ResourceIcon.get(R.drawable.ic_qs_screenrecord);
            state.secondaryLabel = mContext.getString(R.string.quick_settings_screen_record_start);
        }
    }

    @Override
    public int getMetricsCategory() {
        return 0;
    }

    @Override
    public Intent getLongClickIntent() {
        return null;
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_screen_record_label);
    }

    private void startCountdown() {
        Log.d(TAG, "Starting countdown");
        // Close QS, otherwise the permission dialog appears beneath it
        getHost().collapsePanels();
        mController.launchRecordPrompt(this);
    }

    private void cancelCountdown() {
        Log.d(TAG, "Cancelling countdown");
        mController.cancelCountdown();
    }

    private void stopRecording() {
        Log.d(TAG, "Stopping recording from tile");
        mController.stopRecording();
    }
}
