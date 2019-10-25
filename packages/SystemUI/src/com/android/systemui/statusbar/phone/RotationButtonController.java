/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.annotation.StyleRes;
import android.app.StatusBarManager;
import android.content.ContentResolver;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.provider.Settings;
import android.view.IRotationWatcher.Stub;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.WindowManagerGlobal;
import android.view.accessibility.AccessibilityManager;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.view.RotationPolicy;
import com.android.systemui.Dependency;
import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.TaskStackChangeListener;
import com.android.systemui.statusbar.policy.AccessibilityManagerWrapper;
import com.android.systemui.statusbar.policy.KeyButtonDrawable;
import com.android.systemui.statusbar.policy.RotationLockController;

import java.util.Optional;
import java.util.function.Consumer;

/** Contains logic that deals with showing a rotate suggestion button with animation. */
public class RotationButtonController {

    private static final int BUTTON_FADE_IN_OUT_DURATION_MS = 100;
    private static final int NAVBAR_HIDDEN_PENDING_ICON_TIMEOUT_MS = 20000;

    private static final int NUM_ACCEPTED_ROTATION_SUGGESTIONS_FOR_INTRODUCTION = 3;

    private final MetricsLogger mMetricsLogger = Dependency.get(MetricsLogger.class);
    private final ViewRippler mViewRippler = new ViewRippler();

    private @StyleRes int mStyleRes;
    private int mLastRotationSuggestion;
    private boolean mPendingRotationSuggestion;
    private boolean mHoveringRotationSuggestion;
    private RotationLockController mRotationLockController;
    private AccessibilityManagerWrapper mAccessibilityManagerWrapper;
    private TaskStackListenerImpl mTaskStackListener;
    private Consumer<Integer> mRotWatcherListener;
    private boolean mListenersRegistered = false;
    private boolean mIsNavigationBarShowing;

    private final Runnable mRemoveRotationProposal =
            () -> setRotateSuggestionButtonState(false /* visible */);
    private final Runnable mCancelPendingRotationProposal =
            () -> mPendingRotationSuggestion = false;
    private Animator mRotateHideAnimator;

    private final Context mContext;
    private final RotationButton mRotationButton;
    private final Handler mMainThreadHandler = new Handler(Looper.getMainLooper());

    private final Stub mRotationWatcher = new Stub() {
        @Override
        public void onRotationChanged(final int rotation) throws RemoteException {
            // We need this to be scheduled as early as possible to beat the redrawing of
            // window in response to the orientation change.
            mMainThreadHandler.postAtFrontOfQueue(() -> {
                // If the screen rotation changes while locked, potentially update lock to flow with
                // new screen rotation and hide any showing suggestions.
                if (mRotationLockController.isRotationLocked()) {
                    if (shouldOverrideUserLockPrefs(rotation)) {
                        setRotationLockedAtAngle(rotation);
                    }
                    setRotateSuggestionButtonState(false /* visible */, true /* forced */);
                }

                if (mRotWatcherListener != null) {
                    mRotWatcherListener.accept(rotation);
                }
            });
        }
    };

    /**
     * Determines if rotation suggestions disabled2 flag exists in flag
     * @param disable2Flags see if rotation suggestion flag exists in this flag
     * @return whether flag exists
     */
    static boolean hasDisable2RotateSuggestionFlag(int disable2Flags) {
        return (disable2Flags & StatusBarManager.DISABLE2_ROTATE_SUGGESTIONS) != 0;
    }

    RotationButtonController(Context context, @StyleRes int style, RotationButton rotationButton) {
        mContext = context;
        mRotationButton = rotationButton;
        mRotationButton.setRotationButtonController(this);

        mStyleRes = style;
        mIsNavigationBarShowing = true;
        mRotationLockController = Dependency.get(RotationLockController.class);
        mAccessibilityManagerWrapper = Dependency.get(AccessibilityManagerWrapper.class);

        // Register the task stack listener
        mTaskStackListener = new TaskStackListenerImpl();
        mRotationButton.setOnClickListener(this::onRotateSuggestionClick);
        mRotationButton.setOnHoverListener(this::onRotateSuggestionHover);
    }

