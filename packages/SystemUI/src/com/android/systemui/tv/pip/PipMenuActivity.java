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
import android.media.session.MediaController;
import android.os.Bundle;
import android.view.View;

import com.android.systemui.R;

/**
 * Activity to show the PIP menu to control PIP.
 */
public class PipMenuActivity extends Activity implements PipManager.Listener {
    private static final String TAG = "PipMenuActivity";
    private static final boolean DEBUG = false;

    private final PipManager mPipManager = PipManager.getInstance();
    private MediaController mMediaController;

    private View mFullButtonView;
    private View mFullDescriptionView;
    private View mPlayPauseButtonView;
    private View mPlayPauseDescriptionView;
    private View mCloseButtonView;
    private View mCloseDescriptionView;

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        setContentView(R.layout.tv_pip_menu);
        mPipManager.addListener(this);
        mFullButtonView = findViewById(R.id.full);
        mFullDescriptionView = findViewById(R.id.full_desc);
        mFullButtonView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mPipManager.movePipToFullscreen();
                finish();
            }
        });
        mFullButtonView.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                mFullDescriptionView.setVisibility(hasFocus ? View.VISIBLE : View.INVISIBLE);
            }
        });

        mPlayPauseButtonView = findViewById(R.id.play_pause);
        mPlayPauseDescriptionView = findViewById(R.id.play_pause_desc);
        mPlayPauseButtonView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // TODO: Implement play/pause.
            }
        });
        mPlayPauseButtonView.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                mPlayPauseDescriptionView.setVisibility(hasFocus ? View.VISIBLE : View.INVISIBLE);
            }
        });

        mCloseButtonView = findViewById(R.id.close);
        mCloseDescriptionView = findViewById(R.id.close_desc);
        mCloseButtonView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mPipManager.closePip();
                finish();
            }
        });
        mCloseButtonView.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                mCloseDescriptionView.setVisibility(hasFocus ? View.VISIBLE : View.INVISIBLE);
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mPipManager.removeListener(this);
        mPipManager.resumePipResizing(
                PipManager.SUSPEND_PIP_RESIZE_REASON_WAITING_FOR_MENU_ACTIVITY_FINISH);
    }

    @Override
    public void onBackPressed() {
        mPipManager.resizePinnedStack(PipManager.STATE_PIP_OVERLAY);
        finish();
    }

    @Override
    public void onPipActivityClosed() {
        finish();
    }

    @Override
    public void onShowPipMenu() { }

    @Override
    public void onMoveToFullscreen() {
        finish();
    }

    @Override
    public void onPipResizeAboutToStart() {
        finish();
        mPipManager.suspendPipResizing(
                PipManager.SUSPEND_PIP_RESIZE_REASON_WAITING_FOR_MENU_ACTIVITY_FINISH);
    }
}
