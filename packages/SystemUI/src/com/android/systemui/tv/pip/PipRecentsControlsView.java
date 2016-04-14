/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.systemui.tv.pip;

import android.animation.Animator;
import android.animation.AnimatorInflater;
import android.animation.AnimatorSet;
import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.View.OnFocusChangeListener;

import com.android.systemui.R;

import static com.android.systemui.tv.pip.PipManager.PLAYBACK_STATE_PLAYING;
import static com.android.systemui.tv.pip.PipManager.PLAYBACK_STATE_PAUSED;
import static com.android.systemui.tv.pip.PipManager.PLAYBACK_STATE_UNAVAILABLE;

/**
 * An extended version of {@link PipControlsView} that supports animation in Recents.
 */
public class PipRecentsControlsView extends PipControlsView {
    /**
     * An interface to listen user action.
     */
    public interface Listener extends PipControlsView.Listener {
        /**
         * Called when an user presses BACK key and up.
         */
        abstract void onBackPressed();
    }

    private AnimatorSet mFocusGainAnimatorSet;
    private AnimatorSet mFocusLoseAnimatorSet;

    public PipRecentsControlsView(Context context) {
        this(context, null, 0, 0);
    }

    public PipRecentsControlsView(Context context, AttributeSet attrs) {
        this(context, attrs, 0, 0);
    }

    public PipRecentsControlsView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public PipRecentsControlsView(
            Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    public void onFinishInflate() {
        super.onFinishInflate();

        int buttonsFocusGainAnim = R.anim.tv_pip_controls_buttons_in_recents_focus_gain_animation;
        mFocusGainAnimatorSet = new AnimatorSet();
        mFocusGainAnimatorSet.playTogether(
                loadAnimator(this, R.anim.tv_pip_controls_in_recents_focus_gain_animation),
                loadAnimator(mFullButtonView,buttonsFocusGainAnim),
                loadAnimator(mPlayPauseButtonView, buttonsFocusGainAnim),
                loadAnimator(mCloseButtonView, buttonsFocusGainAnim));

        int buttonsFocusLoseAnim = R.anim.tv_pip_controls_buttons_in_recents_focus_lose_animation;
        mFocusLoseAnimatorSet = new AnimatorSet();
        mFocusLoseAnimatorSet.playTogether(
                loadAnimator(this, R.anim.tv_pip_controls_in_recents_focus_lose_animation),
                loadAnimator(mFullButtonView, buttonsFocusLoseAnim),
                loadAnimator(mPlayPauseButtonView, buttonsFocusLoseAnim),
                loadAnimator(mCloseButtonView, buttonsFocusLoseAnim));

        Rect pipBounds = mPipManager.getRecentsFocusedPipBounds();
        int pipControlsMarginTop = getContext().getResources().getDimensionPixelSize(
                R.dimen.recents_tv_pip_controls_margin_top);
        setPadding(0, pipBounds.bottom + pipControlsMarginTop, 0, 0);
    }

    private Animator loadAnimator(View view, int animatorResId) {
        Animator animator = AnimatorInflater.loadAnimator(getContext(), animatorResId);
        animator.setTarget(view);
        return animator;
    }

    /**
     * Starts focus gaining animation.
     */
    public void startFocusGainAnimation() {
        if (mFocusLoseAnimatorSet.isStarted()) {
            mFocusLoseAnimatorSet.cancel();
        }
        mFocusGainAnimatorSet.start();
    }

    /**
     * Starts focus losing animation.
     */
    public void startFocusLoseAnimation() {
        if (mFocusGainAnimatorSet.isStarted()) {
            mFocusGainAnimatorSet.cancel();
        }
        mFocusLoseAnimatorSet.start();
    }

    /**
     * Resets the view to the initial state. (i.e. end of the focus gain)
     */
    public void reset() {
        if (mFocusGainAnimatorSet.isStarted()) {
            mFocusGainAnimatorSet.cancel();
        }
        if (mFocusLoseAnimatorSet.isStarted()) {
            mFocusLoseAnimatorSet.cancel();
        }

        // Reset to initial state (i.e. end of focused)
        requestFocus();
        setTranslationY(0);
        setScaleXY(mFullButtonView, 1);
        setScaleXY(mPlayPauseButtonView, 1);
        setScaleXY(mCloseButtonView, 1);
    }

    private void setScaleXY(View view, float scale) {
        view.setScaleX(scale);
        view.setScaleY(scale);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (!event.isCanceled()
                && event.getKeyCode() == KeyEvent.KEYCODE_BACK
                && event.getAction() == KeyEvent.ACTION_UP) {
            if (mListener != null) {
                ((PipRecentsControlsView.Listener) mListener).onBackPressed();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
}