    void registerListeners() {
        if (mListenersRegistered) {
            return;
        }

        mListenersRegistered = true;
        try {
            WindowManagerGlobal.getWindowManagerService()
                    .watchRotation(mRotationWatcher, mContext.getDisplay().getDisplayId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        ActivityManagerWrapper.getInstance().registerTaskStackListener(mTaskStackListener);
    }

    void unregisterListeners() {
        if (!mListenersRegistered) {
            return;
        }

        mListenersRegistered = false;
        try {
            WindowManagerGlobal.getWindowManagerService().removeRotationWatcher(mRotationWatcher);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        ActivityManagerWrapper.getInstance().unregisterTaskStackListener(mTaskStackListener);
    }

    void addRotationCallback(Consumer<Integer> watcher) {
        mRotWatcherListener = watcher;
    }

    void setRotationLockedAtAngle(int rotationSuggestion) {
        mRotationLockController.setRotationLockedAtAngle(true /* locked */, rotationSuggestion);
    }

    public boolean isRotationLocked() {
        return mRotationLockController.isRotationLocked();
    }

    void setRotateSuggestionButtonState(boolean visible) {
        setRotateSuggestionButtonState(visible, false /* force */);
    }

    void setRotateSuggestionButtonState(final boolean visible, final boolean force) {
        // At any point the the button can become invisible because an a11y service became active.
        // Similarly, a call to make the button visible may be rejected because an a11y service is
        // active. Must account for this.
        // Rerun a show animation to indicate change but don't rerun a hide animation
        if (!visible && !mRotationButton.isVisible()) return;

        final View view = mRotationButton.getCurrentView();
        if (view == null) return;

        final KeyButtonDrawable currentDrawable = mRotationButton.getImageDrawable();
        if (currentDrawable == null) return;

        // Clear any pending suggestion flag as it has either been nullified or is being shown
        mPendingRotationSuggestion = false;
        mMainThreadHandler.removeCallbacks(mCancelPendingRotationProposal);

        // Handle the visibility change and animation
        if (visible) { // Appear and change (cannot force)
            // Stop and clear any currently running hide animations
            if (mRotateHideAnimator != null && mRotateHideAnimator.isRunning()) {
                mRotateHideAnimator.cancel();
            }
            mRotateHideAnimator = null;

            // Reset the alpha if any has changed due to hide animation
            view.setAlpha(1f);

            // Run the rotate icon's animation if it has one
            if (currentDrawable.canAnimate()) {
                currentDrawable.resetAnimation();
                currentDrawable.startAnimation();
            }

            if (!isRotateSuggestionIntroduced()) mViewRippler.start(view);

            // Set visibility unless a11y service is active.
            mRotationButton.show();
        } else { // Hide
            mViewRippler.stop(); // Prevent any pending ripples, force hide or not

            if (force) {
                // If a hide animator is running stop it and make invisible
                if (mRotateHideAnimator != null && mRotateHideAnimator.isRunning()) {
                    mRotateHideAnimator.pause();
                }
                mRotationButton.hide();
                return;
            }

            // Don't start any new hide animations if one is running
            if (mRotateHideAnimator != null && mRotateHideAnimator.isRunning()) return;

            ObjectAnimator fadeOut = ObjectAnimator.ofFloat(view, "alpha", 0f);
            fadeOut.setDuration(BUTTON_FADE_IN_OUT_DURATION_MS);
            fadeOut.setInterpolator(Interpolators.LINEAR);
            fadeOut.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    mRotationButton.hide();
                }
            });

            mRotateHideAnimator = fadeOut;
            fadeOut.start();
        }
    }

    void setDarkIntensity(float darkIntensity) {
        mRotationButton.setDarkIntensity(darkIntensity);
    }

    void onRotationProposal(int rotation, int windowRotation, boolean isValid) {
        if (!mRotationButton.acceptRotationProposal()) {
            return;
        }

        // This method will be called on rotation suggestion changes even if the proposed rotation
        // is not valid for the top app. Use invalid rotation choices as a signal to remove the
        // rotate button if shown.
        if (!isValid) {
            setRotateSuggestionButtonState(false /* visible */);
            return;
        }

        // If window rotation matches suggested rotation, remove any current suggestions
        if (rotation == windowRotation) {
            mMainThreadHandler.removeCallbacks(mRemoveRotationProposal);
            setRotateSuggestionButtonState(false /* visible */);
            return;
        }

        // Prepare to show the navbar icon by updating the icon style to change anim params
        mLastRotationSuggestion = rotation; // Remember rotation for click
        final boolean rotationCCW = isRotationAnimationCCW(windowRotation, rotation);
        int style;
        if (windowRotation == Surface.ROTATION_0 || windowRotation == Surface.ROTATION_180) {
            style = rotationCCW ? R.style.RotateButtonCCWStart90 : R.style.RotateButtonCWStart90;
        } else { // 90 or 270
            style = rotationCCW ? R.style.RotateButtonCCWStart0 : R.style.RotateButtonCWStart0;
        }
        mStyleRes = style;
        mRotationButton.updateIcon();

        if (mIsNavigationBarShowing) {
            // The navbar is visible so show the icon right away
            showAndLogRotationSuggestion();
        } else {
            // If the navbar isn't shown, flag the rotate icon to be shown should the navbar become
            // visible given some time limit.
            mPendingRotationSuggestion = true;
            mMainThreadHandler.removeCallbacks(mCancelPendingRotationProposal);
            mMainThreadHandler.postDelayed(mCancelPendingRotationProposal,
                    NAVBAR_HIDDEN_PENDING_ICON_TIMEOUT_MS);
        }
    }

