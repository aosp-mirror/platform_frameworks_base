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
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.View;
import android.view.View.OnFocusChangeListener;
import android.widget.FrameLayout;

import com.android.systemui.R;

import static com.android.systemui.tv.pip.PipManager.PLAYBACK_STATE_PLAYING;
import static com.android.systemui.tv.pip.PipManager.PLAYBACK_STATE_PAUSED;
import static com.android.systemui.tv.pip.PipManager.PLAYBACK_STATE_UNAVAILABLE;

/**
 * An FrameLayout that contains {@link PipControlsView} with its scrim.
 */
public class PipRecentsControlsView extends FrameLayout {
    /**
     * An interface to listen user action.
     */
    public interface Listener extends PipControlsView.Listener {
        /**
         * Called when an user presses BACK key and up.
         */
        abstract void onBackPressed();
    }

    private final PipManager mPipManager = PipManager.getInstance();
    private Listener mListener;
    private PipControlsView mPipControlsView;
    private View mScrim;
    private Animator mFocusGainAnimator;
    private AnimatorSet mFocusLossAnimatorSet;

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

        mPipControlsView = (PipControlsView) findViewById(R.id.pip_control_contents);
        mScrim = findViewById(R.id.scrim);

        mFocusGainAnimator = loadAnimator(mPipControlsView,
                R.anim.tv_pip_controls_in_recents_focus_gain_animation);

        mFocusLossAnimatorSet = new AnimatorSet();
        mFocusLossAnimatorSet.playSequentially(
                loadAnimator(mPipControlsView,
                        R.anim.tv_pip_controls_in_recents_focus_loss_animation),
                loadAnimator(mScrim, R.anim.tv_pip_controls_in_recents_scrim_fade_in_animation));

        Rect pipBounds = mPipManager.getRecentsFocusedPipBounds();
        setPadding(0, pipBounds.bottom, 0, 0);
    }

    private Animator loadAnimator(View view, int animatorResId) {
        Animator animator = AnimatorInflater.loadAnimator(getContext(), animatorResId);
        animator.setTarget(view);
        return animator;
    }

    /**
     * Starts focus gain animation.
     */
    public void startFocusGainAnimation() {
        // Hides the scrim view as soon as possible, before the PIP resize animation starts.
        // If we don't, PIP will be moved down a bit and a gap between the scrim and PIP will be
        // shown at the bottom of the PIP.
        mScrim.setAlpha(0);
        PipControlButtonView focus = mPipControlsView.getFocusedButton();
        if (focus != null) {
            focus.startFocusGainAnimation();
        }
        startAnimator(mFocusGainAnimator, mFocusLossAnimatorSet);
    }

    /**
     * Starts focus loss animation.
     */
    public void startFocusLossAnimation() {
        PipControlButtonView focus = mPipControlsView.getFocusedButton();
        if (focus != null) {
            focus.startFocusLossAnimation();
        }
        startAnimator(mFocusLossAnimatorSet, mFocusGainAnimator);
    }

    /**
     * Resets the view to the initial state. (i.e. end of the focus gain)
     */
    public void reset() {
        cancelAnimator(mFocusGainAnimator);
        cancelAnimator(mFocusLossAnimatorSet);

        // Reset to initial state (i.e. end of focused)
        mScrim.setAlpha(0);
        mPipControlsView.setTranslationY(0);
        mPipControlsView.setScaleX(1);
        mPipControlsView.setScaleY(1);
        mPipControlsView.reset();
    }

    private static void startAnimator(Animator animator, Animator previousAnimator) {
        cancelAnimator(previousAnimator);
        if (!animator.isStarted()) {
            animator.start();
        }
    }

    private static void cancelAnimator(Animator animator) {
        if (animator.isStarted()) {
            animator.cancel();
        }
    }

    /**
     * Sets listeners.
     */
    public void setListener(Listener listener) {
        mPipControlsView.setListener(listener);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (!event.isCanceled()) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_BACK
                    && event.getAction() == KeyEvent.ACTION_UP) {
                if (mPipControlsView.mListener != null) {
                    ((PipRecentsControlsView.Listener) mPipControlsView.mListener).onBackPressed();
                }
                return true;
            } else if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    mPipManager.getPipRecentsOverlayManager().clearFocus();
                }
                // Consume the down event always to prevent warning logs from ViewRootImpl.
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }
}
