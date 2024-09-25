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

package com.android.systemui.accessibility;

import static android.provider.Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN;
import static android.view.MotionEvent.ACTION_CANCEL;
import static android.view.MotionEvent.ACTION_DOWN;
import static android.view.MotionEvent.ACTION_MOVE;
import static android.view.MotionEvent.ACTION_UP;
import static android.view.WindowInsets.Type.displayCutout;
import static android.view.WindowInsets.Type.systemBars;
import static android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK;

import static com.android.systemui.accessibility.MagnificationModeSwitch.DEFAULT_FADE_OUT_ANIMATION_DELAY_MS;
import static com.android.systemui.accessibility.MagnificationModeSwitch.FADING_ANIMATION_DURATION_MS;
import static com.android.systemui.accessibility.MagnificationModeSwitch.getIconResId;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNotNull;

import static org.hamcrest.CoreMatchers.hasItems;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Handler;
import android.os.SystemClock;
import android.testing.TestableLooper;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewPropertyAnimator;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.app.viewcapture.ViewCapture;
import com.android.app.viewcapture.ViewCaptureAwareWindowManager;
import com.android.internal.graphics.SfVsyncFrameCallbackProvider;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.res.R;

import kotlin.Lazy;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.List;

@SmallTest
@RunWith(AndroidJUnit4.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class MagnificationModeSwitchTest extends SysuiTestCase {

    private static final float FADE_IN_ALPHA = 1f;
    private static final float FADE_OUT_ALPHA = 0f;

    private ImageView mSpyImageView;
    @Mock
    private AccessibilityManager mAccessibilityManager;
    @Mock
    private SfVsyncFrameCallbackProvider mSfVsyncFrameProvider;
    @Mock
    private MagnificationModeSwitch.ClickListener mClickListener;
    @Mock
    private Lazy<ViewCapture> mLazyViewCapture;
    private TestableWindowManager mWindowManager;
    private ViewPropertyAnimator mViewPropertyAnimator;
    private MagnificationModeSwitch mMagnificationModeSwitch;
    private View.OnTouchListener mTouchListener;
    private Runnable mFadeOutAnimation;
    private MotionEventHelper mMotionEventHelper = new MotionEventHelper();

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mContext = Mockito.spy(getContext());
        final WindowManager wm = mContext.getSystemService(WindowManager.class);
        mWindowManager = spy(new TestableWindowManager(wm));
        mContext.addMockSystemService(Context.WINDOW_SERVICE, mWindowManager);
        mContext.addMockSystemService(Context.ACCESSIBILITY_SERVICE, mAccessibilityManager);
        mSpyImageView = Mockito.spy(new ImageView(mContext));
        mViewPropertyAnimator = Mockito.spy(mSpyImageView.animate());
        resetAndStubMockImageViewAndAnimator();
        doAnswer((invocation) -> {
            mTouchListener = invocation.getArgument(0);
            return null;
        }).when(mSpyImageView).setOnTouchListener(
                any(View.OnTouchListener.class));
        doAnswer(invocation -> {
            Choreographer.FrameCallback callback = invocation.getArgument(0);
            callback.doFrame(0);
            return null;
        }).when(mSfVsyncFrameProvider).postFrameCallback(
                any(Choreographer.FrameCallback.class));
        ViewCaptureAwareWindowManager vwm = new ViewCaptureAwareWindowManager(mWindowManager,
                mLazyViewCapture, false);
        mMagnificationModeSwitch = new MagnificationModeSwitch(mContext, mSpyImageView,
                mSfVsyncFrameProvider, mClickListener, vwm);
        assertNotNull(mTouchListener);
    }

    @After
    public void tearDown() {
        mFadeOutAnimation = null;
        mMotionEventHelper.recycleEvents();
        mMagnificationModeSwitch.removeButton();
    }

    @Test
    public void removeButton_buttonIsShowing_removeViewAndUnregisterComponentCallbacks() {
        mMagnificationModeSwitch.showButton(ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);

        mMagnificationModeSwitch.removeButton();

        verify(mWindowManager).removeView(mSpyImageView);
        verify(mViewPropertyAnimator).cancel();
        verify(mContext).unregisterComponentCallbacks(mMagnificationModeSwitch);
    }

    @Test
    public void showFullscreenModeButton_addViewAndSetImageResource() {
        mMagnificationModeSwitch.showButton(ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);

        verify(mSpyImageView).setImageResource(
                getIconResId(ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN));
        assertEquals(mSpyImageView, mWindowManager.getAttachedView());
        assertShowFadingAnimation(FADE_IN_ALPHA);
        assertShowFadingAnimation(FADE_OUT_ALPHA);
    }

    @Test
    public void showButton_excludeSystemGestureArea() {
        mMagnificationModeSwitch.showButton(ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);

        verify(mSpyImageView).setSystemGestureExclusionRects(any(List.class));
    }

    @Test
    public void showMagnificationButton_setA11yTimeout_postDelayedAnimationWithA11yTimeout() {
        final int a11yTimeout = 12345;
        when(mAccessibilityManager.getRecommendedTimeoutMillis(anyInt(), anyInt())).thenReturn(
                a11yTimeout);

        mMagnificationModeSwitch.showButton(ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);

        verify(mAccessibilityManager).getRecommendedTimeoutMillis(
                DEFAULT_FADE_OUT_ANIMATION_DELAY_MS, AccessibilityManager.FLAG_CONTENT_ICONS
                        | AccessibilityManager.FLAG_CONTENT_CONTROLS);
        verify(mSpyImageView).postOnAnimationDelayed(any(Runnable.class), eq((long) a11yTimeout));
    }

    @Test
    public void showMagnificationButton_noA11yServicesRunning_postDelayedAnimationsWithTimeout() {
        final int a11yTimeout = 12345;
        when(mAccessibilityManager.getRecommendedTimeoutMillis(anyInt(), anyInt())).thenReturn(
                a11yTimeout);
        when(mAccessibilityManager.getEnabledAccessibilityServiceList(anyInt()))
                .thenReturn(List.of());

        mMagnificationModeSwitch.showButton(ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);

        verify(mAccessibilityManager).getRecommendedTimeoutMillis(
                DEFAULT_FADE_OUT_ANIMATION_DELAY_MS, AccessibilityManager.FLAG_CONTENT_ICONS
                        | AccessibilityManager.FLAG_CONTENT_CONTROLS);
        verify(mSpyImageView).postOnAnimationDelayed(any(Runnable.class), eq((long) a11yTimeout));
    }

    @Test
    public void showMagnificationButton_voiceAccessRunning_noTimeout() {
        var serviceInfo = createServiceInfoWithName(
                "com.google.android.apps.accessibility.voiceaccess.JustSpeakService");
        when(mAccessibilityManager.getEnabledAccessibilityServiceList(anyInt()))
                .thenReturn(List.of(serviceInfo));

        mMagnificationModeSwitch.showButton(ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);

        verify(mSpyImageView, never()).postOnAnimationDelayed(any(Runnable.class), anyLong());
    }

    @Test
    public void showMagnificationButton_switchAccessRunning_noTimeout() {
        var serviceInfo = createServiceInfoWithName(
                "com.android.switchaccess.SwitchAccessService");
        when(mAccessibilityManager.getEnabledAccessibilityServiceList(anyInt()))
                .thenReturn(List.of(serviceInfo));

        mMagnificationModeSwitch.showButton(ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);

        verify(mSpyImageView, never()).postOnAnimationDelayed(any(Runnable.class), anyLong());
    }

    @Test
    public void showMagnificationButton_switchAccessAndVoiceAccessBothRunning_noTimeout() {
        var switchAccessServiceInfo = createServiceInfoWithName(
                "com.android.switchaccess.SwitchAccessService");
        var voiceAccessServiceInfo = createServiceInfoWithName(
                "com.google.android.apps.accessibility.voiceaccess.JustSpeakService");
        when(mAccessibilityManager.getEnabledAccessibilityServiceList(anyInt()))
                .thenReturn(List.of(switchAccessServiceInfo, voiceAccessServiceInfo));

        mMagnificationModeSwitch.showButton(ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);

        verify(mSpyImageView, never()).postOnAnimationDelayed(any(Runnable.class), anyLong());
    }

    @Test
    public void showMagnificationButton_someOtherServiceRunning_postDelayedAnimationsWithTimeout() {
        final int a11yTimeout = 12345;
        when(mAccessibilityManager.getRecommendedTimeoutMillis(anyInt(), anyInt())).thenReturn(
                a11yTimeout);
        var serviceInfo1 = createServiceInfoWithName("com.test.someService1");
        var serviceInfo2 = createServiceInfoWithName("com.test.someService2");
        when(mAccessibilityManager.getEnabledAccessibilityServiceList(anyInt()))
                .thenReturn(List.of(serviceInfo1, serviceInfo2));

        mMagnificationModeSwitch.showButton(ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);

        verify(mAccessibilityManager).getRecommendedTimeoutMillis(
                DEFAULT_FADE_OUT_ANIMATION_DELAY_MS, AccessibilityManager.FLAG_CONTENT_ICONS
                        | AccessibilityManager.FLAG_CONTENT_CONTROLS);
        verify(mSpyImageView).postOnAnimationDelayed(any(Runnable.class), eq((long) a11yTimeout));
    }

    private AccessibilityServiceInfo createServiceInfoWithName(String name) {
        var resolveInfo = new ResolveInfo();
        resolveInfo.serviceInfo = new ServiceInfo();
        resolveInfo.serviceInfo.name = name;
        var serviceInfo = new AccessibilityServiceInfo();
        serviceInfo.setResolveInfo(resolveInfo);
        return serviceInfo;
    }

    @Test
    public void showMagnificationButton_windowModeAndFadingOut_verifyAnimationEndAction() {
        mMagnificationModeSwitch.showButton(ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);
        executeFadeOutAnimation();

        // Verify the end action after fade-out.
        final ArgumentCaptor<Runnable> endActionCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(mViewPropertyAnimator).withEndAction(endActionCaptor.capture());

        endActionCaptor.getValue().run();

        verify(mViewPropertyAnimator).cancel();
        verify(mWindowManager).removeView(mSpyImageView);
    }

    @Test
    public void onDensityChanged_buttonIsShowing_updateResourcesAndLayout() {
        mMagnificationModeSwitch.showButton(ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);
        resetAndStubMockImageViewAndAnimator();

        mMagnificationModeSwitch.onConfigurationChanged(ActivityInfo.CONFIG_DENSITY);

        InOrder inOrder = Mockito.inOrder(mWindowManager);
        inOrder.verify(mWindowManager).updateViewLayout(eq(mSpyImageView), any());
        inOrder.verify(mWindowManager).removeView(eq(mSpyImageView));
        inOrder.verify(mWindowManager).addView(eq(mSpyImageView), any());
        verify(mSpyImageView).setSystemGestureExclusionRects(any(List.class));
    }

    @Test
    public void onApplyWindowInsetsWithBoundsChange_buttonIsShowing_updateLayoutPosition() {
        mMagnificationModeSwitch.showButton(ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);

        mMagnificationModeSwitch.mDraggableWindowBounds.inset(10, 10);
        mSpyImageView.onApplyWindowInsets(WindowInsets.CONSUMED);

        verify(mWindowManager).updateViewLayout(eq(mSpyImageView),
                any(WindowManager.LayoutParams.class));
        assertLayoutPosition(/* toLeftScreenEdge= */ false);
    }

    @Test
    public void onSystemBarsInsetsChanged_buttonIsShowing_draggableBoundsChanged() {
        mMagnificationModeSwitch.showButton(ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);
        final Rect oldDraggableBounds = new Rect(mMagnificationModeSwitch.mDraggableWindowBounds);

        mWindowManager.setWindowInsets(new WindowInsets.Builder()
                .setInsetsIgnoringVisibility(systemBars(), Insets.of(0, 20, 0, 20))
                .build());
        mSpyImageView.onApplyWindowInsets(WindowInsets.CONSUMED);

        assertNotEquals(oldDraggableBounds, mMagnificationModeSwitch.mDraggableWindowBounds);
    }

    @Test
    public void onDisplayCutoutInsetsChanged_buttonIsShowing_draggableBoundsChanged() {
        mMagnificationModeSwitch.showButton(ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);
        final Rect oldDraggableBounds = new Rect(mMagnificationModeSwitch.mDraggableWindowBounds);

        mWindowManager.setWindowInsets(new WindowInsets.Builder()
                .setInsetsIgnoringVisibility(displayCutout(), Insets.of(20, 30, 20, 30))
                .build());
        mSpyImageView.onApplyWindowInsets(WindowInsets.CONSUMED);

        assertNotEquals(oldDraggableBounds, mMagnificationModeSwitch.mDraggableWindowBounds);
    }

    @Test
    public void onWindowBoundsChanged_buttonIsShowing_draggableBoundsChanged() {
        mWindowManager.setWindowBounds(new Rect(0, 0, 800, 1000));
        mMagnificationModeSwitch.showButton(ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);
        final Rect oldDraggableBounds = new Rect(mMagnificationModeSwitch.mDraggableWindowBounds);

        mWindowManager.setWindowBounds(new Rect(0, 0, 1000, 800));
        mSpyImageView.onApplyWindowInsets(WindowInsets.CONSUMED);

        assertNotEquals(oldDraggableBounds, mMagnificationModeSwitch.mDraggableWindowBounds);
    }

    @Test
    public void onDraggingGestureFinish_buttonIsShowing_stickToRightEdge() {
        final int windowHalfWidth =
                mWindowManager.getCurrentWindowMetrics().getBounds().width() / 2;
        mMagnificationModeSwitch.showButton(ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);

        // Drag button to right side on screen
        final int offset = ViewConfiguration.get(mContext).getScaledTouchSlop() + 10;
        final long downTime = SystemClock.uptimeMillis();
        mTouchListener.onTouch(mSpyImageView, obtainMotionEvent(
                downTime, 0, ACTION_DOWN, 100, 100));
        mTouchListener.onTouch(mSpyImageView,
                obtainMotionEvent(downTime, downTime, ACTION_MOVE, windowHalfWidth - offset, 100));

        mTouchListener.onTouch(mSpyImageView, obtainMotionEvent(
                downTime, downTime, ACTION_UP, windowHalfWidth - offset, 100));

        assertLayoutPosition(/* toLeftScreenEdge= */false);
    }

    @Test
    public void performSingleTap_fullscreenMode_callbackTriggered() {
        mMagnificationModeSwitch.showButton(ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);
        resetAndStubMockImageViewAndAnimator();

        // Perform a single-tap
        final long downTime = SystemClock.uptimeMillis();
        mTouchListener.onTouch(mSpyImageView,
                obtainMotionEvent(downTime, 0, ACTION_DOWN, 100, 100));
        resetAndStubMockImageViewAndAnimator();
        mTouchListener.onTouch(mSpyImageView,
                obtainMotionEvent(downTime, downTime, ACTION_UP, 100, 100));

        verify(mClickListener).onClick(eq(mContext.getDisplayId()));
    }

    @Test
    public void sendDownEvent_fullscreenMode_fadeOutAnimationIsNull() {
        mMagnificationModeSwitch.showButton(ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);
        resetAndStubMockImageViewAndAnimator();

        final long downTime = SystemClock.uptimeMillis();
        mTouchListener.onTouch(mSpyImageView,
                obtainMotionEvent(downTime, 0, ACTION_DOWN, 100, 100));

        assertNull(mFadeOutAnimation);
    }

    @Test
    public void sendDownEvent_fullscreenModeAndFadingOut_cancelAnimation() {
        mMagnificationModeSwitch.showButton(ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);
        executeFadeOutAnimation();
        resetAndStubMockImageViewAndAnimator();

        final long downTime = SystemClock.uptimeMillis();
        mTouchListener.onTouch(mSpyImageView,
                obtainMotionEvent(downTime, 0, ACTION_DOWN, 100, 100));

        verify(mViewPropertyAnimator).cancel();
    }

    @Test
    public void performDragging_showMagnificationButton_updateViewLayout() {
        mMagnificationModeSwitch.showButton(ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);
        resetAndStubMockImageViewAndAnimator();

        // Perform dragging
        final int offset = ViewConfiguration.get(mContext).getScaledTouchSlop() + 10;
        final long downTime = SystemClock.uptimeMillis();
        mTouchListener.onTouch(mSpyImageView, obtainMotionEvent(
                downTime, 0, ACTION_DOWN, 100, 100));

        mTouchListener.onTouch(mSpyImageView,
                obtainMotionEvent(downTime, downTime, ACTION_MOVE, 100 + offset,
                        100));
        verify(mWindowManager).updateViewLayout(eq(mSpyImageView),
                any(WindowManager.LayoutParams.class));

        resetAndStubMockImageViewAndAnimator();
        mTouchListener.onTouch(mSpyImageView, obtainMotionEvent(
                downTime, downTime, ACTION_UP, 100 + offset, 100));

        verify(mClickListener, never()).onClick(anyInt());
        assertShowFadingAnimation(FADE_OUT_ALPHA);
    }

    @Test
    public void performSingleTapActionCanceled_showButtonAnimation() {
        mMagnificationModeSwitch.showButton(ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);
        resetAndStubMockImageViewAndAnimator();

        final long downTime = SystemClock.uptimeMillis();
        mTouchListener.onTouch(mSpyImageView, obtainMotionEvent(
                downTime, downTime, ACTION_DOWN, 100, 100));
        resetAndStubMockImageViewAndAnimator();
        mTouchListener.onTouch(mSpyImageView, obtainMotionEvent(
                downTime, downTime, ACTION_CANCEL, 100, 100));

        verify(mClickListener, never()).onClick(anyInt());
        assertShowFadingAnimation(FADE_OUT_ALPHA);
    }

    @Test
    public void performDraggingActionCanceled_showButtonAnimation() {
        mMagnificationModeSwitch.showButton(ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);
        resetAndStubMockImageViewAndAnimator();

        // Perform dragging
        final long downTime = SystemClock.uptimeMillis();
        final int offset = ViewConfiguration.get(mContext).getScaledTouchSlop() + 10;
        mTouchListener.onTouch(mSpyImageView, obtainMotionEvent(
                0, 0, ACTION_DOWN, 100, 100));
        mTouchListener.onTouch(mSpyImageView, obtainMotionEvent(
                downTime, downTime, ACTION_MOVE, 100 + offset, 100));
        resetAndStubMockImageViewAndAnimator();
        mTouchListener.onTouch(mSpyImageView, obtainMotionEvent(
                downTime, downTime, ACTION_CANCEL, 100 + offset, 100));

        verify(mClickListener, never()).onClick(anyInt());
        assertShowFadingAnimation(FADE_OUT_ALPHA);
    }

    @Test
    public void initializeA11yNode_showWindowModeButton_expectedValues() {
        mMagnificationModeSwitch.showButton(ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);
        final AccessibilityNodeInfo nodeInfo = new AccessibilityNodeInfo();

        mSpyImageView.onInitializeAccessibilityNodeInfo(nodeInfo);

        assertEquals(mContext.getString(R.string.magnification_mode_switch_description),
                nodeInfo.getContentDescription());
        assertEquals(mContext.getString(R.string.magnification_mode_switch_state_full_screen),
                nodeInfo.getStateDescription().toString());
        assertThat(nodeInfo.getActionList(),
                hasItems(new AccessibilityNodeInfo.AccessibilityAction(
                        ACTION_CLICK.getId(), mContext.getResources().getString(
                        R.string.magnification_open_settings_click_label))));
        assertThat(nodeInfo.getActionList(),
                hasItems(new AccessibilityNodeInfo.AccessibilityAction(
                        R.id.accessibility_action_move_up, mContext.getResources().getString(
                        R.string.accessibility_control_move_up))));
        assertThat(nodeInfo.getActionList(),
                hasItems(new AccessibilityNodeInfo.AccessibilityAction(
                        R.id.accessibility_action_move_down, mContext.getResources().getString(
                        R.string.accessibility_control_move_down))));
        assertThat(nodeInfo.getActionList(),
                hasItems(new AccessibilityNodeInfo.AccessibilityAction(
                        R.id.accessibility_action_move_left, mContext.getResources().getString(
                        R.string.accessibility_control_move_left))));
        assertThat(nodeInfo.getActionList(),
                hasItems(new AccessibilityNodeInfo.AccessibilityAction(
                        R.id.accessibility_action_move_right, mContext.getResources().getString(
                        R.string.accessibility_control_move_right))));
    }

    @Test
    public void performClickA11yActions_showWindowModeButton_callbackTriggered() {
        mMagnificationModeSwitch.showButton(ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);
        resetAndStubMockImageViewAndAnimator();

        mSpyImageView.performAccessibilityAction(
                ACTION_CLICK.getId(), null);

        verify(mClickListener).onClick(mContext.getDisplayId());
    }

    @Test
    public void performMoveLeftA11yAction_showButtonAtRightEdge_moveToLeftEdge() {
        mMagnificationModeSwitch.showButton(ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);

        mSpyImageView.performAccessibilityAction(
                R.id.accessibility_action_move_left, null);

        assertLayoutPosition(/* toLeftScreenEdge= */true);
    }

    @Test
    public void showButton_showFadeOutAnimation_fadeOutAnimationCanceled() {
        mMagnificationModeSwitch.showButton(ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);
        assertShowFadingAnimation(FADE_OUT_ALPHA);
        resetAndStubMockImageViewAndAnimator();

        mMagnificationModeSwitch.showButton(ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);

        verify(mViewPropertyAnimator).cancel();
        assertEquals(1f, mSpyImageView.getAlpha());
        assertShowFadingAnimation(FADE_OUT_ALPHA);
    }

    @Test
    public void showButton_hasAccessibilityWindowTitle() {
        mMagnificationModeSwitch.showButton(ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);

        final WindowManager.LayoutParams layoutPrams =
                mWindowManager.getLayoutParamsFromAttachedView();
        assertNotNull(layoutPrams);
        assertEquals(getContext().getResources().getString(
                com.android.internal.R.string.android_system_label),
                layoutPrams.accessibilityTitle);
    }

    @Test
    public void showButton_registerComponentCallbacks() {
        mMagnificationModeSwitch.showButton(ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);

        verify(mContext).registerComponentCallbacks(mMagnificationModeSwitch);
    }

    @Test
    public void onLocaleChanged_buttonIsShowing_updateA11yWindowTitle() {
        final String newA11yWindowTitle = "new a11y window title";
        mMagnificationModeSwitch.showButton(ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);

        getContext().getOrCreateTestableResources().addOverride(
                com.android.internal.R.string.android_system_label, newA11yWindowTitle);
        mMagnificationModeSwitch.onConfigurationChanged(ActivityInfo.CONFIG_LOCALE);

        final WindowManager.LayoutParams layoutParams =
                mWindowManager.getLayoutParamsFromAttachedView();
        assertNotNull(layoutParams);
        assertEquals(newA11yWindowTitle, layoutParams.accessibilityTitle);
    }

    @Test
    public void onRotationChanged_buttonIsShowing_expectedYPosition() {
        final Rect windowBounds = mWindowManager.getCurrentWindowMetrics().getBounds();
        mMagnificationModeSwitch.showButton(ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);
        final Rect oldDraggableBounds = new Rect(mMagnificationModeSwitch.mDraggableWindowBounds);
        final float windowHeightFraction =
                (float) (mWindowManager.getLayoutParamsFromAttachedView().y
                        - oldDraggableBounds.top) / oldDraggableBounds.height();

        // The window bounds and the draggable bounds are changed due to the rotation change.
        final Rect newWindowBounds = new Rect(0, 0, windowBounds.height(), windowBounds.width());
        mWindowManager.setWindowBounds(newWindowBounds);
        mMagnificationModeSwitch.onConfigurationChanged(ActivityInfo.CONFIG_ORIENTATION);

        int expectedY = (int) (windowHeightFraction
                * mMagnificationModeSwitch.mDraggableWindowBounds.height())
                + mMagnificationModeSwitch.mDraggableWindowBounds.top;
        assertEquals(
                "The Y position does not keep the same height ratio after the rotation changed.",
                expectedY, mWindowManager.getLayoutParamsFromAttachedView().y);
    }

    @Test
    public void onScreenSizeChanged_buttonIsShowingOnTheRightSide_expectedPosition() {
        final Rect windowBounds = mWindowManager.getCurrentWindowMetrics().getBounds();
        mMagnificationModeSwitch.showButton(ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);
        final Rect oldDraggableBounds = new Rect(mMagnificationModeSwitch.mDraggableWindowBounds);
        final float windowHeightFraction =
                (float) (mWindowManager.getLayoutParamsFromAttachedView().y
                        - oldDraggableBounds.top) / oldDraggableBounds.height();

        // The window bounds and the draggable bounds are changed due to the screen size change.
        final Rect tmpRect = new Rect(windowBounds);
        tmpRect.scale(2);
        final Rect newWindowBounds = new Rect(tmpRect);
        mWindowManager.setWindowBounds(newWindowBounds);
        mMagnificationModeSwitch.onConfigurationChanged(ActivityInfo.CONFIG_SCREEN_SIZE);

        final int expectedX = mMagnificationModeSwitch.mDraggableWindowBounds.right;
        final int expectedY = (int) (windowHeightFraction
                * mMagnificationModeSwitch.mDraggableWindowBounds.height())
                + mMagnificationModeSwitch.mDraggableWindowBounds.top;
        assertEquals(expectedX, mWindowManager.getLayoutParamsFromAttachedView().x);
        assertEquals(expectedY, mWindowManager.getLayoutParamsFromAttachedView().y);
    }

    private void assertShowFadingAnimation(float alpha) {
        final ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        if (alpha == FADE_IN_ALPHA) { // Fade-in
            verify(mSpyImageView).postOnAnimation(runnableCaptor.capture());
        } else { // Fade-out
            verify(mSpyImageView).postOnAnimationDelayed(runnableCaptor.capture(), anyLong());
        }
        resetAndStubMockAnimator();

        runnableCaptor.getValue().run();

        verify(mViewPropertyAnimator).setDuration(eq(FADING_ANIMATION_DURATION_MS));
        verify(mViewPropertyAnimator).alpha(alpha);
        verify(mViewPropertyAnimator).start();
    }

    private void resetAndStubMockImageViewAndAnimator() {
        resetAndStubMockAnimator();
        Mockito.reset(mSpyImageView);
        final Handler handler = mock(Handler.class);
        when(mSpyImageView.getHandler()).thenReturn(handler);
        doAnswer(invocation -> {
            final Runnable runnable = invocation.getArgument(0);
            runnable.run();
            return null;
        }).when(handler).post(any(Runnable.class));
        doAnswer(invocation -> {
            final Runnable runnable = invocation.getArgument(0);
            runnable.run();
            return null;
        }).when(mSpyImageView).post(any(Runnable.class));
        doReturn(mViewPropertyAnimator).when(mSpyImageView).animate();
        doAnswer((invocation) -> {
            mFadeOutAnimation = invocation.getArgument(0);
            return null;
        }).when(mSpyImageView).postOnAnimationDelayed(any(Runnable.class), anyLong());
        doAnswer((invocation) -> {
            if (mFadeOutAnimation == invocation.getArgument(0)) {
                mFadeOutAnimation = null;
            }
            return null;
        }).when(mSpyImageView).removeCallbacks(any(Runnable.class));
    }

    private void resetAndStubMockAnimator() {
        Mockito.reset(mViewPropertyAnimator);
        doNothing().when(mViewPropertyAnimator).start();
    }

    private MotionEvent obtainMotionEvent(long downTime, long eventTime, int action, float x,
            float y) {
        return mMotionEventHelper.obtainMotionEvent(downTime, eventTime, action, x, y);
    }

    private void executeFadeOutAnimation() {
        assertNotNull(mFadeOutAnimation);
        mFadeOutAnimation.run();
        mFadeOutAnimation = null;
    }

    private void assertLayoutPosition(boolean toLeftScreenEdge) {
        final int expectedX =
                toLeftScreenEdge ? mMagnificationModeSwitch.mDraggableWindowBounds.left
                        : mMagnificationModeSwitch.mDraggableWindowBounds.right;
        final int expectedY = mMagnificationModeSwitch.mDraggableWindowBounds.bottom;
        final WindowManager.LayoutParams layoutParams =
                mWindowManager.getLayoutParamsFromAttachedView();
        assertNotNull(layoutParams);
        assertEquals(expectedX, layoutParams.x);
        assertEquals(expectedY, layoutParams.y);
    }
}
