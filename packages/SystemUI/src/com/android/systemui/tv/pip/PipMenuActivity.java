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

import com.android.systemui.R;
import com.android.systemui.SystemUI;
import com.android.systemui.SystemUIApplication;
import com.android.systemui.recents.Recents;

import static android.content.pm.PackageManager.FEATURE_LEANBACK;
import static android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE;

/**
 * Activity to show the PIP menu to control PIP.
 */
public class PipMenuActivity extends Activity implements PipManager.Listener {
    private static final String TAG = "PipMenuActivity";

    private final PipManager mPipManager = PipManager.getInstance();

    private PipControlsView mPipControlsView;
    private boolean mPipMovedToFullscreen;

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        setContentView(R.layout.tv_pip_menu);
        mPipManager.addListener(this);

        mPipControlsView = (PipControlsView) findViewById(R.id.pip_controls);
    }

    private void restorePipAndFinish() {
        if (!mPipMovedToFullscreen) {
            mPipManager.resizePinnedStack(PipManager.STATE_PIP_OVERLAY);
        }
        finish();
    }

    @Override
    public void onPause() {
        super.onPause();
        restorePipAndFinish();
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
        restorePipAndFinish();
    }

    @Override
    public void onPipEntered() { }

    @Override
    public void onPipActivityClosed() {
        finish();
    }

    @Override
    public void onShowPipMenu() { }

    @Override
    public void onMoveToFullscreen() {
        mPipMovedToFullscreen = true;
        finish();
    }

    @Override
    public void onMediaControllerChanged() { }

    @Override
    public void onPipResizeAboutToStart() {
        finish();
        mPipManager.suspendPipResizing(
                PipManager.SUSPEND_PIP_RESIZE_REASON_WAITING_FOR_MENU_ACTIVITY_FINISH);
    }

    @Override
    public void finish() {
        super.finish();
        if (mPipManager.isRecentsShown() && !mPipMovedToFullscreen) {
            SystemUI[] services = ((SystemUIApplication) getApplication()).getServices();
            for (int i = services.length - 1; i >= 0; i--) {
                if (services[i] instanceof Recents) {
                    ((Recents) services[i]).showRecents(false, null);
                    break;
                }
            }
        }
    }
}
