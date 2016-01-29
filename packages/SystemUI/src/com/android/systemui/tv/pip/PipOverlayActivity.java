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
import android.os.Bundle;
import android.os.Handler;
import android.view.View;

import com.android.systemui.R;

/**
 * Activity to show an overlay on top of PIP activity to show how to pop up PIP menu.
 */
public class PipOverlayActivity extends Activity implements PipManager.Listener {
    private static final String TAG = "PipOverlayActivity";
    private static final boolean DEBUG = false;

    private static final long SHOW_GUIDE_OVERLAY_VIEW_DURATION_MS = 2000;

    private final PipManager mPipManager = PipManager.getInstance();
    private final Handler mHandler = new Handler();

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        setContentView(R.layout.tv_pip_overlay);
        mPipManager.addListener(this);
        final View overlayView = findViewById(R.id.guide_overlay);
        // TODO: apply animation
        overlayView.setVisibility(View.VISIBLE);
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                overlayView.setVisibility(View.INVISIBLE);
            }
        }, SHOW_GUIDE_OVERLAY_VIEW_DURATION_MS);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mHandler.removeCallbacksAndMessages(null);
        mPipManager.removeListener(this);
    }

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
}
