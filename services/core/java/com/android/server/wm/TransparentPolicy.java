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

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Encapsulate logic about translucent activities.
 */
class TransparentPolicy {

    private static final String TAG = TAG_WITH_CLASS_NAME ? "TransparentPolicy" : TAG_ATM;

    // Aspect ratio value to consider as undefined.
    private static final float UNDEFINED_ASPECT_RATIO = 0f;

    // The predicate used to find the first opaque not finishing activity below the potential
    // transparent activity.
    private static final Predicate<ActivityRecord> FIRST_OPAQUE_NOT_FINISHING_ACTIVITY_PREDICATE =
            ActivityRecord::occludesParent;

    // The ActivityRecord this policy relates to.
    private final ActivityRecord mActivityRecord;

    // The LetterboxConfiguration
    private final LetterboxConfiguration mLetterboxConfiguration;

    // The list of observers for the destroy event of candidate opaque activities
    // when dealing with translucent activities.
    private final List<TransparentPolicy> mDestroyListeners = new ArrayList<>();

    // In case of transparent activities we might need to access the aspectRatio of the
    // first opaque activity beneath.
    private float mInheritedMinAspectRatio = UNDEFINED_ASPECT_RATIO;
    private float mInheritedMaxAspectRatio = UNDEFINED_ASPECT_RATIO;

    @Configuration.Orientation
    private int mInheritedOrientation = ORIENTATION_UNDEFINED;

    // The app compat state for the opaque activity if any
    private int mInheritedAppCompatState = APP_COMPAT_STATE_CHANGED__STATE__UNKNOWN;

    // The CompatDisplayInsets of the opaque activity beneath the translucent one.
    private ActivityRecord.CompatDisplayInsets mInheritedCompatDisplayInsets;

    /*
     * WindowContainerListener responsible to make translucent activities inherit
     * constraints from the first opaque activity beneath them. It's null for not
     * translucent activities.
     */
    @Nullable
    private WindowContainerListener mLetterboxConfigListener;


    @Nullable
    @VisibleForTesting
    ActivityRecord mFirstOpaqueActivityBeneath;

    TransparentPolicy(@NonNull ActivityRecord activityRecord,
            @NonNull LetterboxConfiguration letterboxConfiguration) {
        mActivityRecord = activityRecord;
        mLetterboxConfiguration = letterboxConfiguration;
    }

    /**
     * Handles translucent activities letterboxing inheriting constraints from the
     * first opaque activity beneath.
     */
    void updateInheritedLetterbox() {
        final WindowContainer<?> parent = mActivityRecord.getParent();
        if (parent == null) {
            return;
        }
        if (!mLetterboxConfiguration.isTranslucentLetterboxingEnabled()) {
            return;
        }
        if (mLetterboxConfigListener != null) {
            mLetterboxConfigListener.onRemoved();
            clearInheritedConfig();
        }
        // In case mActivityRecord.hasCompatDisplayInsetsWithoutOverride() we don't apply the
        // opaque activity constraints because we're expecting the activity is already letterboxed.
        mFirstOpaqueActivityBeneath = mActivityRecord.getTask().getActivity(
                FIRST_OPAQUE_NOT_FINISHING_ACTIVITY_PREDICATE /* callback */,
                mActivityRecord /* boundary */, false /* includeBoundary */,
                true /* traverseTopToBottom */);
        if (mFirstOpaqueActivityBeneath == null || mFirstOpaqueActivityBeneath.isEmbedded()) {
            // We skip letterboxing if the translucent activity doesn't have any opaque
            // activities beneath or the activity below is embedded which never has letterbox.
            mActivityRecord.recomputeConfiguration();
            return;
        }
        if (mActivityRecord.getTask() == null || mActivityRecord.fillsParent()
                || mActivityRecord.hasCompatDisplayInsetsWithoutInheritance()) {
            return;
        }
        mFirstOpaqueActivityBeneath.mLetterboxUiController.getTransparentPolicy()
                .mDestroyListeners.add(this);
        inheritConfiguration(mFirstOpaqueActivityBeneath);
        mLetterboxConfigListener = WindowContainer.overrideConfigurationPropagation(
                mActivityRecord, mFirstOpaqueActivityBeneath,
                (opaqueConfig, transparentOverrideConfig) -> {
                    resetTranslucentOverrideConfig(transparentOverrideConfig);
                    final Rect parentBounds = parent.getWindowConfiguration().getBounds();
                    final Rect bounds = transparentOverrideConfig.windowConfiguration.getBounds();
                    final Rect letterboxBounds = opaqueConfig.windowConfiguration.getBounds();
                    // We cannot use letterboxBounds directly here because the position relies on
                    // letterboxing. Using letterboxBounds directly, would produce a double offset.
                    bounds.set(parentBounds.left, parentBounds.top,
                            parentBounds.left + letterboxBounds.width(),
                            parentBounds.top + letterboxBounds.height());
                    // We need to initialize appBounds to avoid NPE. The actual value will
                    // be set ahead when resolving the Configuration for the activity.
                    transparentOverrideConfig.windowConfiguration.setAppBounds(new Rect());
                    inheritConfiguration(mFirstOpaqueActivityBeneath);
                    return transparentOverrideConfig;
                });

    }

