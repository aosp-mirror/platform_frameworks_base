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

package com.android.internal.widget;


import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.annotations.Presubmit;
import android.platform.test.flag.junit.SetFlagsRule;
import android.view.Gravity;
import android.view.View;
import android.view.View.MeasureSpec;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.flags.Flags;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import com.google.common.truth.Expect;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

/**
 * Tests the consistency of {@link NotificationOptimizedLinearLayout}'s onMeasure and onLayout
 * implementations with the behavior of the standard Android LinearLayout.
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
@EnableFlags(Flags.FLAG_NOTIF_LINEARLAYOUT_OPTIMIZED)
@Presubmit
public class NotificationOptimizedLinearLayoutComparisonTest {

    @Rule
    public final Expect mExpect = Expect.create();

    private static final int[] ORIENTATIONS = {LinearLayout.VERTICAL, LinearLayout.HORIZONTAL};
    private static final int EXACT_SPEC = MeasureSpec.makeMeasureSpec(500,
            MeasureSpec.EXACTLY);
    private static final int AT_MOST_SPEC = MeasureSpec.makeMeasureSpec(500,
            MeasureSpec.AT_MOST);

    private static final int[] MEASURE_SPECS = {EXACT_SPEC, AT_MOST_SPEC};

    private static final int[] GRAVITIES =
            {Gravity.NO_GRAVITY, Gravity.TOP, Gravity.LEFT, Gravity.CENTER};

    private static final int[] LAYOUT_PARAMS = {MATCH_PARENT, WRAP_CONTENT, 0, 50};
    private static final int[] CHILD_WEIGHTS = {0, 1};

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    private Context mContext;

    @Before
    public void before() {
        mContext = InstrumentationRegistry.getTargetContext();
    }

    @Test
    public void test() throws Throwable {
        for (int orientation : ORIENTATIONS) {
            for (int widthSpec : MEASURE_SPECS) {
                for (int heightSpec : MEASURE_SPECS) {
                    for (int firstChildGravity : GRAVITIES) {
                        for (int secondChildGravity : GRAVITIES) {
                            for (int firstChildLayoutWidth : LAYOUT_PARAMS) {
                                for (int firstChildLayoutHeight : LAYOUT_PARAMS) {
                                    for (int secondChildLayoutWidth : LAYOUT_PARAMS) {
                                        for (int secondChildLayoutHeight : LAYOUT_PARAMS) {
                                            for (int firstChildWeight : CHILD_WEIGHTS) {
                                                for (int secondChildWeight : CHILD_WEIGHTS) {
                                                    executeTest(/*testSpec =*/createTestSpec(
                                                            orientation,
                                                            widthSpec, heightSpec,
                                                            firstChildLayoutWidth,
                                                            firstChildLayoutHeight,
                                                            secondChildLayoutWidth,
                                                            secondChildLayoutHeight,
                                                            firstChildGravity,
                                                            secondChildGravity,
                                                            firstChildWeight,
                                                            secondChildWeight));
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private void executeTest(TestSpec testSpec) {
        // GIVEN
        final List<View> controlChildren =
                new ArrayList<>();
        final List<View> testChildren =
                new ArrayList<>();

        controlChildren.add(
                buildChildView(
                        testSpec.mFirstChildLayoutWidth,
                        testSpec.mFirstChildLayoutHeight,
                        testSpec.mFirstChildGravity,
                        testSpec.mFirstChildWeight));
        controlChildren.add(
                buildChildView(
                        testSpec.mSecondChildLayoutWidth,
                        testSpec.mSecondChildLayoutHeight,
                        testSpec.mSecondChildGravity,
                        testSpec.mSecondChildWeight));

        testChildren.add(
                buildChildView(
                        testSpec.mFirstChildLayoutWidth,
                        testSpec.mFirstChildLayoutHeight,
                        testSpec.mFirstChildGravity,
                        testSpec.mFirstChildWeight));
        testChildren.add(
                buildChildView(
                        testSpec.mSecondChildLayoutWidth,
                        testSpec.mSecondChildLayoutHeight,
                        testSpec.mSecondChildGravity,
                        testSpec.mSecondChildWeight));

        final LinearLayout controlContainer = buildLayout(false,
                testSpec.mOrientation,
                controlChildren);

        final LinearLayout testContainer = buildLayout(true,
                testSpec.mOrientation,
                testChildren);

        // WHEN
        controlContainer.measure(testSpec.mWidthSpec, testSpec.mHeightSpec);
        testContainer.measure(testSpec.mWidthSpec, testSpec.mHeightSpec);
        controlContainer.layout(0, 0, 1000, 1000);
        testContainer.layout(0, 0, 1000, 1000);
        // THEN
        assertLayoutsEqual("Test Case:" + testSpec, controlContainer, testContainer);
    }

    private static class TestSpec {
        private final int mOrientation;
        private final int mWidthSpec;
        private final int mHeightSpec;
        private final int mFirstChildLayoutWidth;
        private final int mFirstChildLayoutHeight;
        private final int mSecondChildLayoutWidth;
        private final int mSecondChildLayoutHeight;
        private final int mFirstChildGravity;
        private final int mSecondChildGravity;
        private final int mFirstChildWeight;
        private final int mSecondChildWeight;

        TestSpec(
                int orientation,
                int widthSpec,
                int heightSpec,
                int firstChildLayoutWidth,
                int firstChildLayoutHeight,
                int secondChildLayoutWidth,
                int secondChildLayoutHeight,
                int firstChildGravity,
                int secondChildGravity,
                int firstChildWeight,
                int secondChildWeight) {
            mOrientation = orientation;
            mWidthSpec = widthSpec;
            mHeightSpec = heightSpec;
            mFirstChildLayoutWidth = firstChildLayoutWidth;
            mFirstChildLayoutHeight = firstChildLayoutHeight;
            mSecondChildLayoutWidth = secondChildLayoutWidth;
            mSecondChildLayoutHeight = secondChildLayoutHeight;
            mFirstChildGravity = firstChildGravity;
            mSecondChildGravity = secondChildGravity;
            mFirstChildWeight = firstChildWeight;
            mSecondChildWeight = secondChildWeight;
        }

        @Override
        public String toString() {
            return "TestSpec{"
                    + "mOrientation=" + orientationToString(mOrientation)
                    + ", mWidthSpec=" + MeasureSpec.toString(mWidthSpec)
                    + ", mHeightSpec=" + MeasureSpec.toString(mHeightSpec)
                    + ", mFirstChildLayoutWidth=" + sizeToString(mFirstChildLayoutWidth)
                    + ", mFirstChildLayoutHeight=" + sizeToString(mFirstChildLayoutHeight)
                    + ", mSecondChildLayoutWidth=" + sizeToString(mSecondChildLayoutWidth)
                    + ", mSecondChildLayoutHeight=" + sizeToString(mSecondChildLayoutHeight)
                    + ", mFirstChildGravity=" + mFirstChildGravity
                    + ", mSecondChildGravity=" + mSecondChildGravity
                    + ", mFirstChildWeight=" + mFirstChildWeight
                    + ", mSecondChildWeight=" + mSecondChildWeight
                    + '}';
        }

        private String orientationToString(int orientation) {
            if (orientation == LinearLayout.VERTICAL) {
                return "vertical";
            } else if (orientation == LinearLayout.HORIZONTAL) {
                return "horizontal";
            }
            throw new IllegalArgumentException();
        }

        private String sizeToString(int size) {
            if (size == WRAP_CONTENT) {
                return "wrap-content";
            }
            if (size == MATCH_PARENT) {
                return "match-parent";
            }
            return String.valueOf(size);
        }
    }

    private LinearLayout buildLayout(boolean isNotificationOptimized,
            @LinearLayout.OrientationMode int orientation, List<View> children) {
        final LinearLayout linearLayout;
        if (isNotificationOptimized) {
            linearLayout = new NotificationOptimizedLinearLayout(mContext);
        } else {
            linearLayout = new LinearLayout(mContext);
        }
        linearLayout.setOrientation(orientation);
        for (int i = 0; i < children.size(); i++) {
            linearLayout.addView(children.get(i));
        }
        return linearLayout;
    }

    private void assertLayoutsEqual(String testCase, View controlView, View testView) {
        mExpect.withMessage("MeasuredWidths are not equal. Test Case:" + testCase)
                .that(testView.getMeasuredWidth()).isEqualTo(controlView.getMeasuredWidth());
        mExpect.withMessage("MeasuredHeights are not equal. Test Case:" + testCase)
                .that(testView.getMeasuredHeight()).isEqualTo(controlView.getMeasuredHeight());
        mExpect.withMessage("Left Positions are not equal. Test Case:" + testCase)
                .that(testView.getLeft()).isEqualTo(controlView.getLeft());
        mExpect.withMessage("Top Positions are not equal. Test Case:" + testCase)
                .that(testView.getTop()).isEqualTo(controlView.getTop());
        if (controlView instanceof ViewGroup && testView instanceof ViewGroup) {
            final ViewGroup controlGroup = (ViewGroup) controlView;
            final ViewGroup testGroup = (ViewGroup) testView;
            // Test and Control Views should be identical by hierarchy for the comparison.
            // That's why mExpect is not used here for assertion.
            assertEquals(controlGroup.getChildCount(), testGroup.getChildCount());

            for (int i = 0; i < controlGroup.getChildCount(); i++) {
                View controlChild = controlGroup.getChildAt(i);
                View testChild = testGroup.getChildAt(i);

                assertLayoutsEqual(testCase, controlChild, testChild);
            }
        }
    }

    private static class TestView extends View {
        TestView(Context context) {
            super(context);
        }

        @Override
        public int getBaseline() {
            return 5;
        }
    }


    private TestSpec createTestSpec(int orientation,
            int widthSpec, int heightSpec,
            int firstChildLayoutWidth, int firstChildLayoutHeight, int secondChildLayoutWidth,
            int secondChildLayoutHeight, int firstChildGravity, int secondChildGravity,
            int firstChildWeight, int secondChildWeight) {

        return new TestSpec(
                orientation,
                widthSpec, heightSpec,
                firstChildLayoutWidth,
                firstChildLayoutHeight,
                secondChildLayoutWidth,
                secondChildLayoutHeight,
                firstChildGravity,
                secondChildGravity,
                firstChildWeight,
                secondChildWeight);
    }

    private View buildChildView(int childLayoutWidth, int childLayoutHeight,
            int childGravity, int childWeight) {
        final View childView = new TestView(mContext);
        // Set desired size using LayoutParams
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(childLayoutWidth,
                childLayoutHeight, childWeight);
        params.gravity = childGravity;
        childView.setLayoutParams(params);
        return childView;
    }
}
