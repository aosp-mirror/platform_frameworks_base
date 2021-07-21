/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.internal.policy;

import static android.view.Display.DEFAULT_DISPLAY;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.app.Activity;
import android.app.EmptyActivity;
import android.content.Context;
import android.hardware.display.DisplayManagerGlobal;
import android.platform.test.annotations.Presubmit;
import android.view.Display;
import android.view.DisplayAdjustments;
import android.view.DisplayInfo;
import android.view.WindowManager;
import android.view.WindowManagerImpl;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.SmallTest;
import androidx.test.rule.ActivityTestRule;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;

/**
 * Tests {@link DecorContext}.
 */
@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public final class DecorContextTest {
    private Context mContext;
    private static final int EXTERNAL_DISPLAY = DEFAULT_DISPLAY + 1;

    @Rule
    public ActivityTestRule<EmptyActivity> mActivityRule =
            new ActivityTestRule<>(EmptyActivity.class);

    @Before
    public void setUp() {
        mContext = ApplicationProvider.getApplicationContext();
    }

    @Test
    public void testDecorContextWithDefaultDisplay() {
        final Context baseContext = Mockito.spy(mContext.getApplicationContext());
        Display defaultDisplay = new Display(DisplayManagerGlobal.getInstance(), DEFAULT_DISPLAY,
                new DisplayInfo(), DisplayAdjustments.DEFAULT_DISPLAY_ADJUSTMENTS);
        final Context defaultDisplayContext = mContext.createDisplayContext(defaultDisplay);
        final PhoneWindow window = new PhoneWindow(defaultDisplayContext);
        DecorContext context = new DecorContext(baseContext, window);

        assertDecorContextDisplay(DEFAULT_DISPLAY, context);

        // TODO(b/166174272): Creating a display context for the default display will result
        // in additional resource creation.
        verify(baseContext, never()).createDisplayContext(any());
    }

    @Test
    public void testDecorContextWithExternalDisplay() {
        Display display = new Display(DisplayManagerGlobal.getInstance(), EXTERNAL_DISPLAY,
                new DisplayInfo(), DisplayAdjustments.DEFAULT_DISPLAY_ADJUSTMENTS);
        final Context defaultDisplayContext = mContext.createDisplayContext(display);
        final PhoneWindow window = new PhoneWindow(defaultDisplayContext);
        DecorContext context = new DecorContext(mContext.getApplicationContext(), window);

        assertDecorContextDisplay(EXTERNAL_DISPLAY, context);
    }

    private static void assertDecorContextDisplay(int expectedDisplayId,
            DecorContext decorContext) {
        Display associatedDisplay = decorContext.getDisplay();
        assertEquals(expectedDisplayId, associatedDisplay.getDisplayId());
    }

    @Test
    public void testGetWindowManagerFromVisualDecorContext() throws Throwable {
        mActivityRule.runOnUiThread(() -> {
            Activity activity = mActivityRule.getActivity();
            final DecorContext decorContext = new DecorContext(mContext.getApplicationContext(),
                    (PhoneWindow) activity.getWindow());
            WindowManagerImpl actualWm = (WindowManagerImpl)
                    decorContext.getSystemService(WindowManager.class);
            WindowManagerImpl expectedWm = (WindowManagerImpl)
                    activity.getSystemService(WindowManager.class);
            // Verify that window manager is from activity not application context.
            assertEquals(expectedWm.mContext, actualWm.mContext);
        });
    }

    @Test
    public void testIsUiContextFromVisualDecorContext() throws Throwable {
        mActivityRule.runOnUiThread(() -> {
            Activity activity = mActivityRule.getActivity();
            final DecorContext decorContext = new DecorContext(mContext.getApplicationContext(),
                    (PhoneWindow) activity.getWindow());
            assertTrue(decorContext.isUiContext());
        });
    }
}