    void destroy() {
        for (int i = mDestroyListeners.size() - 1; i >= 0; i--) {
            mDestroyListeners.get(i).updateInheritedLetterbox();
        }
        mDestroyListeners.clear();
        if (mLetterboxConfigListener != null) {
            mLetterboxConfigListener.onRemoved();
            mLetterboxConfigListener = null;
        }
    }

    boolean hasInheritedLetterboxBehavior() {
        return mLetterboxConfigListener != null;
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
        return mInheritedMinAspectRatio;
    }

    float getInheritedMaxAspectRatio() {
        return mInheritedMaxAspectRatio;
    }

    int getInheritedAppCompatState() {
        return mInheritedAppCompatState;
    }

    @Configuration.Orientation
    int getInheritedOrientation() {
        return mInheritedOrientation;
    }

    ActivityRecord.CompatDisplayInsets getInheritedCompatDisplayInsets() {
        return mInheritedCompatDisplayInsets;
    }

    void clearInheritedCompatDisplayInsets() {
        mInheritedCompatDisplayInsets = null;
    }

    /**
     * In case of translucent activities, it consumes the {@link ActivityRecord} of the first opaque
     * activity beneath using the given consumer and returns {@code true}.
     */
    boolean applyOnOpaqueActivityBelow(@NonNull Consumer<ActivityRecord> consumer) {
        return findOpaqueNotFinishingActivityBelow()
                .map(activityRecord -> {
                    consumer.accept(activityRecord);
                    return true;
                }).orElse(false);
    }

    /**
     * @return The first not finishing opaque activity beneath the current translucent activity
     * if it exists and the strategy is enabled.
     */
    Optional<ActivityRecord> findOpaqueNotFinishingActivityBelow() {
        if (!hasInheritedLetterboxBehavior() || mActivityRecord.getTask() == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(mFirstOpaqueActivityBeneath);
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
        // To avoid wrong behaviour, we're not forcing a specific aspect ratio to activities
        // which are not already providing one (e.g. permission dialogs) and presumably also
        // not resizable.
        if (mActivityRecord.getMinAspectRatio() != UNDEFINED_ASPECT_RATIO) {
            mInheritedMinAspectRatio = firstOpaque.getMinAspectRatio();
        }
        if (mActivityRecord.getMaxAspectRatio() != UNDEFINED_ASPECT_RATIO) {
            mInheritedMaxAspectRatio = firstOpaque.getMaxAspectRatio();
        }
        mInheritedOrientation = firstOpaque.getRequestedConfigurationOrientation();
        mInheritedAppCompatState = firstOpaque.getAppCompatState();
        mInheritedCompatDisplayInsets = firstOpaque.getCompatDisplayInsets();
    }

    private void clearInheritedConfig() {
        if (mFirstOpaqueActivityBeneath != null) {
            mFirstOpaqueActivityBeneath.mLetterboxUiController.getTransparentPolicy()
                    .mDestroyListeners.remove(this);
        }
        mFirstOpaqueActivityBeneath = null;
        mLetterboxConfigListener = null;
        mInheritedMinAspectRatio = UNDEFINED_ASPECT_RATIO;
        mInheritedMaxAspectRatio = UNDEFINED_ASPECT_RATIO;
        mInheritedOrientation = ORIENTATION_UNDEFINED;
        mInheritedAppCompatState = APP_COMPAT_STATE_CHANGED__STATE__UNKNOWN;
        mInheritedCompatDisplayInsets = null;
    }


}
