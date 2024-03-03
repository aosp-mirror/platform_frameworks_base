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

import static android.window.TaskFragmentOperation.OP_TYPE_CREATE_OR_MOVE_TASK_FRAGMENT_DECOR_SURFACE;
import static android.window.TaskFragmentOperation.OP_TYPE_REMOVE_TASK_FRAGMENT_DECOR_SURFACE;

import static androidx.window.extensions.embedding.DividerPresenter.getBoundsOffsetForDivider;
import static androidx.window.extensions.embedding.DividerPresenter.getInitialDividerPosition;
import static androidx.window.extensions.embedding.SplitPresenter.CONTAINER_POSITION_BOTTOM;
import static androidx.window.extensions.embedding.SplitPresenter.CONTAINER_POSITION_LEFT;
import static androidx.window.extensions.embedding.SplitPresenter.CONTAINER_POSITION_RIGHT;
import static androidx.window.extensions.embedding.SplitPresenter.CONTAINER_POSITION_TOP;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Binder;
import android.os.IBinder;
import android.platform.test.annotations.Presubmit;
import android.platform.test.flag.junit.SetFlagsRule;
import android.view.Display;
import android.view.SurfaceControl;
import android.window.TaskFragmentOperation;
import android.window.TaskFragmentParentInfo;
import android.window.WindowContainerTransaction;