    void onDisable2FlagChanged(int state2) {
        final boolean rotateSuggestionsDisabled = hasDisable2RotateSuggestionFlag(state2);
        if (rotateSuggestionsDisabled) onRotationSuggestionsDisabled();
    }

    void onNavigationBarWindowVisibilityChange(boolean showing) {
        if (mIsNavigationBarShowing != showing) {
            mIsNavigationBarShowing = showing;

            // If the navbar is visible, show the rotate button if there's a pending suggestion
            if (showing && mPendingRotationSuggestion) {
                showAndLogRotationSuggestion();
            }
        }
    }

    @StyleRes int getStyleRes() {
        return mStyleRes;
    }

    RotationButton getRotationButton() {
        return mRotationButton;
    }

    private void onRotateSuggestionClick(View v) {
        mMetricsLogger.action(MetricsEvent.ACTION_ROTATION_SUGGESTION_ACCEPTED);
        incrementNumAcceptedRotationSuggestionsIfNeeded();
        setRotationLockedAtAngle(mLastRotationSuggestion);
    }

    private boolean onRotateSuggestionHover(View v, MotionEvent event) {
        final int action = event.getActionMasked();
        mHoveringRotationSuggestion = (action == MotionEvent.ACTION_HOVER_ENTER)
                || (action == MotionEvent.ACTION_HOVER_MOVE);
        rescheduleRotationTimeout(true /* reasonHover */);
        return false; // Must return false so a11y hover events are dispatched correctly.
    }

    private void onRotationSuggestionsDisabled() {
        // Immediately hide the rotate button and clear any planned removal
        setRotateSuggestionButtonState(false /* visible */, true /* force */);
        mMainThreadHandler.removeCallbacks(mRemoveRotationProposal);
    }

    private void showAndLogRotationSuggestion() {
        setRotateSuggestionButtonState(true /* visible */);
        rescheduleRotationTimeout(false /* reasonHover */);
        mMetricsLogger.visible(MetricsEvent.ROTATION_SUGGESTION_SHOWN);
    }

    private boolean shouldOverrideUserLockPrefs(final int rotation) {
        // Only override user prefs when returning to the natural rotation (normally portrait).
        // Don't let apps that force landscape or 180 alter user lock.
        return rotation == RotationPolicy.getNaturalRotation();
    }

    private boolean isRotationAnimationCCW(int from, int to) {
        // All 180deg WM rotation animations are CCW, match that
        if (from == Surface.ROTATION_0 && to == Surface.ROTATION_90) return false;
        if (from == Surface.ROTATION_0 && to == Surface.ROTATION_180) return true; //180d so CCW
        if (from == Surface.ROTATION_0 && to == Surface.ROTATION_270) return true;
        if (from == Surface.ROTATION_90 && to == Surface.ROTATION_0) return true;
        if (from == Surface.ROTATION_90 && to == Surface.ROTATION_180) return false;
        if (from == Surface.ROTATION_90 && to == Surface.ROTATION_270) return true; //180d so CCW
        if (from == Surface.ROTATION_180 && to == Surface.ROTATION_0) return true; //180d so CCW
        if (from == Surface.ROTATION_180 && to == Surface.ROTATION_90) return true;
        if (from == Surface.ROTATION_180 && to == Surface.ROTATION_270) return false;
        if (from == Surface.ROTATION_270 && to == Surface.ROTATION_0) return false;
        if (from == Surface.ROTATION_270 && to == Surface.ROTATION_90) return true; //180d so CCW
        if (from == Surface.ROTATION_270 && to == Surface.ROTATION_180) return true;
        return false; // Default
    }

