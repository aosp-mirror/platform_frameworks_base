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

package com.android.systemui.statusbar.policy;

import android.app.IActivityTaskManager;

import com.android.systemui.keyguard.KeyguardViewMediator;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.policy.KeyguardStateController.Callback;

/**
 * Source of truth for keyguard state: If locked, occluded, has password, trusted etc.
 */
public interface KeyguardStateController extends CallbackController<Callback> {

    /**
     * If the device is locked or unlocked.
     */
    default boolean isUnlocked() {
        return !isShowing() || canDismissLockScreen();
    }

    /**
     * If the lock screen is visible.
     * The keyguard is also visible when the device is asleep or in always on mode, except when
     * the screen timed out and the user can unlock by quickly pressing power.
     *
     * This is unrelated to being locked or not.
     *
     * @see #isUnlocked()
     * @see #canDismissLockScreen()
     */
    boolean isShowing();

    /**
     * Whether the bouncer (PIN/password entry) is currently visible.
     */
    boolean isBouncerShowing();

    /**
     * If swiping up will unlock without asking for a password.
     * @see #isUnlocked()
     */
    boolean canDismissLockScreen();

    /**
     * Whether the keyguard is allowed to rotate, or needs to be locked to the default orientation.
     */
    boolean isKeyguardScreenRotationAllowed();

    /**
     * If the device has PIN/pattern/password or a lock screen at all.
     */
    boolean isMethodSecure();

    /**
     * When there's an {@link android.app.Activity} on top of the keyguard, where
     * {@link android.app.Activity#setShowWhenLocked(boolean)} is true.
     */
    boolean isOccluded();

    /**
     * If a {@link android.service.trust.TrustAgentService} is keeping the device unlocked.
     * {@link #canDismissLockScreen()} is better source of truth that also considers this state.
     */
    boolean isTrusted();

    /**
     * If the keyguard dismissal animation is running.
     * @see #isKeyguardGoingAway()
     */
    boolean isKeyguardFadingAway();

    /**
     * When the keyguard challenge was successfully solved, and {@link android.app.ActivityManager}
     * is launching the activity that will be revealed.
     *
     * This also includes the animation of the keyguard being dismissed, meaning that this will
     * return {@code true} whenever {@link #isKeyguardFadingAway()} also returns {@code true}.
     */
    boolean isKeyguardGoingAway();

    /**
     * Whether we're currently animating between the keyguard and the app/launcher surface behind
     * it, or will be shortly (which happens if we started a fling to dismiss the keyguard).
     * @see {@link KeyguardViewMediator#isAnimatingBetweenKeyguardAndSurfaceBehind()}
     */
    default boolean isAnimatingBetweenKeyguardAndSurfaceBehind() {
        return false;
    };

    /**
     * @return a shortened fading away duration similar to
     * {{@link #getKeyguardFadingAwayDuration()}} which may only span half of the duration, unless
     * we're bypassing
     */
    default long getShortenedFadingAwayDuration() {
        return getKeyguardFadingAwayDuration() / 2;
    }

    /**
     * Notifies that the Keyguard is fading away with the specified timings.
     * @param delay the precalculated animation delay in milliseconds
     * @param fadeoutDuration the duration of the exit animation, in milliseconds
     */
    default void notifyKeyguardFadingAway(long delay, long fadeoutDuratio) {
    }

    /**
     * If there are faces enrolled and user enabled face auth on keyguard.
     */
    default boolean isFaceAuthEnabled() {
        return false;
    }

    /**
     * If the animation that morphs a notification into an app window is playing.
     */
    boolean isLaunchTransitionFadingAway();

    /**
     * How long the keyguard dismissal animation should take when unlocking.
     */
    long getKeyguardFadingAwayDuration();

    /**
     * Delay for {@link #getKeyguardFadingAwayDuration()}.
     */
    long getKeyguardFadingAwayDelay();

    /**
     * Delay when going from {@link StatusBarState#KEYGUARD} to {@link StatusBarState#SHADE} or
     * {@link StatusBarState#SHADE_LOCKED}.
     */
    long calculateGoingToFullShadeDelay();

