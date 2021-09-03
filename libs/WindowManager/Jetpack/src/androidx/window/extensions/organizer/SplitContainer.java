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

package androidx.window.extensions.organizer;

import android.annotation.NonNull;
import android.app.Activity;

import androidx.window.extensions.embedding.SplitPairRule;
import androidx.window.extensions.embedding.SplitPlaceholderRule;
import androidx.window.extensions.embedding.SplitRule;

/**
 * Client-side descriptor of a split that holds two containers.
 */
class SplitContainer {
    private final TaskFragmentContainer mPrimaryContainer;
    private final TaskFragmentContainer mSecondaryContainer;
    private final SplitRule mSplitRule;

    SplitContainer(@NonNull TaskFragmentContainer primaryContainer,
            @NonNull Activity primaryActivity,
            @NonNull TaskFragmentContainer secondaryContainer,
            @NonNull SplitRule splitRule) {
        mPrimaryContainer = primaryContainer;
        mSecondaryContainer = secondaryContainer;
        mSplitRule = splitRule;

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

    boolean isPlaceholderContainer() {
        return (mSplitRule instanceof SplitPlaceholderRule);
    }

    static boolean shouldFinishPrimaryWithSecondary(@NonNull SplitRule splitRule) {
        final boolean isPlaceholderContainer = splitRule instanceof SplitPlaceholderRule;
        final boolean shouldFinishPrimaryWithSecondary = (splitRule instanceof SplitPairRule)
                && ((SplitPairRule) splitRule).shouldFinishPrimaryWithSecondary();
        return shouldFinishPrimaryWithSecondary || isPlaceholderContainer;
    }

    static boolean shouldFinishSecondaryWithPrimary(@NonNull SplitRule splitRule) {
        final boolean isPlaceholderContainer = splitRule instanceof SplitPlaceholderRule;
        final boolean shouldFinishSecondaryWithPrimary = (splitRule instanceof SplitPairRule)
                && ((SplitPairRule) splitRule).shouldFinishSecondaryWithPrimary();
        return shouldFinishSecondaryWithPrimary || isPlaceholderContainer;
    }
}
