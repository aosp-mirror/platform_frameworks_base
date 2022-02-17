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

package android.view.stylus;

import static android.view.stylus.HandwritingTestUtil.createView;

import static com.google.common.truth.Truth.assertThat;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.view.HandwritingInitiator;
import android.view.View;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;


/**
 * Tests for {@link HandwritingInitiator.HandwritingAreaTracker}
 *
 * Build/Install/Run:
 *  atest FrameworksCoreTests:android.view.stylus.HandwritingAreaTrackerTest
 */
@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class HandwritingAreaTrackerTest {
    HandwritingInitiator.HandwritingAreaTracker mHandwritingAreaTracker;
    Context mContext;

    @Before
    public void setup() {
        final Instrumentation mInstrumentation = InstrumentationRegistry.getInstrumentation();
        mContext = mInstrumentation.getTargetContext();
        mHandwritingAreaTracker = new HandwritingInitiator.HandwritingAreaTracker();
    }

    @Test
    public void updateHandwritingAreaForView_singleView() {
        Rect rect = new Rect(0, 0, 100, 100);
        View view = createView(rect);
        mHandwritingAreaTracker.updateHandwritingAreaForView(view);

        List<HandwritingInitiator.HandwritableViewInfo> viewInfos =
                mHandwritingAreaTracker.computeViewInfos();

        assertThat(viewInfos.size()).isEqualTo(1);
        assertThat(viewInfos.get(0).getHandwritingArea()).isEqualTo(rect);
        assertThat(viewInfos.get(0).getView()).isEqualTo(view);
    }

    @Test
    public void updateHandwritingAreaForView_multipleViews() {
        Rect rect1 = new Rect(0, 0, 100, 100);
        Rect rect2 = new Rect(100, 100, 200, 200);

        View view1 = createView(rect1);
        View view2 = createView(rect2);
        mHandwritingAreaTracker.updateHandwritingAreaForView(view1);
        mHandwritingAreaTracker.updateHandwritingAreaForView(view2);

        List<HandwritingInitiator.HandwritableViewInfo> viewInfos =
                mHandwritingAreaTracker.computeViewInfos();

        assertThat(viewInfos.size()).isEqualTo(2);
        assertThat(viewInfos.get(0).getView()).isEqualTo(view1);
        assertThat(viewInfos.get(0).getHandwritingArea()).isEqualTo(rect1);

        assertThat(viewInfos.get(1).getView()).isEqualTo(view2);
        assertThat(viewInfos.get(1).getHandwritingArea()).isEqualTo(rect2);
    }

    @Test
    public void updateHandwritingAreaForView_afterDisableAutoHandwriting() {
        Rect rect1 = new Rect(0, 0, 100, 100);
        Rect rect2 = new Rect(100, 100, 200, 200);

        View view1 = createView(rect1);
        View view2 = createView(rect2);
        mHandwritingAreaTracker.updateHandwritingAreaForView(view1);
        mHandwritingAreaTracker.updateHandwritingAreaForView(view2);

        // There should be 2 views tracked.
        assertThat(mHandwritingAreaTracker.computeViewInfos().size()).isEqualTo(2);

        // Disable autoHandwriting for view1 and update handwriting area.
        view1.setAutoHandwritingEnabled(false);
        mHandwritingAreaTracker.updateHandwritingAreaForView(view1);

        List<HandwritingInitiator.HandwritableViewInfo> viewInfos =
                mHandwritingAreaTracker.computeViewInfos();
        // The view1 has disabled the autoHandwriting, it's not tracked anymore.
        assertThat(viewInfos.size()).isEqualTo(1);

        // view2 is still tracked.
        assertThat(viewInfos.get(0).getView()).isEqualTo(view2);
        assertThat(viewInfos.get(0).getHandwritingArea()).isEqualTo(rect2);
    }

    @Test
    public void updateHandwritingAreaForView_removesInactiveView() {
        Rect rect1 = new Rect(0, 0, 100, 100);
        Rect rect2 = new Rect(100, 100, 200, 200);

        View view1 = createView(rect1);
        View view2 = createView(rect2);
        mHandwritingAreaTracker.updateHandwritingAreaForView(view1);
        mHandwritingAreaTracker.updateHandwritingAreaForView(view2);

        // There should be 2 viewInfos tracked.
        assertThat(mHandwritingAreaTracker.computeViewInfos().size()).isEqualTo(2);

        // Disable autoHandwriting for view1, but update handwriting area for view2.
        view1.setAutoHandwritingEnabled(false);
        mHandwritingAreaTracker.updateHandwritingAreaForView(view2);

        List<HandwritingInitiator.HandwritableViewInfo> viewInfos =
                mHandwritingAreaTracker.computeViewInfos();
        // The view1 has disabled the autoHandwriting, it's not tracked anymore.
        assertThat(viewInfos.size()).isEqualTo(1);

        // view2 is still tracked.
        assertThat(viewInfos.get(0).getView()).isEqualTo(view2);
        assertThat(viewInfos.get(0).getHandwritingArea()).isEqualTo(rect2);
    }
}
