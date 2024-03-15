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

import static androidx.window.extensions.embedding.DividerPresenter.getBoundsOffsetForDivider;
import static androidx.window.extensions.embedding.SplitPresenter.CONTAINER_POSITION_BOTTOM;
import static androidx.window.extensions.embedding.SplitPresenter.CONTAINER_POSITION_LEFT;
import static androidx.window.extensions.embedding.SplitPresenter.CONTAINER_POSITION_RIGHT;
import static androidx.window.extensions.embedding.SplitPresenter.CONTAINER_POSITION_TOP;

import static org.junit.Assert.assertEquals;

import android.platform.test.annotations.Presubmit;

import androidx.annotation.NonNull;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Test class for {@link DividerPresenter}.
 *
 * Build/Install/Run:
 *  atest WMJetpackUnitTests:DividerPresenterTest
 */
@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class DividerPresenterTest {
    @Test
    public void testSanitizeDividerAttributes_setDefaultValues() {
        DividerAttributes attributes =
                new DividerAttributes.Builder(DividerAttributes.DIVIDER_TYPE_DRAGGABLE).build();
        DividerAttributes sanitized = DividerPresenter.sanitizeDividerAttributes(attributes);

        assertEquals(DividerAttributes.DIVIDER_TYPE_DRAGGABLE, sanitized.getDividerType());
        assertEquals(DividerPresenter.DEFAULT_DIVIDER_WIDTH_DP, sanitized.getWidthDp());
        assertEquals(DividerPresenter.DEFAULT_MIN_RATIO, sanitized.getPrimaryMinRatio(),
                0.0f /* delta */);
        assertEquals(DividerPresenter.DEFAULT_MAX_RATIO, sanitized.getPrimaryMaxRatio(),
                0.0f /* delta */);
    }

    @Test
    public void testSanitizeDividerAttributes_notChangingValidValues() {
        DividerAttributes attributes =
                new DividerAttributes.Builder(DividerAttributes.DIVIDER_TYPE_DRAGGABLE)
                        .setWidthDp(10)
                        .setPrimaryMinRatio(0.3f)
                        .setPrimaryMaxRatio(0.7f)
                        .build();
        DividerAttributes sanitized = DividerPresenter.sanitizeDividerAttributes(attributes);

        assertEquals(attributes, sanitized);
    }

    @Test
    public void testGetBoundsOffsetForDivider_ratioSplitType() {
        final int dividerWidthPx = 100;
        final float splitRatio = 0.25f;
        final SplitAttributes.SplitType splitType =
                new SplitAttributes.SplitType.RatioSplitType(splitRatio);
        final int expectedTopLeftOffset = 25;
        final int expectedBottomRightOffset = 75;

        assertDividerOffsetEquals(
                dividerWidthPx, splitType, expectedTopLeftOffset, expectedBottomRightOffset);
    }

    @Test
    public void testGetBoundsOffsetForDivider_ratioSplitType_withRounding() {
        final int dividerWidthPx = 101;
        final float splitRatio = 0.25f;
        final SplitAttributes.SplitType splitType =
                new SplitAttributes.SplitType.RatioSplitType(splitRatio);
        final int expectedTopLeftOffset = 25;
        final int expectedBottomRightOffset = 76;

        assertDividerOffsetEquals(
                dividerWidthPx, splitType, expectedTopLeftOffset, expectedBottomRightOffset);
    }

    @Test
    public void testGetBoundsOffsetForDivider_hingeSplitType() {
        final int dividerWidthPx = 100;
        final SplitAttributes.SplitType splitType =
                new SplitAttributes.SplitType.HingeSplitType(
                        new SplitAttributes.SplitType.RatioSplitType(0.5f));

        final int expectedTopLeftOffset = 50;
        final int expectedBottomRightOffset = 50;

        assertDividerOffsetEquals(
                dividerWidthPx, splitType, expectedTopLeftOffset, expectedBottomRightOffset);
    }

    @Test
    public void testGetBoundsOffsetForDivider_expandContainersSplitType() {
        final int dividerWidthPx = 100;
        final SplitAttributes.SplitType splitType =
                new SplitAttributes.SplitType.ExpandContainersSplitType();
        // Always return 0 for ExpandContainersSplitType as divider is not needed.
        final int expectedTopLeftOffset = 0;
        final int expectedBottomRightOffset = 0;

        assertDividerOffsetEquals(
                dividerWidthPx, splitType, expectedTopLeftOffset, expectedBottomRightOffset);
    }

    private void assertDividerOffsetEquals(
            int dividerWidthPx,
            @NonNull SplitAttributes.SplitType splitType,
            int expectedTopLeftOffset,
            int expectedBottomRightOffset) {
        int offset = getBoundsOffsetForDivider(
                dividerWidthPx,
                splitType,
                CONTAINER_POSITION_LEFT
        );
        assertEquals(-expectedTopLeftOffset, offset);

        offset = getBoundsOffsetForDivider(
                dividerWidthPx,
                splitType,
                CONTAINER_POSITION_RIGHT
        );
        assertEquals(expectedBottomRightOffset, offset);

        offset = getBoundsOffsetForDivider(
                dividerWidthPx,
                splitType,
                CONTAINER_POSITION_TOP
        );
        assertEquals(-expectedTopLeftOffset, offset);

        offset = getBoundsOffsetForDivider(
                dividerWidthPx,
                splitType,
                CONTAINER_POSITION_BOTTOM
        );
        assertEquals(expectedBottomRightOffset, offset);
    }
}
