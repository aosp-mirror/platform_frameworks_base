/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.view;

import android.annotation.NonNull;
import android.graphics.CanvasProperty;
import android.graphics.Paint;

/**
 * @hide
 */
public class RenderNodeAnimator extends android.graphics.animation.RenderNodeAnimator
        implements android.graphics.animation.RenderNodeAnimator.ViewListener {

    private View mViewTarget;

    public RenderNodeAnimator(int property, float finalValue) {
        super(property, finalValue);
    }

    public RenderNodeAnimator(CanvasProperty<Float> property, float finalValue) {
        super(property, finalValue);
    }

    public RenderNodeAnimator(CanvasProperty<Paint> property, int paintField, float finalValue) {
        super(property, paintField, finalValue);
    }

    public RenderNodeAnimator(int x, int y, float startRadius, float endRadius) {
        super(x, y, startRadius, endRadius);
    }

    @Override
    public void onAlphaAnimationStart(float finalAlpha) {
        // Alpha is a special snowflake that has the canonical value stored
        // in mTransformationInfo instead of in RenderNode, so we need to update
        // it with the final value here.
        mViewTarget.ensureTransformationInfo();
        mViewTarget.setAlphaInternal(finalAlpha);
    }

    @Override
    public void invalidateParent(boolean forceRedraw) {
        mViewTarget.invalidateViewProperty(true, false);
    }

    /** @hide */
    public void setTarget(@NonNull View view) {
        mViewTarget = view;
        setViewListener(this);
        setTarget(mViewTarget.mRenderNode);
    }
}
