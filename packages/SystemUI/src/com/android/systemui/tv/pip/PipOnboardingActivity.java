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
import android.graphics.Rect;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup.LayoutParams;

import com.android.systemui.R;

/**
 * Activity to show an overlay on top of PIP activity to show how to pop up PIP menu.
 */
public class PipOnboardingActivity extends Activity implements PipManager.Listener {
    private final PipManager mPipManager = PipManager.getInstance();

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        setContentView(R.layout.tv_pip_onboarding);
        View pipOnboardingView = findViewById(R.id.pip_onboarding);
        View pipOutlineView = findViewById(R.id.pip_outline);
        mPipManager.addListener(this);
        findViewById(R.id.close).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        int pipOutlineSpace = getResources().getDimensionPixelSize(R.dimen.tv_pip_bounds_space);
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        Rect pipBounds = mPipManager.getPipBounds();
        pipOnboardingView.setPadding(
                pipBounds.left - pipOutlineSpace,
                pipBounds.top - pipOutlineSpace,
                screenWidth - pipBounds.right - pipOutlineSpace, 0);

        // Set width and height for outline view to enclose the PIP.
        LayoutParams lp = pipOutlineView.getLayoutParams();
        lp.width = pipBounds.width() + pipOutlineSpace * 2;
        lp.height = pipBounds.height() + pipOutlineSpace * 2;
        pipOutlineView.setLayoutParams(lp);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mPipManager.removeListener(this);
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
    public void onPipResizeAboutToStart() { }

    @Override
    public void onMediaControllerChanged() { }
}
