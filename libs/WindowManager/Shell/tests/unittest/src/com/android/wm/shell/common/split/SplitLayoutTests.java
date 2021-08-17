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

package com.android.wm.shell.common.split;

import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import android.content.res.Configuration;
import android.graphics.Rect;
import android.view.SurfaceControl;

import androidx.test.annotation.UiThreadTest;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.internal.policy.DividerSnapAlgorithm;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.ShellTestCase;
import com.android.wm.shell.common.DisplayImeController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** Tests for {@link SplitLayout} */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class SplitLayoutTests extends ShellTestCase {
    @Mock SplitLayout.SplitLayoutHandler mSplitLayoutHandler;
    @Mock SurfaceControl mRootLeash;
    @Mock DisplayImeController mDisplayImeController;
    @Mock ShellTaskOrganizer mTaskOrganizer;
    private SplitLayout mSplitLayout;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mSplitLayout = new SplitLayout(
                "TestSplitLayout",
                mContext,
                getConfiguration(false),
                mSplitLayoutHandler,
                b -> b.setParent(mRootLeash),
                mDisplayImeController,
                mTaskOrganizer);
    }

    @Test
    @UiThreadTest
    public void testUpdateConfiguration() {
        mSplitLayout.init();
        assertThat(mSplitLayout.updateConfiguration(getConfiguration(false))).isFalse();
        assertThat(mSplitLayout.updateConfiguration(getConfiguration(true))).isTrue();
    }

    @Test
    public void testUpdateDivideBounds() {
        mSplitLayout.updateDivideBounds(anyInt());
        verify(mSplitLayoutHandler).onBoundsChanging(any(SplitLayout.class));
    }

    @Test
    public void testSetDividePosition() {
        mSplitLayout.setDividePosition(anyInt());
        verify(mSplitLayoutHandler).onBoundsChanged(any(SplitLayout.class));
    }

    @Test
    public void testOnDoubleTappedDivider() {
        mSplitLayout.onDoubleTappedDivider();
        verify(mSplitLayoutHandler).onDoubleTappedDivider();
    }

    @Test
    @UiThreadTest
    public void testSnapToDismissTarget() {
        // verify it callbacks properly when the snap target indicates dismissing split.
        DividerSnapAlgorithm.SnapTarget snapTarget = getSnapTarget(0 /* position */,
                DividerSnapAlgorithm.SnapTarget.FLAG_DISMISS_START);
        mSplitLayout.snapToTarget(0 /* currentPosition */, snapTarget);
        verify(mSplitLayoutHandler).onSnappedToDismiss(eq(false));
        snapTarget = getSnapTarget(0 /* position */,
                DividerSnapAlgorithm.SnapTarget.FLAG_DISMISS_END);
        mSplitLayout.snapToTarget(0 /* currentPosition */, snapTarget);
        verify(mSplitLayoutHandler).onSnappedToDismiss(eq(true));
    }

    private static Configuration getConfiguration(boolean isLandscape) {
        final Configuration configuration = new Configuration();
        configuration.unset();
        configuration.orientation = isLandscape ? ORIENTATION_LANDSCAPE : ORIENTATION_PORTRAIT;
        configuration.windowConfiguration.setBounds(
                new Rect(0, 0, isLandscape ? 2160 : 1080, isLandscape ? 1080 : 2160));
        return configuration;
    }

    private static DividerSnapAlgorithm.SnapTarget getSnapTarget(int position, int flag) {
        return new DividerSnapAlgorithm.SnapTarget(
                position /* position */, position /* taskPosition */, flag);
    }
}
