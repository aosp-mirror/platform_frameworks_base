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
import android.view.WindowManager;
import android.widget.ViewFlipper;

import com.android.internal.widget.LockPatternUtils;

/**
 * Subclass of the current view flipper that allows us to overload dispatchTouchEvent() so
 * we can emulate {@link WindowManager.LayoutParams#FLAG_SLIPPERY} within a view hierarchy.
 *
 */
public class KeyguardSecurityViewFlipper extends ViewFlipper implements KeyguardSecurityView {
    private Rect mTempRect = new Rect();

    public KeyguardSecurityViewFlipper(Context context) {
        this(context, null);
    }

    public KeyguardSecurityViewFlipper(Context context, AttributeSet attr) {
        super(context, attr);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        boolean result = super.onTouchEvent(ev);
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

    KeyguardSecurityView getSecurityView() {
        View child = getChildAt(getDisplayedChild());
        if (child instanceof KeyguardSecurityView) {
            return (KeyguardSecurityView) child;
        }
        return null;
    }

    @Override
    public void setKeyguardCallback(KeyguardSecurityCallback callback) {
        KeyguardSecurityView ksv = getSecurityView();
        if (ksv != null) {
            ksv.setKeyguardCallback(callback);
        }
    }

    @Override
    public void setLockPatternUtils(LockPatternUtils utils) {
        KeyguardSecurityView ksv = getSecurityView();
        if (ksv != null) {
            ksv.setLockPatternUtils(utils);
        }
    }

    @Override
    public void reset() {
        KeyguardSecurityView ksv = getSecurityView();
        if (ksv != null) {
            ksv.reset();
        }
    }

    @Override
    public void onPause() {
        KeyguardSecurityView ksv = getSecurityView();
        if (ksv != null) {
            ksv.onPause();
        }
    }

    @Override
    public void onResume() {
        KeyguardSecurityView ksv = getSecurityView();
        if (ksv != null) {
            ksv.onResume();
        }
    }

    @Override
    public boolean needsInput() {
        KeyguardSecurityView ksv = getSecurityView();
        return (ksv != null) ? ksv.needsInput() : false;
    }

    @Override
    public KeyguardSecurityCallback getCallback() {
        KeyguardSecurityView ksv = getSecurityView();
        return (ksv != null) ? ksv.getCallback() : null;
    }

    @Override
    public void showUsabilityHint() {
        KeyguardSecurityView ksv = getSecurityView();
        if (ksv != null) {
            ksv.showUsabilityHint();
        }
    }
}
