/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.internal.policy.impl.keyguard;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ViewFlipper;

/**
 * Subclass of the current view flipper that allows us to overload dispatchTouchEvent() so
 * we can emulate {@link WindowManager.LayoutParams#FLAG_SLIPPERY} within a view hierarchy.
 *
 */
public class KeyguardSecurityViewFlipper extends ViewFlipper {
    private Rect mTempRect = new Rect();

    public KeyguardSecurityViewFlipper(Context context) {
        this(context, null);
    }

    public KeyguardSecurityViewFlipper(Context context, AttributeSet attr) {
        super(context, attr);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        boolean result = super.dispatchTouchEvent(ev);
        mTempRect.set(0, 0, 0, 0);
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == View.VISIBLE) {
                offsetRectIntoDescendantCoords(child, mTempRect);
                ev.offsetLocation(mTempRect.left, mTempRect.top);
                result = child.dispatchTouchEvent(ev) || result;
                ev.offsetLocation(-mTempRect.left, -mTempRect.top);
            }
        }
        return result;
    }

}
