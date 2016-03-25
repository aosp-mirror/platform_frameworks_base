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

package com.android.systemui.tv.pip;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.ImageView;

import com.android.systemui.R;

import static android.app.ActivityManager.StackId.PINNED_STACK_ID;

/**
 * Activity to show an overlay on top of PIP activity to show how to pop up PIP menu.
 */
public class PipOverlayActivity extends Activity implements PipManager.Listener {
    private static final long SHOW_GUIDE_OVERLAY_VIEW_DURATION_MS = 4000;

    /**
     * The single instance of PipOverlayActivity to prevent it from restarting.
     * Note that {@link PipManager} moves the PIPed activity to fullscreen if the activity is
     * restarted. It's because the activity may be started by the Launcher or an intent again,
     * but we don't want do so for the PipOverlayActivity.
     */
    private static PipOverlayActivity sPipOverlayActivity;

    private final PipManager mPipManager = PipManager.getInstance();
    private final Handler mHandler = new Handler();
    private View mGuideOverlayView;
    private View mGuideButtonsView;
    private ImageView mGuideButtonPlayPauseImageView;
    private final Runnable mHideGuideOverlayRunnable = new Runnable() {
        public void run() {
            mGuideOverlayView.setVisibility(View.GONE);
        }
    };

    /**
     * Launches the PIP overlay. This should be only called on the main thread.
     */
    public static void showPipOverlay(Context context) {
        if (sPipOverlayActivity == null) {
            Intent intent = new Intent(context, PipOverlayActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            final ActivityOptions options = ActivityOptions.makeBasic();
            options.setLaunchStackId(PINNED_STACK_ID);
            context.startActivity(intent, options.toBundle());
        }
    }

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        setContentView(R.layout.tv_pip_overlay);
        mGuideOverlayView = findViewById(R.id.guide_overlay);
        mGuideButtonsView = findViewById(R.id.guide_buttons);
        mGuideButtonPlayPauseImageView = (ImageView) findViewById(R.id.guide_button_play_pause);
        mPipManager.addListener(this);

        sPipOverlayActivity = this;
    }

    @Override
    protected void onResume() {
        super.onResume();
        // TODO: Implement animation for this
        if (mPipManager.isRecentsShown()) {
            mGuideOverlayView.setVisibility(View.GONE);
            if (mPipManager.isPipViewFocusdInRecents()) {
                mGuideButtonsView.setVisibility(View.GONE);
            } else {
                mGuideButtonsView.setVisibility(View.VISIBLE);
                updateGuideButtonsView();
            }
        } else {
            mGuideOverlayView.setVisibility(View.VISIBLE);
            mGuideButtonsView.setVisibility(View.GONE);
        }
        mHandler.removeCallbacks(mHideGuideOverlayRunnable);
        mHandler.postDelayed(mHideGuideOverlayRunnable, SHOW_GUIDE_OVERLAY_VIEW_DURATION_MS);
    }

    @Override
    protected void onStop() {
        super.onStop();
        mHandler.removeCallbacks(mHideGuideOverlayRunnable);
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        sPipOverlayActivity = null;
        mHandler.removeCallbacksAndMessages(null);
        mPipManager.removeListener(this);
        mPipManager.resumePipResizing(
                PipManager.SUSPEND_PIP_RESIZE_REASON_WAITING_FOR_OVERLAY_ACTIVITY_FINISH);
    }

    @Override
    public void onPipEntered() { }

    @Override
    public void onPipActivityClosed() {
        finish();
    }

    @Override
    public void onShowPipMenu() {
        finish();
    }

    @Override
    public void onMoveToFullscreen() {
        finish();
    }

    @Override
    public void onPipResizeAboutToStart() {
        finish();
        mPipManager.suspendPipResizing(
                PipManager.SUSPEND_PIP_RESIZE_REASON_WAITING_FOR_OVERLAY_ACTIVITY_FINISH);
    }

    @Override
    public void onMediaControllerChanged() {
        updateGuideButtonsView();
    }

    @Override
    public void finish() {
        sPipOverlayActivity = null;
        super.finish();
    }

    private void updateGuideButtonsView() {
        switch (mPipManager.getPlaybackState()) {
            case PipManager.PLAYBACK_STATE_PLAYING:
                mGuideButtonPlayPauseImageView.setVisibility(View.VISIBLE);
                mGuideButtonPlayPauseImageView.setImageResource(R.drawable.ic_pause_white_24dp);
                break;
            case PipManager.PLAYBACK_STATE_PAUSED:
                mGuideButtonPlayPauseImageView.setVisibility(View.VISIBLE);
                mGuideButtonPlayPauseImageView.setImageResource(
                        R.drawable.ic_play_arrow_white_24dp);
                break;
            case PipManager.PLAYBACK_STATE_UNAVAILABLE:
                mGuideButtonPlayPauseImageView.setVisibility(View.GONE);
                break;
        }
    }
}
