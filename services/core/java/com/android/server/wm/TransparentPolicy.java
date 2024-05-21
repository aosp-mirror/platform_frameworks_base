/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.wm;

import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
import static android.content.res.Configuration.ORIENTATION_UNDEFINED;
import static android.content.res.Configuration.SCREEN_HEIGHT_DP_UNDEFINED;
import static android.content.res.Configuration.SCREEN_WIDTH_DP_UNDEFINED;
import static android.content.res.Configuration.SMALLEST_SCREEN_WIDTH_DP_UNDEFINED;

import static com.android.internal.util.FrameworkStatsLog.APP_COMPAT_STATE_CHANGED__STATE__UNKNOWN;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_ATM;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_WITH_CLASS_NAME;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.res.Configuration;
import android.graphics.Rect;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Encapsulate logic about translucent activities.
 */
class TransparentPolicy {

    private static final String TAG = TAG_WITH_CLASS_NAME ? "TransparentPolicy" : TAG_ATM;

    // The predicate used to find the first opaque not finishing activity below the potential
    // transparent activity.
    private static final Predicate<ActivityRecord> FIRST_OPAQUE_NOT_FINISHING_ACTIVITY_PREDICATE =
            ActivityRecord::occludesParent;

    // The predicate to check to skip the policy
    @NonNull
    private final Predicate<ActivityRecord> mSkipLetterboxPredicate;

    // The ActivityRecord this policy relates to.
    private final ActivityRecord mActivityRecord;

    // If transparent activity policy is enabled.
    private final BooleanSupplier mIsTranslucentLetterboxingEnabledSupplier;

    // The list of observers for the destroy event of candidate opaque activities
    // when dealing with translucent activities.
    private final List<TransparentPolicy> mDestroyListeners = new ArrayList<>();

    // THe current state for the possible transparent activity
    private final TransparentPolicyState mTransparentPolicyState;

    TransparentPolicy(@NonNull ActivityRecord activityRecord,
            @NonNull LetterboxConfiguration letterboxConfiguration,
            @NonNull Predicate<ActivityRecord> skipLetterboxPredicate) {
        mActivityRecord = activityRecord;
        mIsTranslucentLetterboxingEnabledSupplier = () -> letterboxConfiguration
                .isTranslucentLetterboxingEnabled();
        mSkipLetterboxPredicate = skipLetterboxPredicate;
        mTransparentPolicyState = new TransparentPolicyState(activityRecord);
    }

    /**
     * Handles translucent activities letterboxing inheriting constraints from the
     * first opaque activity beneath.
     */
    void start() {
        if (!mIsTranslucentLetterboxingEnabledSupplier.getAsBoolean()) {
            return;
        }
        final WindowContainer<?> parent = mActivityRecord.getParent();
        if (parent == null) {
            return;
        }
        mTransparentPolicyState.reset();
        // In case mActivityRecord.hasCompatDisplayInsetsWithoutOverride() we don't apply the
        // opaque activity constraints because we're expecting the activity is already letterboxed.
        final ActivityRecord firstOpaqueActivity = mActivityRecord.getTask().getActivity(
                FIRST_OPAQUE_NOT_FINISHING_ACTIVITY_PREDICATE /* callback */,
                mActivityRecord /* boundary */, false /* includeBoundary */,
                true /* traverseTopToBottom */);
        // We check if we need for some reason to skip the policy gievn the specific first
        // opaque activity
        if (mSkipLetterboxPredicate.test(firstOpaqueActivity)) {
            return;
        }
        mTransparentPolicyState.start(firstOpaqueActivity);
    }

    void stop() {
        for (int i = mDestroyListeners.size() - 1; i >= 0; i--) {
            mDestroyListeners.get(i).start();
        }
        mDestroyListeners.clear();
        mTransparentPolicyState.reset();
    }

    boolean hasInheritedLetterboxBehavior() {
        return mTransparentPolicyState.isRunning();
    }

    /**
     * @return {@code true} if the current activity is translucent with an opaque activity
     * beneath and needs to inherit its orientation.
     */
    boolean hasInheritedOrientation() {
        // To force a different orientation, the transparent one needs to have an explicit one
        // otherwise the existing one is fine and the actual orientation will depend on the
        // bounds.
        // To avoid wrong behaviour, we're not forcing orientation for activities with not
        // fixed orientation (e.g. permission dialogs).
        return hasInheritedLetterboxBehavior()
                && mActivityRecord.getOverrideOrientation()
                != SCREEN_ORIENTATION_UNSPECIFIED;
    }

