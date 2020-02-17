/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui;

import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_IS_ROUNDED_CORNERS_OVERLAY;

import static com.android.systemui.ScreenDecorations.rectsToRegion;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Handler;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;
import android.view.Display;
import android.view.WindowManager;

import androidx.test.filters.SmallTest;

import com.android.systemui.R.dimen;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.tuner.TunerService;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Collections;

@RunWithLooper
@RunWith(AndroidTestingRunner.class)
@SmallTest
public class ScreenDecorationsTest extends SysuiTestCase {

    private TestableLooper mTestableLooper;
    private ScreenDecorations mScreenDecorations;
    private WindowManager mWindowManager;
    private Handler mMainHandler;
    @Mock
    private TunerService mTunerService;
    @Mock
    private BroadcastDispatcher mBroadcastDispatcher;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        mTestableLooper = TestableLooper.get(this);
        mMainHandler = new Handler(mTestableLooper.getLooper());

        mWindowManager = mock(WindowManager.class);

        Display display = mContext.getSystemService(WindowManager.class).getDefaultDisplay();
        when(mWindowManager.getDefaultDisplay()).thenReturn(display);
        mContext.addMockSystemService(WindowManager.class, mWindowManager);

        mScreenDecorations = new ScreenDecorations(mContext, mMainHandler,
                mBroadcastDispatcher, mTunerService) {
            @Override
            public void start() {
                super.start();
                mTestableLooper.processAllMessages();
            }

            @Override
            Handler startHandlerThread() {
                return new Handler(mTestableLooper.getLooper());
            }

            @Override
            protected void onConfigurationChanged(Configuration newConfig) {
                super.onConfigurationChanged(newConfig);
                mTestableLooper.processAllMessages();
            }

            @Override
            public void onTuningChanged(String key, String newValue) {
                super.onTuningChanged(key, newValue);
                mTestableLooper.processAllMessages();
            }
        };
        reset(mTunerService);
    }

    @Test
    public void testNoRounding_NoCutout() {
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.bool.config_fillMainBuiltInDisplayCutout, false);
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.dimen.rounded_corner_radius, 0);
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.dimen.rounded_corner_radius_top, 0);
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.dimen.rounded_corner_radius_bottom, 0);
        mContext.getOrCreateTestableResources()
                .addOverride(dimen.rounded_corner_content_padding, 0);

        mScreenDecorations.start();
        // No views added.
        verify(mWindowManager, never()).addView(any(), any());
        // No Tuners tuned.
        verify(mTunerService, never()).addTunable(any(), any());
    }

    @Test
    public void testRounding() {
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.bool.config_fillMainBuiltInDisplayCutout, false);
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.dimen.rounded_corner_radius, 20);
        mContext.getOrCreateTestableResources()
                .addOverride(dimen.rounded_corner_content_padding, 20);

        mScreenDecorations.start();
        // Add 2 windows for rounded corners (top and bottom).
        verify(mWindowManager, times(2)).addView(any(), any());

        // One tunable.
        verify(mTunerService, times(1)).addTunable(any(), any());
    }

    @Test
    public void testCutout() {
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.bool.config_fillMainBuiltInDisplayCutout, true);
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.dimen.rounded_corner_radius, 0);
        mContext.getOrCreateTestableResources()
                .addOverride(dimen.rounded_corner_content_padding, 0);

        mScreenDecorations.start();
        // Add 2 windows for rounded corners (top and bottom).
        verify(mWindowManager, times(2)).addView(any(), any());
    }

    @Test
    public void testDelayedCutout() {
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.bool.config_fillMainBuiltInDisplayCutout, false);
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.dimen.rounded_corner_radius, 0);
        mContext.getOrCreateTestableResources()
                .addOverride(dimen.rounded_corner_content_padding, 0);

        mScreenDecorations.start();

        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.bool.config_fillMainBuiltInDisplayCutout, true);
        mScreenDecorations.onConfigurationChanged(new Configuration());

        // Add 2 windows for rounded corners (top and bottom).
        verify(mWindowManager, times(2)).addView(any(), any());
    }

    @Test
    public void hasRoundedCornerOverlayFlagSet() {
        assertThat(mScreenDecorations.getWindowLayoutParams().privateFlags
                        & PRIVATE_FLAG_IS_ROUNDED_CORNERS_OVERLAY,
                is(PRIVATE_FLAG_IS_ROUNDED_CORNERS_OVERLAY));
    }

    @Test
    public void testUpdateRoundedCorners() {
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.bool.config_fillMainBuiltInDisplayCutout, false);
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.dimen.rounded_corner_radius, 20);

        mScreenDecorations.start();
        assertEquals(mScreenDecorations.mRoundedDefault, 20);

        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.dimen.rounded_corner_radius, 5);
        mScreenDecorations.onConfigurationChanged(null);
        assertEquals(mScreenDecorations.mRoundedDefault, 5);
    }

    @Test
    public void testBoundingRectsToRegion() throws Exception {
        Rect rect = new Rect(1, 2, 3, 4);
        assertThat(rectsToRegion(Collections.singletonList(rect)).getBounds(), is(rect));
    }

}
