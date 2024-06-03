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

package androidx.window.extensions.embedding;

import android.content.res.Configuration;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.window.extensions.embedding.SplitAttributes.SplitType.ExpandContainersSplitType;

/** Helper functions for {@link SplitAttributes} */
class SplitAttributesHelper {
    /**
     * Returns whether the split layout direction is reversed. Right-to-left and bottom-to-top are
     * considered reversed.
     */
    static boolean isReversedLayout(
            @NonNull SplitAttributes splitAttributes, @NonNull Configuration configuration) {
        switch (splitAttributes.getLayoutDirection()) {
            case SplitAttributes.LayoutDirection.LEFT_TO_RIGHT:
            case SplitAttributes.LayoutDirection.TOP_TO_BOTTOM:
                return false;
            case SplitAttributes.LayoutDirection.RIGHT_TO_LEFT:
            case SplitAttributes.LayoutDirection.BOTTOM_TO_TOP:
                return true;
            case SplitAttributes.LayoutDirection.LOCALE:
                return configuration.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            default:
                throw new IllegalArgumentException(
                        "Invalid layout direction:" + splitAttributes.getLayoutDirection());
        }
    }

    /**
     * Returns whether the {@link SplitAttributes} is an {@link ExpandContainersSplitType} and it
     * should show a draggable handle that allows the user to drag and restore it into a split.
     * This state is a result of user dragging the divider to fully expand the secondary container.
     */
    static boolean isDraggableExpandType(@NonNull SplitAttributes splitAttributes) {
        final DividerAttributes dividerAttributes = splitAttributes.getDividerAttributes();
        return splitAttributes.getSplitType() instanceof ExpandContainersSplitType
                && dividerAttributes != null
                && dividerAttributes.getDividerType() == DividerAttributes.DIVIDER_TYPE_DRAGGABLE;

    }
}
