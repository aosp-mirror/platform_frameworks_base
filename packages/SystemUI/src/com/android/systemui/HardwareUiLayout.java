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

package com.android.systemui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;

import com.android.systemui.tuner.TunerService;
import com.android.systemui.tuner.TunerService.Tunable;
import com.android.systemui.util.leak.RotationUtils;

import static com.android.systemui.util.leak.RotationUtils.ROTATION_LANDSCAPE;
import static com.android.systemui.util.leak.RotationUtils.ROTATION_NONE;
import static com.android.systemui.util.leak.RotationUtils.ROTATION_SEASCAPE;

public class HardwareUiLayout extends LinearLayout implements Tunable {

    private static final String EDGE_BLEED = "sysui_hwui_edge_bleed";
    private static final String ROUNDED_DIVIDER = "sysui_hwui_rounded_divider";
    private final int[] mTmp2 = new int[2];
    private View mList;
    private View mSeparatedView;
    private int mOldHeight;
    private boolean mAnimating;
    private AnimatorSet mAnimation;
    private View mDivision;
    private boolean mHasOutsideTouch;
    private HardwareBgDrawable mListBackground;
    private HardwareBgDrawable mSeparatedViewBackground;
    private Animator mAnimator;
    private boolean mCollapse;
    private boolean mHasSeparatedButton;
    private int mEndPoint;
    private boolean mEdgeBleed;
    private boolean mRoundedDivider;
    private int mRotation = ROTATION_NONE;
    private boolean mRotatedBackground;
    private boolean mSwapOrientation = true;

