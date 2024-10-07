/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.systemui.complication;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.view.View;

import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.complication.ComplicationLayoutEngine.Margins;
import com.android.systemui.res.R;
import com.android.systemui.touch.TouchInsetManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class ComplicationLayoutEngineTest extends SysuiTestCase {
    @Mock
    ConstraintLayout mLayout;

    @Mock
    TouchInsetManager.TouchInsetSession mTouchSession;

    ComplicationLayoutEngine createComplicationLayoutEngine() {
        return createComplicationLayoutEngine(0);
    }

    ComplicationLayoutEngine createComplicationLayoutEngine(int spacing) {
        return new ComplicationLayoutEngine(mLayout, spacing, 0, 0, 0, 0, mTouchSession, 0, 0);
    }

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
    }

    private static class ViewInfo {
        private static int sNextId = 1;
        public final ComplicationId id;
        public final View view;
        public final ComplicationLayoutParams lp;

        @Complication.Category
        public final int category;

        private static ComplicationId.Factory sFactory = new ComplicationId.Factory();

        ViewInfo(ComplicationLayoutParams params, @Complication.Category int category,
                ConstraintLayout layout) {
            this.lp = params;
            this.category = category;
            this.view = Mockito.mock(View.class);
            this.id = sFactory.getNextId();
            when(view.getId()).thenReturn(sNextId++);
            when(view.getParent()).thenReturn(layout);
        }

        void clearInvocations() {
            Mockito.clearInvocations(view);
        }
    }

    private void verifyChange(ViewInfo viewInfo,
            boolean verifyAdd,
            Consumer<ConstraintLayout.LayoutParams> paramConsumer) {
        ArgumentCaptor<ConstraintLayout.LayoutParams> lpCaptor =
                ArgumentCaptor.forClass(ConstraintLayout.LayoutParams.class);
        verify(viewInfo.view).setLayoutParams(lpCaptor.capture());

        if (verifyAdd) {
            verify(mLayout).addView(eq(viewInfo.view));
        }

        ConstraintLayout.LayoutParams capturedParams = lpCaptor.getValue();
        paramConsumer.accept(capturedParams);
    }

    private void addComplication(ComplicationLayoutEngine engine, ViewInfo info) {
        engine.addComplication(info.id, info.view, info.lp, info.category);
    }

    @Test
    public void testCombineMargins() {
        final Random rand = new Random();
        final Margins margins1 = new Margins(rand.nextInt(), rand.nextInt(), rand.nextInt(),
                rand.nextInt());
        final Margins margins2 = new Margins(rand.nextInt(), rand.nextInt(), rand.nextInt(),
                rand.nextInt());
        final Margins combined = Margins.combine(margins1, margins2);
        assertThat(margins1.start + margins2.start).isEqualTo(combined.start);
        assertThat(margins1.top + margins2.top).isEqualTo(combined.top);
        assertThat(margins1.end + margins2.end).isEqualTo(combined.end);
        assertThat(margins1.bottom + margins2.bottom).isEqualTo(combined.bottom);
    }

    @Test
    public void testComplicationMarginPosition() {
        final Random rand = new Random();
        final int startMargin = rand.nextInt();
        final int topMargin = rand.nextInt();
        final int endMargin = rand.nextInt();
        final int bottomMargin = rand.nextInt();
        final int spacing = rand.nextInt();

        final ComplicationLayoutEngine engine = new ComplicationLayoutEngine(mLayout, spacing,
                startMargin, topMargin, endMargin, bottomMargin, mTouchSession, 0, 0);

        final ViewInfo firstViewInfo = new ViewInfo(
                new ComplicationLayoutParams(
                        100,
                        100,
                        ComplicationLayoutParams.POSITION_TOP
                                | ComplicationLayoutParams.POSITION_END,
                        ComplicationLayoutParams.DIRECTION_DOWN,
                        0),
                Complication.CATEGORY_SYSTEM,
                mLayout);

        addComplication(engine, firstViewInfo);
        firstViewInfo.clearInvocations();

        final ViewInfo secondViewInfo = new ViewInfo(
                new ComplicationLayoutParams(
                        100,
                        100,
                        ComplicationLayoutParams.POSITION_TOP
                                | ComplicationLayoutParams.POSITION_END,
                        ComplicationLayoutParams.DIRECTION_DOWN,
                        0),
                Complication.CATEGORY_STANDARD,
                mLayout);

        addComplication(engine, secondViewInfo);


        // The first added view should have margins from both directions from the corner position.
        verifyChange(firstViewInfo, false, lp -> {
            assertThat(lp.topMargin).isEqualTo(topMargin);
            assertThat(lp.getMarginEnd()).isEqualTo(endMargin);
        });

        // The second view should be spaced below the first view and have the side end margin.
        verifyChange(secondViewInfo, false, lp -> {
            assertThat(lp.topMargin).isEqualTo(spacing);
            assertThat(lp.getMarginEnd()).isEqualTo(endMargin);
        });
    }

    /**
     * Makes sure the engine properly places a view within the {@link ConstraintLayout}.
     */
    @Test
    public void testSingleLayout() {
        final ViewInfo firstViewInfo = new ViewInfo(
                new ComplicationLayoutParams(
                        100,
                        100,
                        ComplicationLayoutParams.POSITION_TOP
                        | ComplicationLayoutParams.POSITION_END,
                        ComplicationLayoutParams.DIRECTION_DOWN,
                        0),
                Complication.CATEGORY_STANDARD,
                mLayout);

        final ComplicationLayoutEngine engine = createComplicationLayoutEngine();
        addComplication(engine, firstViewInfo);

        // Ensure the view is added to the top end corner
        verifyChange(firstViewInfo, true, lp -> {
            assertThat(lp.topToTop == ConstraintLayout.LayoutParams.PARENT_ID).isTrue();
            assertThat(lp.endToEnd == ConstraintLayout.LayoutParams.PARENT_ID).isTrue();
        });
    }

    /**
     * Makes sure the engine properly places a view within the {@link ConstraintLayout}.
     */
    @Test
    public void testSnapToGuide() {
        final ViewInfo firstViewInfo = new ViewInfo(
                new ComplicationLayoutParams(
                        100,
                        100,
                        ComplicationLayoutParams.POSITION_TOP
                                | ComplicationLayoutParams.POSITION_END,
                        ComplicationLayoutParams.DIRECTION_DOWN,
                        0,
                        true),
                Complication.CATEGORY_STANDARD,
                mLayout);

        final ComplicationLayoutEngine engine = createComplicationLayoutEngine();
        addComplication(engine, firstViewInfo);

        // Ensure the view is added to the top end corner
        verifyChange(firstViewInfo, true, lp -> {
            assertThat(lp.topToTop == ConstraintLayout.LayoutParams.PARENT_ID).isTrue();
            assertThat(lp.endToEnd == ConstraintLayout.LayoutParams.PARENT_ID).isTrue();
            assertThat(lp.startToEnd == R.id.complication_end_guide).isTrue();
        });
    }

    /**
     * Ensures layout in a particular direction updates.
     */
    @Test
    public void testDirectionLayout() {
        final ComplicationLayoutEngine engine = createComplicationLayoutEngine();

        final ViewInfo firstViewInfo = new ViewInfo(
                new ComplicationLayoutParams(
                        100,
                        100,
                        ComplicationLayoutParams.POSITION_TOP
                                | ComplicationLayoutParams.POSITION_END,
                        ComplicationLayoutParams.DIRECTION_DOWN,
                        0),
                Complication.CATEGORY_STANDARD,
                mLayout);

        addComplication(engine, firstViewInfo);

        firstViewInfo.clearInvocations();

        final ViewInfo secondViewInfo = new ViewInfo(
                new ComplicationLayoutParams(
                        100,
                        100,
                        ComplicationLayoutParams.POSITION_TOP
                                | ComplicationLayoutParams.POSITION_END,
                        ComplicationLayoutParams.DIRECTION_DOWN,
                        0),
                Complication.CATEGORY_SYSTEM,
                mLayout);

        addComplication(engine, secondViewInfo);

        // The first added view should now be underneath the second view.
        verifyChange(firstViewInfo, false, lp -> {
            assertThat(lp.topToBottom == secondViewInfo.view.getId()).isTrue();
            assertThat(lp.endToEnd == ConstraintLayout.LayoutParams.PARENT_ID).isTrue();
        });

        // The second view should be in the top position.
        verifyChange(secondViewInfo, true, lp -> {
            assertThat(lp.topToTop == ConstraintLayout.LayoutParams.PARENT_ID).isTrue();
            assertThat(lp.endToEnd == ConstraintLayout.LayoutParams.PARENT_ID).isTrue();
        });
    }

    /**
     * Ensures layout in a particular position updates.
     */
    @Test
    public void testPositionLayout() {
        final ComplicationLayoutEngine engine = createComplicationLayoutEngine();

        final ViewInfo firstViewInfo = new ViewInfo(
                new ComplicationLayoutParams(
                        100,
                        100,
                        ComplicationLayoutParams.POSITION_TOP
                                | ComplicationLayoutParams.POSITION_END,
                        ComplicationLayoutParams.DIRECTION_DOWN,
                        0),
                Complication.CATEGORY_STANDARD,
                mLayout);

        addComplication(engine, firstViewInfo);

        final ViewInfo secondViewInfo = new ViewInfo(
                new ComplicationLayoutParams(
                        100,
                        100,
                        ComplicationLayoutParams.POSITION_TOP
                                | ComplicationLayoutParams.POSITION_END,
                        ComplicationLayoutParams.DIRECTION_DOWN,
                        0),
                Complication.CATEGORY_SYSTEM,
                mLayout);

        addComplication(engine, secondViewInfo);

        firstViewInfo.clearInvocations();
        secondViewInfo.clearInvocations();

        final ViewInfo thirdViewInfo = new ViewInfo(
                new ComplicationLayoutParams(
                        100,
                        100,
                        ComplicationLayoutParams.POSITION_TOP
                                | ComplicationLayoutParams.POSITION_END,
                        ComplicationLayoutParams.DIRECTION_START,
                        1),
                Complication.CATEGORY_SYSTEM,
                mLayout);

        addComplication(engine, thirdViewInfo);

        // The first added view should now be underneath the second view.
        verifyChange(firstViewInfo, false, lp -> {
            assertThat(lp.topToBottom == secondViewInfo.view.getId()).isTrue();
            assertThat(lp.endToEnd == ConstraintLayout.LayoutParams.PARENT_ID).isTrue();
        });

        // The second view should be in underneath the third view.
        verifyChange(secondViewInfo, false, lp -> {
            assertThat(lp.topToBottom == thirdViewInfo.view.getId()).isTrue();
            assertThat(lp.endToEnd == ConstraintLayout.LayoutParams.PARENT_ID).isTrue();
        });

        // The third view should be in at the top.
        verifyChange(thirdViewInfo, true, lp -> {
            assertThat(lp.topToTop == ConstraintLayout.LayoutParams.PARENT_ID).isTrue();
            assertThat(lp.endToEnd == ConstraintLayout.LayoutParams.PARENT_ID).isTrue();
        });

        final ViewInfo fourthViewInfo = new ViewInfo(
                new ComplicationLayoutParams(
                        100,
                        100,
                        ComplicationLayoutParams.POSITION_TOP
                                | ComplicationLayoutParams.POSITION_END,
                        ComplicationLayoutParams.DIRECTION_START,
                        1),
                Complication.CATEGORY_STANDARD,
                mLayout);

        addComplication(engine, fourthViewInfo);

        verifyChange(fourthViewInfo, true, lp -> {
            assertThat(lp.topToTop == ConstraintLayout.LayoutParams.PARENT_ID).isTrue();
            assertThat(lp.endToStart == thirdViewInfo.view.getId()).isTrue();
        });
    }

    /**
     * Ensures default margin is applied
     */
    @Test
    public void testDefaultMargin() {
        final int margin = 5;
        final ComplicationLayoutEngine engine = createComplicationLayoutEngine(margin);

        final ViewInfo firstViewInfo = new ViewInfo(
                new ComplicationLayoutParams(
                        100,
                        100,
                        ComplicationLayoutParams.POSITION_TOP
                                | ComplicationLayoutParams.POSITION_END,
                        ComplicationLayoutParams.DIRECTION_DOWN,
                        0),
                Complication.CATEGORY_STANDARD,
                mLayout);

        addComplication(engine, firstViewInfo);

        final ViewInfo secondViewInfo = new ViewInfo(
                new ComplicationLayoutParams(
                        100,
                        100,
                        ComplicationLayoutParams.POSITION_TOP
                                | ComplicationLayoutParams.POSITION_END,
                        ComplicationLayoutParams.DIRECTION_START,
                        0),
                Complication.CATEGORY_SYSTEM,
                mLayout);

        addComplication(engine, secondViewInfo);

        firstViewInfo.clearInvocations();
        secondViewInfo.clearInvocations();

        final ViewInfo thirdViewInfo = new ViewInfo(
                new ComplicationLayoutParams(
                        100,
                        100,
                        ComplicationLayoutParams.POSITION_TOP
                                | ComplicationLayoutParams.POSITION_END,
                        ComplicationLayoutParams.DIRECTION_START,
                        1),
                Complication.CATEGORY_SYSTEM,
                mLayout);

        addComplication(engine, thirdViewInfo);

        // The first added view should now be underneath the third view.
        verifyChange(firstViewInfo, false, lp -> {
            assertThat(lp.topToBottom == thirdViewInfo.view.getId()).isTrue();
            assertThat(lp.endToEnd == ConstraintLayout.LayoutParams.PARENT_ID).isTrue();
            assertThat(lp.topMargin).isEqualTo(margin);
        });

        // The second view should be to the start of the third view.
        verifyChange(secondViewInfo, false, lp -> {
            assertThat(lp.endToStart == thirdViewInfo.view.getId()).isTrue();
            assertThat(lp.topToTop == ConstraintLayout.LayoutParams.PARENT_ID).isTrue();
            assertThat(lp.getMarginEnd()).isEqualTo(margin);
        });

        // The third view should be at the top end corner. No margin should be applied.
        verifyChange(thirdViewInfo, true, lp -> {
            assertThat(lp.topToTop == ConstraintLayout.LayoutParams.PARENT_ID).isTrue();
            assertThat(lp.endToEnd == ConstraintLayout.LayoutParams.PARENT_ID).isTrue();
            assertThat(lp.getMarginStart()).isEqualTo(0);
            assertThat(lp.getMarginEnd()).isEqualTo(0);
            assertThat(lp.topMargin).isEqualTo(0);
            assertThat(lp.bottomMargin).isEqualTo(0);
        });
    }

    /**
     * Ensures complication margin is applied
     */
    @Test
    public void testComplicationMargin() {
        final int defaultMargin = 5;
        final int complicationMargin = 10;
        final ComplicationLayoutEngine engine = createComplicationLayoutEngine(defaultMargin);

        final ViewInfo firstViewInfo = new ViewInfo(
                new ComplicationLayoutParams(
                        100,
                        100,
                        ComplicationLayoutParams.POSITION_TOP
                                | ComplicationLayoutParams.POSITION_END,
                        ComplicationLayoutParams.DIRECTION_DOWN,
                        0,
                        complicationMargin),
                Complication.CATEGORY_STANDARD,
                mLayout);

        addComplication(engine, firstViewInfo);

        final ViewInfo secondViewInfo = new ViewInfo(
                new ComplicationLayoutParams(
                        100,
                        100,
                        ComplicationLayoutParams.POSITION_TOP
                                | ComplicationLayoutParams.POSITION_END,
                        ComplicationLayoutParams.DIRECTION_START,
                        0),
                Complication.CATEGORY_SYSTEM,
                mLayout);

        addComplication(engine, secondViewInfo);

        firstViewInfo.clearInvocations();
        secondViewInfo.clearInvocations();

        final ViewInfo thirdViewInfo = new ViewInfo(
                new ComplicationLayoutParams(
                        100,
                        100,
                        ComplicationLayoutParams.POSITION_TOP
                                | ComplicationLayoutParams.POSITION_END,
                        ComplicationLayoutParams.DIRECTION_START,
                        1),
                Complication.CATEGORY_SYSTEM,
                mLayout);

        addComplication(engine, thirdViewInfo);

        // The first added view should now be underneath the third view.
        verifyChange(firstViewInfo, false, lp -> {
            assertThat(lp.topToBottom == thirdViewInfo.view.getId()).isTrue();
            assertThat(lp.endToEnd == ConstraintLayout.LayoutParams.PARENT_ID).isTrue();
            assertThat(lp.topMargin).isEqualTo(complicationMargin);
        });

        // The second view should be to the start of the third view.
        verifyChange(secondViewInfo, false, lp -> {
            assertThat(lp.endToStart == thirdViewInfo.view.getId()).isTrue();
            assertThat(lp.topToTop == ConstraintLayout.LayoutParams.PARENT_ID).isTrue();
            assertThat(lp.getMarginEnd()).isEqualTo(defaultMargin);
        });
    }

    /**
     * Ensures layout sets correct max width constraint.
     */
    @Test
    public void testWidthConstraint() {
        final int maxWidth = 20;
        final ComplicationLayoutEngine engine = createComplicationLayoutEngine();

        final ViewInfo viewStartDirection = new ViewInfo(
                new ComplicationLayoutParams(
                        100,
                        100,
                        ComplicationLayoutParams.POSITION_TOP
                                | ComplicationLayoutParams.POSITION_END,
                        ComplicationLayoutParams.DIRECTION_START,
                        0,
                        5,
                        maxWidth),
                Complication.CATEGORY_STANDARD,
                mLayout);
        final ViewInfo viewEndDirection = new ViewInfo(
                new ComplicationLayoutParams(
                        100,
                        100,
                        ComplicationLayoutParams.POSITION_TOP
                                | ComplicationLayoutParams.POSITION_START,
                        ComplicationLayoutParams.DIRECTION_END,
                        0,
                        5,
                        maxWidth),
                Complication.CATEGORY_STANDARD,
                mLayout);

        addComplication(engine, viewStartDirection);
        addComplication(engine, viewEndDirection);

        // Verify both horizontal direction views have max width set correctly, and max height is
        // not set.
        verifyChange(viewStartDirection, false, lp -> {
            assertThat(lp.matchConstraintMaxWidth).isEqualTo(maxWidth);
            assertThat(lp.matchConstraintMaxHeight).isEqualTo(0);
        });
        verifyChange(viewEndDirection, false, lp -> {
            assertThat(lp.matchConstraintMaxWidth).isEqualTo(maxWidth);
            assertThat(lp.matchConstraintMaxHeight).isEqualTo(0);
        });
    }

    /**
     * Ensures layout sets correct max height constraint.
     */
    @Test
    public void testHeightConstraint() {
        final int maxHeight = 20;
        final ComplicationLayoutEngine engine = createComplicationLayoutEngine();

        final ViewInfo viewUpDirection = new ViewInfo(
                new ComplicationLayoutParams(
                        100,
                        100,
                        ComplicationLayoutParams.POSITION_BOTTOM
                                | ComplicationLayoutParams.POSITION_END,
                        ComplicationLayoutParams.DIRECTION_UP,
                        0,
                        5,
                        maxHeight),
                Complication.CATEGORY_STANDARD,
                mLayout);
        final ViewInfo viewDownDirection = new ViewInfo(
                new ComplicationLayoutParams(
                        100,
                        100,
                        ComplicationLayoutParams.POSITION_TOP
                                | ComplicationLayoutParams.POSITION_END,
                        ComplicationLayoutParams.DIRECTION_DOWN,
                        0,
                        5,
                        maxHeight),
                Complication.CATEGORY_STANDARD,
                mLayout);

        addComplication(engine, viewUpDirection);
        addComplication(engine, viewDownDirection);

        // Verify both vertical direction views have max height set correctly, and max width is
        // not set.
        verifyChange(viewUpDirection, false, lp -> {
            assertThat(lp.matchConstraintMaxHeight).isEqualTo(maxHeight);
            assertThat(lp.matchConstraintMaxWidth).isEqualTo(0);
        });
        verifyChange(viewDownDirection, false, lp -> {
            assertThat(lp.matchConstraintMaxHeight).isEqualTo(maxHeight);
            assertThat(lp.matchConstraintMaxWidth).isEqualTo(0);
        });
    }

    /**
     * Ensures layout does not set any constraint if not specified.
     */
    @Test
    public void testConstraintNotSetWhenNotSpecified() {
        final ComplicationLayoutEngine engine = createComplicationLayoutEngine();

        final ViewInfo view = new ViewInfo(
                new ComplicationLayoutParams(
                        100,
                        100,
                        ComplicationLayoutParams.POSITION_TOP
                                | ComplicationLayoutParams.POSITION_END,
                        ComplicationLayoutParams.DIRECTION_DOWN,
                        0,
                        5),
                Complication.CATEGORY_STANDARD,
                mLayout);

        addComplication(engine, view);

        // Verify neither max height nor max width set.
        verifyChange(view, false, lp -> {
            assertThat(lp.matchConstraintMaxHeight).isEqualTo(0);
            assertThat(lp.matchConstraintMaxWidth).isEqualTo(0);
        });
    }

    /**
     * Ensures layout in a particular position updates.
     */
    @Test
    public void testRemoval() {
        final ComplicationLayoutEngine engine = createComplicationLayoutEngine();

        final ViewInfo firstViewInfo = new ViewInfo(
                new ComplicationLayoutParams(
                        100,
                        100,
                        ComplicationLayoutParams.POSITION_TOP
                                | ComplicationLayoutParams.POSITION_END,
                        ComplicationLayoutParams.DIRECTION_DOWN,
                        0),
                Complication.CATEGORY_STANDARD,
                mLayout);

        engine.addComplication(firstViewInfo.id, firstViewInfo.view, firstViewInfo.lp,
                firstViewInfo.category);

        final ViewInfo secondViewInfo = new ViewInfo(
                new ComplicationLayoutParams(
                        100,
                        100,
                        ComplicationLayoutParams.POSITION_TOP
                                | ComplicationLayoutParams.POSITION_END,
                        ComplicationLayoutParams.DIRECTION_DOWN,
                        0),
                Complication.CATEGORY_SYSTEM,
                mLayout);

        engine.addComplication(secondViewInfo.id, secondViewInfo.view, secondViewInfo.lp,
                secondViewInfo.category);

        firstViewInfo.clearInvocations();

        engine.removeComplication(secondViewInfo.id);
        verify(mLayout).removeView(eq(secondViewInfo.view));

        verifyChange(firstViewInfo, true, lp -> {
            assertThat(lp.topToTop == ConstraintLayout.LayoutParams.PARENT_ID).isTrue();
            assertThat(lp.endToEnd == ConstraintLayout.LayoutParams.PARENT_ID).isTrue();
        });
    }

    /**
     * Ensures a second removal of a complication is a no-op.
     */
    @Test
    public void testDoubleRemoval() {
        final ComplicationLayoutEngine engine = createComplicationLayoutEngine();

        final ViewInfo firstViewInfo = new ViewInfo(
                new ComplicationLayoutParams(
                        100,
                        100,
                        ComplicationLayoutParams.POSITION_TOP
                                | ComplicationLayoutParams.POSITION_END,
                        ComplicationLayoutParams.DIRECTION_DOWN,
                        0),
                Complication.CATEGORY_STANDARD,
                mLayout);

        engine.addComplication(firstViewInfo.id, firstViewInfo.view, firstViewInfo.lp,
                firstViewInfo.category);
        verify(mLayout).addView(firstViewInfo.view);

        assertThat(engine.removeComplication(firstViewInfo.id)).isTrue();
        verify(firstViewInfo.view).getParent();
        verify(mLayout).removeView(firstViewInfo.view);

        Mockito.clearInvocations(mLayout, firstViewInfo.view);
        assertThat(engine.removeComplication(firstViewInfo.id)).isFalse();
        verify(firstViewInfo.view, never()).getParent();
        verify(mLayout, never()).removeView(firstViewInfo.view);
    }

    @Test
    public void testGetViews() {
        final ComplicationLayoutEngine engine = createComplicationLayoutEngine();

        final ViewInfo topEndView = new ViewInfo(
                new ComplicationLayoutParams(
                        100,
                        100,
                        ComplicationLayoutParams.POSITION_TOP
                                | ComplicationLayoutParams.POSITION_END,
                        ComplicationLayoutParams.DIRECTION_DOWN,
                        0),
                Complication.CATEGORY_STANDARD,
                mLayout);

        addComplication(engine, topEndView);

        final ViewInfo topStartView = new ViewInfo(
                new ComplicationLayoutParams(
                        100,
                        100,
                        ComplicationLayoutParams.POSITION_TOP
                                | ComplicationLayoutParams.POSITION_START,
                        ComplicationLayoutParams.DIRECTION_DOWN,
                        0),
                Complication.CATEGORY_SYSTEM,
                mLayout);

        addComplication(engine, topStartView);

        final ViewInfo bottomEndView = new ViewInfo(
                new ComplicationLayoutParams(
                        100,
                        100,
                        ComplicationLayoutParams.POSITION_BOTTOM
                                | ComplicationLayoutParams.POSITION_END,
                        ComplicationLayoutParams.DIRECTION_START,
                        1),
                Complication.CATEGORY_SYSTEM,
                mLayout);

        addComplication(engine, bottomEndView);

        verifyViewsAtPosition(engine, ComplicationLayoutParams.POSITION_TOP, topStartView,
                topEndView);
        verifyViewsAtPosition(engine,
                ComplicationLayoutParams.POSITION_TOP | ComplicationLayoutParams.POSITION_START,
                topStartView);
        verifyViewsAtPosition(engine,
                ComplicationLayoutParams.POSITION_BOTTOM,
                bottomEndView);
    }

    private void verifyViewsAtPosition(ComplicationLayoutEngine engine, int position,
            ViewInfo... views) {
        final List<Integer> idList = engine.getViewsAtPosition(position).stream()
                .map(View::getId)
                .collect(Collectors.toList());

        assertThat(idList).containsExactlyElementsIn(
                Arrays.stream(views)
                        .map(viewInfo -> viewInfo.view.getId())
                        .collect(Collectors.toList()));
    }
}