import androidx.annotation.NonNull;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.window.flags.Flags;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

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
    @Rule
    public final SetFlagsRule mSetFlagRule = new SetFlagsRule();

    @Mock
    private DividerPresenter.Renderer mRenderer;

    @Mock
    private WindowContainerTransaction mTransaction;

    @Mock
    private TaskFragmentParentInfo mParentInfo;

    @Mock
    private SplitContainer mSplitContainer;

    @Mock
    private SurfaceControl mSurfaceControl;

    private DividerPresenter mDividerPresenter;

    private final IBinder mPrimaryContainerToken = new Binder();

    private final IBinder mSecondaryContainerToken = new Binder();

    private final IBinder mAnotherContainerToken = new Binder();

    private DividerPresenter.Properties mProperties;

    private static final DividerAttributes DEFAULT_DIVIDER_ATTRIBUTES =
            new DividerAttributes.Builder(DividerAttributes.DIVIDER_TYPE_DRAGGABLE).build();

    private static final DividerAttributes ANOTHER_DIVIDER_ATTRIBUTES =
            new DividerAttributes.Builder(DividerAttributes.DIVIDER_TYPE_DRAGGABLE)
                    .setWidthDp(10).build();

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mSetFlagRule.enableFlags(Flags.FLAG_ACTIVITY_EMBEDDING_INTERACTIVE_DIVIDER_FLAG);

        when(mParentInfo.getDisplayId()).thenReturn(Display.DEFAULT_DISPLAY);
        when(mParentInfo.getConfiguration()).thenReturn(new Configuration());
        when(mParentInfo.getDecorSurface()).thenReturn(mSurfaceControl);

        when(mSplitContainer.getCurrentSplitAttributes()).thenReturn(
                new SplitAttributes.Builder()
                        .setDividerAttributes(DEFAULT_DIVIDER_ATTRIBUTES)
                        .build());
        final TaskFragmentContainer mockPrimaryContainer =
                createMockTaskFragmentContainer(
                        mPrimaryContainerToken, new Rect(0, 0, 950, 1000));
        final TaskFragmentContainer mockSecondaryContainer =
                createMockTaskFragmentContainer(
                        mSecondaryContainerToken, new Rect(1000, 0, 2000, 1000));
        when(mSplitContainer.getPrimaryContainer()).thenReturn(mockPrimaryContainer);
        when(mSplitContainer.getSecondaryContainer()).thenReturn(mockSecondaryContainer);

        mProperties = new DividerPresenter.Properties(
                new Configuration(),
                DEFAULT_DIVIDER_ATTRIBUTES,
                mSurfaceControl,
                getInitialDividerPosition(mSplitContainer),
                true /* isVerticalSplit */,
                Display.DEFAULT_DISPLAY);

        mDividerPresenter = new DividerPresenter();
        mDividerPresenter.mProperties = mProperties;
        mDividerPresenter.mRenderer = mRenderer;
        mDividerPresenter.mDecorSurfaceOwner = mPrimaryContainerToken;
    }

    @Test
    public void testUpdateDivider() {
        when(mSplitContainer.getCurrentSplitAttributes()).thenReturn(
                new SplitAttributes.Builder()
                        .setDividerAttributes(ANOTHER_DIVIDER_ATTRIBUTES)
                        .build());
        mDividerPresenter.updateDivider(
                mTransaction,
                mParentInfo,
                mSplitContainer);

        assertNotEquals(mProperties, mDividerPresenter.mProperties);
        verify(mRenderer).update();
        verify(mTransaction, never()).addTaskFragmentOperation(any(), any());
    }

    @Test
    public void testUpdateDivider_updateDecorSurfaceOwnerIfPrimaryContainerChanged() {
        final TaskFragmentContainer mockPrimaryContainer =
                createMockTaskFragmentContainer(
                        mAnotherContainerToken, new Rect(0, 0, 750, 1000));
        final TaskFragmentContainer mockSecondaryContainer =
                createMockTaskFragmentContainer(
                        mSecondaryContainerToken, new Rect(800, 0, 2000, 1000));
        when(mSplitContainer.getPrimaryContainer()).thenReturn(mockPrimaryContainer);
        when(mSplitContainer.getSecondaryContainer()).thenReturn(mockSecondaryContainer);
        mDividerPresenter.updateDivider(
                mTransaction,
                mParentInfo,
                mSplitContainer);

        assertNotEquals(mProperties, mDividerPresenter.mProperties);
        verify(mRenderer).update();
        final TaskFragmentOperation operation = new TaskFragmentOperation.Builder(
                OP_TYPE_CREATE_OR_MOVE_TASK_FRAGMENT_DECOR_SURFACE)
                .build();
        assertEquals(mAnotherContainerToken, mDividerPresenter.mDecorSurfaceOwner);
        verify(mTransaction).addTaskFragmentOperation(mAnotherContainerToken, operation);
    }

    @Test
    public void testUpdateDivider_noChangeIfPropertiesIdentical() {
        mDividerPresenter.updateDivider(
                mTransaction,
                mParentInfo,
                mSplitContainer);

        assertEquals(mProperties, mDividerPresenter.mProperties);
        verify(mRenderer, never()).update();
        verify(mTransaction, never()).addTaskFragmentOperation(any(), any());
    }

    @Test
    public void testUpdateDivider_dividerRemovedWhenSplitContainerIsNull() {
        mDividerPresenter.updateDivider(
                mTransaction,
                mParentInfo,
                null /* splitContainer */);
        final TaskFragmentOperation taskFragmentOperation = new TaskFragmentOperation.Builder(
                OP_TYPE_REMOVE_TASK_FRAGMENT_DECOR_SURFACE)
                .build();

        verify(mTransaction).addTaskFragmentOperation(
                mPrimaryContainerToken, taskFragmentOperation);
        verify(mRenderer).release();
        assertNull(mDividerPresenter.mRenderer);
        assertNull(mDividerPresenter.mProperties);
        assertNull(mDividerPresenter.mDecorSurfaceOwner);
    }

    @Test
    public void testUpdateDivider_dividerRemovedWhenDividerAttributesIsNull() {
        when(mSplitContainer.getCurrentSplitAttributes()).thenReturn(
                new SplitAttributes.Builder().setDividerAttributes(null).build());
        mDividerPresenter.updateDivider(
                mTransaction,
                mParentInfo,
                mSplitContainer);
        final TaskFragmentOperation taskFragmentOperation = new TaskFragmentOperation.Builder(
                OP_TYPE_REMOVE_TASK_FRAGMENT_DECOR_SURFACE)
                .build();

        verify(mTransaction).addTaskFragmentOperation(
                mPrimaryContainerToken, taskFragmentOperation);
        verify(mRenderer).release();
        assertNull(mDividerPresenter.mRenderer);
        assertNull(mDividerPresenter.mProperties);
        assertNull(mDividerPresenter.mDecorSurfaceOwner);
    }

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
                        .setWidthDp(24)
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

    private TaskFragmentContainer createMockTaskFragmentContainer(
            @NonNull IBinder token, @NonNull Rect bounds) {
        final TaskFragmentContainer container = mock(TaskFragmentContainer.class);
        when(container.getTaskFragmentToken()).thenReturn(token);
        when(container.getLastRequestedBounds()).thenReturn(bounds);
        return container;
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