    float getInheritedMinAspectRatio() {
        return mTransparentPolicyState.getInheritedMinAspectRatio();
    }

    float getInheritedMaxAspectRatio() {
        return mTransparentPolicyState.getInheritedMaxAspectRatio();
    }

    int getInheritedAppCompatState() {
        return mTransparentPolicyState.getInheritedAppCompatState();
    }

    @Configuration.Orientation
    int getInheritedOrientation() {
        return mTransparentPolicyState.getInheritedOrientation();
    }

    ActivityRecord.CompatDisplayInsets getInheritedCompatDisplayInsets() {
        return mTransparentPolicyState.getInheritedCompatDisplayInsets();
    }

    void clearInheritedCompatDisplayInsets() {
        mTransparentPolicyState.clearInheritedCompatDisplayInsets();
    }

    TransparentPolicyState getTransparentPolicyState() {
        return mTransparentPolicyState;
    }

    /**
     * In case of translucent activities, it consumes the {@link ActivityRecord} of the first opaque
     * activity beneath using the given consumer and returns {@code true}.
     */
    boolean applyOnOpaqueActivityBelow(@NonNull Consumer<ActivityRecord> consumer) {
        return mTransparentPolicyState.applyOnOpaqueActivityBelow(consumer);
    }

    /**
     * @return The first not finishing opaque activity beneath the current translucent activity
     * if it exists and the strategy is enabled.
     */
    Optional<ActivityRecord> findOpaqueNotFinishingActivityBelow() {
        return mTransparentPolicyState.findOpaqueNotFinishingActivityBelow();
    }

    /** Resets the screen size related fields so they can be resolved by requested bounds later. */
    private static void resetTranslucentOverrideConfig(Configuration config) {
        // The values for the following properties will be defined during the configuration
        // resolution in {@link ActivityRecord#resolveOverrideConfiguration} using the
        // properties inherited from the first not finishing opaque activity beneath.
        config.orientation = ORIENTATION_UNDEFINED;
        config.screenWidthDp = config.compatScreenWidthDp = SCREEN_WIDTH_DP_UNDEFINED;
        config.screenHeightDp = config.compatScreenHeightDp = SCREEN_HEIGHT_DP_UNDEFINED;
        config.smallestScreenWidthDp = config.compatSmallestScreenWidthDp =
                SMALLEST_SCREEN_WIDTH_DP_UNDEFINED;
    }

    private void inheritConfiguration(ActivityRecord firstOpaque) {
        mTransparentPolicyState.inheritFromOpaque(firstOpaque);
    }

    /**
     * Encapsulate the state for the current translucent activity when the transparent policy
     * has started.
     */
    static class TransparentPolicyState {
        // Aspect ratio value to consider as undefined.
        private static final float UNDEFINED_ASPECT_RATIO = 0f;

        @NonNull
        private final ActivityRecord mActivityRecord;

        @Configuration.Orientation
        private int mInheritedOrientation = ORIENTATION_UNDEFINED;
        private float mInheritedMinAspectRatio = UNDEFINED_ASPECT_RATIO;
        private float mInheritedMaxAspectRatio = UNDEFINED_ASPECT_RATIO;

        // The app compat state for the opaque activity if any
        private int mInheritedAppCompatState = APP_COMPAT_STATE_CHANGED__STATE__UNKNOWN;

        // The CompatDisplayInsets of the opaque activity beneath the translucent one.
        private ActivityRecord.CompatDisplayInsets mInheritedCompatDisplayInsets;

        @Nullable
        ActivityRecord mFirstOpaqueActivity;

        /*
         * WindowContainerListener responsible to make translucent activities inherit
         * constraints from the first opaque activity beneath them. It's null for not
         * translucent activities.
         */
        @Nullable
        private WindowContainerListener mLetterboxConfigListener;

        TransparentPolicyState(@NonNull ActivityRecord activityRecord) {
            mActivityRecord = activityRecord;
        }

