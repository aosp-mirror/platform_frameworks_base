/*
 * Copyright 2025 The Android Open Source Project
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

package com.android.server.accessibility.autoclick;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.android.server.testutils.MockitoUtilsKt.eq;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.assertNotNull;
import static org.testng.AssertJUnit.assertNull;

import android.content.Context;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.provider.Settings;
import android.testing.AndroidTestingRunner;
import android.testing.TestableContext;
import android.testing.TestableLooper;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;

import com.android.server.accessibility.AccessibilityTraceManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Test cases for {@link AutoclickController}. */
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class AutoclickControllerTest {

    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();
    @Rule public final MockitoRule mMockitoRule = MockitoJUnit.rule();

    @Rule
    public TestableContext mTestableContext =
            new TestableContext(getInstrumentation().getContext());

    private TestableLooper mTestableLooper;
    @Mock private AccessibilityTraceManager mMockTrace;
    @Mock private WindowManager mMockWindowManager;
    private AutoclickController mController;

    @Before
    public void setUp() {
        mTestableLooper = TestableLooper.get(this);
        mTestableContext.addMockSystemService(Context.WINDOW_SERVICE, mMockWindowManager);
        mController =
                new AutoclickController(mTestableContext, mTestableContext.getUserId(), mMockTrace);
    }

    @After
    public void tearDown() {
        mTestableLooper.processAllMessages();
    }

    @Test
    public void onMotionEvent_lazyInitClickScheduler() {
        assertNull(mController.mClickScheduler);

        injectFakeMouseActionDownEvent();

        assertNotNull(mController.mClickScheduler);
    }

    @Test
    public void onMotionEvent_nonMouseSource_notInitClickScheduler() {
        assertNull(mController.mClickScheduler);

        injectFakeNonMouseActionDownEvent();

        assertNull(mController.mClickScheduler);
    }

    @Test
    public void onMotionEvent_lazyInitAutoclickSettingsObserver() {
        assertNull(mController.mAutoclickSettingsObserver);

        injectFakeMouseActionDownEvent();

        assertNotNull(mController.mAutoclickSettingsObserver);
    }

    @Test
    @EnableFlags(com.android.server.accessibility.Flags.FLAG_ENABLE_AUTOCLICK_INDICATOR)
    public void onMotionEvent_flagOn_lazyInitAutoclickIndicatorScheduler() {
        assertNull(mController.mAutoclickIndicatorScheduler);

        injectFakeMouseActionDownEvent();

        assertNotNull(mController.mAutoclickIndicatorScheduler);
    }

    @Test
    @DisableFlags(com.android.server.accessibility.Flags.FLAG_ENABLE_AUTOCLICK_INDICATOR)
    public void onMotionEvent_flagOff_notInitAutoclickIndicatorScheduler() {
        assertNull(mController.mAutoclickIndicatorScheduler);

        injectFakeMouseActionDownEvent();

        assertNull(mController.mAutoclickIndicatorScheduler);
    }

    @Test
    @EnableFlags(com.android.server.accessibility.Flags.FLAG_ENABLE_AUTOCLICK_INDICATOR)
    public void onMotionEvent_flagOn_lazyInitAutoclickIndicatorView() {
        assertNull(mController.mAutoclickIndicatorView);

        injectFakeMouseActionDownEvent();

        assertNotNull(mController.mAutoclickIndicatorView);
    }

    @Test
    @DisableFlags(com.android.server.accessibility.Flags.FLAG_ENABLE_AUTOCLICK_INDICATOR)
    public void onMotionEvent_flagOff_notInitAutoclickIndicatorView() {
        assertNull(mController.mAutoclickIndicatorView);

        injectFakeMouseActionDownEvent();

        assertNull(mController.mAutoclickIndicatorView);
    }

    @Test
    @EnableFlags(com.android.server.accessibility.Flags.FLAG_ENABLE_AUTOCLICK_INDICATOR)
    public void onMotionEvent_flagOn_addAutoclickIndicatorViewToWindowManager() {
        injectFakeMouseActionDownEvent();

        verify(mMockWindowManager).addView(eq(mController.mAutoclickIndicatorView), any());
    }

    @Test
    @EnableFlags(com.android.server.accessibility.Flags.FLAG_ENABLE_AUTOCLICK_INDICATOR)
    public void onDestroy_flagOn_removeAutoclickIndicatorViewToWindowManager() {
        injectFakeMouseActionDownEvent();

        mController.onDestroy();

        verify(mMockWindowManager).removeView(mController.mAutoclickIndicatorView);
    }

    @Test
    public void onMotionEvent_initClickSchedulerDelayFromSetting() {
        injectFakeMouseActionDownEvent();

        int delay =
                Settings.Secure.getIntForUser(
                        mTestableContext.getContentResolver(),
                        Settings.Secure.ACCESSIBILITY_AUTOCLICK_DELAY,
                        AccessibilityManager.AUTOCLICK_DELAY_DEFAULT,
                        mTestableContext.getUserId());
        assertEquals(delay, mController.mClickScheduler.getDelayForTesting());
    }

    @Test
    @EnableFlags(com.android.server.accessibility.Flags.FLAG_ENABLE_AUTOCLICK_INDICATOR)
    public void onMotionEvent_flagOn_initCursorAreaSizeFromSetting() {
        injectFakeMouseActionDownEvent();

        int size =
                Settings.Secure.getIntForUser(
                        mTestableContext.getContentResolver(),
                        Settings.Secure.ACCESSIBILITY_AUTOCLICK_CURSOR_AREA_SIZE,
                        AccessibilityManager.AUTOCLICK_CURSOR_AREA_SIZE_DEFAULT,
                        mTestableContext.getUserId());
        assertEquals(size, mController.mAutoclickIndicatorView.getRadiusForTesting());
    }

    @Test
    public void onDestroy_clearClickScheduler() {
        injectFakeMouseActionDownEvent();

        mController.onDestroy();

        assertNull(mController.mClickScheduler);
    }

    @Test
    public void onDestroy_clearAutoclickSettingsObserver() {
        injectFakeMouseActionDownEvent();

        mController.onDestroy();

        assertNull(mController.mAutoclickSettingsObserver);
    }

    @Test
    @EnableFlags(com.android.server.accessibility.Flags.FLAG_ENABLE_AUTOCLICK_INDICATOR)
    public void onDestroy_flagOn_clearAutoclickIndicatorScheduler() {
        injectFakeMouseActionDownEvent();

        mController.onDestroy();

        assertNull(mController.mAutoclickIndicatorScheduler);
    }

    private void injectFakeMouseActionDownEvent() {
        MotionEvent event = getFakeMotionDownEvent();
        event.setSource(InputDevice.SOURCE_MOUSE);
        mController.onMotionEvent(event, event, /* policyFlags= */ 0);
    }

    private void injectFakeNonMouseActionDownEvent() {
        MotionEvent event = getFakeMotionDownEvent();
        event.setSource(InputDevice.SOURCE_KEYBOARD);
        mController.onMotionEvent(event, event, /* policyFlags= */ 0);
    }

    private MotionEvent getFakeMotionDownEvent() {
        return MotionEvent.obtain(
                /* downTime= */ 0,
                /* eventTime= */ 0,
                /* action= */ MotionEvent.ACTION_DOWN,
                /* x= */ 0,
                /* y= */ 0,
                /* metaState= */ 0);
    }
}
