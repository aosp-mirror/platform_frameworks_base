/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.volume;

import static com.android.systemui.util.leak.RotationUtils.ROTATION_LANDSCAPE;
import static com.android.systemui.util.leak.RotationUtils.ROTATION_NONE;
import static com.android.systemui.util.leak.RotationUtils.ROTATION_SEASCAPE;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;

import com.android.systemui.R;
import com.android.systemui.util.leak.RotationUtils;

public class VolumeUiLayout extends FrameLayout  {

    private View mChild;
    private int mOldHeight;
    private boolean mAnimating;
    private AnimatorSet mAnimation;
    private boolean mHasOutsideTouch;
    private int mRotation = ROTATION_NONE;
    public VolumeUiLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
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

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        if (mChild == null) {
            if (getChildCount() != 0) {
                mChild = getChildAt(0);
                mOldHeight = mChild.getMeasuredHeight();
                updateRotation();
            } else {
                return;
            }
        }
        int newHeight = mChild.getMeasuredHeight();
        if (newHeight != mOldHeight) {
            animateChild(mOldHeight, newHeight);
        }
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateRotation();
    }

    private void updateRotation() {
        int rotation = RotationUtils.getRotation(getContext());
        if (rotation != mRotation) {
            rotate(mRotation, rotation);
            mRotation = rotation;
        }
    }

    private void rotate(View view, int from, int to, boolean swapDimens) {
        if (from != ROTATION_NONE && to != ROTATION_NONE) {
            // Rather than handling this confusing case, just do 2 rotations.
            rotate(view, from, ROTATION_NONE, swapDimens);
            rotate(view, ROTATION_NONE, to, swapDimens);
            return;
        }
        if (from == ROTATION_LANDSCAPE || to == ROTATION_SEASCAPE) {
            rotateRight(view);
        } else {
            rotateLeft(view);
        }
        if (to != ROTATION_NONE) {
            if (swapDimens && view instanceof LinearLayout) {
                LinearLayout linearLayout = (LinearLayout) view;
                linearLayout.setOrientation(LinearLayout.HORIZONTAL);
                swapDimens(view);
            }
        } else {
            if (swapDimens && view instanceof LinearLayout) {
                LinearLayout linearLayout = (LinearLayout) view;
                linearLayout.setOrientation(LinearLayout.VERTICAL);
                swapDimens(view);
            }
        }
    }

    private void rotate(int from, int to) {
        View footer = mChild.findViewById(R.id.footer);
        rotate(footer, from, to, false);
        rotate(this, from, to, true);
        rotate(mChild, from, to, true);
        ViewGroup rows = mChild.findViewById(R.id.volume_dialog_rows);
        rotate(rows, from, to, true);
        int rowCount = rows.getChildCount();
        for (int i = 0; i < rowCount; i++) {
            View child = rows.getChildAt(i);
            if (to == ROTATION_SEASCAPE) {
                rotateSeekBars(to, 0);
            } else if (to == ROTATION_LANDSCAPE) {
                rotateSeekBars(to, 180);
            } else {
                rotateSeekBars(to, 270);
            }
            rotate(child, from, to, true);
        }
    }

    private void swapDimens(View v) {
        if (v == null) {
            return;
        }
        ViewGroup.LayoutParams params = v.getLayoutParams();
        int h = params.width;
        params.width = params.height;
        params.height = h;
        v.setLayoutParams(params);
    }

    private void rotateSeekBars(int to, int rotation) {
        SeekBar seekbar = mChild.findViewById(R.id.volume_row_slider);
        if (seekbar != null) {
            seekbar.setRotation((float) rotation);
        }

        View parent = mChild.findViewById(R.id.volume_row_slider_frame);
        swapDimens(parent);
        ViewGroup.LayoutParams params = seekbar.getLayoutParams();
        ViewGroup.LayoutParams parentParams = parent.getLayoutParams();
        if (to != ROTATION_NONE) {
            params.height = parentParams.height;
            params.width = parentParams.width;
        } else {
            params.height = parentParams.width;
            params.width = parentParams.height;
        }
        seekbar.setLayoutParams(params);
    }

    private int rotateGravityRight(int gravity) {
        int retGravity = 0;
        int layoutDirection = getLayoutDirection();
        final int absoluteGravity = Gravity.getAbsoluteGravity(gravity, layoutDirection);
        final int verticalGravity = gravity & Gravity.VERTICAL_GRAVITY_MASK;

        switch (absoluteGravity & Gravity.HORIZONTAL_GRAVITY_MASK) {
            case Gravity.CENTER_HORIZONTAL:
                retGravity |= Gravity.CENTER_VERTICAL;
                break;
            case Gravity.RIGHT:
                retGravity |= Gravity.BOTTOM;
                break;
            case Gravity.LEFT:
            default:
                retGravity |= Gravity.TOP;
                break;
        }

        switch (verticalGravity) {
            case Gravity.CENTER_VERTICAL:
                retGravity |= Gravity.CENTER_HORIZONTAL;
                break;
            case Gravity.BOTTOM:
                retGravity |= Gravity.LEFT;
                break;
            case Gravity.TOP:
            default:
                retGravity |= Gravity.RIGHT;
                break;
        }
        return retGravity;
    }

    private int rotateGravityLeft(int gravity) {
        if (gravity == -1) {
            gravity = Gravity.TOP | Gravity.START;
        }
        int retGravity = 0;
        int layoutDirection = getLayoutDirection();
        final int absoluteGravity = Gravity.getAbsoluteGravity(gravity, layoutDirection);
        final int verticalGravity = gravity & Gravity.VERTICAL_GRAVITY_MASK;

        switch (absoluteGravity & Gravity.HORIZONTAL_GRAVITY_MASK) {
            case Gravity.CENTER_HORIZONTAL:
                retGravity |= Gravity.CENTER_VERTICAL;
                break;
            case Gravity.RIGHT:
                retGravity |= Gravity.TOP;
                break;
            case Gravity.LEFT:
            default:
                retGravity |= Gravity.BOTTOM;
                break;
        }

        switch (verticalGravity) {
            case Gravity.CENTER_VERTICAL:
                retGravity |= Gravity.CENTER_HORIZONTAL;
                break;
            case Gravity.BOTTOM:
                retGravity |= Gravity.RIGHT;
                break;
            case Gravity.TOP:
            default:
                retGravity |= Gravity.LEFT;
                break;
        }
        return retGravity;
    }

    private void rotateLeft(View v) {
        if (v.getParent() instanceof FrameLayout) {
            LayoutParams p = (LayoutParams) v.getLayoutParams();
            p.gravity = rotateGravityLeft(p.gravity);
        }

        v.setPadding(v.getPaddingTop(), v.getPaddingRight(), v.getPaddingBottom(),
                v.getPaddingLeft());
        MarginLayoutParams params = (MarginLayoutParams) v.getLayoutParams();
        params.setMargins(params.topMargin, params.rightMargin, params.bottomMargin,
                params.leftMargin);
        v.setLayoutParams(params);
    }

    private void rotateRight(View v) {
        if (v.getParent() instanceof FrameLayout) {
            LayoutParams p = (LayoutParams) v.getLayoutParams();
            p.gravity = rotateGravityRight(p.gravity);
        }

        v.setPadding(v.getPaddingBottom(), v.getPaddingLeft(), v.getPaddingTop(),
                v.getPaddingRight());
        MarginLayoutParams params = (MarginLayoutParams) v.getLayoutParams();
        params.setMargins(params.bottomMargin, params.leftMargin, params.topMargin,
                params.rightMargin);
        v.setLayoutParams(params);
    }

    private void animateChild(int oldHeight, int newHeight) {
        if (true) return;
        if (mAnimating) {
            mAnimation.cancel();
        }
        mAnimating = true;
        mAnimation = new AnimatorSet();
        mAnimation.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mAnimating = false;
            }
        });
        int fromTop = mChild.getTop();
        int fromBottom = mChild.getBottom();
        int toTop = fromTop - ((newHeight - oldHeight) / 2);
        int toBottom = fromBottom + ((newHeight - oldHeight) / 2);
        ObjectAnimator top = ObjectAnimator.ofInt(mChild, "top", fromTop, toTop);
        mAnimation.playTogether(top,
                ObjectAnimator.ofInt(mChild, "bottom", fromBottom, toBottom));
    }


    @Override
    public ViewOutlineProvider getOutlineProvider() {
        return super.getOutlineProvider();
    }

    public void setOutsideTouchListener(OnClickListener onClickListener) {
        mHasOutsideTouch = true;
        requestLayout();
        setOnClickListener(onClickListener);
        setClickable(true);
        setFocusable(true);
    }

    public static VolumeUiLayout get(View v) {
        if (v instanceof VolumeUiLayout) return (VolumeUiLayout) v;
        if (v.getParent() instanceof View) {
            return get((View) v.getParent());
        }
        return null;
    }

    private final ViewTreeObserver.OnComputeInternalInsetsListener mInsetsListener = inoutInfo -> {
        if (mHasOutsideTouch || (mChild == null)) {
            inoutInfo.setTouchableInsets(
                    ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_FRAME);
            return;
        }
        inoutInfo.setTouchableInsets(
                ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_CONTENT);
        inoutInfo.contentInsets.set(mChild.getLeft(), mChild.getTop(),
                0, getBottom() - mChild.getBottom());
    };
}
