/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.notification;

import android.view.View;

import com.android.systemui.statusbar.CrossFadeHelper;
import com.android.systemui.statusbar.TransformableView;
import com.android.systemui.statusbar.ViewTransformationHelper;

/**
 * A custom transformation that modifies the interpolator
 */
public abstract class CustomInterpolatorTransformation
        extends ViewTransformationHelper.CustomTransformation {

    private final int mViewType;

    public CustomInterpolatorTransformation(int viewType) {
        mViewType = viewType;
    }

    @Override
    public boolean transformTo(TransformState ownState, TransformableView notification,
            float transformationAmount) {
        if (!hasCustomTransformation()) {
            return false;
        }
        TransformState otherState = notification.getCurrentState(mViewType);
        if (otherState == null) {
            return false;
        }
        View view = ownState.getTransformedView();
        CrossFadeHelper.fadeOut(view, transformationAmount);
        ownState.transformViewFullyTo(otherState, this, transformationAmount);
        otherState.recycle();
        return true;
    }

    protected boolean hasCustomTransformation() {
        return true;
    }

    @Override
    public boolean transformFrom(TransformState ownState,
            TransformableView notification, float transformationAmount) {
        if (!hasCustomTransformation()) {
            return false;
        }
        TransformState otherState = notification.getCurrentState(mViewType);
        if (otherState == null) {
            return false;
        }
        View view = ownState.getTransformedView();
        CrossFadeHelper.fadeIn(view, transformationAmount);
        ownState.transformViewFullyFrom(otherState, this, transformationAmount);
        otherState.recycle();
        return true;
    }
}
