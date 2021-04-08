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

package com.android.systemui.navigationbar.buttons;

import static com.android.systemui.Interpolators.LINEAR;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.view.View;
import android.view.View.AccessibilityDelegate;

import com.android.systemui.Dependency;
import com.android.systemui.assist.AssistManager;

import java.util.ArrayList;

/**
 * Dispatches common view calls to multiple views.  This is used to handle
 * multiples of the same nav bar icon appearing.
 */
public class ButtonDispatcher {
    private static final int FADE_DURATION_IN = 150;
    private static final int FADE_DURATION_OUT = 250;

    private final ArrayList<View> mViews = new ArrayList<>();

    private final int mId;
    private final AssistManager mAssistManager;

    private View.OnClickListener mClickListener;
    private View.OnTouchListener mTouchListener;
    private View.OnLongClickListener mLongClickListener;
    private View.OnHoverListener mOnHoverListener;
    private Boolean mLongClickable;
    private Float mAlpha;
    private Float mDarkIntensity;
    private Integer mVisibility = View.VISIBLE;
    private Boolean mDelayTouchFeedback;
    private KeyButtonDrawable mImageDrawable;
    private View mCurrentView;
    private boolean mVertical;
    private ValueAnimator mFadeAnimator;
    private AccessibilityDelegate mAccessibilityDelegate;

    private final ValueAnimator.AnimatorUpdateListener mAlphaListener = animation ->
            setAlpha(
                    (float) animation.getAnimatedValue(),
                    false /* animate */,
                    false /* cancelAnimator */);

    private final AnimatorListenerAdapter mFadeListener = new AnimatorListenerAdapter() {
        @Override
        public void onAnimationEnd(Animator animation) {
            mFadeAnimator = null;
            setVisibility(getAlpha() == 1 ? View.VISIBLE : View.INVISIBLE);
        }
    };

    public ButtonDispatcher(int id) {
        mId = id;
        mAssistManager = Dependency.get(AssistManager.class);
    }

    public void clear() {
        mViews.clear();
    }

    public void addView(View view) {
        mViews.add(view);
        view.setOnClickListener(mClickListener);
        view.setOnTouchListener(mTouchListener);
        view.setOnLongClickListener(mLongClickListener);
        view.setOnHoverListener(mOnHoverListener);
        if (mLongClickable != null) {
            view.setLongClickable(mLongClickable);
        }
        if (mAlpha != null) {
            view.setAlpha(mAlpha);
        }
        if (mVisibility != null) {
            view.setVisibility(mVisibility);
        }
        if (mAccessibilityDelegate != null) {
            view.setAccessibilityDelegate(mAccessibilityDelegate);
        }
        if (view instanceof ButtonInterface) {
            final ButtonInterface button = (ButtonInterface) view;
            if (mDarkIntensity != null) {
                button.setDarkIntensity(mDarkIntensity);
            }
            if (mImageDrawable != null) {
                button.setImageDrawable(mImageDrawable);
            }
            if (mDelayTouchFeedback != null) {
                button.setDelayTouchFeedback(mDelayTouchFeedback);
            }
            button.setVertical(mVertical);
        }
    }

    public int getId() {
        return mId;
    }

    public int getVisibility() {
        return mVisibility != null ? mVisibility : View.VISIBLE;
    }

    public boolean isVisible() {
        return getVisibility() == View.VISIBLE;
    }

    public float getAlpha() {
        return mAlpha != null ? mAlpha : 1;
    }

    public KeyButtonDrawable getImageDrawable() {
        return mImageDrawable;
    }

    public void setImageDrawable(KeyButtonDrawable drawable) {
        mImageDrawable = drawable;
        final int N = mViews.size();
        for (int i = 0; i < N; i++) {
            if (mViews.get(i) instanceof ButtonInterface) {
                ((ButtonInterface) mViews.get(i)).setImageDrawable(mImageDrawable);
            }
        }
        if (mImageDrawable != null) {
            mImageDrawable.setCallback(mCurrentView);
        }
    }

    public void setVisibility(int visibility) {
        if (mVisibility == visibility) return;
        if (mFadeAnimator != null) {
            mFadeAnimator.cancel();
        }

        mVisibility = visibility;
        final int N = mViews.size();
        for (int i = 0; i < N; i++) {
            mViews.get(i).setVisibility(mVisibility);
        }
    }

    public void abortCurrentGesture() {
        // This seems to be an instantaneous thing, so not going to persist it.
        final int N = mViews.size();
        for (int i = 0; i < N; i++) {
            if (mViews.get(i) instanceof ButtonInterface) {
                ((ButtonInterface) mViews.get(i)).abortCurrentGesture();
            }
        }
    }

    public void setAlpha(float alpha) {
        setAlpha(alpha, false /* animate */);
    }

    public void setAlpha(float alpha, boolean animate) {
        setAlpha(alpha, animate, true /* cancelAnimator */);
    }

    public void setAlpha(float alpha, boolean animate, long duration) {
        setAlpha(alpha, animate, duration, true /* cancelAnimator */);
    }

