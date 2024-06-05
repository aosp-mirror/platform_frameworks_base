/*
 * Copyright (C) 2023 The Android Open Source Project
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

import androidx.annotation.NonNull;

/**
 * Client-side descriptor of a split that holds two containers while the secondary
 * container is pinned on top of the Task and the primary container is the container that is
 * currently below the secondary container. The primary container could be updated to
 * another container whenever the existing primary container is removed or no longer
 * be the container that's right behind the secondary container.
 */
class SplitPinContainer extends SplitContainer {

    SplitPinContainer(@NonNull TaskFragmentContainer primaryContainer,
            @NonNull TaskFragmentContainer secondaryContainer,
            @NonNull SplitPinRule splitPinRule,
            @NonNull SplitAttributes splitAttributes) {
        super(primaryContainer, primaryContainer.getTopNonFinishingActivity(), secondaryContainer,
                splitPinRule, splitAttributes, true /* isPrimaryContainerMutable */);
    }

    @Override
    public String toString() {
        return "SplitPinContainer{"
                + " primaryContainer=" + getPrimaryContainer()
                + " secondaryContainer=" + getSecondaryContainer()
                + " splitPinRule=" + getSplitRule()
                + " splitAttributes" + getCurrentSplitAttributes()
                + "}";
    }
}
