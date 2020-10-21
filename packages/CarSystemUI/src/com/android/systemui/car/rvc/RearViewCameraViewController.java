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

package com.android.systemui.car.rvc;

import android.app.ActivityView;
import android.app.ActivityView.StateCallback;
import android.content.ComponentName;
import android.content.Intent;
import android.content.res.Resources;
import android.util.Slog;
import android.view.ViewGroup;
import android.widget.LinearLayout.LayoutParams;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.R;
import com.android.systemui.car.window.OverlayViewController;
import com.android.systemui.car.window.OverlayViewGlobalStateController;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;

import javax.inject.Inject;

/** View controller for the rear view camera. */
@SysUISingleton
public class RearViewCameraViewController extends OverlayViewController {
    private static final String TAG = "RearViewCameraView";
    private static final boolean DBG = false;

    private final ComponentName mRearViewCameraActivity;
    private ViewGroup mRvcView;
    private final LayoutParams mRvcViewLayoutParams = new LayoutParams(
            LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, /* weight= */ 1.0f);
    @VisibleForTesting
    ActivityView mActivityView;
    @VisibleForTesting
    final StateCallback mActivityViewCallback = new StateCallback() {
        @Override
        public void onActivityViewReady(ActivityView view) {
            Intent intent = new Intent(Intent.ACTION_MAIN)
                    .setComponent(mRearViewCameraActivity)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
                    .addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            // TODO(b/170899079): Migrate this to FixedActivityService.
            view.startActivity(intent);
        }

        @Override
        public void onActivityViewDestroyed(ActivityView view) {}
    };

    @Inject
    public RearViewCameraViewController(
            @Main Resources resources,
            OverlayViewGlobalStateController overlayViewGlobalStateController) {
        super(R.id.rear_view_camera_stub, overlayViewGlobalStateController);
        String rearViewCameraActivityName = resources.getString(
                R.string.config_rearViewCameraActivity);
        if (!rearViewCameraActivityName.isEmpty()) {
            mRearViewCameraActivity = ComponentName.unflattenFromString(rearViewCameraActivityName);
            if (DBG) Slog.d(TAG, "mRearViewCameraActivity=" + mRearViewCameraActivity);
        } else {
            mRearViewCameraActivity = null;
            Slog.e(TAG, "RearViewCameraViewController is disabled, since no Activity is defined");
        }
    }

    @Override
    protected void onFinishInflate() {
        mRvcView = (ViewGroup) getLayout().findViewById(R.id.rear_view_camera_container);
        getLayout().findViewById(R.id.close_button).setOnClickListener(v -> {
            stop();
        });
    }

    @Override
    protected void hideInternal() {
        super.hideInternal();
        if (DBG) Slog.d(TAG, "hideInternal: mActivityView=" + mActivityView);
        if (mActivityView == null) return;
        mRvcView.removeView(mActivityView);
        // Release ActivityView since the Activity on ActivityView (with showWhenLocked flag) keeps
        // running even if ActivityView is hidden.
        mActivityView.release();
        mActivityView = null;
    }

    @Override
    protected void showInternal() {
        super.showInternal();
        if (DBG) Slog.d(TAG, "showInternal: mActivityView=" + mActivityView);
        if (mActivityView != null) return;
        mActivityView = new ActivityView(mRvcView.getContext());
        mActivityView.setCallback(mActivityViewCallback);
        mActivityView.setLayoutParams(mRvcViewLayoutParams);
        mRvcView.addView(mActivityView, /* index= */ 0);
    }

    boolean isShown() {
        return mActivityView != null;
    }

    boolean isEnabled() {
        return mRearViewCameraActivity != null;
    }

    @Override
    protected boolean shouldShowHUN() {
        return false;
    }

    @Override
    protected boolean shouldShowWhenOccluded() {
        // Returns true to show it on top of Keylock.
        return true;
    }

    @Override
    protected boolean shouldShowNavigationBarInsets() {
        return true;
    }

    @Override
    protected boolean shouldShowStatusBarInsets() {
        return true;
    }
}