    /**
     * How much (from 0f to 1f) the keyguard is dismissed, either via a swipe gesture or an
     * animation.
     */
    float getDismissAmount();

    /**
     * Whether the keyguard is being dismissed due to direct user input, rather than a canned
     * animation.
     */
    boolean isDismissingFromSwipe();

    /**
     * Whether a fling animation is currently playing on the keyguard, either to dismiss it or to
     * cancel dismissing it.
     */
    boolean isFlingingToDismissKeyguard();

    /**
     * Whether a fling animation is currently playing on the keyguard, either to dismiss it or to
     * cancel dismissing it, and that animation started during a swipe gesture. Fling animations
     * can also be started without a swipe (e.g. activity launch from lock screen notification), so
     * this is a way to tell them apart for animation purposes.
     */
    boolean isFlingingToDismissKeyguardDuringSwipeGesture();

    /**
     * Whether a fling animation is currently playing on the keyguard to cancel dismissing it, after
     * the user released their finger during a swipe gesture.
     */
    boolean isSnappingKeyguardBackAfterSwipe();

    /** **/
    default void setLaunchTransitionFadingAway(boolean b) {}
    /** **/
    default void notifyKeyguardGoingAway(boolean b) {}
    /** **/
    default void notifyKeyguardDoneFading() {}
    /** **/
    default void notifyKeyguardState(boolean showing, boolean occluded) {}
    /** **/
    default void notifyBouncerShowing(boolean showing) {}

    /**
     * Updates the keyguard state to reflect that it's in the process of being dismissed, either by
     * a swipe gesture on the lock screen or by a canned animation.
     *
     * @param dismissAmount 0f means we're not dismissed at all, 1f means we have been completely
     *                      swiped away.
     * @param dismissingFromTouch True if this change was caused by direct user interaction, false
     *                            if it's due to an animation.
     */
    default void notifyKeyguardDismissAmountChanged(
            float dismissAmount, boolean dismissingFromTouch) {}

    /**
     * Updates the keyguard state to reflect that a dismiss fling gesture has started.
     *
     * @param dismiss Whether we're flinging to dismiss (upward) or to cancel a dismiss gesture.
     */
    void notifyPanelFlingStart(boolean dismiss);

    /** Updates the keyguard state to reflect that a dismiss fling gesture has ended. */
    void notifyPanelFlingEnd();

    /**
     * Callback for authentication events.
     */
    interface Callback {
        /**
         * Called when the locked state of the device changes. The lock screen might still be
         * showing on some cases, like when a {@link android.service.trust.TrustAgentService} is
         * active, or face auth was triggered but the user didn't swipe up to dismiss the lock
         * screen yet.
         */
        default void onUnlockedChanged() {}

        /**
         * If the lock screen is active or not. This is different from being locked, since the lock
         * screen can be visible but unlocked by {@link android.service.trust.TrustAgentService} or
         * face unlock.
         *
         * @see #isShowing()
         */
        default void onKeyguardShowingChanged() {}

        /**
         * Called when the bouncer (PIN/password entry) is shown or hidden.
         */
        default void onBouncerShowingChanged() {}

        /**
         * Triggered when the device was just unlocked and the lock screen is being dismissed.
         */
        default void onKeyguardFadingAwayChanged() {}

        /**
         * We've called {@link IActivityTaskManager#keyguardGoingAway}, which initiates the unlock
         * sequence.
         */
        default void onKeyguardGoingAwayChanged() {}

        /**
         * Triggered when the keyguard dismiss amount has changed, via either a swipe gesture or an
         * animation.
         */
        default void onKeyguardDismissAmountChanged() {}

        /**
         * Triggered when face auth becomes available or unavailable. Value should be queried with
         * {@link KeyguardStateController#isFaceAuthEnabled()}.
         */
        default void onFaceAuthEnabledChanged() {}

        /**
         * Triggered when the notification panel is starting or has finished
         * fading away on transition to an app.
         */
        default void onLaunchTransitionFadingAwayChanged() {}
    }
}
