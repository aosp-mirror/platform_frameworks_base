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

import androidx.window.extensions.ExtensionSplitPairRule;

/**
 * Client-side descriptor of a split that holds two containers.
 */
class SplitContainer {
    private final TaskFragmentContainer mPrimaryContainer;
    private final TaskFragmentContainer mSecondaryContainer;
    private final ExtensionSplitPairRule mSplitPairRule;

    SplitContainer(@NonNull TaskFragmentContainer primaryContainer,
            @NonNull Activity primaryActivity,
            @NonNull TaskFragmentContainer secondaryContainer,
            @NonNull ExtensionSplitPairRule splitPairRule) {
        mPrimaryContainer = primaryContainer;
        mSecondaryContainer = secondaryContainer;
        mSplitPairRule = splitPairRule;

        if (mSplitPairRule.finishPrimaryWithSecondary || mSplitPairRule.useAsPlaceholder) {
            mSecondaryContainer.addActivityToFinishOnExit(primaryActivity);
        }
        if (mSplitPairRule.finishSecondaryWithPrimary || mSplitPairRule.useAsPlaceholder) {
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
    ExtensionSplitPairRule getSplitPairRule() {
        return mSplitPairRule;
    }
}
