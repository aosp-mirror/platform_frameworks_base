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
import static com.android.systemui.tuner.TunablePadding.FLAG_END;
import static com.android.systemui.tuner.TunablePadding.FLAG_START;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Fragment;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Handler;
import android.support.test.filters.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;

import com.android.systemui.R.dimen;
import com.android.systemui.ScreenDecorations.TunablePaddingTagListener;
import com.android.systemui.fragments.FragmentHostManager;
import com.android.systemui.fragments.FragmentService;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.statusbar.phone.StatusBarWindowView;
import com.android.systemui.tuner.TunablePadding;
import com.android.systemui.tuner.TunablePadding.TunablePaddingService;
import com.android.systemui.tuner.TunerService;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;

@RunWithLooper
@RunWith(AndroidTestingRunner.class)
@SmallTest
public class ScreenDecorationsTest extends SysuiTestCase {

    private TestableLooper mTestableLooper;
    private ScreenDecorations mScreenDecorations;
    private StatusBar mStatusBar;
    private WindowManager mWindowManager;
    private FragmentService mFragmentService;
    private FragmentHostManager mFragmentHostManager;
    private TunerService mTunerService;
    private StatusBarWindowView mView;
    private TunablePaddingService mTunablePaddingService;

    @Before
    public void setup() {
        mTestableLooper = TestableLooper.get(this);
        mDependency.injectTestDependency(Dependency.MAIN_HANDLER,
                new Handler(mTestableLooper.getLooper()));
        mTunablePaddingService = mDependency.injectMockDependency(TunablePaddingService.class);
        mTunerService = mDependency.injectMockDependency(TunerService.class);
        mFragmentService = mDependency.injectMockDependency(FragmentService.class);

        mStatusBar = mock(StatusBar.class);
        mWindowManager = mock(WindowManager.class);
        mView = spy(new StatusBarWindowView(mContext, null));
        when(mStatusBar.getStatusBarWindow()).thenReturn(mView);
        mContext.putComponent(StatusBar.class, mStatusBar);

        Display display = mContext.getSystemService(WindowManager.class).getDefaultDisplay();
        when(mWindowManager.getDefaultDisplay()).thenReturn(display);
        mContext.addMockSystemService(WindowManager.class, mWindowManager);

        mFragmentHostManager = mock(FragmentHostManager.class);
        when(mFragmentService.getFragmentHostManager(any())).thenReturn(mFragmentHostManager);


        mScreenDecorations = new ScreenDecorations() {
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
        mScreenDecorations.mContext = mContext;
        mScreenDecorations.mComponents = mContext.getComponents();
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
        // No Fragments watched.
        verify(mFragmentHostManager, never()).addTagListener(any(), any());
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

        // Add 2 tag listeners for each of the fragments that are needed.
        verify(mFragmentHostManager, times(2)).addTagListener(any(), any());
        // One tunable.
        verify(mTunerService, times(1)).addTunable(any(), any());
        // One TunablePadding.
        verify(mTunablePaddingService, times(1)).add(any(), anyString(), anyInt(), anyInt());
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
    public void testPaddingTagListener() {
        TunablePaddingTagListener tagListener = new TunablePaddingTagListener(14, 5);
        View v = mock(View.class);
        View child = mock(View.class);
        Fragment f = mock(Fragment.class);
        TunablePadding padding = mock(TunablePadding.class);

        when(mTunablePaddingService.add(any(), anyString(), anyInt(), anyInt()))
                .thenReturn(padding);
        when(f.getView()).thenReturn(v);
        when(v.findViewById(5)).thenReturn(child);

        // Trigger callback and verify we get a TunablePadding created.
        tagListener.onFragmentViewCreated(null, f);
        verify(mTunablePaddingService).add(eq(child), eq(ScreenDecorations.PADDING), eq(14),
                eq(FLAG_START | FLAG_END));

        // Call again and verify destroy is called.
        tagListener.onFragmentViewCreated(null, f);
        verify(padding).destroy();
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
