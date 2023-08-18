/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;


import android.graphics.Matrix;
import android.graphics.Region;
import android.platform.test.annotations.Presubmit;
import android.widget.LinearLayout;

import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.filters.SmallTest;

import com.android.frameworks.coretests.R;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * Test basic functions of ViewGroup.
 *
 * Build/Install/Run:
 *     atest FrameworksCoreTests:ViewGroupTest
 */
@Presubmit
@SmallTest
public class ViewGroupGetChildLocalHitRegionTest {
    @Rule
    public ActivityScenarioRule<ViewGroupTestActivity> mScenarioRule =
            new ActivityScenarioRule<>(ViewGroupTestActivity.class);

    private LinearLayout mRoot;
    private final int[] mRootLocation = new int[2];

    @Before
    public void setup() {
        mScenarioRule.getScenario().onActivity(activity -> {
            mRoot = activity.findViewById(R.id.linear_layout);
            mRoot.getLocationInWindow(mRootLocation);
        });
    }

    @Test
    public void testGetChildLocalHitRegion() {
        assertGetChildLocalHitRegion(R.id.view);
    }

    @Test
    public void testGetChildLocalHitRegion_withScale() {
        assertGetChildLocalHitRegion(R.id.view_scale);
    }

    @Test
    public void testGetChildLocalHitRegion_withTranslate() {
        assertGetChildLocalHitRegion(R.id.view_translate);
    }

    @Test
    public void testGetChildLocalHitRegion_overlap_noMotionEvent() {
        assertGetChildLocalHitRegion(R.id.view_overlap_bottom);
    }
    @Test
    public void testGetChildLocalHitRegion_overlap_withMotionEvent() {
        // In this case, view_cover_bottom is partially covered by the view_cover_top.
        // The returned region is the bounds of the bottom view subtract the bounds of the top view.
        assertGetChildLocalHitRegion(R.id.view_overlap_top, R.id.view_overlap_bottom);
    }

    @Test
    public void testGetChildLocalHitRegion_cover_withMotionEvent() {
        // In this case, view_cover_bottom is completely covered by the view_cover_top.
        // The returned region is expected to be empty.
        assertGetChildLocalHitRegionEmpty(R.id.view_cover_top, R.id.view_cover_bottom);
    }

    private void injectMotionEvent(View view, boolean isHover) {
        int[] location = new int[2];
        view.getLocationInWindow(location);

        float x = location[0] + view.getWidth() / 2f;
        float y = location[1] + view.getHeight() / 2f;

        int action = isHover ? MotionEvent.ACTION_HOVER_ENTER : MotionEvent.ACTION_DOWN;
        MotionEvent motionEvent = MotionEvent.obtain(/* downtime= */ 0, /* eventTime= */ 0, action,
                x, y, /* pressure= */ 0, /* size= */ 0, /* metaState= */ 0,
                /* xPrecision= */ 1, /* yPrecision= */ 1, /* deviceId= */0, /* edgeFlags= */0);

        View rootView = view.getRootView();
        rootView.dispatchPointerEvent(motionEvent);
    }

    private void assertGetChildLocalHitRegion(int viewId) {
        assertGetChildLocalHitRegion(viewId, /* isHover= */ true);
        assertGetChildLocalHitRegion(viewId, /* isHover= */ false);
    }

    /**
     * Assert ViewParent#getChildLocalHitRegion for a single view.
     * @param viewId the viewId of the tested view.
     * @param isHover if true, check the hit region of the hover events. Otherwise, check the hit
     *                region of the touch events.
     */
    private void assertGetChildLocalHitRegion(int viewId, boolean isHover) {
        mScenarioRule.getScenario().onActivity(activity -> {
            View view = activity.findViewById(viewId);

            Matrix actualMatrix = new Matrix();
            Region actualRegion = new Region(0, 0, view.getWidth(), view.getHeight());
            boolean actualNotEmpty = view.getParent()
                    .getChildLocalHitRegion(view, actualRegion, actualMatrix, isHover);

            int[] windowLocation = new int[2];
            view.getLocationInWindow(windowLocation);
            Matrix expectMatrix = new Matrix();
            expectMatrix.preScale(1 / view.getScaleX(), 1 / view.getScaleY());
            expectMatrix.preTranslate(-windowLocation[0], -windowLocation[1]);

            Region expectRegion = new Region(0, 0, view.getWidth(), view.getHeight());

            assertThat(actualNotEmpty).isTrue();
            assertThat(actualMatrix).isEqualTo(expectMatrix);
            assertThat(actualRegion).isEqualTo(expectRegion);
        });
    }

