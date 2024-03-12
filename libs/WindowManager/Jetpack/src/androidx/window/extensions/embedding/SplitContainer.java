/*
 * Copyright (C) 2021 The Android Open Source Project
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

package androidx.window.extensions.embedding;

import android.app.Activity;
import android.os.Binder;
import android.os.IBinder;
import android.util.Pair;
import android.util.Size;
import android.window.TaskFragmentParentInfo;
import android.window.WindowContainerTransaction;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.window.extensions.core.util.function.Function;

/**
 * Client-side descriptor of a split that holds two containers.
 */
class SplitContainer {
    @NonNull
    private TaskFragmentContainer mPrimaryContainer;
    @NonNull
    private final TaskFragmentContainer mSecondaryContainer;
    @NonNull
    private final SplitRule mSplitRule;
    /** @see SplitContainer#getCurrentSplitAttributes() */
    @NonNull
    private SplitAttributes mCurrentSplitAttributes;
    /** @see SplitContainer#getDefaultSplitAttributes() */
    @NonNull
    private SplitAttributes mDefaultSplitAttributes;
    @NonNull
    private final IBinder mToken;

    /**
     * Whether the selection of which container is primary can be changed at runtime. Runtime
     * updates is currently possible only for {@link SplitPinContainer}
     *
     * @see SplitPinContainer
     */
    private final boolean mIsPrimaryContainerMutable;

    SplitContainer(@NonNull TaskFragmentContainer primaryContainer,
            @NonNull Activity primaryActivity,
            @NonNull TaskFragmentContainer secondaryContainer,
            @NonNull SplitRule splitRule,
            @NonNull SplitAttributes splitAttributes) {
        this(primaryContainer, primaryActivity, secondaryContainer, splitRule, splitAttributes,
                false /* isPrimaryContainerMutable */);
    }

    SplitContainer(@NonNull TaskFragmentContainer primaryContainer,
            @NonNull Activity primaryActivity,
            @NonNull TaskFragmentContainer secondaryContainer,
            @NonNull SplitRule splitRule,
            @NonNull SplitAttributes splitAttributes, boolean isPrimaryContainerMutable) {
        mPrimaryContainer = primaryContainer;
        mSecondaryContainer = secondaryContainer;
        mSplitRule = splitRule;
        mDefaultSplitAttributes = splitRule.getDefaultSplitAttributes();
        mCurrentSplitAttributes = splitAttributes;
        mToken = new Binder("SplitContainer");
        mIsPrimaryContainerMutable = isPrimaryContainerMutable;

        if (shouldFinishPrimaryWithSecondary(splitRule)) {
            if (mPrimaryContainer.getRunningActivityCount() == 1
                    && mPrimaryContainer.hasActivity(primaryActivity.getActivityToken())) {
                mSecondaryContainer.addContainerToFinishOnExit(mPrimaryContainer);
            } else {
                // Only adding the activity to be finished vs. the entire TaskFragment while
                // the secondary container exits because there are other unrelated activities in the
                // primary TaskFragment.
                mSecondaryContainer.addActivityToFinishOnExit(primaryActivity);
            }
        }
        if (shouldFinishSecondaryWithPrimary(splitRule)) {
            mPrimaryContainer.addContainerToFinishOnExit(mSecondaryContainer);
        }
    }

    void setPrimaryContainer(@NonNull TaskFragmentContainer primaryContainer) {
        if (!mIsPrimaryContainerMutable) {
            throw new IllegalStateException("Cannot update primary TaskFragmentContainer");
        }
        mPrimaryContainer = primaryContainer;
    }

    @NonNull
    TaskFragmentContainer getPrimaryContainer() {
        return mPrimaryContainer;
    }

    @NonNull
    TaskFragmentContainer getSecondaryContainer() {
        return mSecondaryContainer;
    }

    @NonNull
    SplitRule getSplitRule() {
        return mSplitRule;
    }

    /**
     * Returns the current {@link SplitAttributes} this {@code SplitContainer} is showing.
     * <p>
     * If the {@code SplitAttributes} calculator function is not set by
     * {@link SplitController#setSplitAttributesCalculator(Function)}, the current
     * {@code SplitAttributes} is either to expand the containers if the size constraints of
     * {@link #getSplitRule()} are not satisfied,
     * or the {@link #getDefaultSplitAttributes()}, otherwise.
     * </p><p>
     * If the {@code SplitAttributes} calculator function is set, the current
     * {@code SplitAttributes} will be customized by the function, which can be any
     * {@code SplitAttributes}.
     * </p>
     *
     * @see SplitAttributes.SplitType.ExpandContainersSplitType
     */
    @NonNull
    SplitAttributes getCurrentSplitAttributes() {
        return mCurrentSplitAttributes;
    }

    /**
     * Returns the default {@link SplitAttributes} when the parent task container bounds satisfy
     * {@link #getSplitRule()} constraints.
     * <p>
     * The value is usually from {@link SplitRule#getDefaultSplitAttributes} unless it is overridden
     * by {@link SplitController#updateSplitAttributes(IBinder, SplitAttributes)}.
     */
    @NonNull
    SplitAttributes getDefaultSplitAttributes() {
        return mDefaultSplitAttributes;
    }

