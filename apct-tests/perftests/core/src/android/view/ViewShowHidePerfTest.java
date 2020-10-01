/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.view;

import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;
import android.perftests.utils.PerfTestActivity;
import android.view.View.MeasureSpec;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.LargeTest;
import androidx.test.rule.ActivityTestRule;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.ArrayList;
import java.util.List;

@RunWith(Parameterized.class)
@LargeTest
public class ViewShowHidePerfTest {

    @Rule
    public ActivityTestRule<PerfTestActivity> mActivityRule =
            new ActivityTestRule<>(PerfTestActivity.class);

    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    public Context getContext() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    static abstract class SubTreeFactory {
        String mName;
        SubTreeFactory(String name) { mName = name; }

        abstract View create(Context context, int depth);

        @Override
        public String toString() {
            return mName;
        }
    }

    private static SubTreeFactory[] sSubTreeFactories = new SubTreeFactory[] {
            new SubTreeFactory("NestedLinearLayoutTree") {
                private int mColorToggle = 0;

                private void createNestedLinearLayoutTree(Context context, LinearLayout parent,
                        int remainingDepth) {
                    if (remainingDepth <= 0) {
                        mColorToggle = (mColorToggle + 1) % 4;
                        parent.setBackgroundColor((mColorToggle < 2) ? Color.RED : Color.BLUE);
                        return;
                    }

                    boolean vertical = remainingDepth % 2 == 0;
                    parent.setOrientation(vertical ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);

                    for (int i = 0; i < 2; i++) {
                        LinearLayout child = new LinearLayout(context);
                        // vertical: match parent in x axis, horizontal: y axis.
                        parent.addView(child, new LinearLayout.LayoutParams(
                                (vertical ? ViewGroup.LayoutParams.MATCH_PARENT : 0),
                                (vertical ? 0 : ViewGroup.LayoutParams.MATCH_PARENT),
                                1.0f));

                        createNestedLinearLayoutTree(context, child, remainingDepth - 1);
                    }
                }

                @Override
                public View create(Context context, int depth) {
                    LinearLayout root = new LinearLayout(context);
                    createNestedLinearLayoutTree(context, root, depth - 1);
                    return root;
                }
            },
            new SubTreeFactory("ImageViewList") {
                @Override
                public View create(Context context, int depth) {
                    LinearLayout root = new LinearLayout(context);
                    root.setOrientation(LinearLayout.HORIZONTAL);
                    int childCount = (int) Math.pow(2, depth);
                    for (int i = 0; i < childCount; i++) {
                        ImageView imageView = new ImageView(context);
                        root.addView(imageView, new LinearLayout.LayoutParams(
                                0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f));
                        imageView.setImageDrawable(new ColorDrawable(Color.RED));
                    }
                    return root;
                }
            },
    };


    @Parameterized.Parameters(name = "Factory:{0},depth:{1}")
    public static Iterable<Object[]> params() {
        List<Object[]> params = new ArrayList<>();
        for (int depth : new int[] { 6 }) {
            for (SubTreeFactory subTreeFactory : sSubTreeFactories) {
                params.add(new Object[]{ subTreeFactory, depth });
            }
        }
        return params;
    }

    private final View mChild;

    public ViewShowHidePerfTest(SubTreeFactory subTreeFactory, int depth) {
        mChild = subTreeFactory.create(getContext(), depth);
    }

    interface TestCallback {
        void run(BenchmarkState state, int width, int height, ViewGroup parent, View child);
    }

    private void testParentWithChild(TestCallback callback) throws Throwable {
        mActivityRule.runOnUiThread(() -> {
            final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();

            FrameLayout parent = new FrameLayout(getContext());
            mActivityRule.getActivity().setContentView(parent);

            final int width = 1000;
            final int height = 1000;
            layout(width, height, parent);

            callback.run(state, width, height, parent, mChild);
        });
    }

    private void updateAndValidateDisplayList(View view) {
        boolean hasDisplayList = view.updateDisplayListIfDirty().hasDisplayList();
        assertTrue(hasDisplayList);
    }

    private void layout(int width, int height, View view) {
        view.measure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        view.layout(0, 0, height, width);
    }

    @Test
    public void testRemove() throws Throwable {
        testParentWithChild((state, width, height, parent, child) -> {
            while (state.keepRunning()) {
                state.pauseTiming();
                updateAndValidateDisplayList(parent); // Note, done to be safe, likely not needed
                parent.addView(child);
                layout(width, height, child);
                updateAndValidateDisplayList(parent);
                state.resumeTiming();

                parent.removeAllViews();
            }
        });
    }

    @Test
    public void testAdd() throws Throwable {
        testParentWithChild((state, width, height, parent, child) -> {
            while (state.keepRunning()) {
                state.pauseTiming();
                layout(width, height, child); // Note, done to be safe, likely not needed
                updateAndValidateDisplayList(parent); // Note, done to be safe, likely not needed
                parent.removeAllViews();
                updateAndValidateDisplayList(parent);
                state.resumeTiming();

                parent.addView(child);
            }
        });
    }

    @Test
    public void testRecordAfterAdd() throws Throwable {
        testParentWithChild((state, width, height, parent, child) -> {
            while (state.keepRunning()) {
                state.pauseTiming();
                parent.removeAllViews();
                updateAndValidateDisplayList(parent); // Note, done to be safe, likely not needed
                parent.addView(child);
                layout(width, height, child);
                state.resumeTiming();

                updateAndValidateDisplayList(parent);
            }
        });
    }

    private void testVisibility(int fromVisibility, int toVisibility) throws Throwable {
        testParentWithChild((state, width, height, parent, child) -> {
            parent.addView(child);

            while (state.keepRunning()) {
                state.pauseTiming();
                layout(width, height, parent);
                updateAndValidateDisplayList(parent);
                child.setVisibility(fromVisibility);
                layout(width, height, parent);
                updateAndValidateDisplayList(parent);
                state.resumeTiming();

                child.setVisibility(toVisibility);
            }
        });
    }

    @Test
    public void testInvisibleToVisible() throws Throwable {
        testVisibility(View.INVISIBLE, View.VISIBLE);
    }

    @Test
    public void testVisibleToInvisible() throws Throwable {
        testVisibility(View.VISIBLE, View.INVISIBLE);
    }
    @Test
    public void testGoneToVisible() throws Throwable {
        testVisibility(View.GONE, View.VISIBLE);
    }

    @Test
    public void testVisibleToGone() throws Throwable {
        testVisibility(View.VISIBLE, View.GONE);
    }
}