    private void assertGetChildLocalHitRegion(int viewIdTop, int viewIdBottom) {
        assertGetChildLocalHitRegion(viewIdTop, viewIdBottom, /* isHover= */ true);
        assertGetChildLocalHitRegion(viewIdTop, viewIdBottom, /* isHover= */ false);
    }

    /**
     * Assert ViewParent#getChildLocalHitRegion of a view that is covered by another view. It will
     * inject {@link MotionEvent}s to the view on top first and then get the hit region of the
     * bottom view.
     *
     * @param viewIdTop the view id of the test view on top.
     * @param viewIdBottom the view id of the test view at the bottom.
     * @param isHover if true, check the hit region of the hover events. Otherwise, check the hit
     *                region of the touch events.
     */
    private void assertGetChildLocalHitRegion(int viewIdTop, int viewIdBottom, boolean isHover) {
        mScenarioRule.getScenario().onActivity(activity -> {
            View viewTop = activity.findViewById(viewIdTop);
            View viewBottom = activity.findViewById(viewIdBottom);

            injectMotionEvent(viewTop, isHover);

            Matrix actualMatrix = new Matrix();
            Region actualRegion = new Region(0, 0, viewBottom.getWidth(), viewBottom.getHeight());
            boolean actualNotEmpty = viewBottom.getParent()
                    .getChildLocalHitRegion(viewBottom, actualRegion, actualMatrix, isHover);

            int[] windowLocation = new int[2];
            viewBottom.getLocationInWindow(windowLocation);
            Matrix expectMatrix = new Matrix();
            expectMatrix.preTranslate(-windowLocation[0], -windowLocation[1]);

            Region expectRegion = new Region(0, 0, viewBottom.getWidth(), viewBottom.getHeight());
            expectRegion.op(0, 0, viewTop.getWidth(), viewTop.getHeight(), Region.Op.DIFFERENCE);

            assertThat(actualNotEmpty).isTrue();
            assertThat(actualMatrix).isEqualTo(expectMatrix);
            assertThat(actualRegion).isEqualTo(expectRegion);
        });
    }

    private void assertGetChildLocalHitRegionEmpty(int viewIdTop, int viewIdBottom) {
        assertGetChildLocalHitRegionEmpty(viewIdTop, viewIdBottom, /* isHover= */ true);
        assertGetChildLocalHitRegionEmpty(viewIdTop, viewIdBottom, /* isHover= */ false);
    }

    /**
     * Assert ViewParent#getChildLocalHitRegion returns an empty region for a view that is
     * completely covered by another view. It will inject {@link MotionEvent}s to the view on top
     * first and then get the hit region of the
     * bottom view.
     *
     * @param viewIdTop the view id of the test view on top.
     * @param viewIdBottom the view id of the test view at the bottom.
     * @param isHover if true, check the hit region of the hover events. Otherwise, check the hit
     *                region of the touch events.
     */
    private void assertGetChildLocalHitRegionEmpty(int viewIdTop, int viewIdBottom,
            boolean isHover) {
        mScenarioRule.getScenario().onActivity(activity -> {
            View viewTop = activity.findViewById(viewIdTop);
            View viewBottom = activity.findViewById(viewIdBottom);

            injectMotionEvent(viewTop, isHover);

            Region actualRegion = new Region(0, 0, viewBottom.getWidth(), viewBottom.getHeight());
            boolean actualNotEmpty = viewBottom.getParent()
                    .getChildLocalHitRegion(viewBottom, actualRegion, new Matrix(), isHover);

            assertThat(actualNotEmpty).isFalse();
            assertThat(actualRegion.isEmpty()).isTrue();
        });
    }
}
