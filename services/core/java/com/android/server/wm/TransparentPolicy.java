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
 * <p/>
 * An activity is defined as translucent if {@link ActivityRecord#fillsParent()} returns
 * {@code false}. When the policy is running for a letterboxed activity, a transparent activity
 * will inherit constraints about bounds, aspect ratios and orientation from the first not finishing
 * activity below.
 */
class TransparentPolicy {

    private static final String TAG = TAG_WITH_CLASS_NAME ? "TransparentPolicy" : TAG_ATM;

    // The predicate used to find the first opaque not finishing activity below the potential
    // transparent activity.
    private static final Predicate<ActivityRecord> FIRST_OPAQUE_NOT_FINISHING_ACTIVITY_PREDICATE =
            ActivityRecord::occludesParent;

    // The ActivityRecord this policy relates to.
    @NonNull
    private final ActivityRecord mActivityRecord;

    // If transparent activity policy is enabled.
    @NonNull
    private final BooleanSupplier mIsTranslucentLetterboxingEnabledSupplier;

    // The list of observers for the destroy event of candidate opaque activities
    // when dealing with translucent activities.
    @NonNull
    private final List<TransparentPolicy> mDestroyListeners = new ArrayList<>();

    // The current state for the possible transparent activity
    @NonNull
    private final TransparentPolicyState mTransparentPolicyState;

    TransparentPolicy(@NonNull ActivityRecord activityRecord,
            @NonNull LetterboxConfiguration letterboxConfiguration) {
        mActivityRecord = activityRecord;
        mIsTranslucentLetterboxingEnabledSupplier =
                letterboxConfiguration::isTranslucentLetterboxingEnabled;
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
        if (shouldSkipTransparentPolicy(firstOpaqueActivity)) {
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

    /**
     * @return {@code true} if the current activity is translucent with an opaque activity
     * beneath and the related policy is running. In this case it will inherit bounds, orientation
     * and aspect ratios from the first opaque activity beneath.
     */
    boolean isRunning() {
        return mTransparentPolicyState.isRunning();
    }

    /**
     * @return {@code true} if the current activity is translucent with an opaque activity
     * beneath and needs to inherit its orientation.
     */
    boolean hasInheritedOrientation() {
        // To avoid wrong behaviour (e.g. permission dialogs not centered or with wrong size),
        // transparent activities inherit orientation from the first opaque activity below only if
        // they explicitly define an orientation different from SCREEN_ORIENTATION_UNSPECIFIED.
        return isRunning()
                && mActivityRecord.getOverrideOrientation()
                != SCREEN_ORIENTATION_UNSPECIFIED;
    }

    float getInheritedMinAspectRatio() {
        return mTransparentPolicyState.mInheritedMinAspectRatio;
    }

    float getInheritedMaxAspectRatio() {
        return mTransparentPolicyState.mInheritedMaxAspectRatio;
    }

    int getInheritedAppCompatState() {
        return mTransparentPolicyState.mInheritedAppCompatState;
    }

    @Configuration.Orientation
    int getInheritedOrientation() {
        return mTransparentPolicyState.mInheritedOrientation;
    }

    ActivityRecord.CompatDisplayInsets getInheritedCompatDisplayInsets() {
        return mTransparentPolicyState.mInheritedCompatDisplayInsets;
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

    @NonNull
    Optional<ActivityRecord> getFirstOpaqueActivity() {
        return isRunning() ? Optional.of(mTransparentPolicyState.mFirstOpaqueActivity)
                : Optional.empty();
    }

    /**
     * @return The first not finishing opaque activity beneath the current translucent activity
     * if it exists and the strategy is enabled.
     */
    Optional<ActivityRecord> findOpaqueNotFinishingActivityBelow() {
        return mTransparentPolicyState.findOpaqueNotFinishingActivityBelow();
    }

    // We evaluate the case when the policy should not be applied.
    private boolean shouldSkipTransparentPolicy(@Nullable ActivityRecord opaqueActivity) {
        if (opaqueActivity == null || opaqueActivity.isEmbedded()) {
            // We skip letterboxing if the translucent activity doesn't have any
            // opaque activities beneath or the activity below is embedded which
            // never has letterbox.
            mActivityRecord.recomputeConfiguration();
            return true;
        }
        if (mActivityRecord.getTask() == null || mActivityRecord.fillsParent()
                || mActivityRecord.hasCompatDisplayInsetsWithoutInheritance()) {
            return true;
        }
        return false;
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
        @Nullable
        private ActivityRecord.CompatDisplayInsets mInheritedCompatDisplayInsets;

        @Nullable
        private ActivityRecord mFirstOpaqueActivity;

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
            mFirstOpaqueActivity.mAppCompatController.getTransparentPolicy()
                    .mDestroyListeners.add(mActivityRecord.mAppCompatController
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
                mFirstOpaqueActivity.mAppCompatController.getTransparentPolicy()
                        .mDestroyListeners.remove(mActivityRecord.mAppCompatController
                                .getTransparentPolicy());
            }
            mFirstOpaqueActivity = null;
        }

        private boolean isRunning() {
            return mLetterboxConfigListener != null;
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
