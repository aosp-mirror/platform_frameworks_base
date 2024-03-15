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

import static android.util.TypedValue.COMPLEX_UNIT_DIP;

import static androidx.window.extensions.embedding.DividerAttributes.RATIO_UNSET;
import static androidx.window.extensions.embedding.DividerAttributes.WIDTH_UNSET;
import static androidx.window.extensions.embedding.SplitPresenter.CONTAINER_POSITION_BOTTOM;
import static androidx.window.extensions.embedding.SplitPresenter.CONTAINER_POSITION_LEFT;
import static androidx.window.extensions.embedding.SplitPresenter.CONTAINER_POSITION_RIGHT;
import static androidx.window.extensions.embedding.SplitPresenter.CONTAINER_POSITION_TOP;

import android.annotation.Nullable;
import android.app.ActivityThread;
import android.content.Context;
import android.util.TypedValue;

import androidx.annotation.NonNull;

import com.android.internal.annotations.VisibleForTesting;
import com.android.window.flags.Flags;

/**
 * Manages the rendering and interaction of the divider.
 */
class DividerPresenter {
    // TODO(b/327067596) Update based on UX guidance.
    @VisibleForTesting static final float DEFAULT_MIN_RATIO = 0.35f;
    @VisibleForTesting static final float DEFAULT_MAX_RATIO = 0.65f;
    @VisibleForTesting static final int DEFAULT_DIVIDER_WIDTH_DP = 24;

    static int getDividerWidthPx(@NonNull DividerAttributes dividerAttributes) {
        int dividerWidthDp = dividerAttributes.getWidthDp();

        // TODO(b/329193115) support divider on secondary display
        final Context applicationContext = ActivityThread.currentActivityThread().getApplication();

        return (int) TypedValue.applyDimension(
                COMPLEX_UNIT_DIP,
                dividerWidthDp,
                applicationContext.getResources().getDisplayMetrics());
    }

    /**
     * Returns the container bound offset that is a result of the presence of a divider.
     *
     * The offset is the relative position change for the container edge that is next to the divider
     * due to the presence of the divider. The value could be negative or positive depending on the
     * container position. Positive values indicate that the edge is shifting towards the right
     * (or bottom) and negative values indicate that the edge is shifting towards the left (or top).
     *
     * @param splitAttributes the {@link SplitAttributes} of the split container that we want to
     *                        compute bounds offset.
     * @param position        the position of the container in the split that we want to compute
     *                        bounds offset for.
     * @return the bounds offset in pixels.
     */
    static int getBoundsOffsetForDivider(
            @NonNull SplitAttributes splitAttributes,
            @SplitPresenter.ContainerPosition int position) {
        if (!Flags.activityEmbeddingInteractiveDividerFlag()) {
            return 0;
        }
        final DividerAttributes dividerAttributes = splitAttributes.getDividerAttributes();
        if (dividerAttributes == null) {
            return 0;
        }
        final int dividerWidthPx = getDividerWidthPx(dividerAttributes);
        return getBoundsOffsetForDivider(
                dividerWidthPx,
                splitAttributes.getSplitType(),
                position);
    }

    @VisibleForTesting
    static int getBoundsOffsetForDivider(
            int dividerWidthPx,
            @NonNull SplitAttributes.SplitType splitType,
            @SplitPresenter.ContainerPosition int position) {
        if (splitType instanceof SplitAttributes.SplitType.ExpandContainersSplitType) {
            // No divider is needed for the ExpandContainersSplitType.
            return 0;
        }
        int primaryOffset;
        if (splitType instanceof final SplitAttributes.SplitType.RatioSplitType splitRatio) {
            // When a divider is present, both containers shrink by an amount proportional to their
            // split ratio and sum to the width of the divider, so that the ending sizing of the
            // containers still maintain the same ratio.
            primaryOffset = (int) (dividerWidthPx * splitRatio.getRatio());
        } else {
            // Hinge split type (and other future split types) will have the divider width equally
            // distributed to both containers.
            primaryOffset = dividerWidthPx / 2;
        }
        final int secondaryOffset = dividerWidthPx - primaryOffset;
        switch (position) {
            case CONTAINER_POSITION_LEFT:
            case CONTAINER_POSITION_TOP:
                return -primaryOffset;
            case CONTAINER_POSITION_RIGHT:
            case CONTAINER_POSITION_BOTTOM:
                return secondaryOffset;
            default:
                throw new IllegalArgumentException("Unknown position:" + position);
        }
    }

    /**
     * Sanitizes and sets default values in the {@link DividerAttributes}.
     *
     * Unset values will be set with system default values. See
     * {@link DividerAttributes#WIDTH_UNSET} and {@link DividerAttributes#RATIO_UNSET}.
     *
     * @param dividerAttributes input {@link DividerAttributes}
     * @return a {@link DividerAttributes} that has all values properly set.
     */
    @Nullable
    static DividerAttributes sanitizeDividerAttributes(
            @Nullable DividerAttributes dividerAttributes) {
        if (dividerAttributes == null) {
            return null;
        }
        int widthDp = dividerAttributes.getWidthDp();
        if (widthDp == WIDTH_UNSET) {
            widthDp = DEFAULT_DIVIDER_WIDTH_DP;
        }

        float minRatio = dividerAttributes.getPrimaryMinRatio();
        if (minRatio == RATIO_UNSET) {
            minRatio = DEFAULT_MIN_RATIO;
        }

        float maxRatio = dividerAttributes.getPrimaryMaxRatio();
        if (maxRatio == RATIO_UNSET) {
            maxRatio = DEFAULT_MAX_RATIO;
        }

        return new DividerAttributes.Builder(dividerAttributes)
                .setWidthDp(widthDp)
                .setPrimaryMinRatio(minRatio)
                .setPrimaryMaxRatio(maxRatio)
                .build();
    }
}