    public void setAlpha(float alpha, boolean animate, boolean cancelAnimator) {
        setAlpha(
                alpha,
                animate,
                (getAlpha() < alpha) ? FADE_DURATION_IN : FADE_DURATION_OUT,
                cancelAnimator);
    }

    public void setAlpha(float alpha, boolean animate, long duration, boolean cancelAnimator) {
        if (mFadeAnimator != null && (cancelAnimator || animate)) {
            mFadeAnimator.cancel();
        }
        if (animate) {
            setVisibility(View.VISIBLE);
            mFadeAnimator = ValueAnimator.ofFloat(getAlpha(), alpha);
            mFadeAnimator.setDuration(duration);
            mFadeAnimator.setInterpolator(LINEAR);
            mFadeAnimator.addListener(mFadeListener);
            mFadeAnimator.addUpdateListener(mAlphaListener);
            mFadeAnimator.start();
        } else {
            // Discretize the alpha updates to prevent too frequent updates when there is a long
            // alpha animation
            int prevAlpha = (int) (getAlpha() * 255);
            int nextAlpha = (int) (alpha * 255);
            if (prevAlpha != nextAlpha) {
                mAlpha = nextAlpha / 255f;
                final int N = mViews.size();
                for (int i = 0; i < N; i++) {
                    mViews.get(i).setAlpha(mAlpha);
                }
            }
        }
    }

    public void setDarkIntensity(float darkIntensity) {
        mDarkIntensity = darkIntensity;
        final int N = mViews.size();
        for (int i = 0; i < N; i++) {
            if (mViews.get(i) instanceof ButtonInterface) {
                ((ButtonInterface) mViews.get(i)).setDarkIntensity(darkIntensity);
            }
        }
    }

    public void setDelayTouchFeedback(boolean delay) {
        mDelayTouchFeedback = delay;
        final int N = mViews.size();
        for (int i = 0; i < N; i++) {
            if (mViews.get(i) instanceof ButtonInterface) {
                ((ButtonInterface) mViews.get(i)).setDelayTouchFeedback(delay);
            }
        }
    }

    public void setOnClickListener(View.OnClickListener clickListener) {
        mClickListener = clickListener;
        final int N = mViews.size();
        for (int i = 0; i < N; i++) {
            mViews.get(i).setOnClickListener(mClickListener);
        }
    }

    public void setOnTouchListener(View.OnTouchListener touchListener) {
        mTouchListener = touchListener;
        final int N = mViews.size();
        for (int i = 0; i < N; i++) {
            mViews.get(i).setOnTouchListener(mTouchListener);
        }
    }

    public void setLongClickable(boolean isLongClickable) {
        mLongClickable = isLongClickable;
        final int N = mViews.size();
        for (int i = 0; i < N; i++) {
            mViews.get(i).setLongClickable(mLongClickable);
        }
    }

    public void setOnLongClickListener(View.OnLongClickListener longClickListener) {
        mLongClickListener = longClickListener;
        final int N = mViews.size();
        for (int i = 0; i < N; i++) {
            mViews.get(i).setOnLongClickListener(mLongClickListener);
        }
    }

    public void setOnHoverListener(View.OnHoverListener hoverListener) {
        mOnHoverListener = hoverListener;
        final int N = mViews.size();
        for (int i = 0; i < N; i++) {
            mViews.get(i).setOnHoverListener(mOnHoverListener);
        }
    }

    public void setAccessibilityDelegate(AccessibilityDelegate delegate) {
        mAccessibilityDelegate = delegate;
        final int N = mViews.size();
        for (int i = 0; i < N; i++) {
            mViews.get(i).setAccessibilityDelegate(delegate);
        }
    }

    public void setClickable(boolean clickable) {
        abortCurrentGesture();
        final int N = mViews.size();
        for (int i = 0; i < N; i++) {
            mViews.get(i).setClickable(clickable);
        }
    }

    public void setTranslation(int x, int y, int z) {
        final int N = mViews.size();
        for (int i = 0; i < N; i++) {
            final View view = mViews.get(i);
            view.setTranslationX(x);
            view.setTranslationY(y);
            view.setTranslationZ(z);
        }
    }

    public ArrayList<View> getViews() {
        return mViews;
    }

    public View getCurrentView() {
        return mCurrentView;
    }

    public void setCurrentView(View currentView) {
        mCurrentView = currentView.findViewById(mId);
        if (mImageDrawable != null) {
            mImageDrawable.setCallback(mCurrentView);
        }
        if (mCurrentView != null) {
            mCurrentView.setTranslationX(0);
            mCurrentView.setTranslationY(0);
            mCurrentView.setTranslationZ(0);
        }
    }

    public void setVertical(boolean vertical) {
        mVertical = vertical;
        final int N = mViews.size();
        for (int i = 0; i < N; i++) {
            final View view = mViews.get(i);
            if (view instanceof ButtonInterface) {
                ((ButtonInterface) view).setVertical(vertical);
            }
        }
    }

    /**
     * Executes when button is detached from window.
     */
    public void onDestroy() {
    }
}
