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
 * limitations under the License.
 */

package com.android.systemui;

import android.content.Context;
import android.graphics.Region;
import android.graphics.Region.Op;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.ViewTreeObserver.InternalInsetsInfo;
import android.view.ViewTreeObserver.OnComputeInternalInsetsListener;
import android.widget.FrameLayout;

/**
 * Frame layout that will intercept the touches of children if they want to
 */
public class RegionInterceptingFrameLayout extends FrameLayout {
    public RegionInterceptingFrameLayout(Context context) {
        super(context);
    }

    public RegionInterceptingFrameLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public RegionInterceptingFrameLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public RegionInterceptingFrameLayout(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnComputeInternalInsetsListener(mInsetsListener);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        getViewTreeObserver().removeOnComputeInternalInsetsListener(mInsetsListener);
    }

    private final OnComputeInternalInsetsListener mInsetsListener = internalInsetsInfo -> {
        internalInsetsInfo.setTouchableInsets(InternalInsetsInfo.TOUCHABLE_INSETS_REGION);
        internalInsetsInfo.touchableRegion.setEmpty();
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (!(child instanceof RegionInterceptableView)) {
                continue;
            }
            RegionInterceptableView riv = (RegionInterceptableView) child;
            if (!riv.shouldInterceptTouch()) {
                continue;
            }
            Region unionRegion = riv.getInterceptRegion();
            if (unionRegion == null) {
                continue;
            }

            internalInsetsInfo.touchableRegion.op(riv.getInterceptRegion(), Op.UNION);
        }
    };

    public interface RegionInterceptableView {
        default public boolean shouldInterceptTouch() {
            return false;
        }

        public Region getInterceptRegion();
    }
}
