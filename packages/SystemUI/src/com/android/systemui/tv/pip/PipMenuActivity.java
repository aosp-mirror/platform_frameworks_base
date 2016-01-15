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
import android.util.Log;
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

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        setContentView(R.layout.tv_pip_menu);
        mPipManager.addListener(this);
        findViewById(R.id.full).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mPipManager.movePipToFullscreen();
            }
        });
        findViewById(R.id.exit).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mPipManager.closePip();
                finish();
            }
        });
        findViewById(R.id.cancel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mPipManager.showPipOverlay();
                finish();
            }
        });
    }

    @Override
    protected void onDestroy() {
        mPipManager.removeListener(this);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        mPipManager.showPipOverlay();
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
}
