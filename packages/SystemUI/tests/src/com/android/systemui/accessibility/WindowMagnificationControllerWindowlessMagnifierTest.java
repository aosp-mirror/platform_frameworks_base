/*
 * Copyright (C) 2024 The Android Open Source Project
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

import static android.content.pm.PackageManager.FEATURE_WINDOW_MAGNIFICATION;
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import static android.content.res.Configuration.ORIENTATION_UNDEFINED;
import static android.view.MotionEvent.ACTION_DOWN;
import static android.view.MotionEvent.ACTION_UP;
import static android.view.WindowInsets.Type.systemGestures;
import static android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;

import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_MAGNIFICATION_OVERLAP;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.AdditionalAnswers.returnsSecondArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static java.util.Arrays.asList;

import android.animation.ValueAnimator;
import android.annotation.IdRes;
import android.annotation.Nullable;
import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Insets;
import android.graphics.PointF;
import android.graphics.Rect;
import android.os.Handler;
import android.os.RemoteException;
import android.os.SystemClock;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.provider.Settings;
import android.testing.TestableLooper;
import android.testing.TestableResources;
import android.util.Size;
import android.view.AttachedSurfaceControl;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.SurfaceControlViewHost;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewRootImpl;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.IRemoteMagnificationAnimationCallback;
import android.widget.FrameLayout;
import android.window.InputTransferToken;

import androidx.test.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import com.android.app.viewcapture.ViewCaptureAwareWindowManager;
import com.android.systemui.Flags;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.animation.AnimatorTestRule;
import com.android.systemui.kosmos.KosmosJavaAdapter;
import com.android.systemui.model.SysUiState;
import com.android.systemui.res.R;
import com.android.systemui.settings.FakeDisplayTracker;
import com.android.systemui.util.FakeSharedPreferences;
import com.android.systemui.util.leak.ReferenceTestUtils;
import com.android.systemui.util.settings.SecureSettings;
import com.android.systemui.utils.os.FakeHandler;

import com.google.common.util.concurrent.AtomicDouble;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

@LargeTest
@TestableLooper.RunWithLooper
@RunWith(AndroidJUnit4.class)
@EnableFlags(Flags.FLAG_CREATE_WINDOWLESS_WINDOW_MAGNIFIER)
public class WindowMagnificationControllerWindowlessMagnifierTest extends SysuiTestCase {

    @Rule
    // NOTE: pass 'null' to allow this test advances time on the main thread.
    public final AnimatorTestRule mAnimatorTestRule = new AnimatorTestRule(/* test= */ null);

    private static final int LAYOUT_CHANGE_TIMEOUT_MS = 5000;
    @Mock
    private MirrorWindowControl mMirrorWindowControl;
    @Mock
    private WindowMagnifierCallback mWindowMagnifierCallback;
    @Mock
    IRemoteMagnificationAnimationCallback mAnimationCallback;
    @Mock
    IRemoteMagnificationAnimationCallback mAnimationCallback2;

    private SurfaceControl.Transaction mTransaction;
    @Mock
    private SecureSettings mSecureSettings;
    @Mock
    private ViewCaptureAwareWindowManager mViewCaptureAwareWindowManager;

    private long mWaitAnimationDuration;
    private long mWaitBounceEffectDuration;

    private Handler mHandler;
    private TestableWindowManager mWindowManager;
    private SysUiState mSysUiState;
    private Resources mResources;
    private WindowMagnificationAnimationController mWindowMagnificationAnimationController;
    private WindowMagnificationController mWindowMagnificationController;
    private Instrumentation mInstrumentation;
    private final ValueAnimator mValueAnimator = ValueAnimator.ofFloat(0, 1.0f).setDuration(0);
    private final FakeDisplayTracker mDisplayTracker = new FakeDisplayTracker(mContext);

    private View mSpyView;
    private View.OnTouchListener mTouchListener;

    private MotionEventHelper mMotionEventHelper = new MotionEventHelper();

    // This list contains all SurfaceControlViewHosts created during a given test. If the
    // magnification window is recreated during a test, the list will contain more than a single
    // element.
    private List<SurfaceControlViewHost> mSurfaceControlViewHosts = new ArrayList<>();
    // The most recently created SurfaceControlViewHost.
    private SurfaceControlViewHost mSurfaceControlViewHost;
    private KosmosJavaAdapter mKosmos;
    private FakeSharedPreferences mSharedPreferences;

    /**
     *  return whether window magnification is supported for current test context.
     */
    private boolean isWindowModeSupported() {
        return getContext().getPackageManager().hasSystemFeature(FEATURE_WINDOW_MAGNIFICATION);
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mContext = spy(mContext);
        mKosmos = new KosmosJavaAdapter(this);
        mContext = Mockito.spy(getContext());
        mHandler = new FakeHandler(TestableLooper.get(this).getLooper());
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        final WindowManager wm = mContext.getSystemService(WindowManager.class);
        mWindowManager = spy(new TestableWindowManager(wm));

        mContext.addMockSystemService(Context.WINDOW_SERVICE, mWindowManager);
        mSysUiState = new SysUiState(mDisplayTracker, mKosmos.getSceneContainerPlugin());
        mSysUiState.addCallback(Mockito.mock(SysUiState.SysUiStateCallback.class));
        when(mSecureSettings.getIntForUser(anyString(), anyInt(), anyInt())).then(
                returnsSecondArg());
        when(mSecureSettings.getFloatForUser(anyString(), anyFloat(), anyInt())).then(
                returnsSecondArg());

        mResources = getContext().getOrCreateTestableResources().getResources();
        // prevent the config orientation from undefined, which may cause config.diff method
        // neglecting the orientation update.
        if (mResources.getConfiguration().orientation == ORIENTATION_UNDEFINED) {
            mResources.getConfiguration().orientation = ORIENTATION_PORTRAIT;
        }

        // Using the animation duration in WindowMagnificationAnimationController for testing.
        mWaitAnimationDuration = mResources.getInteger(
                com.android.internal.R.integer.config_longAnimTime);
        // Using the bounce effect duration in WindowMagnificationController for testing.
        mWaitBounceEffectDuration = mResources.getInteger(
                com.android.internal.R.integer.config_shortAnimTime);

        mWindowMagnificationAnimationController = new WindowMagnificationAnimationController(
                mContext, mValueAnimator);
        Supplier<SurfaceControlViewHost> scvhSupplier = () -> {
            mSurfaceControlViewHost = spy(new SurfaceControlViewHost(
                    mContext, mContext.getDisplay(), new InputTransferToken(),
                    "WindowMagnification"));
            ViewRootImpl viewRoot = mock(ViewRootImpl.class);
            when(mSurfaceControlViewHost.getRootSurfaceControl()).thenReturn(viewRoot);
            mSurfaceControlViewHosts.add(mSurfaceControlViewHost);
            return mSurfaceControlViewHost;
        };
        mTransaction = spy(new SurfaceControl.Transaction());
        mSharedPreferences = new FakeSharedPreferences();
        when(mContext.getSharedPreferences(
                eq("window_magnification_preferences"), anyInt()))
                .thenReturn(mSharedPreferences);
        mWindowMagnificationController =
                new WindowMagnificationController(
                        mContext,
                        mHandler,
                        mWindowMagnificationAnimationController,
                        mMirrorWindowControl,
                        mTransaction,
                        mWindowMagnifierCallback,
                        mSysUiState,
                        mSecureSettings,
                        scvhSupplier,
                        /* sfVsyncFrameProvider= */ null,
                        /* globalWindowSessionSupplier= */ null,
                        mViewCaptureAwareWindowManager);

        verify(mMirrorWindowControl).setWindowDelegate(
                any(MirrorWindowControl.MirrorWindowDelegate.class));
        mSpyView = Mockito.spy(new View(mContext));
        doAnswer((invocation) -> {
            mTouchListener = invocation.getArgument(0);
            return null;
        }).when(mSpyView).setOnTouchListener(
                any(View.OnTouchListener.class));

        // skip test if window magnification is not supported to prevent fail results. (b/279820875)
        Assume.assumeTrue(isWindowModeSupported());
    }

    @After
    public void tearDown() {
        mInstrumentation.runOnMainSync(
                () -> {
                    mWindowMagnificationController.deleteWindowMagnification();
                });
        mValueAnimator.cancel();
    }

    @Test
    public void initWindowMagnificationController_checkAllowDiagonalScrollingWithSecureSettings() {
        verify(mSecureSettings).getIntForUser(
                eq(Settings.Secure.ACCESSIBILITY_ALLOW_DIAGONAL_SCROLLING),
                /* def */ eq(1), /* userHandle= */ anyInt());
        assertThat(mWindowMagnificationController.isDiagonalScrollingEnabled()).isTrue();
    }

    @Test
    public void enableWindowMagnification_showControlAndNotifyBoundsChanged() {
        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.updateWindowMagnificationInternal(Float.NaN, Float.NaN,
                    Float.NaN);
        });

        verify(mMirrorWindowControl).showControl();
        verify(mWindowMagnifierCallback,
                timeout(LAYOUT_CHANGE_TIMEOUT_MS).atLeastOnce()).onWindowMagnifierBoundsChanged(
                eq(mContext.getDisplayId()), any(Rect.class));
    }

    @Test
    public void enableWindowMagnification_notifySourceBoundsChanged() {
        mInstrumentation.runOnMainSync(
                () -> mWindowMagnificationController.enableWindowMagnification(Float.NaN, Float.NaN,
                        Float.NaN, /* magnificationFrameOffsetRatioX= */ 0,
                        /* magnificationFrameOffsetRatioY= */ 0, null));

        // Waits for the surface created
        verify(mWindowMagnifierCallback, timeout(LAYOUT_CHANGE_TIMEOUT_MS)).onSourceBoundsChanged(
                (eq(mContext.getDisplayId())), any());
    }

    @Test
    public void enableWindowMagnification_disabled_notifySourceBoundsChanged() {
        enableWindowMagnification_notifySourceBoundsChanged();
        mInstrumentation.runOnMainSync(
                () -> mWindowMagnificationController.deleteWindowMagnification(null));
        Mockito.reset(mWindowMagnifierCallback);

        enableWindowMagnification_notifySourceBoundsChanged();
    }

    @Test
    public void enableWindowMagnification_withAnimation_schedulesFrame() {
        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.enableWindowMagnification(2.0f, 10,
                    10, /* magnificationFrameOffsetRatioX= */ 0,
                    /* magnificationFrameOffsetRatioY= */ 0,
                    Mockito.mock(IRemoteMagnificationAnimationCallback.class));
        });
        advanceTimeBy(LAYOUT_CHANGE_TIMEOUT_MS);

        verify(mTransaction, atLeastOnce()).setGeometry(any(), any(), any(),
                eq(Surface.ROTATION_0));
    }

    @Test
    public void moveWindowMagnifier_enabled_notifySourceBoundsChanged() {
        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.enableWindowMagnification(Float.NaN, Float.NaN,
                    Float.NaN, 0, 0, null);
        });

        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.moveWindowMagnifier(10, 10);
        });

        final ArgumentCaptor<Rect> sourceBoundsCaptor = ArgumentCaptor.forClass(Rect.class);
        verify(mWindowMagnifierCallback, atLeast(2)).onSourceBoundsChanged(
                (eq(mContext.getDisplayId())), sourceBoundsCaptor.capture());
        assertThat(mWindowMagnificationController.getCenterX())
                .isEqualTo(sourceBoundsCaptor.getValue().exactCenterX());
        assertThat(mWindowMagnificationController.getCenterY())
                .isEqualTo(sourceBoundsCaptor.getValue().exactCenterY());
    }

    @Test
    public void enableWindowMagnification_systemGestureExclusionRectsIsSet() {
        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.updateWindowMagnificationInternal(Float.NaN, Float.NaN,
                    Float.NaN);
        });
        // Wait for Rects updated.
        waitForIdleSync();

        List<Rect> rects = mSurfaceControlViewHost.getView().getSystemGestureExclusionRects();
        assertThat(rects).isNotEmpty();
    }

    @Ignore("The default window size should be constrained after fixing b/288056772")
    @Test
    public void enableWindowMagnification_LargeScreen_windowSizeIsConstrained() {
        final int screenSize = mWindowManager.getCurrentWindowMetrics().getBounds().width() * 10;
        mWindowManager.setWindowBounds(new Rect(0, 0, screenSize, screenSize));

        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.updateWindowMagnificationInternal(Float.NaN, Float.NaN,
                    Float.NaN);
        });

        final int halfScreenSize = screenSize / 2;
        ViewGroup.LayoutParams params = mSurfaceControlViewHost.getView().getLayoutParams();
        // The frame size should be the half of smaller value of window height/width unless it
        //exceed the max frame size.
        assertThat(params.width).isLessThan(halfScreenSize);
        assertThat(params.height).isLessThan(halfScreenSize);
    }

    @Test
    public void deleteWindowMagnification_destroyControlAndUnregisterComponentCallback() {
        mInstrumentation.runOnMainSync(
                () -> mWindowMagnificationController.updateWindowMagnificationInternal(Float.NaN,
                        Float.NaN,
                        Float.NaN));

        mInstrumentation.runOnMainSync(
                () -> mWindowMagnificationController.deleteWindowMagnification());

        verify(mMirrorWindowControl).destroyControl();
        verify(mContext).unregisterComponentCallbacks(mWindowMagnificationController);
    }

    @Test
    public void deleteWindowMagnification_enableAtTheBottom_overlapFlagIsFalse() {
        final WindowManager wm = mContext.getSystemService(WindowManager.class);
        final Rect bounds = wm.getCurrentWindowMetrics().getBounds();
        setSystemGestureInsets();

        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.updateWindowMagnificationInternal(Float.NaN, Float.NaN,
                    bounds.bottom);
        });
        ReferenceTestUtils.waitForCondition(this::hasMagnificationOverlapFlag);

        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.deleteWindowMagnification();
        });

        verify(mMirrorWindowControl).destroyControl();
        assertThat(hasMagnificationOverlapFlag()).isFalse();
    }

    @Test
    public void deleteWindowMagnification_notifySourceBoundsChanged() {
        mInstrumentation.runOnMainSync(
                () -> mWindowMagnificationController.updateWindowMagnificationInternal(Float.NaN,
                        Float.NaN,
                        Float.NaN));

        mInstrumentation.runOnMainSync(
                () -> mWindowMagnificationController.deleteWindowMagnification());

        // The first time is for notifying magnification enabled and the second time is for
        // notifying magnification disabled.
        verify(mWindowMagnifierCallback, times(2)).onSourceBoundsChanged(
                (eq(mContext.getDisplayId())), any());
    }

    @Test
    public void moveMagnifier_schedulesFrame() {
        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.updateWindowMagnificationInternal(Float.NaN, Float.NaN,
                    Float.NaN);
        });

        waitForIdleSync();
        mInstrumentation.runOnMainSync(
                () -> mWindowMagnificationController.moveWindowMagnifier(100f, 100f));

        verify(mTransaction, atLeastOnce()).setGeometry(any(), any(), any(),
                eq(Surface.ROTATION_0));
    }

    @Test
    public void moveWindowMagnifierToPositionWithAnimation_expectedValuesAndInvokeCallback()
            throws RemoteException {
        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.enableWindowMagnification(Float.NaN, Float.NaN,
                    Float.NaN, 0, 0, null);
        });

        final ArgumentCaptor<Rect> sourceBoundsCaptor = ArgumentCaptor.forClass(Rect.class);
        verify(mWindowMagnifierCallback, timeout(LAYOUT_CHANGE_TIMEOUT_MS))
                .onSourceBoundsChanged((eq(mContext.getDisplayId())), sourceBoundsCaptor.capture());
        final float targetCenterX = sourceBoundsCaptor.getValue().exactCenterX() + 10;
        final float targetCenterY = sourceBoundsCaptor.getValue().exactCenterY() + 10;

        reset(mWindowMagnifierCallback);
        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.moveWindowMagnifierToPosition(
                    targetCenterX, targetCenterY, mAnimationCallback);
        });
        advanceTimeBy(mWaitAnimationDuration);

        verify(mAnimationCallback, times(1)).onResult(eq(true));
        verify(mAnimationCallback, never()).onResult(eq(false));
        verify(mWindowMagnifierCallback, timeout(LAYOUT_CHANGE_TIMEOUT_MS))
                .onSourceBoundsChanged((eq(mContext.getDisplayId())), sourceBoundsCaptor.capture());
        assertThat(mWindowMagnificationController.getCenterX())
                .isEqualTo(sourceBoundsCaptor.getValue().exactCenterX());
        assertThat(mWindowMagnificationController.getCenterY())
                .isEqualTo(sourceBoundsCaptor.getValue().exactCenterY());
        assertThat(mWindowMagnificationController.getCenterX()).isEqualTo(targetCenterX);
        assertThat(mWindowMagnificationController.getCenterY()).isEqualTo(targetCenterY);
    }

    @Test
    public void moveWindowMagnifierToPositionMultipleTimes_expectedValuesAndInvokeCallback()
            throws RemoteException {
        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.enableWindowMagnification(Float.NaN, Float.NaN,
                    Float.NaN, 0, 0, null);
        });

        final ArgumentCaptor<Rect> sourceBoundsCaptor = ArgumentCaptor.forClass(Rect.class);
        verify(mWindowMagnifierCallback, timeout(LAYOUT_CHANGE_TIMEOUT_MS))
                .onSourceBoundsChanged((eq(mContext.getDisplayId())), sourceBoundsCaptor.capture());
        final float centerX = sourceBoundsCaptor.getValue().exactCenterX();
        final float centerY = sourceBoundsCaptor.getValue().exactCenterY();

        reset(mWindowMagnifierCallback);
        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.moveWindowMagnifierToPosition(
                    centerX + 10, centerY + 10, mAnimationCallback);
            mWindowMagnificationController.moveWindowMagnifierToPosition(
                    centerX + 20, centerY + 20, mAnimationCallback);
            mWindowMagnificationController.moveWindowMagnifierToPosition(
                    centerX + 30, centerY + 30, mAnimationCallback);
            mWindowMagnificationController.moveWindowMagnifierToPosition(
                    centerX + 40, centerY + 40, mAnimationCallback2);
        });
        advanceTimeBy(mWaitAnimationDuration);

        // only the last one callback will return true
        verify(mAnimationCallback2).onResult(eq(true));
        // the others will return false
        verify(mAnimationCallback, times(3)).onResult(eq(false));
        verify(mWindowMagnifierCallback, timeout(LAYOUT_CHANGE_TIMEOUT_MS))
                .onSourceBoundsChanged((eq(mContext.getDisplayId())), sourceBoundsCaptor.capture());
        assertThat(mWindowMagnificationController.getCenterX())
                .isEqualTo(sourceBoundsCaptor.getValue().exactCenterX());
        assertThat(mWindowMagnificationController.getCenterY())
                .isEqualTo(sourceBoundsCaptor.getValue().exactCenterY());
        assertThat(mWindowMagnificationController.getCenterX()).isEqualTo(centerX + 40);
        assertThat(mWindowMagnificationController.getCenterY()).isEqualTo(centerY + 40);
    }

    @Test
    public void setScale_enabled_expectedValueAndUpdateStateDescription() {
        mInstrumentation.runOnMainSync(
                () -> mWindowMagnificationController.updateWindowMagnificationInternal(2.0f,
                        Float.NaN, Float.NaN));

        mInstrumentation.runOnMainSync(() -> mWindowMagnificationController.setScale(3.0f));

        assertThat(mWindowMagnificationController.getScale()).isEqualTo(3.0f);
        final View mirrorView = mSurfaceControlViewHost.getView();
        assertThat(mirrorView).isNotNull();
        assertThat(mirrorView.getStateDescription().toString()).contains("300");
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
        final int newRotation = simulateRotateTheDevice();
        final Rect windowBounds = new Rect(mWindowManager.getCurrentWindowMetrics().getBounds());
        final float center = Math.min(windowBounds.exactCenterX(), windowBounds.exactCenterY());
        final float displayWidth = windowBounds.width();
        final PointF magnifiedCenter = new PointF(center, center + 5f);
        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.updateWindowMagnificationInternal(Float.NaN,
                    magnifiedCenter.x, magnifiedCenter.y);
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

        assertThat(mWindowMagnificationController.mRotation).isEqualTo(newRotation);
        final PointF expectedCenter = new PointF(magnifiedCenter.y,
                displayWidth - magnifiedCenter.x);
        final PointF actualCenter = new PointF(mWindowMagnificationController.getCenterX(),
                mWindowMagnificationController.getCenterY());
        assertThat(actualCenter).isEqualTo(expectedCenter);
    }

    @Test
    public void onOrientationChanged_disabled_updateDisplayRotation() {
        final Rect windowBounds = new Rect(mWindowManager.getCurrentWindowMetrics().getBounds());
        // Rotate the window clockwise 90 degree.
        windowBounds.set(windowBounds.top, windowBounds.left, windowBounds.bottom,
                windowBounds.right);
        mWindowManager.setWindowBounds(windowBounds);
        final int newRotation = simulateRotateTheDevice();

        mInstrumentation.runOnMainSync(() -> mWindowMagnificationController.onConfigurationChanged(
                ActivityInfo.CONFIG_ORIENTATION));

        assertThat(mWindowMagnificationController.mRotation).isEqualTo(newRotation);
    }

    @Test
    public void onScreenSizeAndDensityChanged_enabledAtTheCenterOfScreen_keepSameWindowSizeRatio() {
        // The default position is at the center of the screen.
        final float expectedRatio = 0.5f;

        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.updateWindowMagnificationInternal(Float.NaN, Float.NaN,
                    Float.NaN);
        });

        // Screen size and density change
        mContext.getResources().getConfiguration().smallestScreenWidthDp =
                mContext.getResources().getConfiguration().smallestScreenWidthDp * 2;
        final Rect testWindowBounds = new Rect(
                mWindowManager.getCurrentWindowMetrics().getBounds());
        testWindowBounds.set(testWindowBounds.left, testWindowBounds.top,
                testWindowBounds.right + 100, testWindowBounds.bottom + 100);
        mWindowManager.setWindowBounds(testWindowBounds);

        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.onConfigurationChanged(ActivityInfo.CONFIG_SCREEN_SIZE);
        });

        // The ratio of center to window size should be the same.
        assertThat(mWindowMagnificationController.getCenterX() / testWindowBounds.width())
                .isEqualTo(expectedRatio);
        assertThat(mWindowMagnificationController.getCenterY() / testWindowBounds.height())
                .isEqualTo(expectedRatio);
    }

    @DisableFlags(Flags.FLAG_SAVE_AND_RESTORE_MAGNIFICATION_SETTINGS_BUTTONS)
    @Test
    public void onScreenSizeAndDensityChanged_enabled_restoreSavedMagnifierWindow() {
        int newSmallestScreenWidthDp =
                mContext.getResources().getConfiguration().smallestScreenWidthDp * 2;
        int windowFrameSize = mResources.getDimensionPixelSize(
                com.android.internal.R.dimen.accessibility_window_magnifier_min_size);
        Size preferredWindowSize = new Size(windowFrameSize, windowFrameSize);
        mSharedPreferences
                .edit()
                .putString(String.valueOf(newSmallestScreenWidthDp),
                        preferredWindowSize.toString())
                .commit();
        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.updateWindowMagnificationInternal(Float.NaN, Float.NaN,
                    Float.NaN);
        });

        // Screen density and size change
        mContext.getResources().getConfiguration().smallestScreenWidthDp = newSmallestScreenWidthDp;
        final Rect testWindowBounds = new Rect(
                mWindowManager.getCurrentWindowMetrics().getBounds());
        testWindowBounds.set(testWindowBounds.left, testWindowBounds.top,
                testWindowBounds.right + 100, testWindowBounds.bottom + 100);
        mWindowManager.setWindowBounds(testWindowBounds);
        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.onConfigurationChanged(ActivityInfo.CONFIG_SCREEN_SIZE);
        });

        // wait for rect update
        waitForIdleSync();
        ViewGroup.LayoutParams params = mSurfaceControlViewHost.getView().getLayoutParams();
        final int mirrorSurfaceMargin = mResources.getDimensionPixelSize(
                R.dimen.magnification_mirror_surface_margin);
        // The width and height of the view include the magnification frame and the margins.
        assertThat(params.width).isEqualTo(windowFrameSize + 2 * mirrorSurfaceMargin);
        assertThat(params.height).isEqualTo(windowFrameSize + 2 * mirrorSurfaceMargin);
    }

    @EnableFlags(Flags.FLAG_SAVE_AND_RESTORE_MAGNIFICATION_SETTINGS_BUTTONS)
    @Test
    public void onScreenSizeAndDensityChanged_enabled_restoreSavedMagnifierIndexAndWindow() {
        int newSmallestScreenWidthDp =
                mContext.getResources().getConfiguration().smallestScreenWidthDp * 2;
        int windowFrameSize = mResources.getDimensionPixelSize(
                com.android.internal.R.dimen.accessibility_window_magnifier_min_size);
        Size preferredWindowSize = new Size(windowFrameSize, windowFrameSize);
        mSharedPreferences
                .edit()
                .putString(String.valueOf(newSmallestScreenWidthDp),
                        WindowMagnificationFrameSpec.serialize(
                                WindowMagnificationSettings.MagnificationSize.CUSTOM,
                                preferredWindowSize))
                .commit();
        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.updateWindowMagnificationInternal(Float.NaN, Float.NaN,
                    Float.NaN);
        });

        // Screen density and size change
        mContext.getResources().getConfiguration().smallestScreenWidthDp = newSmallestScreenWidthDp;
        final Rect testWindowBounds = new Rect(
                mWindowManager.getCurrentWindowMetrics().getBounds());
        testWindowBounds.set(testWindowBounds.left, testWindowBounds.top,
                testWindowBounds.right + 100, testWindowBounds.bottom + 100);
        mWindowManager.setWindowBounds(testWindowBounds);
        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.onConfigurationChanged(ActivityInfo.CONFIG_SCREEN_SIZE);
        });

        // wait for rect update
        waitForIdleSync();
        verify(mWindowMagnifierCallback).onWindowMagnifierBoundsRestored(
                eq(mContext.getDisplayId()),
                eq(WindowMagnificationSettings.MagnificationSize.CUSTOM));
        ViewGroup.LayoutParams params = mSurfaceControlViewHost.getView().getLayoutParams();
        final int mirrorSurfaceMargin = mResources.getDimensionPixelSize(
                R.dimen.magnification_mirror_surface_margin);
        // The width and height of the view include the magnification frame and the margins.
        assertThat(params.width).isEqualTo(windowFrameSize + 2 * mirrorSurfaceMargin);
        assertThat(params.height).isEqualTo(windowFrameSize + 2 * mirrorSurfaceMargin);
    }

    @Test
    public void screenSizeIsChangedToLarge_enabled_defaultWindowSize() {
        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.updateWindowMagnificationInternal(Float.NaN, Float.NaN,
                    Float.NaN);
        });
        final int screenSize = mWindowManager.getCurrentWindowMetrics().getBounds().width() * 10;
        // Screen size and density change
        mContext.getResources().getConfiguration().smallestScreenWidthDp =
                mContext.getResources().getConfiguration().smallestScreenWidthDp * 2;
        mWindowManager.setWindowBounds(new Rect(0, 0, screenSize, screenSize));

        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.onConfigurationChanged(ActivityInfo.CONFIG_SCREEN_SIZE);
        });

        final int defaultWindowSize =
                mWindowMagnificationController.getMagnificationWindowSizeFromIndex(
                        WindowMagnificationSettings.MagnificationSize.MEDIUM);
        ViewGroup.LayoutParams params = mSurfaceControlViewHost.getView().getLayoutParams();

        assertThat(params.width).isEqualTo(defaultWindowSize);
        assertThat(params.height).isEqualTo(defaultWindowSize);
    }

    @Test
    public void onDensityChanged_enabled_updateDimensionsAndResetWindowMagnification() {
        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.updateWindowMagnificationInternal(Float.NaN, Float.NaN,
                    Float.NaN);
            Mockito.reset(mWindowManager);
            Mockito.reset(mMirrorWindowControl);
        });

        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.onConfigurationChanged(ActivityInfo.CONFIG_DENSITY);
        });

        verify(mResources, atLeastOnce()).getDimensionPixelSize(anyInt());
        verify(mSurfaceControlViewHosts.get(0)).release();
        verify(mMirrorWindowControl).destroyControl();
        verify(mSurfaceControlViewHosts.get(1)).setView(any(), any());
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
            mWindowMagnificationController.updateWindowMagnificationInternal(2.5f, Float.NaN,
                    Float.NaN);
        });
        final View mirrorView = mSurfaceControlViewHost.getView();
        assertThat(mirrorView).isNotNull();
        final AccessibilityNodeInfo nodeInfo = new AccessibilityNodeInfo();

        mirrorView.onInitializeAccessibilityNodeInfo(nodeInfo);

        assertThat(nodeInfo.getContentDescription()).isNotNull();
        assertThat(nodeInfo.getStateDescription().toString()).contains("250");
        assertThat(nodeInfo.getActionList()).containsExactlyElementsIn(asList(
                new AccessibilityAction(AccessibilityAction.ACTION_CLICK.getId(),
                        mContext.getResources().getString(
                        R.string.magnification_open_settings_click_label)),
                new AccessibilityAction(R.id.accessibility_action_zoom_in,
                        mContext.getString(R.string.accessibility_control_zoom_in)),
                new AccessibilityAction(R.id.accessibility_action_zoom_out,
                        mContext.getString(R.string.accessibility_control_zoom_out)),
                new AccessibilityAction(R.id.accessibility_action_move_right,
                        mContext.getString(R.string.accessibility_control_move_right)),
                new AccessibilityAction(R.id.accessibility_action_move_left,
                        mContext.getString(R.string.accessibility_control_move_left)),
                new AccessibilityAction(R.id.accessibility_action_move_down,
                        mContext.getString(R.string.accessibility_control_move_down)),
                new AccessibilityAction(R.id.accessibility_action_move_up,
                        mContext.getString(R.string.accessibility_control_move_up))));
    }

    @Test
    public void performA11yActions_visible_expectedResults() {
        final int displayId = mContext.getDisplayId();
        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.updateWindowMagnificationInternal(1.5f, Float.NaN,
                    Float.NaN);
        });

        final View mirrorView = mSurfaceControlViewHost.getView();
        assertThat(mirrorView.performAccessibilityAction(R.id.accessibility_action_zoom_out, null))
                .isTrue();
        // Minimum scale is 1.0.
        verify(mWindowMagnifierCallback).onPerformScaleAction(
                eq(displayId), /* scale= */ eq(1.0f), /* updatePersistence= */ eq(true));

        assertThat(mirrorView.performAccessibilityAction(R.id.accessibility_action_zoom_in, null))
                .isTrue();
        verify(mWindowMagnifierCallback).onPerformScaleAction(
                eq(displayId), /* scale= */ eq(2.5f), /* updatePersistence= */ eq(true));

        // TODO: Verify the final state when the mirror surface is visible.
        assertThat(mirrorView.performAccessibilityAction(R.id.accessibility_action_move_up, null))
                .isTrue();
        assertThat(
                mirrorView.performAccessibilityAction(R.id.accessibility_action_move_down, null))
                .isTrue();
        assertThat(
                mirrorView.performAccessibilityAction(R.id.accessibility_action_move_right, null))
                .isTrue();
        assertThat(
                mirrorView.performAccessibilityAction(R.id.accessibility_action_move_left, null))
                .isTrue();
        verify(mWindowMagnifierCallback, times(4)).onMove(eq(displayId));

        assertThat(mirrorView.performAccessibilityAction(
                AccessibilityAction.ACTION_CLICK.getId(), null)).isTrue();
        verify(mWindowMagnifierCallback).onClickSettingsButton(eq(displayId));
    }

    @Test
    public void performA11yActions_visible_notifyAccessibilityActionPerformed() {
        final int displayId = mContext.getDisplayId();
        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.updateWindowMagnificationInternal(2.5f, Float.NaN,
                    Float.NaN);
        });

        final View mirrorView = mSurfaceControlViewHost.getView();
        mirrorView.performAccessibilityAction(R.id.accessibility_action_move_up, null);

        verify(mWindowMagnifierCallback).onAccessibilityActionPerformed(eq(displayId));
    }

    @Test
    public void windowMagnifierEditMode_performA11yClickAction_exitEditMode() {
        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.updateWindowMagnificationInternal(Float.NaN, Float.NaN,
                    Float.NaN);
            mWindowMagnificationController.setEditMagnifierSizeMode(true);
        });

        View closeButton = getInternalView(R.id.close_button);
        View bottomRightCorner = getInternalView(R.id.bottom_right_corner);
        View bottomLeftCorner = getInternalView(R.id.bottom_left_corner);
        View topRightCorner = getInternalView(R.id.top_right_corner);
        View topLeftCorner = getInternalView(R.id.top_left_corner);

        assertThat(closeButton.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(bottomRightCorner.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(bottomLeftCorner.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(topRightCorner.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(topLeftCorner.getVisibility()).isEqualTo(View.VISIBLE);

        final View mirrorView = mSurfaceControlViewHost.getView();
        mInstrumentation.runOnMainSync(() ->
                mirrorView.performAccessibilityAction(AccessibilityAction.ACTION_CLICK.getId(),
                        null));

        assertThat(closeButton.getVisibility()).isEqualTo(View.GONE);
        assertThat(bottomRightCorner.getVisibility()).isEqualTo(View.GONE);
        assertThat(bottomLeftCorner.getVisibility()).isEqualTo(View.GONE);
        assertThat(topRightCorner.getVisibility()).isEqualTo(View.GONE);
        assertThat(topLeftCorner.getVisibility()).isEqualTo(View.GONE);
    }

    @Test

    public void windowWidthIsNotMax_performA11yActionIncreaseWidth_windowWidthIncreased() {
        final Rect windowBounds = mWindowManager.getCurrentWindowMetrics().getBounds();
        final int startingWidth = (int) (windowBounds.width() * 0.8);
        final int startingHeight = (int) (windowBounds.height() * 0.8);
        final float changeWindowSizeAmount = mContext.getResources().getFraction(
                R.fraction.magnification_resize_window_size_amount,
                /* base= */ 1,
                /* pbase= */ 1);

        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.updateWindowMagnificationInternal(Float.NaN, Float.NaN,
                    Float.NaN);
            mWindowMagnificationController.setWindowSize(startingWidth, startingHeight);
            mWindowMagnificationController.setEditMagnifierSizeMode(true);
        });

        final View mirrorView = mSurfaceControlViewHost.getView();
        final AtomicInteger actualWindowHeight = new AtomicInteger();
        final AtomicInteger actualWindowWidth = new AtomicInteger();

        mInstrumentation.runOnMainSync(
                () -> {
                    mirrorView.performAccessibilityAction(
                            R.id.accessibility_action_increase_window_width, null);
                    actualWindowHeight.set(
                            mSurfaceControlViewHost.getView().getLayoutParams().height);
                    actualWindowWidth.set(
                            mSurfaceControlViewHost.getView().getLayoutParams().width);
                });

        final int mirrorSurfaceMargin = mResources.getDimensionPixelSize(
                R.dimen.magnification_mirror_surface_margin);
        // Window width includes the magnifier frame and the margin. Increasing the window size
        // will be increasing the amount of the frame size only.
        int newWindowWidth =
                (int) ((startingWidth - 2 * mirrorSurfaceMargin) * (1 + changeWindowSizeAmount))
                        + 2 * mirrorSurfaceMargin;
        assertThat(actualWindowWidth.get()).isEqualTo(newWindowWidth);
        assertThat(actualWindowHeight.get()).isEqualTo(startingHeight);
    }

    @Test
    public void windowHeightIsNotMax_performA11yActionIncreaseHeight_windowHeightIncreased() {
        final Rect windowBounds = mWindowManager.getCurrentWindowMetrics().getBounds();
        final int startingWidth = (int) (windowBounds.width() * 0.8);
        final int startingHeight = (int) (windowBounds.height() * 0.8);
        final float changeWindowSizeAmount = mContext.getResources().getFraction(
                R.fraction.magnification_resize_window_size_amount,
                /* base= */ 1,
                /* pbase= */ 1);

        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.updateWindowMagnificationInternal(Float.NaN, Float.NaN,
                    Float.NaN);
            mWindowMagnificationController.setWindowSize(startingWidth, startingHeight);
            mWindowMagnificationController.setEditMagnifierSizeMode(true);
        });

        final View mirrorView = mSurfaceControlViewHost.getView();
        final AtomicInteger actualWindowHeight = new AtomicInteger();
        final AtomicInteger actualWindowWidth = new AtomicInteger();

        mInstrumentation.runOnMainSync(
                () -> {
                    mirrorView.performAccessibilityAction(
                            R.id.accessibility_action_increase_window_height, null);
                    actualWindowHeight.set(
                            mSurfaceControlViewHost.getView().getLayoutParams().height);
                    actualWindowWidth.set(
                            mSurfaceControlViewHost.getView().getLayoutParams().width);
                });

        final int mirrorSurfaceMargin = mResources.getDimensionPixelSize(
                R.dimen.magnification_mirror_surface_margin);
        // Window height includes the magnifier frame and the margin. Increasing the window size
        // will be increasing the amount of the frame size only.
        int newWindowHeight =
                (int) ((startingHeight - 2 * mirrorSurfaceMargin) * (1 + changeWindowSizeAmount))
                        + 2 * mirrorSurfaceMargin;
        assertThat(actualWindowWidth.get()).isEqualTo(startingWidth);
        assertThat(actualWindowHeight.get()).isEqualTo(newWindowHeight);
    }

    @Test
    public void windowWidthIsMax_noIncreaseWindowWidthA11yAction() {
        final Rect windowBounds = mWindowManager.getCurrentWindowMetrics().getBounds();
        final int startingWidth = windowBounds.width();
        final int startingHeight = windowBounds.height();

        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.updateWindowMagnificationInternal(Float.NaN, Float.NaN,
                    Float.NaN);
            mWindowMagnificationController.setWindowSize(startingWidth, startingHeight);
            mWindowMagnificationController.setEditMagnifierSizeMode(true);
        });

        final View mirrorView = mSurfaceControlViewHost.getView();
        final AccessibilityNodeInfo accessibilityNodeInfo =
                mirrorView.createAccessibilityNodeInfo();
        assertThat(accessibilityNodeInfo.getActionList()).doesNotContain(
                new AccessibilityAction(
                        R.id.accessibility_action_increase_window_width,
                        mContext.getString(
                                R.string.accessibility_control_increase_window_width)));
    }

    @Test
    public void windowHeightIsMax_noIncreaseWindowHeightA11yAction() {
        final Rect windowBounds = mWindowManager.getCurrentWindowMetrics().getBounds();
        final int startingWidth = windowBounds.width();
        final int startingHeight = windowBounds.height();

        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.updateWindowMagnificationInternal(Float.NaN, Float.NaN,
                    Float.NaN);
            mWindowMagnificationController.setWindowSize(startingWidth, startingHeight);
            mWindowMagnificationController.setEditMagnifierSizeMode(true);
        });

        final View mirrorView = mSurfaceControlViewHost.getView();
        final AccessibilityNodeInfo accessibilityNodeInfo =
                mirrorView.createAccessibilityNodeInfo();
        assertThat(accessibilityNodeInfo.getActionList()).doesNotContain(
                new AccessibilityAction(
                        R.id.accessibility_action_increase_window_height, null));
    }

    @Test
    public void windowWidthIsNotMin_performA11yActionDecreaseWidth_windowWidthDecreased() {
        int mMinWindowSize = mResources.getDimensionPixelSize(
                com.android.internal.R.dimen.accessibility_window_magnifier_min_size);
        final int startingSize = (int) (mMinWindowSize * 1.1);
        final float changeWindowSizeAmount = mContext.getResources().getFraction(
                R.fraction.magnification_resize_window_size_amount,
                /* base= */ 1,
                /* pbase= */ 1);
        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.updateWindowMagnificationInternal(Float.NaN, Float.NaN,
                    Float.NaN);
            mWindowMagnificationController.setWindowSize(startingSize, startingSize);
            mWindowMagnificationController.setEditMagnifierSizeMode(true);
        });

        final View mirrorView = mSurfaceControlViewHost.getView();
        final AtomicInteger actualWindowHeight = new AtomicInteger();
        final AtomicInteger actualWindowWidth = new AtomicInteger();

        mInstrumentation.runOnMainSync(
                () -> {
                    mirrorView.performAccessibilityAction(
                            R.id.accessibility_action_decrease_window_width, null);
                    actualWindowHeight.set(
                            mSurfaceControlViewHost.getView().getLayoutParams().height);
                    actualWindowWidth.set(
                            mSurfaceControlViewHost.getView().getLayoutParams().width);
                });

        final int mirrorSurfaceMargin = mResources.getDimensionPixelSize(
                R.dimen.magnification_mirror_surface_margin);
        // Window width includes the magnifier frame and the margin. Decreasing the window size
        // will be decreasing the amount of the frame size only.
        int newWindowWidth =
                (int) ((startingSize - 2 * mirrorSurfaceMargin) * (1 - changeWindowSizeAmount))
                        + 2 * mirrorSurfaceMargin;
        assertThat(actualWindowWidth.get()).isEqualTo(newWindowWidth);
        assertThat(actualWindowHeight.get()).isEqualTo(startingSize);
    }

    @Test
    public void windowHeightIsNotMin_performA11yActionDecreaseHeight_windowHeightDecreased() {
        int mMinWindowSize = mResources.getDimensionPixelSize(
                com.android.internal.R.dimen.accessibility_window_magnifier_min_size);
        final int startingSize = (int) (mMinWindowSize * 1.1);
        final float changeWindowSizeAmount = mContext.getResources().getFraction(
                R.fraction.magnification_resize_window_size_amount,
                /* base= */ 1,
                /* pbase= */ 1);

        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.updateWindowMagnificationInternal(Float.NaN, Float.NaN,
                    Float.NaN);
            mWindowMagnificationController.setWindowSize(startingSize, startingSize);
            mWindowMagnificationController.setEditMagnifierSizeMode(true);
        });

        final View mirrorView = mSurfaceControlViewHost.getView();
        final AtomicInteger actualWindowHeight = new AtomicInteger();
        final AtomicInteger actualWindowWidth = new AtomicInteger();

        mInstrumentation.runOnMainSync(
                () -> {
                    mirrorView.performAccessibilityAction(
                            R.id.accessibility_action_decrease_window_height, null);
                    actualWindowHeight.set(
                            mSurfaceControlViewHost.getView().getLayoutParams().height);
                    actualWindowWidth.set(
                            mSurfaceControlViewHost.getView().getLayoutParams().width);
                });

        final int mirrorSurfaceMargin = mResources.getDimensionPixelSize(
                R.dimen.magnification_mirror_surface_margin);
        // Window height includes the magnifier frame and the margin. Decreasing the window size
        // will be decreasing the amount of the frame size only.
        int newWindowHeight =
                (int) ((startingSize - 2 * mirrorSurfaceMargin) * (1 - changeWindowSizeAmount))
                        + 2 * mirrorSurfaceMargin;
        assertThat(actualWindowWidth.get()).isEqualTo(startingSize);
        assertThat(actualWindowHeight.get()).isEqualTo(newWindowHeight);
    }

    @Test
    public void windowWidthIsMin_noDecreaseWindowWidthA11yAction() {
        int mMinWindowSize = mResources.getDimensionPixelSize(
                com.android.internal.R.dimen.accessibility_window_magnifier_min_size);
        final int startingSize = mMinWindowSize;

        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.updateWindowMagnificationInternal(Float.NaN, Float.NaN,
                    Float.NaN);
            mWindowMagnificationController.setWindowSize(startingSize, startingSize);
            mWindowMagnificationController.setEditMagnifierSizeMode(true);
        });

        final View mirrorView = mSurfaceControlViewHost.getView();
        final AccessibilityNodeInfo accessibilityNodeInfo =
                mirrorView.createAccessibilityNodeInfo();
        assertThat(accessibilityNodeInfo.getActionList()).doesNotContain(
                new AccessibilityAction(
                        R.id.accessibility_action_decrease_window_width, null));
    }

    @Test
    public void windowHeightIsMin_noDecreaseWindowHeightA11yAction() {
        int mMinWindowSize = mResources.getDimensionPixelSize(
                com.android.internal.R.dimen.accessibility_window_magnifier_min_size);
        final int startingSize = mMinWindowSize;

        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.updateWindowMagnificationInternal(Float.NaN, Float.NaN,
                    Float.NaN);
            mWindowMagnificationController.setWindowSize(startingSize, startingSize);
            mWindowMagnificationController.setEditMagnifierSizeMode(true);
        });

        final View mirrorView = mSurfaceControlViewHost.getView();
        final AccessibilityNodeInfo accessibilityNodeInfo =
                mirrorView.createAccessibilityNodeInfo();
        assertThat(accessibilityNodeInfo.getActionList()).doesNotContain(
                new AccessibilityAction(
                        R.id.accessibility_action_decrease_window_height, null));
    }

    @Test
    public void enableWindowMagnification_hasA11yWindowTitle() {
        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.updateWindowMagnificationInternal(Float.NaN, Float.NaN,
                    Float.NaN);
        });

        assertThat(getAccessibilityWindowTitle()).isEqualTo(getContext().getResources().getString(
                com.android.internal.R.string.android_system_label));
    }

    @Test
    public void enableWindowMagnificationWithScaleLessThanOne_enabled_disabled() {
        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.updateWindowMagnificationInternal(Float.NaN, Float.NaN,
                    Float.NaN);
        });

        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.updateWindowMagnificationInternal(0.9f, Float.NaN,
                    Float.NaN);
        });

        assertThat(mWindowMagnificationController.getScale()).isEqualTo(Float.NaN);
    }

    @Test
    public void enableWindowMagnification_rotationIsChanged_updateRotationValue() {
        // the config orientation should not be undefined, since it would cause config.diff
        // returning 0 and thus the orientation changed would not be detected
        assertThat(mResources.getConfiguration().orientation).isNotEqualTo(ORIENTATION_UNDEFINED);

        final Configuration config = mResources.getConfiguration();
        config.orientation = config.orientation == ORIENTATION_LANDSCAPE ? ORIENTATION_PORTRAIT
                : ORIENTATION_LANDSCAPE;
        final int newRotation = simulateRotateTheDevice();

        mInstrumentation.runOnMainSync(
                () -> mWindowMagnificationController.updateWindowMagnificationInternal(Float.NaN,
                        Float.NaN, Float.NaN));

        assertThat(mWindowMagnificationController.mRotation).isEqualTo(newRotation);
    }

    @Test
    public void enableWindowMagnification_registerComponentCallback() {
        mInstrumentation.runOnMainSync(
                () -> mWindowMagnificationController.updateWindowMagnificationInternal(Float.NaN,
                        Float.NaN,
                        Float.NaN));

        verify(mContext).registerComponentCallbacks(mWindowMagnificationController);
    }

    @Test
    public void onLocaleChanged_enabled_updateA11yWindowTitle() {
        final String newA11yWindowTitle = "new a11y window title";
        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.updateWindowMagnificationInternal(Float.NaN, Float.NaN,
                    Float.NaN);
        });
        final TestableResources testableResources = getContext().getOrCreateTestableResources();
        testableResources.addOverride(com.android.internal.R.string.android_system_label,
                newA11yWindowTitle);

        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.onConfigurationChanged(ActivityInfo.CONFIG_LOCALE);
        });

        assertThat(getAccessibilityWindowTitle()).isEqualTo(newA11yWindowTitle);
    }

    @Ignore("it's flaky in presubmit but works in abtd, filter for now. b/305654925")
    @Test
    public void onSingleTap_enabled_scaleAnimates() {
        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.updateWindowMagnificationInternal(Float.NaN, Float.NaN,
                    Float.NaN);
        });

        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.onSingleTap(mSpyView);
        });

        final View mirrorView = mSurfaceControlViewHost.getView();

        final AtomicDouble maxScaleX = new AtomicDouble();
        advanceTimeBy(mWaitBounceEffectDuration, /* runnableOnEachRefresh= */ () -> {
            // For some reason the fancy way doesn't compile...
            // maxScaleX.getAndAccumulate(mirrorView.getScaleX(), Math::max);
            final double oldMax = maxScaleX.get();
            final double newMax = Math.max(mirrorView.getScaleX(), oldMax);
            assertThat(maxScaleX.compareAndSet(oldMax, newMax)).isTrue();
        });

        assertThat(maxScaleX.get()).isGreaterThan(1.0);
    }

    @Test
    public void moveWindowMagnificationToTheBottom_enabledWithGestureInset_overlapFlagIsTrue() {
        final Rect bounds = mWindowManager.getCurrentWindowMetrics().getBounds();
        setSystemGestureInsets();
        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.updateWindowMagnificationInternal(Float.NaN, Float.NaN,
                    Float.NaN);
        });

        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.moveWindowMagnifier(0, bounds.height());
        });

        ReferenceTestUtils.waitForCondition(() -> hasMagnificationOverlapFlag());
    }

    @Test
    public void moveWindowMagnificationToRightEdge_dragHandleMovesToLeftAndUpdatesTapExcludeRegion()
            throws RemoteException {
        final Rect bounds = mWindowManager.getCurrentWindowMetrics().getBounds();
        setSystemGestureInsets();
        mInstrumentation.runOnMainSync(
                () -> {
                    mWindowMagnificationController.updateWindowMagnificationInternal(
                            Float.NaN, Float.NaN, Float.NaN);
                });
        // Wait for Region updated.
        waitForIdleSync();

        mInstrumentation.runOnMainSync(
                () -> {
                    mWindowMagnificationController.moveWindowMagnifier(bounds.width(), 0);
                });
        // Wait for Region updated.
        waitForIdleSync();

        AttachedSurfaceControl viewRoot = mSurfaceControlViewHost.getRootSurfaceControl();
        // Verifying two times in: (1) enable window magnification (2) reposition drag handle
        verify(viewRoot, times(2)).setTouchableRegion(any());

        View dragButton = getInternalView(R.id.drag_handle);
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) dragButton.getLayoutParams();
        assertThat(params.gravity).isEqualTo(Gravity.BOTTOM | Gravity.LEFT);
    }

    @Test
    public void moveWindowMagnificationToLeftEdge_dragHandleMovesToRightAndUpdatesTapExcludeRegion()
            throws RemoteException {
        final Rect bounds = mWindowManager.getCurrentWindowMetrics().getBounds();
        setSystemGestureInsets();
        mInstrumentation.runOnMainSync(
                () -> {
                    mWindowMagnificationController.updateWindowMagnificationInternal(
                            Float.NaN, Float.NaN, Float.NaN);
                });
        // Wait for Region updated.
        waitForIdleSync();

        mInstrumentation.runOnMainSync(
                () -> {
                    mWindowMagnificationController.moveWindowMagnifier(-bounds.width(), 0);
                });
        // Wait for Region updated.
        waitForIdleSync();

        AttachedSurfaceControl viewRoot = mSurfaceControlViewHost.getRootSurfaceControl();
        // Verifying one times in: (1) enable window magnification
        verify(viewRoot).setTouchableRegion(any());

        View dragButton = getInternalView(R.id.drag_handle);
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) dragButton.getLayoutParams();
        assertThat(params.gravity).isEqualTo(Gravity.BOTTOM | Gravity.RIGHT);
    }

    @Test
    public void setMinimumWindowSize_enabled_expectedWindowSize() {
        final int minimumWindowSize = mResources.getDimensionPixelSize(
                com.android.internal.R.dimen.accessibility_window_magnifier_min_size);
        final int  expectedWindowHeight = minimumWindowSize;
        final int  expectedWindowWidth = minimumWindowSize;
        mInstrumentation.runOnMainSync(
                () -> mWindowMagnificationController.updateWindowMagnificationInternal(Float.NaN,
                        Float.NaN, Float.NaN));

        final AtomicInteger actualWindowHeight = new AtomicInteger();
        final AtomicInteger actualWindowWidth = new AtomicInteger();
        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.setWindowSize(expectedWindowWidth, expectedWindowHeight);
            actualWindowHeight.set(mSurfaceControlViewHost.getView().getLayoutParams().height);
            actualWindowWidth.set(mSurfaceControlViewHost.getView().getLayoutParams().width);

        });

        assertThat(actualWindowHeight.get()).isEqualTo(expectedWindowHeight);
        assertThat(actualWindowWidth.get()).isEqualTo(expectedWindowWidth);
    }

    @Test
    public void setMinimumWindowSizeThenEnable_expectedWindowSize() {
        final int minimumWindowSize = mResources.getDimensionPixelSize(
                com.android.internal.R.dimen.accessibility_window_magnifier_min_size);
        final int  expectedWindowHeight = minimumWindowSize;
        final int  expectedWindowWidth = minimumWindowSize;

        final AtomicInteger actualWindowHeight = new AtomicInteger();
        final AtomicInteger actualWindowWidth = new AtomicInteger();
        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.setWindowSize(expectedWindowWidth, expectedWindowHeight);
            mWindowMagnificationController.updateWindowMagnificationInternal(Float.NaN,
                    Float.NaN, Float.NaN);
            actualWindowHeight.set(mSurfaceControlViewHost.getView().getLayoutParams().height);
            actualWindowWidth.set(mSurfaceControlViewHost.getView().getLayoutParams().width);
        });

        assertThat(actualWindowHeight.get()).isEqualTo(expectedWindowHeight);
        assertThat(actualWindowWidth.get()).isEqualTo(expectedWindowWidth);
    }

    @Test
    public void setWindowSizeLessThanMin_enabled_minimumWindowSize() {
        final int minimumWindowSize = mResources.getDimensionPixelSize(
                com.android.internal.R.dimen.accessibility_window_magnifier_min_size);
        mInstrumentation.runOnMainSync(
                () -> mWindowMagnificationController.updateWindowMagnificationInternal(Float.NaN,
                        Float.NaN, Float.NaN));

        final AtomicInteger actualWindowHeight = new AtomicInteger();
        final AtomicInteger actualWindowWidth = new AtomicInteger();
        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.setWindowSize(minimumWindowSize - 10,
                    minimumWindowSize - 10);
            actualWindowHeight.set(mSurfaceControlViewHost.getView().getLayoutParams().height);
            actualWindowWidth.set(mSurfaceControlViewHost.getView().getLayoutParams().width);
        });

        assertThat(actualWindowHeight.get()).isEqualTo(minimumWindowSize);
        assertThat(actualWindowWidth.get()).isEqualTo(minimumWindowSize);
    }

    @Test
    public void setWindowSizeLargerThanScreenSize_enabled_windowSizeIsScreenSize() {
        final Rect bounds = mWindowManager.getCurrentWindowMetrics().getBounds();
        mInstrumentation.runOnMainSync(
                () -> mWindowMagnificationController.updateWindowMagnificationInternal(Float.NaN,
                        Float.NaN, Float.NaN));

        final AtomicInteger actualWindowHeight = new AtomicInteger();
        final AtomicInteger actualWindowWidth = new AtomicInteger();
        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.setWindowSize(bounds.width() + 10, bounds.height() + 10);
            actualWindowHeight.set(mSurfaceControlViewHost.getView().getLayoutParams().height);
            actualWindowWidth.set(mSurfaceControlViewHost.getView().getLayoutParams().width);
        });

        assertThat(actualWindowHeight.get()).isEqualTo(bounds.height());
        assertThat(actualWindowWidth.get()).isEqualTo(bounds.width());
    }

    @Test
    public void changeMagnificationSize_expectedWindowSize() {
        final Rect bounds = mWindowManager.getCurrentWindowMetrics().getBounds();

        final float magnificationScaleLarge = 2.5f;
        final int initSize = Math.min(bounds.width(), bounds.height()) / 3;
        final int magnificationSize = (int) (initSize * magnificationScaleLarge)
                - (int) (initSize * magnificationScaleLarge) % 2;

        final int expectedWindowHeight = magnificationSize;
        final int expectedWindowWidth = magnificationSize;

        mInstrumentation.runOnMainSync(
                () ->
                        mWindowMagnificationController.updateWindowMagnificationInternal(
                                Float.NaN, Float.NaN, Float.NaN));

        final AtomicInteger actualWindowHeight = new AtomicInteger();
        final AtomicInteger actualWindowWidth = new AtomicInteger();
        mInstrumentation.runOnMainSync(
                () -> {
                    mWindowMagnificationController.changeMagnificationSize(
                            WindowMagnificationSettings.MagnificationSize.LARGE);
                    actualWindowHeight.set(
                            mSurfaceControlViewHost.getView().getLayoutParams().height);
                    actualWindowWidth.set(
                            mSurfaceControlViewHost.getView().getLayoutParams().width);
                });

        assertThat(actualWindowHeight.get()).isEqualTo(expectedWindowHeight);
        assertThat(actualWindowWidth.get()).isEqualTo(expectedWindowWidth);
    }

    @Test
    public void editModeOnDragCorner_resizesWindow() {
        final Rect bounds = mWindowManager.getCurrentWindowMetrics().getBounds();

        final int startingSize = (int) (bounds.width() / 2);

        mInstrumentation.runOnMainSync(
                () ->
                        mWindowMagnificationController.updateWindowMagnificationInternal(
                                Float.NaN, Float.NaN, Float.NaN));

        final AtomicInteger actualWindowHeight = new AtomicInteger();
        final AtomicInteger actualWindowWidth = new AtomicInteger();

        mInstrumentation.runOnMainSync(
                () -> {
                    mWindowMagnificationController.setWindowSize(startingSize, startingSize);
                    mWindowMagnificationController.setEditMagnifierSizeMode(true);
                });

        waitForIdleSync();

        mInstrumentation.runOnMainSync(
                () -> {
                    mWindowMagnificationController
                            .onDrag(getInternalView(R.id.bottom_right_corner), 2f, 1f);
                    actualWindowHeight.set(
                            mSurfaceControlViewHost.getView().getLayoutParams().height);
                    actualWindowWidth.set(
                            mSurfaceControlViewHost.getView().getLayoutParams().width);
                });

        assertThat(actualWindowHeight.get()).isEqualTo(startingSize + 1);
        assertThat(actualWindowWidth.get()).isEqualTo(startingSize + 2);
    }

    @Test
    public void editModeOnDragEdge_resizesWindowInOnlyOneDirection() {
        final Rect bounds = mWindowManager.getCurrentWindowMetrics().getBounds();

        final int startingSize = (int) (bounds.width() / 2f);

        mInstrumentation.runOnMainSync(
                () ->
                        mWindowMagnificationController.updateWindowMagnificationInternal(
                                Float.NaN, Float.NaN, Float.NaN));

        final AtomicInteger actualWindowHeight = new AtomicInteger();
        final AtomicInteger actualWindowWidth = new AtomicInteger();

        mInstrumentation.runOnMainSync(
                () -> {
                    mWindowMagnificationController.setWindowSize(startingSize, startingSize);
                    mWindowMagnificationController.setEditMagnifierSizeMode(true);
                    mWindowMagnificationController
                            .onDrag(getInternalView(R.id.bottom_handle), 2f, 1f);
                    actualWindowHeight.set(
                            mSurfaceControlViewHost.getView().getLayoutParams().height);
                    actualWindowWidth.set(
                            mSurfaceControlViewHost.getView().getLayoutParams().width);
                });
        assertThat(actualWindowHeight.get()).isEqualTo(startingSize + 1);
        assertThat(actualWindowWidth.get()).isEqualTo(startingSize);
    }

    @Test
    public void setWindowCenterOutOfScreen_enabled_magnificationCenterIsInsideTheScreen() {

        final int minimumWindowSize = mResources.getDimensionPixelSize(
                com.android.internal.R.dimen.accessibility_window_magnifier_min_size);
        final Rect bounds = mWindowManager.getCurrentWindowMetrics().getBounds();
        mInstrumentation.runOnMainSync(
                () -> mWindowMagnificationController.updateWindowMagnificationInternal(Float.NaN,
                        Float.NaN, Float.NaN));

        final AtomicInteger magnificationCenterX = new AtomicInteger();
        final AtomicInteger magnificationCenterY = new AtomicInteger();
        mInstrumentation.runOnMainSync(() -> {
            mWindowMagnificationController.setWindowSizeAndCenter(minimumWindowSize,
                    minimumWindowSize, bounds.right, bounds.bottom);
            magnificationCenterX.set((int) mWindowMagnificationController.getCenterX());
            magnificationCenterY.set((int) mWindowMagnificationController.getCenterY());
        });

        assertThat(magnificationCenterX.get()).isLessThan(bounds.right);
        assertThat(magnificationCenterY.get()).isLessThan(bounds.bottom);
    }

    @Test
    public void performSingleTap_DragHandle() {
        final Rect bounds = mWindowManager.getCurrentWindowMetrics().getBounds();
        mInstrumentation.runOnMainSync(
                () -> {
                    mWindowMagnificationController.updateWindowMagnificationInternal(
                            1.5f, bounds.centerX(), bounds.centerY());
                });
        View dragButton = getInternalView(R.id.drag_handle);

        // Perform a single-tap
        final long downTime = SystemClock.uptimeMillis();
        dragButton.dispatchTouchEvent(
                obtainMotionEvent(downTime, 0, ACTION_DOWN, 100, 100));
        dragButton.dispatchTouchEvent(
                obtainMotionEvent(downTime, downTime, ACTION_UP, 100, 100));

        verify(mSurfaceControlViewHost).setView(any(View.class), any());
    }

    private <T extends View> T getInternalView(@IdRes int idRes) {
        View mirrorView = mSurfaceControlViewHost.getView();
        T view = mirrorView.findViewById(idRes);
        assertThat(view).isNotNull();
        return view;
    }

    private MotionEvent obtainMotionEvent(long downTime, long eventTime, int action, float x,
            float y) {
        return mMotionEventHelper.obtainMotionEvent(downTime, eventTime, action, x, y);
    }

    private String getAccessibilityWindowTitle() {
        final View mirrorView = mSurfaceControlViewHost.getView();
        if (mirrorView == null) {
            return null;
        }
        WindowManager.LayoutParams layoutParams =
                (WindowManager.LayoutParams) mirrorView.getLayoutParams();
        return layoutParams.accessibilityTitle.toString();
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

    private int updateMirrorSurfaceMarginDimension() {
        return mContext.getResources().getDimensionPixelSize(
                R.dimen.magnification_mirror_surface_margin);
    }

    @Surface.Rotation
    private int simulateRotateTheDevice() {
        final Display display = Mockito.spy(mContext.getDisplay());
        final int currentRotation = display.getRotation();
        final int newRotation = (currentRotation + 1) % 4;
        when(display.getRotation()).thenReturn(newRotation);
        when(mContext.getDisplay()).thenReturn(display);
        return newRotation;
    }

    // advance time based on the device frame refresh rate
    private void advanceTimeBy(long timeDelta) {
        advanceTimeBy(timeDelta, /* runnableOnEachRefresh= */ null);
    }

    // advance time based on the device frame refresh rate, and trigger runnable on each refresh
    private void advanceTimeBy(long timeDelta, @Nullable Runnable runnableOnEachRefresh) {
        final float frameRate = mContext.getDisplay().getRefreshRate();
        final int timeSlot = (int) (1000 / frameRate);
        int round = (int) Math.ceil((double) timeDelta / timeSlot);
        for (; round >= 0; round--) {
            mInstrumentation.runOnMainSync(() -> {
                mAnimatorTestRule.advanceTimeBy(timeSlot);
                if (runnableOnEachRefresh != null) {
                    runnableOnEachRefresh.run();
                }
            });
        }
    }
}
