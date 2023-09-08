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

package com.android.server.accessibility.magnification;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.graphics.Rect;
import android.os.Handler;
import android.testing.TestableContext;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@LargeTest
@RunWith(AndroidJUnit4.class)
public class MagnificationThumbnailTest {
    @Rule
    public final TestableContext mContext = new TestableContext(
            InstrumentationRegistry.getInstrumentation().getContext());

    private final WindowManager mMockWindowManager = mock(WindowManager.class);
    private final Handler mHandler = mContext.getMainThreadHandler();

    private MagnificationThumbnail mMagnificationThumbnail;

    @Before
    public void setUp() {
        var metrics = new WindowMetrics(new Rect(), WindowInsets.CONSUMED, 1f);
        when(mMockWindowManager.getCurrentWindowMetrics()).thenReturn(metrics);
        mMagnificationThumbnail = new MagnificationThumbnail(
                mContext,
                mMockWindowManager,
                mHandler
        );
    }

    @Test
    public void updateThumbnailShows() {
        runOnMainSync(() -> mMagnificationThumbnail.updateThumbnail(
                /* scale=   */ 2f,
                /* centerX= */ 5,
                /* centerY= */ 10
        ));
        idle();

        runOnMainSync(() -> mMagnificationThumbnail.updateThumbnail(
                /* scale=   */ 2.2f,
                /* centerX= */ 15,
                /* centerY= */ 50
        ));
        idle();

        verify(mMockWindowManager).addView(eq(mMagnificationThumbnail.mThumbnailLayout), any());
        assertThat(mMagnificationThumbnail.mThumbnailLayout.getAlpha()).isGreaterThan(0.5f);
    }

    @Test
    public void updateThumbnailLingersThenHidesAfterTimeout() throws InterruptedException {
        runOnMainSync(() -> mMagnificationThumbnail.updateThumbnail(
                /* scale=   */ 2f,
                /* centerX= */ 5,
                /* centerY= */ 10
        ));
        idle();

        // Wait for the linger delay then fade out animation
        Thread.sleep(2000L);
        idle();

        verify(mMockWindowManager).removeView(eq(mMagnificationThumbnail.mThumbnailLayout));
        assertThat(mMagnificationThumbnail.mThumbnailLayout.getAlpha()).isLessThan(0.1f);
    }

    @Test
    public void hideThumbnailRemoves() throws InterruptedException {
        runOnMainSync(() -> mMagnificationThumbnail.updateThumbnail(
                /* scale=   */ 2f,
                /* centerX= */ 5,
                /* centerY= */ 10
        ));
        idle();

        runOnMainSync(() -> mMagnificationThumbnail.hideThumbnail());
        idle();

        // Wait for the fade out animation
        Thread.sleep(1100L);

        verify(mMockWindowManager).removeView(eq(mMagnificationThumbnail.mThumbnailLayout));
        assertThat(mMagnificationThumbnail.mThumbnailLayout.getAlpha()).isLessThan(0.1f);
    }

    @Test
    public void hideShowHideShowHideRemoves() throws InterruptedException {
        runOnMainSync(() -> mMagnificationThumbnail.hideThumbnail());
        idle();

        runOnMainSync(() -> mMagnificationThumbnail.updateThumbnail(
                /* scale=   */ 2f,
                /* centerX= */ 5,
                /* centerY= */ 10
        ));
        idle();

        // Wait for the fade in animation
        Thread.sleep(200L);

        runOnMainSync(() -> mMagnificationThumbnail.hideThumbnail());
        idle();

        runOnMainSync(() -> mMagnificationThumbnail.updateThumbnail(
                /* scale=   */ 2f,
                /* centerX= */ 5,
                /* centerY= */ 10
        ));
        idle();

        runOnMainSync(() -> mMagnificationThumbnail.hideThumbnail());
        idle();


        // Wait for the fade out animation
        Thread.sleep(1100L);

        verify(mMockWindowManager).removeView(eq(mMagnificationThumbnail.mThumbnailLayout));
        assertThat(mMagnificationThumbnail.mThumbnailLayout.getAlpha()).isLessThan(0.1f);
    }

    @Test
    public void hideWithoutShowDoesNothing() throws InterruptedException {
        runOnMainSync(() -> mMagnificationThumbnail.hideThumbnail());
        idle();

        // Wait for the fade out animation
        Thread.sleep(1100L);

        verify(mMockWindowManager, never())
                .addView(eq(mMagnificationThumbnail.mThumbnailLayout), any());
        verify(mMockWindowManager, never())
                .removeView(eq(mMagnificationThumbnail.mThumbnailLayout));
    }

    @Test
    public void whenHidden_setBoundsDoesNotShow() throws InterruptedException {
        runOnMainSync(() -> mMagnificationThumbnail.setThumbnailBounds(
                new Rect(),
                /* scale=   */ 2f,
                /* centerX= */ 5,
                /* centerY= */ 10
        ));
        idle();

        // Wait for the fade in/out animation
        Thread.sleep(1100L);

        verify(mMockWindowManager, never())
                .addView(eq(mMagnificationThumbnail.mThumbnailLayout), any());
        verify(mMockWindowManager, never())
                .removeView(eq(mMagnificationThumbnail.mThumbnailLayout));
        verify(mMockWindowManager, never())
                .updateViewLayout(eq(mMagnificationThumbnail.mThumbnailLayout), any());
    }

    @Test
    public void whenVisible_setBoundsUpdatesLayout() throws InterruptedException {
        runOnMainSync(() -> mMagnificationThumbnail.updateThumbnail(
                /* scale=   */ 2f,
                /* centerX= */ 5,
                /* centerY= */ 10
        ));
        runOnMainSync(() -> mMagnificationThumbnail.setThumbnailBounds(
                new Rect(),
                /* scale=   */ 2f,
                /* centerX= */ 5,
                /* centerY= */ 10
        ));
        idle();

        verify(mMockWindowManager).updateViewLayout(
                eq(mMagnificationThumbnail.mThumbnailLayout),
                /* params= */ any()
        );
    }

    private static void idle() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private static void runOnMainSync(Runnable runnable) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(runnable);
    }
}
