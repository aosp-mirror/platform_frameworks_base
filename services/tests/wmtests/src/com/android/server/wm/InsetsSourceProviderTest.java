/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.wm;

import static android.view.InsetsState.TYPE_TOP_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;

import static org.junit.Assert.assertEquals;

import android.graphics.Insets;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.view.InsetsSource;

import androidx.test.filters.FlakyTest;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;

@SmallTest
@FlakyTest(detail = "Promote once confirmed non-flaky")
@Presubmit
public class InsetsSourceProviderTest extends WindowTestsBase {

    private InsetsSource mSource = new InsetsSource(TYPE_TOP_BAR);
    private InsetsSourceProvider mProvider;

    @Before
    public void setUp() throws Exception {
        mSource.setVisible(true);
        mProvider = new InsetsSourceProvider(mSource,
                mDisplayContent.getInsetsStateController(), mDisplayContent);
    }

    @Test
    public void testPostLayout() {
        final WindowState topBar = createWindow(null, TYPE_APPLICATION, "parentWindow");
        topBar.getFrameLw().set(0, 0, 500, 100);
        topBar.mHasSurface = true;
        mProvider.setWindow(topBar, null);
        mProvider.onPostLayout();
        assertEquals(new Rect(0, 0, 500, 100), mProvider.getSource().getFrame());
        assertEquals(Insets.of(0, 100, 0, 0),
                mProvider.getSource().calculateInsets(new Rect(0, 0, 500, 500),
                        false /* ignoreVisibility */));
    }

    @Test
    public void testPostLayout_invisible() {
        final WindowState topBar = createWindow(null, TYPE_APPLICATION, "parentWindow");
        topBar.getFrameLw().set(0, 0, 500, 100);
        mProvider.setWindow(topBar, null);
        mProvider.onPostLayout();
        assertEquals(Insets.NONE, mProvider.getSource().calculateInsets(new Rect(0, 0, 500, 500),
                        false /* ignoreVisibility */));
    }

    @Test
    public void testPostLayout_frameProvider() {
        final WindowState topBar = createWindow(null, TYPE_APPLICATION, "parentWindow");
        topBar.getFrameLw().set(0, 0, 500, 100);
        mProvider.setWindow(topBar,
                (displayFrames, windowState, rect) -> {
                    rect.set(10, 10, 20, 20);
                });
        mProvider.onPostLayout();
        assertEquals(new Rect(10, 10, 20, 20), mProvider.getSource().getFrame());
    }
}
