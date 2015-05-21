/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.mediadump;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.MediaController;

/**
 * A media tool to play a video and dump the screen display
 * into raw RGB files. Check VideoDumpView for tech details.
 */
public class VideoDumpActivity extends Activity {

    private Context context;

    private View mainView;
    private VideoDumpView mVideoView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        context = this;

        mainView = createView();
        setContentView(mainView);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mVideoView.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mVideoView.onResume();
    }

    private View createView() {
        mVideoView = new VideoDumpView(this);
        mVideoView.setMediaController(new MediaController(context));

        LinearLayout mainLayout = new LinearLayout(this);
        mainLayout.addView(mVideoView, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT));

        return mainLayout;
    }

    protected void onStop() {
        if (mVideoView != null) {
            if (mVideoView.isPlaying()) {
                mVideoView.stopPlayback();
            }
        }
        super.onStop();
    }
}

