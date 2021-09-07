/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.biometrics;

import android.annotation.NonNull;
import android.graphics.PointF;

import com.android.systemui.R;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.phone.StatusBar;

/**
 * Class that coordinates non-HBM animations during enrollment.
 */
public class UdfpsEnrollViewController extends UdfpsAnimationViewController<UdfpsEnrollView> {

    private final int mEnrollProgressBarRadius;
    @NonNull private final UdfpsEnrollHelper mEnrollHelper;
    @NonNull private final UdfpsEnrollHelper.Listener mEnrollHelperListener =
            new UdfpsEnrollHelper.Listener() {
        @Override
        public void onEnrollmentProgress(int remaining, int totalSteps) {
            mView.onEnrollmentProgress(remaining, totalSteps);
        }

        @Override
        public void onLastStepAcquired() {
            mView.onLastStepAcquired();
        }
    };

    protected UdfpsEnrollViewController(
            @NonNull UdfpsEnrollView view,
            @NonNull UdfpsEnrollHelper enrollHelper,
            @NonNull StatusBarStateController statusBarStateController,
            @NonNull StatusBar statusBar,
            @NonNull DumpManager dumpManager) {
        super(view, statusBarStateController, statusBar, dumpManager);
        mEnrollProgressBarRadius = getContext().getResources()
                .getInteger(R.integer.config_udfpsEnrollProgressBar);
        mEnrollHelper = enrollHelper;
        mView.setEnrollHelper(mEnrollHelper);
    }

    @Override
    @NonNull String getTag() {
        return "UdfpsEnrollViewController";
    }

    @Override
    protected void onViewAttached() {
        super.onViewAttached();
        if (mEnrollHelper.shouldShowProgressBar()) {
            // Only need enrollment updates if the progress bar is showing :)
            mEnrollHelper.setListener(mEnrollHelperListener);
        }
    }

    @NonNull
    @Override
    public PointF getTouchTranslation() {
        if (!mEnrollHelper.isGuidedEnrollmentStage()) {
            return new PointF(0, 0);
        } else {
            return mEnrollHelper.getNextGuidedEnrollmentPoint();
        }
    }

    @Override
    public int getPaddingX() {
        return mEnrollProgressBarRadius;
    }

    @Override
    public int getPaddingY() {
        return mEnrollProgressBarRadius;
    }
}
