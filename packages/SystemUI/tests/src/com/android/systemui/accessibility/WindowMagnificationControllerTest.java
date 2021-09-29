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
import static android.view.WindowInsets.Type.systemGestures;
import static android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;

import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_MAGNIFICATION_OVERLAP;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItems;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Resources;
import android.graphics.Insets;
import android.graphics.PointF;
import android.graphics.Rect;
import android.os.Handler;
import android.os.SystemClock;
import android.testing.AndroidTestingRunner;
import android.testing.TestableResources;
import android.text.TextUtils;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.LargeTest;

import com.android.internal.graphics.SfVsyncFrameCallbackProvider;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.model.SysUiState;
import com.android.systemui.util.leak.ReferenceTestUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.List;

@LargeTest
@RunWith(AndroidTestingRunner.class)
public class WindowMagnificationControllerTest extends SysuiTestCase {

    private static final int LAYOUT_CHANGE_TIMEOUT_MS = 5000;
    @Mock
    private Handler mHandler;
    @Mock
    private SfVsyncFrameCallbackProvider mSfVsyncFrameProvider;
    @Mock
    private MirrorWindowControl mMirrorWindowControl;
    @Mock
    private WindowMagnifierCallback mWindowMagnifierCallback;
    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private SurfaceControl.Transaction mTransaction = new SurfaceControl.Transaction();
    private TestableWindowManager mWindowManager;
    private SysUiState mSysUiState = new SysUiState();
    private Resources mResources;
    private WindowMagnificationController mWindowMagnificationController;
    private Instrumentation mInstrumentation;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mContext = Mockito.spy(getContext());
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        final WindowManager wm = mContext.getSystemService(WindowManager.class);
        mWindowManager = spy(new TestableWindowManager(wm));

        mContext.addMockSystemService(Context.WINDOW_SERVICE, mWindowManager);
        doAnswer(invocation -> {
            FrameCallback callback = invocation.getArgument(0);
            callback.doFrame(0);
            return null;
        }).when(mSfVsyncFrameProvider).postFrameCallback(
                any(FrameCallback.class));
        doAnswer(invocation -> {
            final Runnable runnable = invocation.getArgument(0);
            runnable.run();
            return null;
        }).when(mHandler).post(
                any(Runnable.class));

        mSysUiState.addCallback(Mockito.mock(SysUiState.SysUiStateCallback.class));

        mResources = getContext().getOrCreateTestableResources().getResources();
        mWindowMagnificationController = new WindowMagnificationController(mContext,
                mHandler, mSfVsyncFrameProvider,
                mMirrorWindowControl, mTransaction, mWindowMagnifierCallback, mSysUiState);

