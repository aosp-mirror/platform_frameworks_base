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
import static android.provider.Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW;
import static android.view.MotionEvent.ACTION_CANCEL;
import static android.view.MotionEvent.ACTION_DOWN;
import static android.view.MotionEvent.ACTION_MOVE;
import static android.view.MotionEvent.ACTION_UP;
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
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Handler;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.testing.AndroidTestingRunner;
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

import java.util.List;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class MagnificationModeSwitchTest extends SysuiTestCase {

    private static final float FADE_IN_ALPHA = 1f;
    private static final float FADE_OUT_ALPHA = 0f;

    private ImageView mSpyImageView;
    @Mock
    private AccessibilityManager mAccessibilityManager;
    @Mock
    private SfVsyncFrameCallbackProvider mSfVsyncFrameProvider;
    @Mock
    private Handler mHandler;
    private TestableWindowManager mWindowManager;
    private ViewPropertyAnimator mViewPropertyAnimator;
    private MagnificationModeSwitch mMagnificationModeSwitch;
    private View.OnTouchListener mTouchListener;
    private Runnable mFadeOutAnimation;
    private MotionEventHelper mMotionEventHelper = new MotionEventHelper();

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
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
        mMagnificationModeSwitch = new MagnificationModeSwitch(mContext, mSpyImageView,
                mSfVsyncFrameProvider);
        assertNotNull(mTouchListener);
    }

    @After
    public void tearDown() {
        mFadeOutAnimation = null;
        mMotionEventHelper.recycleEvents();
    }

    @Test
    public void removeButton_buttonIsShowing_removeView() {
        mMagnificationModeSwitch.showButton(ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW);

        mMagnificationModeSwitch.removeButton();

        verify(mWindowManager).removeView(mSpyImageView);
        verify(mViewPropertyAnimator).cancel();
    }

    @Test
    public void showWindowModeButton_fullscreenMode_addViewAndSetImageResource() {
        mMagnificationModeSwitch.showButton(ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW);

        verify(mSpyImageView).setImageResource(
                getIconResId(ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW));
        verify(mWindowManager).addView(eq(mSpyImageView), any(WindowManager.LayoutParams.class));
        assertShowFadingAnimation(FADE_IN_ALPHA);
        assertShowFadingAnimation(FADE_OUT_ALPHA);
    }

    @Test
    public void showButton_excludeSystemGestureArea() {
        mMagnificationModeSwitch.showButton(ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW);

        verify(mSpyImageView).setSystemGestureExclusionRects(any(List.class));
    }

    @Test
    public void showMagnificationButton_setA11yTimeout_postDelayedAnimationWithA11yTimeout() {
        final int a11yTimeout = 12345;
        when(mAccessibilityManager.getRecommendedTimeoutMillis(anyInt(), anyInt())).thenReturn(
                a11yTimeout);

        mMagnificationModeSwitch.showButton(ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW);

        verify(mAccessibilityManager).getRecommendedTimeoutMillis(
                DEFAULT_FADE_OUT_ANIMATION_DELAY_MS, AccessibilityManager.FLAG_CONTENT_ICONS
                        | AccessibilityManager.FLAG_CONTENT_CONTROLS);
        verify(mSpyImageView).postOnAnimationDelayed(any(Runnable.class), eq((long) a11yTimeout));
    }

    @Test
    public void showMagnificationButton_windowModeAndFadingOut_verifyAnimationEndAction() {
        mMagnificationModeSwitch.showButton(ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW);
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

        verify(mWindowManager).updateViewLayout(eq(mSpyImageView), any());
        verify(mSpyImageView).setSystemGestureExclusionRects(any(List.class));
    }

    @Test
    public void onApplyWindowInsetsWithBoundsChange_buttonIsShowing_updateLayoutPosition() {
        mMagnificationModeSwitch.showButton(ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);

        mMagnificationModeSwitch.mDraggableWindowBounds.inset(10, 10);
        mSpyImageView.onApplyWindowInsets(WindowInsets.CONSUMED);

        verify(mWindowManager).updateViewLayout(eq(mSpyImageView),
                any(WindowManager.LayoutParams.class));
        assertLayoutPosition();
    }

    @Test
    public void onApplyWindowInsetsWithWindowInsetsChange_buttonIsShowing_draggableBoundsChanged() {
        mMagnificationModeSwitch.showButton(ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);
        final Rect oldDraggableBounds = new Rect(mMagnificationModeSwitch.mDraggableWindowBounds);

        mWindowManager.setWindowInsets(new WindowInsets.Builder()
                .setInsetsIgnoringVisibility(systemBars(), Insets.of(0, 20, 0, 20))
                .build());
        mSpyImageView.onApplyWindowInsets(WindowInsets.CONSUMED);

        assertNotEquals(oldDraggableBounds, mMagnificationModeSwitch.mDraggableWindowBounds);
    }

    @Test
    public void performSingleTap_fullscreenMode_removeViewAndChangeSettingsValue() {
        mMagnificationModeSwitch.showButton(ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);
        resetAndStubMockImageViewAndAnimator();

        // Perform a single-tap
        final long downTime = SystemClock.uptimeMillis();
        mTouchListener.onTouch(mSpyImageView,
                obtainMotionEvent(downTime, 0, ACTION_DOWN, 100, 100));
        resetAndStubMockImageViewAndAnimator();
        mTouchListener.onTouch(mSpyImageView,
                obtainMotionEvent(downTime, downTime, ACTION_UP, 100, 100));

        verifyTapAction(ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW);
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
        final int previousMode = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE, 0, UserHandle.USER_CURRENT);

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

        assertModeUnchanged(previousMode);
        assertShowFadingAnimation(FADE_OUT_ALPHA);
    }

    @Test
    public void performSingleTapActionCanceled_showButtonAnimation() {
        mMagnificationModeSwitch.showButton(ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);
        resetAndStubMockImageViewAndAnimator();
        final int previousMode = Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE, 0);

        final long downTime = SystemClock.uptimeMillis();
        mTouchListener.onTouch(mSpyImageView, obtainMotionEvent(
                downTime, downTime, ACTION_DOWN, 100, 100));
        resetAndStubMockImageViewAndAnimator();
        mTouchListener.onTouch(mSpyImageView, obtainMotionEvent(
                downTime, downTime, ACTION_CANCEL, 100, 100));

        assertModeUnchanged(previousMode);
        assertShowFadingAnimation(FADE_OUT_ALPHA);
    }

    @Test
    public void performDraggingActionCanceled_showButtonAnimation() {
        mMagnificationModeSwitch.showButton(ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);
        resetAndStubMockImageViewAndAnimator();
        final int previousMode = Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE, 0);

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

        assertModeUnchanged(previousMode);
        assertShowFadingAnimation(FADE_OUT_ALPHA);
    }

    @Test
    public void initializeA11yNode_showWindowModeButton_expectedValues() {
        mMagnificationModeSwitch.showButton(ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW);
        final AccessibilityNodeInfo nodeInfo = new AccessibilityNodeInfo();

        mSpyImageView.onInitializeAccessibilityNodeInfo(nodeInfo);

        assertEquals(mContext.getString(R.string.magnification_mode_switch_description),
                nodeInfo.getContentDescription());
        assertEquals(mContext.getString(R.string.magnification_mode_switch_state_window),
                nodeInfo.getStateDescription());
        assertThat(nodeInfo.getActionList(),
                hasItems(new AccessibilityNodeInfo.AccessibilityAction(
                        ACTION_CLICK.getId(), mContext.getResources().getString(
                        R.string.magnification_mode_switch_click_label))));
    }

    @Test
    public void performA11yActions_showWindowModeButton_verifyTapAction() {
        mMagnificationModeSwitch.showButton(ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW);
        resetAndStubMockImageViewAndAnimator();

        mSpyImageView.performAccessibilityAction(
                ACTION_CLICK.getId(), null);

        verifyTapAction(ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);
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
        mMagnificationModeSwitch.showButton(ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW);

        final WindowManager.LayoutParams layoutPrams =
                mWindowManager.getLayoutParamsFromAttachedView();
        assertNotNull(layoutPrams);
        assertEquals(getContext().getResources().getString(
                com.android.internal.R.string.android_system_label),
                layoutPrams.accessibilityTitle);
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

    private void assertModeUnchanged(int expectedMode) {
        final int actualMode = Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE, 0);
        assertEquals(expectedMode, actualMode);
    }

    private void assertShowFadingAnimation(float alpha) {
        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
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

    /**
     * Verifies the tap behaviour including the image of the button and the magnification mode.
     *
     * @param expectedMode the expected mode after tapping
     */
    private void verifyTapAction(int expectedMode) {
        verify(mViewPropertyAnimator).cancel();
        verify(mSpyImageView).setImageResource(
                getIconResId(expectedMode));
        verify(mWindowManager).removeView(mSpyImageView);
        final int actualMode = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE, 0, UserHandle.USER_CURRENT);
        assertEquals(expectedMode, actualMode);
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

    private void assertLayoutPosition() {
        final int expectedX = mMagnificationModeSwitch.mDraggableWindowBounds.right;
        final int expectedY = mMagnificationModeSwitch.mDraggableWindowBounds.bottom;
        final WindowManager.LayoutParams layoutParams =
                mWindowManager.getLayoutParamsFromAttachedView();
        assertNotNull(layoutParams);
        assertEquals(expectedX, layoutParams.x);
        assertEquals(expectedY, layoutParams.y);
    }
}