    public HardwareUiLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        updateSettings();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateSettings();
        Dependency.get(TunerService.class).addTunable(this, EDGE_BLEED, ROUNDED_DIVIDER);
        getViewTreeObserver().addOnComputeInternalInsetsListener(mInsetsListener);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        getViewTreeObserver().removeOnComputeInternalInsetsListener(mInsetsListener);
        Dependency.get(TunerService.class).removeTunable(this);
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        updateSettings();
    }

    private void updateSettings() {
        mEdgeBleed = Settings.Secure.getInt(getContext().getContentResolver(),
                EDGE_BLEED, 0) != 0;
        mRoundedDivider = Settings.Secure.getInt(getContext().getContentResolver(),
                ROUNDED_DIVIDER, 0) != 0;
        updateEdgeMargin(mEdgeBleed ? 0 : getEdgePadding());
        mListBackground = new HardwareBgDrawable(mRoundedDivider, !mEdgeBleed, getContext());
        mSeparatedViewBackground = new HardwareBgDrawable(mRoundedDivider, !mEdgeBleed,
                getContext());
        if (mList != null) {
            mList.setBackground(mListBackground);
            mSeparatedView.setBackground(mSeparatedViewBackground);
            requestLayout();
        }
    }

    private void updateEdgeMargin(int edge) {
        if (mList != null) {
            MarginLayoutParams params = (MarginLayoutParams) mList.getLayoutParams();
            if (mRotation == ROTATION_LANDSCAPE) {
                params.topMargin = edge;
            } else if (mRotation == ROTATION_SEASCAPE) {
                params.bottomMargin = edge;
            } else {
                params.rightMargin = edge;
            }
            mList.setLayoutParams(params);
        }

        if (mSeparatedView != null) {
            MarginLayoutParams params = (MarginLayoutParams) mSeparatedView.getLayoutParams();
            if (mRotation == ROTATION_LANDSCAPE) {
                params.topMargin = edge;
            } else if (mRotation == ROTATION_SEASCAPE) {
                params.bottomMargin = edge;
            } else {
                params.rightMargin = edge;
            }
            mSeparatedView.setLayoutParams(params);
        }
    }

    private int getEdgePadding() {
        return getContext().getResources().getDimensionPixelSize(R.dimen.edge_margin);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        if (mList == null) {
            if (getChildCount() != 0) {
                mList = getChildAt(0);
                mList.setBackground(mListBackground);
                mSeparatedView = getChildAt(1);
                mSeparatedView.setBackground(mSeparatedViewBackground);
                updateEdgeMargin(mEdgeBleed ? 0 : getEdgePadding());
                mOldHeight = mList.getMeasuredHeight();
                updateRotation();
            } else {
                return;
            }
        }
        int newHeight = mList.getMeasuredHeight();
        if (newHeight != mOldHeight) {
            animateChild(mOldHeight, newHeight);
        }

        post(() -> updatePaddingAndGravityIfTooTall());
        post(() -> updatePosition());
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateRotation();
    }

    public void setSwapOrientation(boolean swapOrientation) {
        mSwapOrientation = swapOrientation;
    }

    private void updateRotation() {
        int rotation = RotationUtils.getRotation(getContext());
        if (rotation != mRotation) {
            rotate(mRotation, rotation);
            mRotation = rotation;
        }
    }

    private void rotate(int from, int to) {
        if (from != ROTATION_NONE && to != ROTATION_NONE) {
            // Rather than handling this confusing case, just do 2 rotations.
            rotate(from, ROTATION_NONE);
            rotate(ROTATION_NONE, to);
            return;
        }
        if (from == ROTATION_LANDSCAPE || to == ROTATION_SEASCAPE) {
            rotateRight();
        } else {
            rotateLeft();
        }
        if (mHasSeparatedButton) {
            if (from == ROTATION_SEASCAPE || to == ROTATION_SEASCAPE) {
                // Separated view has top margin, so seascape separated view need special rotation,
                // not a full left or right rotation.
                swapLeftAndTop(mSeparatedView);
            } else if (from == ROTATION_LANDSCAPE) {
                rotateRight(mSeparatedView);
            } else {
                rotateLeft(mSeparatedView);
            }
        }
        if (to != ROTATION_NONE) {
            if (mList instanceof LinearLayout) {
                mRotatedBackground = true;
                mListBackground.setRotatedBackground(true);
                mSeparatedViewBackground.setRotatedBackground(true);
                LinearLayout linearLayout = (LinearLayout) mList;
                if (mSwapOrientation) {
                    linearLayout.setOrientation(LinearLayout.HORIZONTAL);
                    setOrientation(LinearLayout.HORIZONTAL);
                }
                swapDimens(mList);
                swapDimens(mSeparatedView);
            }
        } else {
            if (mList instanceof LinearLayout) {
                mRotatedBackground = false;
                mListBackground.setRotatedBackground(false);
                mSeparatedViewBackground.setRotatedBackground(false);
                LinearLayout linearLayout = (LinearLayout) mList;
                if (mSwapOrientation) {
                    linearLayout.setOrientation(LinearLayout.VERTICAL);
                    setOrientation(LinearLayout.VERTICAL);
                }
                swapDimens(mList);
                swapDimens(mSeparatedView);
            }
        }
    }

    private void rotateRight() {
        rotateRight(this);
        rotateRight(mList);
        swapDimens(this);

        LayoutParams p = (LayoutParams) mList.getLayoutParams();
        p.gravity = rotateGravityRight(p.gravity);
        mList.setLayoutParams(p);

        LayoutParams separatedViewLayoutParams = (LayoutParams) mSeparatedView.getLayoutParams();
        separatedViewLayoutParams.gravity = rotateGravityRight(separatedViewLayoutParams.gravity);
        mSeparatedView.setLayoutParams(separatedViewLayoutParams);

        setGravity(rotateGravityRight(getGravity()));
    }

    private void swapDimens(View v) {
        ViewGroup.LayoutParams params = v.getLayoutParams();
        int h = params.width;
        params.width = params.height;
        params.height = h;
        v.setLayoutParams(params);
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

    private void rotateLeft() {
        rotateLeft(this);
        rotateLeft(mList);
        swapDimens(this);

        LayoutParams p = (LayoutParams) mList.getLayoutParams();
        p.gravity = rotateGravityLeft(p.gravity);
        mList.setLayoutParams(p);

        LayoutParams separatedViewLayoutParams = (LayoutParams) mSeparatedView.getLayoutParams();
        separatedViewLayoutParams.gravity = rotateGravityLeft(separatedViewLayoutParams.gravity);
        mSeparatedView.setLayoutParams(separatedViewLayoutParams);

        setGravity(rotateGravityLeft(getGravity()));
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
        v.setPadding(v.getPaddingTop(), v.getPaddingRight(), v.getPaddingBottom(),
                v.getPaddingLeft());
        MarginLayoutParams params = (MarginLayoutParams) v.getLayoutParams();
        params.setMargins(params.topMargin, params.rightMargin, params.bottomMargin,
                params.leftMargin);
        v.setLayoutParams(params);
    }

    private void rotateRight(View v) {
        v.setPadding(v.getPaddingBottom(), v.getPaddingLeft(), v.getPaddingTop(),
                v.getPaddingRight());
        MarginLayoutParams params = (MarginLayoutParams) v.getLayoutParams();
        params.setMargins(params.bottomMargin, params.leftMargin, params.topMargin,
                params.rightMargin);
        v.setLayoutParams(params);
    }

    private void swapLeftAndTop(View v) {
        v.setPadding(v.getPaddingTop(), v.getPaddingLeft(), v.getPaddingBottom(),
                v.getPaddingRight());
        MarginLayoutParams params = (MarginLayoutParams) v.getLayoutParams();
        params.setMargins(params.topMargin, params.leftMargin, params.bottomMargin,
                params.rightMargin);
        v.setLayoutParams(params);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        post(() -> updatePosition());
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
        int fromTop = mList.getTop();
        int fromBottom = mList.getBottom();
        int toTop = fromTop - ((newHeight - oldHeight) / 2);
        int toBottom = fromBottom + ((newHeight - oldHeight) / 2);
        ObjectAnimator top = ObjectAnimator.ofInt(mList, "top", fromTop, toTop);
        top.addUpdateListener(animation -> mListBackground.invalidateSelf());
        mAnimation.playTogether(top,
                ObjectAnimator.ofInt(mList, "bottom", fromBottom, toBottom));
    }

    public void setDivisionView(View v) {
        mDivision = v;
        if (mDivision != null) {
            mDivision.addOnLayoutChangeListener(
                    (v1, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                            updatePosition());
        }
        updatePosition();
    }

    private void updatePosition() {
        if (mList == null) return;
        // If got separated button, setRotatedBackground to false,
        // all items won't get white background.
        mListBackground.setRotatedBackground(mHasSeparatedButton);
        mSeparatedViewBackground.setRotatedBackground(mHasSeparatedButton);
        if (mDivision != null && mDivision.getVisibility() == VISIBLE) {
            int index = mRotatedBackground ? 0 : 1;
            mDivision.getLocationOnScreen(mTmp2);
            float trans = mRotatedBackground ? mDivision.getTranslationX()
                    : mDivision.getTranslationY();
            int viewTop = (int) (mTmp2[index] + trans);
            mList.getLocationOnScreen(mTmp2);
            viewTop -= mTmp2[index];
            setCutPoint(viewTop);
        } else {
            setCutPoint(mList.getMeasuredHeight());
        }
    }

    private void setCutPoint(int point) {
        int curPoint = mListBackground.getCutPoint();
        if (curPoint == point) return;
        if (getAlpha() == 0 || curPoint == 0) {
            mListBackground.setCutPoint(point);
            return;
        }
        if (mAnimator != null) {
            if (mEndPoint == point) {
                return;
            }
            mAnimator.cancel();
        }
        mEndPoint = point;
        mAnimator = ObjectAnimator.ofInt(mListBackground, "cutPoint", curPoint, point);
        if (mCollapse) {
            mAnimator.setStartDelay(300);
            mCollapse = false;
        }
        mAnimator.start();
    }

    // If current power menu height larger then screen height, remove padding to break power menu
    // alignment and set menu center vertical within the screen.
    private void updatePaddingAndGravityIfTooTall() {
        int defaultTopPadding;
        int viewsTotalHeight;
        int separatedViewTopMargin;
        int screenHeight;
        int totalHeight;
        int targetGravity;
        MarginLayoutParams params = (MarginLayoutParams) mSeparatedView.getLayoutParams();
        switch (RotationUtils.getRotation(getContext())) {
            case RotationUtils.ROTATION_LANDSCAPE:
                defaultTopPadding = getPaddingLeft();
                viewsTotalHeight = mList.getMeasuredWidth() + mSeparatedView.getMeasuredWidth();
                separatedViewTopMargin = mHasSeparatedButton ? params.leftMargin : 0;
                screenHeight = getMeasuredWidth();
                targetGravity = Gravity.CENTER_HORIZONTAL|Gravity.TOP;
                break;
            case RotationUtils.ROTATION_SEASCAPE:
                defaultTopPadding = getPaddingRight();
                viewsTotalHeight = mList.getMeasuredWidth() + mSeparatedView.getMeasuredWidth();
                separatedViewTopMargin = mHasSeparatedButton ? params.leftMargin : 0;
                screenHeight = getMeasuredWidth();
                targetGravity = Gravity.CENTER_HORIZONTAL|Gravity.BOTTOM;
                break;
            default: // Portrait
                defaultTopPadding = getPaddingTop();
                viewsTotalHeight = mList.getMeasuredHeight() + mSeparatedView.getMeasuredHeight();
                separatedViewTopMargin = mHasSeparatedButton ? params.topMargin : 0;
                screenHeight = getMeasuredHeight();
                targetGravity = Gravity.CENTER_VERTICAL|Gravity.RIGHT;
                break;
        }
        totalHeight = defaultTopPadding + viewsTotalHeight + separatedViewTopMargin;
        if (totalHeight >= screenHeight) {
            setPadding(0, 0, 0, 0);
            setGravity(targetGravity);
        }
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

    public void setCollapse() {
        mCollapse = true;
    }

    public void setHasSeparatedButton(boolean hasSeparatedButton) {
        mHasSeparatedButton = hasSeparatedButton;
    }

    public static HardwareUiLayout get(View v) {
        if (v instanceof HardwareUiLayout) return (HardwareUiLayout) v;
        if (v.getParent() instanceof View) {
            return get((View) v.getParent());
        }
        return null;
    }

    private final ViewTreeObserver.OnComputeInternalInsetsListener mInsetsListener = inoutInfo -> {
        if (mHasOutsideTouch || (mList == null)) {
            inoutInfo.setTouchableInsets(
                    ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_FRAME);
            return;
        }
        inoutInfo.setTouchableInsets(
                ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_CONTENT);
        inoutInfo.contentInsets.set(mList.getLeft(), mList.getTop(),
                0, getBottom() - mList.getBottom());
    };
}
