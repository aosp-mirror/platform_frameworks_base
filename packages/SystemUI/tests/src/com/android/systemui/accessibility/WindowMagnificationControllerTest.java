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

package com.android.systemui.accessibility;

import static android.view.Choreographer.FrameCallback;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_MODE_GESTURAL;
import static android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItems;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Resources;
import android.os.Handler;
import android.os.SystemClock;
import android.testing.AndroidTestingRunner;
import android.testing.TestableResources;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import com.android.internal.graphics.SfVsyncFrameCallbackProvider;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class WindowMagnificationControllerTest extends SysuiTestCase {

    @Mock
    Handler mHandler;
    @Mock
    SfVsyncFrameCallbackProvider mSfVsyncFrameProvider;
    @Mock
    MirrorWindowControl mMirrorWindowControl;
    @Mock
    WindowMagnifierCallback mWindowMagnifierCallback;
    @Mock
    SurfaceControl.Transaction mTransaction;
    @Mock
    private WindowManager mWindowManager;
    private Resources mResources;
    private WindowMagnificationController mWindowMagnificationController;
    private Instrumentation mInstrumentation;
    private View mMirrorView;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mContext = Mockito.spy(getContext());
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        WindowManager wm = mContext.getSystemService(WindowManager.class);
        doAnswer(invocation ->
                wm.getMaximumWindowMetrics()
        ).when(mWindowManager).getMaximumWindowMetrics();
        mContext.addMockSystemService(Context.WINDOW_SERVICE, mWindowManager);
        doAnswer(invocation -> {
            mMirrorView = invocation.getArgument(0);
            WindowManager.LayoutParams lp = invocation.getArgument(1);
            mMirrorView.setLayoutParams(lp);
            return null;
        }).when(mWindowManager).addView(any(View.class), any(WindowManager.LayoutParams.class));
        doAnswer(invocation -> {
            mMirrorView = null;
            return null;
        }).when(mWindowManager).removeView(any(View.class));
        doAnswer(invocation -> {
            FrameCallback callback = invocation.getArgument(0);
            callback.doFrame(0);
            return null;
        }).when(mSfVsyncFrameProvider).postFrameCallback(
                any(FrameCallback.class));
        when(mTransaction.remove(any())).thenReturn(mTransaction);
        when(mTransaction.setGeometry(any(), any(), any(),
                anyInt())).thenReturn(mTransaction);
        mResources = getContext().getOrCreateTestableResources().getResources();
        mWindowMagnificationController = new WindowMagnificationController(mContext,
                mHandler, mSfVsyncFrameProvider,
                mMirrorWindowControl, mTransaction, mWindowMagnifierCallback);
        verify(mMirrorWindowControl).setWindowDelegate(
                any(MirrorWindowControl.MirrorWindowDelegate.class));
    }

    @After
    public void tearDown() {
        mInstrumentation.runOnMainSync(
                () -> mWindowMagnificationController.deleteWindowMagnification());
    }

    @Test
    public void enableWindowMagnification_showControl() {
        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.enableWindowMagnification(Float.NaN, Float.NaN,
                    Float.NaN);
        });
        verify(mMirrorWindowControl).showControl();
    }

    @Test
    public void deleteWindowMagnification_destroyControl() {
        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.enableWindowMagnification(Float.NaN, Float.NaN,
                    Float.NaN);
        });

        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.deleteWindowMagnification();
        });

        verify(mMirrorWindowControl).destroyControl();
    }

    @Test
    public void moveMagnifier_schedulesFrame() {
        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.enableWindowMagnification(Float.NaN, Float.NaN,
                    Float.NaN);
            mWindowMagnificationController.moveWindowMagnifier(100f, 100f);
        });

        verify(mSfVsyncFrameProvider, atLeastOnce()).postFrameCallback(any());
    }

    @Test
    public void setScale_enabled_expectedValueAndUpdateStateDescription() {
        mInstrumentation.runOnMainSync(
                () -> mWindowMagnificationController.enableWindowMagnification(2.0f, Float.NaN,
                        Float.NaN));

        mInstrumentation.runOnMainSync(() -> mWindowMagnificationController.setScale(3.0f));

        assertEquals(3.0f, mWindowMagnificationController.getScale(), 0);
        ArgumentCaptor<Runnable> runnableArgumentCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(mHandler).postDelayed(runnableArgumentCaptor.capture(), anyLong());
        runnableArgumentCaptor.getValue().run();
        assertThat(mMirrorView.getStateDescription().toString(), containsString("300"));
    }

    @Test
    public void onConfigurationChanged_disabled_withoutException() {
        Display display = Mockito.spy(mContext.getDisplay());
        when(display.getRotation()).thenReturn(Surface.ROTATION_90);
        when(mContext.getDisplay()).thenReturn(display);

        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.onConfigurationChanged(ActivityInfo.CONFIG_DENSITY);
            mWindowMagnificationController.onConfigurationChanged(ActivityInfo.CONFIG_ORIENTATION);
        });
    }

    @Test
    public void onOrientationChanged_enabled_updateDisplayRotationAndLayout() {
        final Display display = Mockito.spy(mContext.getDisplay());
        when(display.getRotation()).thenReturn(Surface.ROTATION_90);
        when(mContext.getDisplay()).thenReturn(display);
        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.enableWindowMagnification(Float.NaN, Float.NaN,
                    Float.NaN);
            Mockito.reset(mWindowManager);
        });

        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.onConfigurationChanged(ActivityInfo.CONFIG_ORIENTATION);
        });

        assertEquals(Surface.ROTATION_90, mWindowMagnificationController.mRotation);
        verify(mWindowManager).updateViewLayout(any(), any());
    }

    @Test
    public void onOrientationChanged_disabled_updateDisplayRotation() {
        final Display display = Mockito.spy(mContext.getDisplay());
        when(display.getRotation()).thenReturn(Surface.ROTATION_90);
        when(mContext.getDisplay()).thenReturn(display);

        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.onConfigurationChanged(ActivityInfo.CONFIG_ORIENTATION);
        });

        assertEquals(Surface.ROTATION_90, mWindowMagnificationController.mRotation);
    }

    @Test
    public void onDensityChanged_enabled_updateDimensionsAndResetWindowMagnification() {
        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.enableWindowMagnification(Float.NaN, Float.NaN,
                    Float.NaN);
            Mockito.reset(mWindowManager);
            Mockito.reset(mMirrorWindowControl);
        });

        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.onConfigurationChanged(ActivityInfo.CONFIG_DENSITY);
        });

        verify(mResources, atLeastOnce()).getDimensionPixelSize(anyInt());
        verify(mWindowManager).removeView(any());
        verify(mMirrorWindowControl).destroyControl();
        verify(mWindowManager).addView(any(), any());
        verify(mMirrorWindowControl).showControl();
    }

    @Test
    public void onDensityChanged_disabled_updateDimensions() {
        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.onConfigurationChanged(ActivityInfo.CONFIG_DENSITY);
        });

        verify(mResources, atLeastOnce()).getDimensionPixelSize(anyInt());
    }

    @Test
    public void initializeA11yNode_enabled_expectedValues() {
        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.enableWindowMagnification(2.5f, Float.NaN,
                    Float.NaN);
        });
        assertNotNull(mMirrorView);
        final AccessibilityNodeInfo nodeInfo = new AccessibilityNodeInfo();

        mMirrorView.onInitializeAccessibilityNodeInfo(nodeInfo);

        assertNotNull(nodeInfo.getContentDescription());
        assertThat(nodeInfo.getStateDescription().toString(), containsString("250"));
        assertThat(nodeInfo.getActionList(),
                hasItems(new AccessibilityAction(R.id.accessibility_action_zoom_in, null),
                        new AccessibilityAction(R.id.accessibility_action_zoom_out, null),
                        new AccessibilityAction(R.id.accessibility_action_move_right, null),
                        new AccessibilityAction(R.id.accessibility_action_move_left, null),
                        new AccessibilityAction(R.id.accessibility_action_move_down, null),
                        new AccessibilityAction(R.id.accessibility_action_move_up, null)));
    }

    @Test
    public void performA11yActions_visible_expectedResults() {
        final int displayId = mContext.getDisplayId();
        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.enableWindowMagnification(2.5f, Float.NaN,
                    Float.NaN);
        });
        assertNotNull(mMirrorView);

        assertTrue(
                mMirrorView.performAccessibilityAction(R.id.accessibility_action_zoom_out, null));
        // Minimum scale is 2.0.
        verify(mWindowMagnifierCallback).onPerformScaleAction(eq(displayId), eq(2.0f));

        assertTrue(mMirrorView.performAccessibilityAction(R.id.accessibility_action_zoom_in, null));
        verify(mWindowMagnifierCallback).onPerformScaleAction(eq(displayId), eq(3.5f));

        // TODO: Verify the final state when the mirror surface is visible.
        assertTrue(mMirrorView.performAccessibilityAction(R.id.accessibility_action_move_up, null));
        assertTrue(
                mMirrorView.performAccessibilityAction(R.id.accessibility_action_move_down, null));
        assertTrue(
                mMirrorView.performAccessibilityAction(R.id.accessibility_action_move_right, null));
        assertTrue(
                mMirrorView.performAccessibilityAction(R.id.accessibility_action_move_left, null));
    }

    @Test
    public void onNavigationModeChanged_updateMirrorViewLayout() {
        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.enableWindowMagnification(Float.NaN, Float.NaN,
                    Float.NaN);
            mWindowMagnificationController.onNavigationModeChanged(NAV_BAR_MODE_GESTURAL);
        });

        verify(mWindowManager).updateViewLayout(eq(mMirrorView), any());
    }

    @Test
    public void enableWindowMagnification_hasA11yWindowTitle() {
        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.enableWindowMagnification(Float.NaN, Float.NaN,
                    Float.NaN);
        });

        ArgumentCaptor<WindowManager.LayoutParams> paramsArgumentCaptor = ArgumentCaptor.forClass(
                WindowManager.LayoutParams.class);
        verify(mWindowManager).addView(eq(mMirrorView), paramsArgumentCaptor.capture());
        assertEquals(getContext().getResources().getString(
                com.android.internal.R.string.android_system_label),
                paramsArgumentCaptor.getValue().accessibilityTitle);
    }

    @Test
    public void onLocaleChanged_enabled_updateA11yWindowTitle() {
        final String newA11yWindowTitle = "new a11y window title";
        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.enableWindowMagnification(Float.NaN, Float.NaN,
                    Float.NaN);
        });
        final TestableResources testableResources = getContext().getOrCreateTestableResources();
        testableResources.addOverride(com.android.internal.R.string.android_system_label,
                newA11yWindowTitle);
        when(mContext.getResources()).thenReturn(testableResources.getResources());

        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.onConfigurationChanged(ActivityInfo.CONFIG_LOCALE);
        });

        ArgumentCaptor<WindowManager.LayoutParams> paramsArgumentCaptor = ArgumentCaptor.forClass(
                WindowManager.LayoutParams.class);
        verify(mWindowManager).updateViewLayout(eq(mMirrorView), paramsArgumentCaptor.capture());
        assertEquals(newA11yWindowTitle, paramsArgumentCaptor.getValue().accessibilityTitle);
    }

    @Test
    public void onSingleTap_enabled_scaleIsChanged() {
        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.enableWindowMagnification(Float.NaN, Float.NaN,
                    Float.NaN);
        });

        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.onSingleTap();
        });

        final long timeout = SystemClock.uptimeMillis() + 1000;
        while (SystemClock.uptimeMillis() < timeout) {
            SystemClock.sleep(10);

            if (Float.compare(1.0f, mMirrorView.getScaleX()) < 0) {
                return;
            }
        }
        fail("mMirrorView scale is not changed");
    }
}
