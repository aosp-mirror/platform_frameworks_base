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
import android.widget.TextView;
import android.widget.flags.Flags;

import androidx.test.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

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
    private static final int[] CHILD_MARGINS = {0, 10, -10};
    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    private Context mContext;

    @Before
    public void before() {
        mContext = InstrumentationRegistry.getTargetContext();
    }


    @Test
    public void test() throws Throwable {
        final List<View> controlChildren =
                new ArrayList<>();
        final List<View> testChildren =
                new ArrayList<>();

        final View controlChild1 = buildChildView();
        final View controlChild2 = buildChildView();
        controlChildren.add(controlChild1);
        controlChildren.add(controlChild2);

        final View testChild1 = buildChildView();
        final View testChild2 = buildChildView();
        testChildren.add(testChild1);
        testChildren.add(testChild2);

        final LinearLayout controlContainer = buildLayout(false, controlChildren);

        final LinearLayout testContainer = buildLayout(true, testChildren);

        final LinearLayout.LayoutParams firstChildLayoutParams = new LinearLayout.LayoutParams(0,
                0);
        final LinearLayout.LayoutParams secondChildLayoutParams = new LinearLayout.LayoutParams(0,
                0);
        controlChild1.setLayoutParams(firstChildLayoutParams);
        controlChild2.setLayoutParams(secondChildLayoutParams);
        testChild1.setLayoutParams(firstChildLayoutParams);
        testChild2.setLayoutParams(secondChildLayoutParams);

        for (int orientation : ORIENTATIONS) {
            controlContainer.setOrientation(orientation);
            testContainer.setOrientation(orientation);

            for (int firstChildLayoutWidth : LAYOUT_PARAMS) {
                firstChildLayoutParams.width = firstChildLayoutWidth;
                for (int firstChildLayoutHeight : LAYOUT_PARAMS) {
                    firstChildLayoutParams.height = firstChildLayoutHeight;

                    for (int secondChildLayoutWidth : LAYOUT_PARAMS) {
                        secondChildLayoutParams.width = secondChildLayoutWidth;
                        for (int secondChildLayoutHeight : LAYOUT_PARAMS) {
                            secondChildLayoutParams.height = secondChildLayoutHeight;

                            for (int firstChildMargin : CHILD_MARGINS) {
                                firstChildLayoutParams.setMargins(firstChildMargin,
                                        firstChildMargin, firstChildMargin, firstChildMargin);
                                for (int secondChildMargin : CHILD_MARGINS) {
                                    secondChildLayoutParams.setMargins(secondChildMargin,
                                            secondChildMargin, secondChildMargin,
                                            secondChildMargin);

                                    for (int firstChildGravity : GRAVITIES) {
                                        firstChildLayoutParams.gravity = firstChildGravity;
                                        for (int secondChildGravity : GRAVITIES) {
                                            secondChildLayoutParams.gravity = secondChildGravity;

                                            for (int firstChildWeight : CHILD_WEIGHTS) {
                                                firstChildLayoutParams.weight = firstChildWeight;
                                                for (int secondChildWeight : CHILD_WEIGHTS) {
                                                    secondChildLayoutParams.weight =
                                                            secondChildWeight;

                                                    for (int widthSpec : MEASURE_SPECS) {
                                                        for (int heightSpec : MEASURE_SPECS) {
                                                            executeTest(controlContainer,
                                                                    testContainer,
                                                                    createTestSpec(
                                                                            orientation,
                                                                            widthSpec, heightSpec,
                                                                            firstChildLayoutWidth,
                                                                            firstChildLayoutHeight,
                                                                            secondChildLayoutWidth,
                                                                            secondChildLayoutHeight,
                                                                            firstChildGravity,
                                                                            secondChildGravity,
                                                                            firstChildWeight,
                                                                            secondChildWeight,
                                                                            firstChildMargin,
                                                                            secondChildMargin)
                                                            );
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
        }
    }

    private void executeTest(LinearLayout controlContainer, LinearLayout testContainer,
            TestSpec testSpec) {
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
        private final int mFirstChildMargin;
        private final int mSecondChildMargin;

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
                int secondChildWeight,
                int firstChildMargin,
                int secondChildMargin) {
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
            mFirstChildMargin = firstChildMargin;
            mSecondChildMargin = secondChildMargin;
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
                    + ", mFirstChildMargin=" + mFirstChildMargin
                    + ", mSecondChildMargin=" + mSecondChildMargin
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

    private LinearLayout buildLayout(boolean isNotificationOptimized, List<View> children) {
        final LinearLayout linearLayout;
        if (isNotificationOptimized) {
            linearLayout = new NotificationOptimizedLinearLayout(mContext);
        } else {
            linearLayout = new LinearLayout(mContext);
        }
        for (int i = 0; i < children.size(); i++) {
            linearLayout.addView(children.get(i));
        }
        return linearLayout;
    }

    private void assertLayoutsEqual(String testCase, View controlView, View testView) {
        mExpect.withMessage(
                        "MeasuredWidths are not equal. Test Case:" + testCase)
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

    private TestSpec createTestSpec(int orientation,
            int widthSpec, int heightSpec,
            int firstChildLayoutWidth, int firstChildLayoutHeight, int secondChildLayoutWidth,
            int secondChildLayoutHeight, int firstChildGravity, int secondChildGravity,
            int firstChildWeight, int secondChildWeight, int firstChildMargin,
            int secondChildMargin) {

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
                secondChildWeight,
                firstChildMargin,
                secondChildMargin);
    }

    private View buildChildView() {
        final View childView = new TextView(mContext);
        // this is initial value. We are going to mutate this layout params during the test.
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(MATCH_PARENT,
                WRAP_CONTENT);
        childView.setLayoutParams(params);
        return childView;
    }
}