    private void rescheduleRotationTimeout(final boolean reasonHover) {
        // May be called due to a new rotation proposal or a change in hover state
        if (reasonHover) {
            // Don't reschedule if a hide animator is running
            if (mRotateHideAnimator != null && mRotateHideAnimator.isRunning()) return;
            // Don't reschedule if not visible
            if (!mRotationButton.isVisible()) return;
        }

        // Stop any pending removal
        mMainThreadHandler.removeCallbacks(mRemoveRotationProposal);
        // Schedule timeout
        mMainThreadHandler.postDelayed(mRemoveRotationProposal,
                computeRotationProposalTimeout());
    }

    private int computeRotationProposalTimeout() {
        return mAccessibilityManagerWrapper.getRecommendedTimeoutMillis(
                mHoveringRotationSuggestion ? 16000 : 5000,
                AccessibilityManager.FLAG_CONTENT_CONTROLS);
    }

    private boolean isRotateSuggestionIntroduced() {
        ContentResolver cr = mContext.getContentResolver();
        return Settings.Secure.getInt(cr, Settings.Secure.NUM_ROTATION_SUGGESTIONS_ACCEPTED, 0)
                >= NUM_ACCEPTED_ROTATION_SUGGESTIONS_FOR_INTRODUCTION;
    }

    private void incrementNumAcceptedRotationSuggestionsIfNeeded() {
        // Get the number of accepted suggestions
        ContentResolver cr = mContext.getContentResolver();
        final int numSuggestions = Settings.Secure.getInt(cr,
                Settings.Secure.NUM_ROTATION_SUGGESTIONS_ACCEPTED, 0);

        // Increment the number of accepted suggestions only if it would change intro mode
        if (numSuggestions < NUM_ACCEPTED_ROTATION_SUGGESTIONS_FOR_INTRODUCTION) {
            Settings.Secure.putInt(cr, Settings.Secure.NUM_ROTATION_SUGGESTIONS_ACCEPTED,
                    numSuggestions + 1);
        }
    }

    private class TaskStackListenerImpl extends TaskStackChangeListener {
        // Invalidate any rotation suggestion on task change or activity orientation change
        // Note: all callbacks happen on main thread

        @Override
        public void onTaskStackChanged() {
            setRotateSuggestionButtonState(false /* visible */);
        }

        @Override
        public void onTaskRemoved(int taskId) {
            setRotateSuggestionButtonState(false /* visible */);
        }

        @Override
        public void onTaskMovedToFront(int taskId) {
            setRotateSuggestionButtonState(false /* visible */);
        }

        @Override
        public void onActivityRequestedOrientationChanged(int taskId, int requestedOrientation) {
            // Only hide the icon if the top task changes its requestedOrientation
            // Launcher can alter its requestedOrientation while it's not on top, don't hide on this
            Optional.ofNullable(ActivityManagerWrapper.getInstance())
                    .map(ActivityManagerWrapper::getRunningTask)
                    .ifPresent(a -> {
                        if (a.id == taskId) setRotateSuggestionButtonState(false /* visible */);
                    });
        }
    }

    private class ViewRippler {
        private static final int RIPPLE_OFFSET_MS = 50;
        private static final int RIPPLE_INTERVAL_MS = 2000;
        private View mRoot;

        public void start(View root) {
            stop(); // Stop any pending ripple animations

            mRoot = root;

            // Schedule pending ripples, offset the 1st to avoid problems with visibility change
            mRoot.postOnAnimationDelayed(mRipple, RIPPLE_OFFSET_MS);
            mRoot.postOnAnimationDelayed(mRipple, RIPPLE_INTERVAL_MS);
            mRoot.postOnAnimationDelayed(mRipple, 2 * RIPPLE_INTERVAL_MS);
            mRoot.postOnAnimationDelayed(mRipple, 3 * RIPPLE_INTERVAL_MS);
            mRoot.postOnAnimationDelayed(mRipple, 4 * RIPPLE_INTERVAL_MS);
        }

        public void stop() {
            if (mRoot != null) mRoot.removeCallbacks(mRipple);
        }

        private final Runnable mRipple = new Runnable() {
            @Override
            public void run() { // Cause the ripple to fire via false presses
                if (!mRoot.isAttachedToWindow()) return;
                mRoot.setPressed(true /* pressed */);
                mRoot.setPressed(false /* pressed */);
            }
        };
    }
}