        verify(mMirrorWindowControl).setWindowDelegate(
                any(MirrorWindowControl.MirrorWindowDelegate.class));
    }

    @After
    public void tearDown() {
        mInstrumentation.runOnMainSync(
                () -> mWindowMagnificationController.deleteWindowMagnification());
    }

    @Test
    public void enableWindowMagnification_showControlAndNotifyBoundsChanged() {
        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.enableWindowMagnification(Float.NaN, Float.NaN,
                    Float.NaN);
        });

        verify(mMirrorWindowControl).showControl();
        verify(mWindowMagnifierCallback,
                timeout(LAYOUT_CHANGE_TIMEOUT_MS).atLeastOnce()).onWindowMagnifierBoundsChanged(
                eq(mContext.getDisplayId()), any(Rect.class));
    }

    @Test
    public void enableWindowMagnification_systemGestureExclusionRectsIsSet() {
        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.enableWindowMagnification(Float.NaN, Float.NaN,
                    Float.NaN);
        });
        // Wait for Rects updated.
        waitForIdleSync();

        List<Rect> rects = mWindowManager.getAttachedView().getSystemGestureExclusionRects();
        assertFalse(rects.isEmpty());
    }

    @Test
    public void enableWindowMagnification_LargeScreen_windowSizeIsConstrained() {
        final int screenSize = mContext.getResources().getDimensionPixelSize(
                R.dimen.magnification_max_frame_size) * 10;
        mWindowManager.setWindowBounds(new Rect(0, 0, screenSize, screenSize));
        //We need to initialize new one because the window size is determined when initialization.
        final WindowMagnificationController controller = new WindowMagnificationController(mContext,
                mHandler, mSfVsyncFrameProvider,
                mMirrorWindowControl, mTransaction, mWindowMagnifierCallback, mSysUiState);

        mInstrumentation.runOnMainSync(() -> {
            controller.enableWindowMagnification(Float.NaN, Float.NaN,
                    Float.NaN);
        });

        final int halfScreenSize = screenSize / 2;
        WindowManager.LayoutParams params = mWindowManager.getLayoutParamsFromAttachedView();
        // The frame size should be the half of smaller value of window height/width unless it
        //exceed the max frame size.
        assertTrue(params.width < halfScreenSize);
        assertTrue(params.height < halfScreenSize);
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
    public void deleteWindowMagnification_enableAtTheBottom_overlapFlagIsFalse() {
        final WindowManager wm = mContext.getSystemService(WindowManager.class);
        final Rect bounds = wm.getCurrentWindowMetrics().getBounds();
        setSystemGestureInsets();

        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.enableWindowMagnification(Float.NaN, Float.NaN,
                    bounds.bottom);
        });
        ReferenceTestUtils.waitForCondition(this::hasMagnificationOverlapFlag);

        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.deleteWindowMagnification();
        });

        verify(mMirrorWindowControl).destroyControl();
        assertFalse(hasMagnificationOverlapFlag());
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
        doAnswer(invocation -> {
            final Runnable runnable = invocation.getArgument(0);
            runnable.run();
            return null;
        }).when(mHandler).postDelayed(any(Runnable.class), anyLong());

        mInstrumentation.runOnMainSync(
                () -> mWindowMagnificationController.enableWindowMagnification(2.0f, Float.NaN,
                        Float.NaN));

        mInstrumentation.runOnMainSync(() -> mWindowMagnificationController.setScale(3.0f));

        assertEquals(3.0f, mWindowMagnificationController.getScale(), 0);
        final View mirrorView = mWindowManager.getAttachedView();
        assertNotNull(mirrorView);
        assertThat(mirrorView.getStateDescription().toString(), containsString("300"));
    }

    @Test
    public void onConfigurationChanged_disabled_withoutException() {
        Display display = Mockito.spy(mContext.getDisplay());
        when(display.getRotation()).thenReturn(Surface.ROTATION_90);
        when(mContext.getDisplay()).thenReturn(display);

        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.onConfigurationChanged(ActivityInfo.CONFIG_DENSITY);
            mWindowMagnificationController.onConfigurationChanged(ActivityInfo.CONFIG_ORIENTATION);
            mWindowMagnificationController.onConfigurationChanged(ActivityInfo.CONFIG_LOCALE);
            mWindowMagnificationController.onConfigurationChanged(ActivityInfo.CONFIG_SCREEN_SIZE);
        });
    }

    @Test
    public void onOrientationChanged_enabled_updateDisplayRotationAndCenterStayAtSamePosition() {
        final Display display = Mockito.spy(mContext.getDisplay());
        final int currentRotation = display.getRotation();
        final int newRotation = (currentRotation + 1) % 4;
        when(display.getRotation()).thenReturn(newRotation);
        when(mContext.getDisplay()).thenReturn(display);
        final Rect windowBounds = new Rect(mWindowManager.getCurrentWindowMetrics().getBounds());
        final float center = Math.min(windowBounds.exactCenterX(), windowBounds.exactCenterY());
        final PointF magnifiedCenter = new PointF(center, center + 5f);
        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.enableWindowMagnification(Float.NaN, magnifiedCenter.x,
                    magnifiedCenter.y);
            // Get the center again in case the center we set is out of screen.
            magnifiedCenter.set(mWindowMagnificationController.getCenterX(),
                    mWindowMagnificationController.getCenterY());
        });
        // Rotate the window clockwise 90 degree.
        windowBounds.set(windowBounds.top, windowBounds.left, windowBounds.bottom,
                windowBounds.right);
        mWindowManager.setWindowBounds(windowBounds);

        mInstrumentation.runOnMainSync(() -> mWindowMagnificationController.onConfigurationChanged(
                ActivityInfo.CONFIG_ORIENTATION));

        assertEquals(newRotation, mWindowMagnificationController.mRotation);
        final PointF expectedCenter = new PointF(magnifiedCenter.y, magnifiedCenter.x);
        final PointF actualCenter = new PointF(mWindowMagnificationController.getCenterX(),
                mWindowMagnificationController.getCenterY());
        assertEquals(expectedCenter, actualCenter);
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
    public void onScreenSizeChanged_enabledAtTheCenterOfScreen_keepSameWindowSizeRatio() {
        // The default position is at the center of the screen.
        final float expectedRatio = 0.5f;
        final Rect testWindowBounds = new Rect(
                mWindowManager.getCurrentWindowMetrics().getBounds());
        testWindowBounds.set(testWindowBounds.left, testWindowBounds.top,
                testWindowBounds.right + 100, testWindowBounds.bottom + 100);
        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.enableWindowMagnification(Float.NaN, Float.NaN,
                    Float.NaN);
        });
        mWindowManager.setWindowBounds(testWindowBounds);

        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.onConfigurationChanged(ActivityInfo.CONFIG_SCREEN_SIZE);
        });

        // The ratio of center to window size should be the same.
        assertEquals(expectedRatio,
                mWindowMagnificationController.getCenterX() / testWindowBounds.width(),
                0);
        assertEquals(expectedRatio,
                mWindowMagnificationController.getCenterY() / testWindowBounds.height(),
                0);
    }
    @Test
    public void screenSizeIsChangedToLarge_enabled_windowSizeIsConstrained() {
        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.enableWindowMagnification(Float.NaN, Float.NaN,
                    Float.NaN);
        });
        final int screenSize = mContext.getResources().getDimensionPixelSize(
                R.dimen.magnification_max_frame_size) * 10;
        mWindowManager.setWindowBounds(new Rect(0, 0, screenSize, screenSize));

        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.onConfigurationChanged(ActivityInfo.CONFIG_SCREEN_SIZE);
        });

        final int halfScreenSize = screenSize / 2;
        WindowManager.LayoutParams params = mWindowManager.getLayoutParamsFromAttachedView();
        // The frame size should be the half of smaller value of window height/width unless it
        //exceed the max frame size.
        assertTrue(params.width < halfScreenSize);
        assertTrue(params.height < halfScreenSize);
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
        final View mirrorView = mWindowManager.getAttachedView();
        assertNotNull(mirrorView);
        final AccessibilityNodeInfo nodeInfo = new AccessibilityNodeInfo();

        mirrorView.onInitializeAccessibilityNodeInfo(nodeInfo);

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

        final View mirrorView = mWindowManager.getAttachedView();
        assertTrue(
                mirrorView.performAccessibilityAction(R.id.accessibility_action_zoom_out, null));
        // Minimum scale is 2.0.
        verify(mWindowMagnifierCallback).onPerformScaleAction(eq(displayId), eq(2.0f));

        assertTrue(mirrorView.performAccessibilityAction(R.id.accessibility_action_zoom_in, null));
        verify(mWindowMagnifierCallback).onPerformScaleAction(eq(displayId), eq(3.5f));

        // TODO: Verify the final state when the mirror surface is visible.
        assertTrue(mirrorView.performAccessibilityAction(R.id.accessibility_action_move_up, null));
        assertTrue(
                mirrorView.performAccessibilityAction(R.id.accessibility_action_move_down, null));
        assertTrue(
                mirrorView.performAccessibilityAction(R.id.accessibility_action_move_right, null));
        assertTrue(
                mirrorView.performAccessibilityAction(R.id.accessibility_action_move_left, null));
    }

    @Test
    public void performA11yActions_visible_notifyAccessibilityActionPerformed() {
        final int displayId = mContext.getDisplayId();
        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.enableWindowMagnification(2.5f, Float.NaN,
                    Float.NaN);
        });

        final View mirrorView = mWindowManager.getAttachedView();
        mirrorView.performAccessibilityAction(R.id.accessibility_action_move_up, null);

        verify(mWindowMagnifierCallback).onAccessibilityActionPerformed(eq(displayId));
    }

    @Test
    public void enableWindowMagnification_hasA11yWindowTitle() {
        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.enableWindowMagnification(Float.NaN, Float.NaN,
                    Float.NaN);
        });

        assertEquals(getContext().getResources().getString(
                com.android.internal.R.string.android_system_label), getAccessibilityWindowTitle());
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

        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.onConfigurationChanged(ActivityInfo.CONFIG_LOCALE);
        });

        assertTrue(TextUtils.equals(newA11yWindowTitle, getAccessibilityWindowTitle()));
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

        final View mirrorView = mWindowManager.getAttachedView();
        final long timeout = SystemClock.uptimeMillis() + 1000;
        while (SystemClock.uptimeMillis() < timeout) {
            SystemClock.sleep(10);
            if (Float.compare(1.0f, mirrorView.getScaleX()) < 0) {
                return;
            }
        }
        fail("MirrorView scale is not changed");
    }

    @Test
    public void moveWindowMagnificationToTheBottom_enabledWithGestureInset_overlapFlagIsTrue() {
        final Rect bounds = mWindowManager.getCurrentWindowMetrics().getBounds();
        setSystemGestureInsets();
        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.enableWindowMagnification(Float.NaN, Float.NaN,
                    Float.NaN);
        });

        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.moveWindowMagnifier(0, bounds.height());
        });

        ReferenceTestUtils.waitForCondition(() -> hasMagnificationOverlapFlag());
    }

    private CharSequence getAccessibilityWindowTitle() {
        final View mirrorView = mWindowManager.getAttachedView();
        if (mirrorView == null) {
            return null;
        }
        WindowManager.LayoutParams layoutParams =
                (WindowManager.LayoutParams) mirrorView.getLayoutParams();
        return layoutParams.accessibilityTitle;
    }

    private boolean hasMagnificationOverlapFlag() {
        return (mSysUiState.getFlags() & SYSUI_STATE_MAGNIFICATION_OVERLAP) != 0;
    }

    private void setSystemGestureInsets() {
        final WindowInsets testInsets = new WindowInsets.Builder()
                .setInsets(systemGestures(), Insets.of(0, 0, 0, 10))
                .build();
        mWindowManager.setWindowInsets(testInsets);
    }
}
