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

package com.android.keyguard;

import android.annotation.NonNull;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewDebug;
import android.view.ViewGroup;
import android.view.ViewHierarchyEncoder;
import android.widget.FrameLayout;
import android.widget.ViewFlipper;

import com.android.internal.widget.LockPatternUtils;
import com.android.systemui.R;

/**
 * Subclass of the current view flipper that allows us to overload dispatchTouchEvent() so
 * we can emulate {@link android.view.WindowManager.LayoutParams#FLAG_SLIPPERY} within a view
 * hierarchy.
 */
public class KeyguardSecurityViewFlipper extends ViewFlipper implements KeyguardSecurityView {
    private static final String TAG = "KeyguardSecurityViewFlipper";
    private static final boolean DEBUG = KeyguardConstants.DEBUG;

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
    public void onResume(int reason) {
        KeyguardSecurityView ksv = getSecurityView();
        if (ksv != null) {
            ksv.onResume(reason);
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
    public void showPromptReason(int reason) {
        KeyguardSecurityView ksv = getSecurityView();
        if (ksv != null) {
            ksv.showPromptReason(reason);
        }
    }

    @Override
    public void showMessage(CharSequence message, ColorStateList colorState) {
        KeyguardSecurityView ksv = getSecurityView();
        if (ksv != null) {
            ksv.showMessage(message, colorState);
        }
    }

    @Override
    public void showUsabilityHint() {
        KeyguardSecurityView ksv = getSecurityView();
        if (ksv != null) {
            ksv.showUsabilityHint();
        }
    }

    @Override
    public void startAppearAnimation() {
        KeyguardSecurityView ksv = getSecurityView();
        if (ksv != null) {
            ksv.startAppearAnimation();
        }
    }

    @Override
    public boolean startDisappearAnimation(Runnable finishRunnable) {
        KeyguardSecurityView ksv = getSecurityView();
        if (ksv != null) {
            return ksv.startDisappearAnimation(finishRunnable);
        } else {
            return false;
        }
    }

    @Override
    public CharSequence getTitle() {
        KeyguardSecurityView ksv = getSecurityView();
        if (ksv != null) {
            return ksv.getTitle();
        }
        return "";
    }

    @Override
    protected boolean checkLayoutParams(ViewGroup.LayoutParams p) {
        return p instanceof LayoutParams;
    }

    @Override
    protected ViewGroup.LayoutParams generateLayoutParams(ViewGroup.LayoutParams p) {
        return p instanceof LayoutParams ? new LayoutParams((LayoutParams) p) : new LayoutParams(p);
    }

    @Override
    public LayoutParams generateLayoutParams(AttributeSet attrs) {
        return new LayoutParams(getContext(), attrs);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        final int widthMode = MeasureSpec.getMode(widthSpec);
        final int heightMode = MeasureSpec.getMode(heightSpec);
        if (DEBUG && widthMode != MeasureSpec.AT_MOST) {
            Log.w(TAG, "onMeasure: widthSpec " + MeasureSpec.toString(widthSpec) +
                    " should be AT_MOST");
        }
        if (DEBUG && heightMode != MeasureSpec.AT_MOST) {
            Log.w(TAG, "onMeasure: heightSpec " + MeasureSpec.toString(heightSpec) +
                    " should be AT_MOST");
        }

        final int widthSize = MeasureSpec.getSize(widthSpec);
        final int heightSize = MeasureSpec.getSize(heightSpec);
        int maxWidth = widthSize;
        int maxHeight = heightSize;
        final int count = getChildCount();
        for (int i = 0; i < count; i++) {
            final View child = getChildAt(i);
            final LayoutParams lp = (LayoutParams) child.getLayoutParams();

            if (lp.maxWidth > 0 && lp.maxWidth < maxWidth) {
                maxWidth = lp.maxWidth;
            }
            if (lp.maxHeight > 0 && lp.maxHeight < maxHeight) {
                maxHeight = lp.maxHeight;
            }
        }

        final int wPadding = getPaddingLeft() + getPaddingRight();
        final int hPadding = getPaddingTop() + getPaddingBottom();
        maxWidth = Math.max(0, maxWidth - wPadding);
        maxHeight = Math.max(0, maxHeight - hPadding);

        int width = widthMode == MeasureSpec.EXACTLY ? widthSize : 0;
        int height = heightMode == MeasureSpec.EXACTLY ? heightSize : 0;
        for (int i = 0; i < count; i++) {
            final View child = getChildAt(i);
            final LayoutParams lp = (LayoutParams) child.getLayoutParams();

            final int childWidthSpec = makeChildMeasureSpec(maxWidth, lp.width);
            final int childHeightSpec = makeChildMeasureSpec(maxHeight, lp.height);

            child.measure(childWidthSpec, childHeightSpec);

            width = Math.max(width, Math.min(child.getMeasuredWidth(), widthSize - wPadding));
            height = Math.max(height, Math.min(child.getMeasuredHeight(), heightSize - hPadding));
        }
        setMeasuredDimension(width + wPadding, height + hPadding);
    }

    private int makeChildMeasureSpec(int maxSize, int childDimen) {
        final int mode;
        final int size;
        switch (childDimen) {
            case LayoutParams.WRAP_CONTENT:
                mode = MeasureSpec.AT_MOST;
                size = maxSize;
                break;
            case LayoutParams.MATCH_PARENT:
                mode = MeasureSpec.EXACTLY;
                size = maxSize;
                break;
            default:
                mode = MeasureSpec.EXACTLY;
                size = Math.min(maxSize, childDimen);
                break;
        }
        return MeasureSpec.makeMeasureSpec(size, mode);
    }

    public static class LayoutParams extends FrameLayout.LayoutParams {
        @ViewDebug.ExportedProperty(category = "layout")
        public int maxWidth;

        @ViewDebug.ExportedProperty(category = "layout")
        public int maxHeight;

        public LayoutParams(ViewGroup.LayoutParams other) {
            super(other);
        }

        public LayoutParams(LayoutParams other) {
            super(other);

            maxWidth = other.maxWidth;
            maxHeight = other.maxHeight;
        }

        public LayoutParams(Context c, AttributeSet attrs) {
            super(c, attrs);

            final TypedArray a = c.obtainStyledAttributes(attrs,
                    R.styleable.KeyguardSecurityViewFlipper_Layout, 0, 0);
            maxWidth = a.getDimensionPixelSize(
                    R.styleable.KeyguardSecurityViewFlipper_Layout_layout_maxWidth, 0);
            maxHeight = a.getDimensionPixelSize(
                    R.styleable.KeyguardSecurityViewFlipper_Layout_layout_maxHeight, 0);
            a.recycle();
        }

        /** @hide */
        @Override
        protected void encodeProperties(@NonNull ViewHierarchyEncoder encoder) {
            super.encodeProperties(encoder);

            encoder.addProperty("layout:maxWidth", maxWidth);
            encoder.addProperty("layout:maxHeight", maxHeight);
        }
    }
}