        private void start(@NonNull ActivityRecord firstOpaqueActivity) {
            mFirstOpaqueActivity = firstOpaqueActivity;
            mFirstOpaqueActivity.mLetterboxUiController.getTransparentPolicy()
                    .mDestroyListeners.add(mActivityRecord.mLetterboxUiController
                            .getTransparentPolicy());
            inheritFromOpaque(firstOpaqueActivity);
            final WindowContainer<?> parent = mActivityRecord.getParent();
            mLetterboxConfigListener = WindowContainer.overrideConfigurationPropagation(
                    mActivityRecord, mFirstOpaqueActivity,
                    (opaqueConfig, transparentOverrideConfig) -> {
                        resetTranslucentOverrideConfig(transparentOverrideConfig);
                        final Rect parentBounds = parent.getWindowConfiguration().getBounds();
                        final Rect bounds = transparentOverrideConfig
                                .windowConfiguration.getBounds();
                        final Rect letterboxBounds = opaqueConfig.windowConfiguration.getBounds();
                        // We cannot use letterboxBounds directly here because the position relies
                        // on letterboxing. Using letterboxBounds directly, would produce a
                        // double offset.
                        bounds.set(parentBounds.left, parentBounds.top,
                                parentBounds.left + letterboxBounds.width(),
                                parentBounds.top + letterboxBounds.height());
                        // We need to initialize appBounds to avoid NPE. The actual value will
                        // be set ahead when resolving the Configuration for the activity.
                        transparentOverrideConfig.windowConfiguration.setAppBounds(new Rect());
                        inheritFromOpaque(mFirstOpaqueActivity);
                        return transparentOverrideConfig;
                    });
        }

        private void inheritFromOpaque(@NonNull ActivityRecord opaqueActivity) {
            // To avoid wrong behaviour, we're not forcing a specific aspect ratio to activities
            // which are not already providing one (e.g. permission dialogs) and presumably also
            // not resizable.
            if (mActivityRecord.getMinAspectRatio() != UNDEFINED_ASPECT_RATIO) {
                mInheritedMinAspectRatio = opaqueActivity.getMinAspectRatio();
            }
            if (mActivityRecord.getMaxAspectRatio() != UNDEFINED_ASPECT_RATIO) {
                mInheritedMaxAspectRatio = opaqueActivity.getMaxAspectRatio();
            }
            mInheritedOrientation = opaqueActivity.getRequestedConfigurationOrientation();
            mInheritedAppCompatState = opaqueActivity.getAppCompatState();
            mInheritedCompatDisplayInsets = opaqueActivity.getCompatDisplayInsets();
        }

        private void reset() {
            if (mLetterboxConfigListener != null) {
                mLetterboxConfigListener.onRemoved();
            }
            mLetterboxConfigListener = null;
            mInheritedOrientation = ORIENTATION_UNDEFINED;
            mInheritedMinAspectRatio = UNDEFINED_ASPECT_RATIO;
            mInheritedMaxAspectRatio = UNDEFINED_ASPECT_RATIO;
            mInheritedAppCompatState = APP_COMPAT_STATE_CHANGED__STATE__UNKNOWN;
            mInheritedCompatDisplayInsets = null;
            if (mFirstOpaqueActivity != null) {
                mFirstOpaqueActivity.mLetterboxUiController.getTransparentPolicy()
                        .mDestroyListeners.remove(mActivityRecord.mLetterboxUiController
                                .getTransparentPolicy());
            }
            mFirstOpaqueActivity = null;
        }

        private boolean isRunning() {
            return mLetterboxConfigListener != null;
        }

        private int getInheritedOrientation() {
            return mInheritedOrientation;
        }

        private float getInheritedMinAspectRatio() {
            return mInheritedMinAspectRatio;
        }

        private float getInheritedMaxAspectRatio() {
            return mInheritedMaxAspectRatio;
        }

        private int getInheritedAppCompatState() {
            return mInheritedAppCompatState;
        }

        private ActivityRecord.CompatDisplayInsets getInheritedCompatDisplayInsets() {
            return mInheritedCompatDisplayInsets;
        }

        private void clearInheritedCompatDisplayInsets() {
            mInheritedCompatDisplayInsets = null;
        }

        /**
         * @return The first not finishing opaque activity beneath the current translucent activity
         * if it exists and the strategy is enabled.
         */
        private Optional<ActivityRecord> findOpaqueNotFinishingActivityBelow() {
            if (!isRunning() || mActivityRecord.getTask() == null) {
                return Optional.empty();
            }
            return Optional.ofNullable(mFirstOpaqueActivity);
        }

        /**
         * In case of translucent activities, it consumes the {@link ActivityRecord} of the first
         * opaque activity beneath using the given consumer and returns {@code true}.
         */
        private boolean applyOnOpaqueActivityBelow(@NonNull Consumer<ActivityRecord> consumer) {
            return findOpaqueNotFinishingActivityBelow()
                    .map(activityRecord -> {
                        consumer.accept(activityRecord);
                        return true;
                    }).orElse(false);
        }
    }

}
