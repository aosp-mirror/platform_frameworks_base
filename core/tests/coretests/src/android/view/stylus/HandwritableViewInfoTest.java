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

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.view.HandwritingInitiator;
import android.view.View;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class HandwritableViewInfoTest {

    @Test
    public void constructorTest() {
        final Rect rect = new Rect(1, 2, 3, 4);
        final View view = createView(rect);
        final HandwritingInitiator.HandwritableViewInfo handwritableViewInfo =
                new HandwritingInitiator.HandwritableViewInfo(view);

        assertThat(handwritableViewInfo.getView()).isEqualTo(view);
        // It's labeled dirty by default.
        assertTrue(handwritableViewInfo.mIsDirty);
    }

    @Test
    public void update() {
        final Rect rect = new Rect(1, 2, 3, 4);
        final View view = createView(rect);
        final HandwritingInitiator.HandwritableViewInfo handwritableViewInfo =
                new HandwritingInitiator.HandwritableViewInfo(view);

        assertThat(handwritableViewInfo.getView()).isEqualTo(view);

        final boolean isViewInfoValid = handwritableViewInfo.update();

        assertTrue(isViewInfoValid);
        assertThat(handwritableViewInfo.getHandwritingArea()).isEqualTo(rect);
        assertFalse(handwritableViewInfo.mIsDirty);
    }

    @Test
    public void update_viewDisableAutoHandwriting() {
        final Rect rect = new Rect(1, 2, 3, 4);
        final View view = HandwritingTestUtil.createView(rect, false /* autoHandwritingEnabled */);
        final HandwritingInitiator.HandwritableViewInfo handwritableViewInfo =
                new HandwritingInitiator.HandwritableViewInfo(view);

        assertThat(handwritableViewInfo.getView()).isEqualTo(view);

        final boolean isViewInfoValid = handwritableViewInfo.update();

        // Return false because the view disabled autoHandwriting.
        assertFalse(isViewInfoValid);
        // The view disabled the autoHandwriting, and it won't update the handwriting area.
        assertThat(handwritableViewInfo.getHandwritingArea()).isNull();
    }

}
