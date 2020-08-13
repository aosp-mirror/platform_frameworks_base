/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.collection.render;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.testing.AndroidTestingRunner;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class ShadeViewDifferTest extends SysuiTestCase {
    private ShadeViewDiffer mDiffer;

    private FakeController mRootController = new FakeController(mContext, "RootController");
    private FakeController mController1 = new FakeController(mContext, "Controller1");
    private FakeController mController2 = new FakeController(mContext, "Controller2");
    private FakeController mController3 = new FakeController(mContext, "Controller3");
    private FakeController mController4 = new FakeController(mContext, "Controller4");
    private FakeController mController5 = new FakeController(mContext, "Controller5");
    private FakeController mController6 = new FakeController(mContext, "Controller6");
    private FakeController mController7 = new FakeController(mContext, "Controller7");

    @Mock
    ShadeViewDifferLogger mLogger;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mDiffer = new ShadeViewDiffer(mRootController, mLogger);
    }

    @Test
    public void testAddInitialViews() {
        // WHEN a spec is applied to an empty root
        // THEN the final tree matches the spec
        applySpecAndCheck(
                node(mController1),
                node(mController2,
                        node(mController3),
                        node(mController4)
                ),
                node(mController5)
        );
    }

    @Test
    public void testDetachViews() {
        // GIVEN a preexisting tree of controllers
        applySpecAndCheck(
                node(mController1),
                node(mController2,
                        node(mController3),
                        node(mController4)
                ),
                node(mController5)
        );

        // WHEN the new spec removes nodes
        // THEN the final tree matches the spec
        applySpecAndCheck(
                node(mController5)
        );
    }

    @Test
    public void testReparentChildren() {
        // GIVEN a preexisting tree of controllers
        applySpecAndCheck(
                node(mController1),
                node(mController2,
                        node(mController3),
                        node(mController4)
                ),
                node(mController5)
        );

        // WHEN the parents of the controllers are all shuffled around
        // THEN the final tree matches the spec
        applySpecAndCheck(
                node(mController1),
                node(mController4),
                node(mController3,
                        node(mController2)
                )
        );
    }

    @Test
    public void testReorderChildren() {
        // GIVEN a preexisting tree of controllers
        applySpecAndCheck(
                node(mController1),
                node(mController2),
                node(mController3),
                node(mController4)
        );

        // WHEN the children change order
        // THEN the final tree matches the spec
        applySpecAndCheck(
                node(mController3),
                node(mController2),
                node(mController4),
                node(mController1)
        );
    }

    @Test
    public void testRemovedGroupsAreKeptTogether() {
        // GIVEN a preexisting tree with a group
        applySpecAndCheck(
                node(mController1),
                node(mController2,
                        node(mController3),
                        node(mController4),
                        node(mController5)
                )
        );

        // WHEN the new spec removes the entire group
        applySpecAndCheck(
                node(mController1)
        );

        // THEN the group children are still attached to their parent
        assertEquals(mController2.getView(), mController3.getView().getParent());
        assertEquals(mController2.getView(), mController4.getView().getParent());
        assertEquals(mController2.getView(), mController5.getView().getParent());
    }

    @Test
    public void testUnmanagedViews() {
        // GIVEN a preexisting tree of controllers
        applySpecAndCheck(
                node(mController1),
                node(mController2,
                        node(mController3),
                        node(mController4)
                ),
                node(mController5)
        );

        // GIVEN some additional unmanaged views attached to the tree
        View unmanagedView1 = new View(mContext);
        View unmanagedView2 = new View(mContext);

        mRootController.getView().addView(unmanagedView1, 1);
        mController2.getView().addView(unmanagedView2, 0);

        // WHEN a new spec is applied with additional nodes
        // THEN the final tree matches the spec
        applySpecAndCheck(
                node(mController1),
                node(mController2,
                        node(mController3),
                        node(mController4),
                        node(mController6)
                ),
                node(mController5),
                node(mController7)
        );

        // THEN the unmanaged views have been pushed to the end of their parents
        assertEquals(unmanagedView1, mRootController.view.getChildAt(4));
        assertEquals(unmanagedView2, mController2.view.getChildAt(3));
    }

    private void applySpecAndCheck(NodeSpec spec) {
        mDiffer.applySpec(spec);
        checkMatchesSpec(spec);
    }

    private void applySpecAndCheck(SpecBuilder... children) {
        applySpecAndCheck(node(mRootController, children).build());
    }

    private void checkMatchesSpec(NodeSpec spec) {
        final NodeController parent = spec.getController();
        final List<NodeSpec> children = spec.getChildren();

        for (int i = 0; i < children.size(); i++) {
            NodeSpec childSpec = children.get(i);
            View view = parent.getChildAt(i);

            assertEquals(
                    "Child " + i + " of parent " + parent.getNodeLabel() + " should be "
                            + childSpec.getController().getNodeLabel() + " but is instead "
                            + (view != null ? mDiffer.getViewLabel(view) : "null"),
                    view,
                    childSpec.getController().getView());

            if (!childSpec.getChildren().isEmpty()) {
                checkMatchesSpec(childSpec);
            }
        }
    }

    private static class FakeController implements NodeController {

        public final FrameLayout view;
        private final String mLabel;

        FakeController(Context context, String label) {
            view = new FrameLayout(context);
            mLabel = label;
        }

        @NonNull
        @Override
        public String getNodeLabel() {
            return mLabel;
        }

        @NonNull
        @Override
        public FrameLayout getView() {
            return view;
        }

        @Override
        public int getChildCount() {
            return view.getChildCount();
        }

        @Override
        public View getChildAt(int index) {
            return view.getChildAt(index);
        }

        @Override
        public void addChildAt(@NonNull NodeController child, int index) {
            view.addView(child.getView(), index);
        }

        @Override
        public void moveChildTo(@NonNull NodeController child, int index) {
            view.removeView(child.getView());
            view.addView(child.getView(), index);
        }

        @Override
        public void removeChild(@NonNull NodeController child, boolean isTransfer) {
            view.removeView(child.getView());
        }
    }

    private static class SpecBuilder {
        private final NodeController mController;
        private final SpecBuilder[] mChildren;

        SpecBuilder(NodeController controller, SpecBuilder... children) {
            mController = controller;
            mChildren = children;
        }

        public NodeSpec build() {
            return build(null);
        }

        public NodeSpec build(@Nullable NodeSpec parent) {
            final NodeSpecImpl spec = new NodeSpecImpl(parent, mController);
            for (SpecBuilder childBuilder : mChildren) {
                spec.getChildren().add(childBuilder.build(spec));
            }
            return spec;
        }
    }

    private static SpecBuilder node(NodeController controller, SpecBuilder... children) {
        return new SpecBuilder(controller, children);
    }
}
