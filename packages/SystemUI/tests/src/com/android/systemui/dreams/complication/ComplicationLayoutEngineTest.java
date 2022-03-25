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
package com.android.systemui.dreams.complication;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.testing.AndroidTestingRunner;
import android.view.View;

import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.test.filters.SmallTest;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.touch.TouchInsetManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.function.Consumer;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class ComplicationLayoutEngineTest extends SysuiTestCase {
    @Mock
    ConstraintLayout mLayout;

    @Mock
    TouchInsetManager.TouchInsetSession mTouchSession;

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

        final ComplicationLayoutEngine engine =
                new ComplicationLayoutEngine(mLayout, 0, mTouchSession, 0, 0);
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

        final ComplicationLayoutEngine engine =
                new ComplicationLayoutEngine(mLayout, 0, mTouchSession, 0, 0);
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
        final ComplicationLayoutEngine engine =
                new ComplicationLayoutEngine(mLayout, 0, mTouchSession, 0, 0);

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
        final ComplicationLayoutEngine engine =
                new ComplicationLayoutEngine(mLayout, 0, mTouchSession, 0, 0);

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
     * Ensures margin is applied
     */
    @Test
    public void testMargin() {
        final int margin = 5;
        final ComplicationLayoutEngine engine =
                new ComplicationLayoutEngine(mLayout, margin, mTouchSession, 0, 0);

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

        // The first added view should now be underneath the second view.
        verifyChange(firstViewInfo, false, lp -> {
            assertThat(lp.topToBottom == thirdViewInfo.view.getId()).isTrue();
            assertThat(lp.endToEnd == ConstraintLayout.LayoutParams.PARENT_ID).isTrue();
            assertThat(lp.topMargin).isEqualTo(margin);
        });

        // The second view should be in underneath the third view.
        verifyChange(secondViewInfo, false, lp -> {
            assertThat(lp.endToStart == thirdViewInfo.view.getId()).isTrue();
            assertThat(lp.topToTop == ConstraintLayout.LayoutParams.PARENT_ID).isTrue();
            assertThat(lp.getMarginEnd()).isEqualTo(margin);
        });

        // The third view should be in at the top.
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
     * Ensures layout in a particular position updates.
     */
    @Test
    public void testRemoval() {
        final ComplicationLayoutEngine engine =
                new ComplicationLayoutEngine(mLayout, 0, mTouchSession, 0, 0);

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
        final ComplicationLayoutEngine engine =
                new ComplicationLayoutEngine(mLayout, 0, mTouchSession, 0, 0);

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
}