    @NonNull
    IBinder getToken() {
        return mToken;
    }

    /**
     * Updates the {@link SplitAttributes} to this container.
     * It is usually used when there's a folding state change or
     * {@link SplitController#onTaskFragmentParentInfoChanged(WindowContainerTransaction,
     * int, TaskFragmentParentInfo)}.
     */
    void updateCurrentSplitAttributes(@NonNull SplitAttributes splitAttributes) {
        mCurrentSplitAttributes = splitAttributes;
    }

    /**
     * Overrides the default {@link SplitAttributes} to this container, which may be different
     * from {@link SplitRule#getDefaultSplitAttributes}.
     */
    void updateDefaultSplitAttributes(@NonNull SplitAttributes splitAttributes) {
        mDefaultSplitAttributes = splitAttributes;
    }

    @NonNull
    TaskContainer getTaskContainer() {
        return getPrimaryContainer().getTaskContainer();
    }

    /** Returns the minimum dimension pair of primary container and secondary container. */
    @NonNull
    Pair<Size, Size> getMinDimensionsPair() {
        return new Pair<>(mPrimaryContainer.getMinDimensions(),
                mSecondaryContainer.getMinDimensions());
    }

    boolean isPlaceholderContainer() {
        return (mSplitRule instanceof SplitPlaceholderRule);
    }

    /**
     * Returns the SplitInfo representing this container.
     *
     * @return the SplitInfo representing this container if the underlying TaskFragmentContainers
     * are stable, or {@code null} if any TaskFragmentContainer is in an intermediate state.
     */
    @Nullable
    SplitInfo toSplitInfoIfStable() {
        final ActivityStack primaryActivityStack = mPrimaryContainer.toActivityStackIfStable();
        final ActivityStack secondaryActivityStack = mSecondaryContainer.toActivityStackIfStable();
        if (primaryActivityStack == null || secondaryActivityStack == null) {
            return null;
        }
        return new SplitInfo(primaryActivityStack, secondaryActivityStack,
                mCurrentSplitAttributes, SplitInfo.Token.createFromBinder(mToken));
    }

    static boolean shouldFinishPrimaryWithSecondary(@NonNull SplitRule splitRule) {
        final boolean isPlaceholderContainer = splitRule instanceof SplitPlaceholderRule;
        final boolean shouldFinishPrimaryWithSecondary = (splitRule instanceof SplitPairRule)
                && ((SplitPairRule) splitRule).getFinishPrimaryWithSecondary()
                != SplitRule.FINISH_NEVER;
        return shouldFinishPrimaryWithSecondary || isPlaceholderContainer;
    }

    static boolean shouldFinishSecondaryWithPrimary(@NonNull SplitRule splitRule) {
        final boolean isPlaceholderContainer = splitRule instanceof SplitPlaceholderRule;
        final boolean shouldFinishSecondaryWithPrimary = (splitRule instanceof SplitPairRule)
                && ((SplitPairRule) splitRule).getFinishSecondaryWithPrimary()
                != SplitRule.FINISH_NEVER;
        return shouldFinishSecondaryWithPrimary || isPlaceholderContainer;
    }

    static boolean shouldFinishAssociatedContainerWhenStacked(int finishBehavior) {
        return finishBehavior == SplitRule.FINISH_ALWAYS;
    }

    static boolean shouldFinishAssociatedContainerWhenAdjacent(int finishBehavior) {
        return finishBehavior == SplitRule.FINISH_ALWAYS
                || finishBehavior == SplitRule.FINISH_ADJACENT;
    }

    static int getFinishPrimaryWithSecondaryBehavior(@NonNull SplitRule splitRule) {
        if (splitRule instanceof SplitPlaceholderRule) {
            return ((SplitPlaceholderRule) splitRule).getFinishPrimaryWithSecondary();
        }
        if (splitRule instanceof SplitPairRule) {
            return ((SplitPairRule) splitRule).getFinishPrimaryWithSecondary();
        }
        return SplitRule.FINISH_NEVER;
    }

    static int getFinishSecondaryWithPrimaryBehavior(@NonNull SplitRule splitRule) {
        if (splitRule instanceof SplitPlaceholderRule) {
            return SplitRule.FINISH_ALWAYS;
        }
        if (splitRule instanceof SplitPairRule) {
            return ((SplitPairRule) splitRule).getFinishSecondaryWithPrimary();
        }
        return SplitRule.FINISH_NEVER;
    }

    static boolean isStickyPlaceholderRule(@NonNull SplitRule splitRule) {
        if (!(splitRule instanceof SplitPlaceholderRule)) {
            return false;
        }
        return ((SplitPlaceholderRule) splitRule).isSticky();
    }

    @Override
    public String toString() {
        return "SplitContainer{"
                + " primaryContainer=" + mPrimaryContainer
                + ", secondaryContainer=" + mSecondaryContainer
                + ", splitRule=" + mSplitRule
                + ", currentSplitAttributes" + mCurrentSplitAttributes
                + ", defaultSplitAttributes" + mDefaultSplitAttributes
                + "}";
    }
}
