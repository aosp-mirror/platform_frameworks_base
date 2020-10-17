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
import static android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK;

import static com.android.systemui.accessibility.MagnificationModeSwitch.DEFAULT_FADE_OUT_ANIMATION_DELAY_MS;
import static com.android.systemui.accessibility.MagnificationModeSwitch.FADING_ANIMATION_DURATION_MS;
import static com.android.systemui.accessibility.MagnificationModeSwitch.getIconResId;

import static junit.framework.Assert.assertEquals;

import static org.hamcrest.CoreMatchers.hasItems;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.provider.Settings;
import android.testing.AndroidTestingRunner;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewPropertyAnimator;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;

import androidx.test.filters.SmallTest;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class MagnificationModeSwitchTest extends SysuiTestCase {

    private static final float FADE_IN_ALPHA = 1f;
    private static final float FADE_OUT_ALPHA = 0f;

    private ImageView mSpyImageView;
    @Mock
    private AccessibilityManager mAccessibilityManager;
    @Mock
    private WindowManager mWindowManager;
    @Mock
    private ViewPropertyAnimator mViewPropertyAnimator;
    private MagnificationModeSwitch mMagnificationModeSwitch;
    @Captor
    private ArgumentCaptor<View.OnTouchListener> mTouchListenerCaptor;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        WindowManager wm = mContext.getSystemService(WindowManager.class);
        doAnswer(invocation ->
                wm.getMaximumWindowMetrics()
        ).when(mWindowManager).getMaximumWindowMetrics();
        mContext.addMockSystemService(Context.WINDOW_SERVICE, mWindowManager);
        mContext.addMockSystemService(Context.ACCESSIBILITY_SERVICE, mAccessibilityManager);
        mSpyImageView = Mockito.spy(new ImageView(mContext));
        resetMockImageViewAndAnimator();

        mMagnificationModeSwitch = new MagnificationModeSwitch(mContext, mSpyImageView);
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
    public void showMagnificationButton_a11yTimeout_autoFadeOut() {
        final int a11yTimeout = 12345;
        when(mAccessibilityManager.getRecommendedTimeoutMillis(anyInt(), anyInt())).thenReturn(
                a11yTimeout);

        mMagnificationModeSwitch.showButton(ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW);

        verify(mAccessibilityManager).getRecommendedTimeoutMillis(
                DEFAULT_FADE_OUT_ANIMATION_DELAY_MS, AccessibilityManager.FLAG_CONTENT_ICONS
                        | AccessibilityManager.FLAG_CONTENT_CONTROLS);
        final ArgumentCaptor<Runnable> fadeOutCaptor = ArgumentCaptor.forClass(Runnable.class);
        final ArgumentCaptor<Long> fadeOutDelay = ArgumentCaptor.forClass(Long.class);
        verify(mSpyImageView).postOnAnimationDelayed(fadeOutCaptor.capture(),
                fadeOutDelay.capture());
        assertEquals(a11yTimeout, (long) fadeOutDelay.getValue());

        // Verify the end action after fade-out.
        fadeOutCaptor.getValue().run();
        final ArgumentCaptor<Runnable> endActionCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(mViewPropertyAnimator).withEndAction(endActionCaptor.capture());

        endActionCaptor.getValue().run();

        verify(mViewPropertyAnimator).cancel();
        verify(mWindowManager).removeView(mSpyImageView);
    }

    @Test
    public void onConfigurationChanged_buttonIsShowing_setImageResource() {
        mMagnificationModeSwitch.showButton(ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);
        resetMockImageViewAndAnimator();

        mMagnificationModeSwitch.onConfigurationChanged(ActivityInfo.CONFIG_DENSITY);

        verify(mSpyImageView).setImageResource(
                getIconResId(ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN));
    }

    @Test
    public void performSingleTap_fullscreenMode_removeViewAndChangeSettingsValue() {
        mMagnificationModeSwitch.showButton(ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);
        resetMockImageViewAndAnimator();

        // Perform a single-tap
        final View.OnTouchListener listener = mTouchListenerCaptor.getValue();
        listener.onTouch(mSpyImageView, MotionEvent.obtain(
                0, 0, ACTION_DOWN, 100, 100, 0));
        verify(mViewPropertyAnimator).cancel();

        resetMockImageViewAndAnimator();
        listener.onTouch(mSpyImageView, MotionEvent.obtain(
                0, ViewConfiguration.getTapTimeout(), ACTION_UP, 100, 100, 0));
        verifyTapAction(ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW);
    }

    @Test
    public void showMagnificationButton_performDragging_updateViewLayout() {
        mMagnificationModeSwitch.showButton(ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);
        resetMockImageViewAndAnimator();

        // Perform dragging
        final View.OnTouchListener listener = mTouchListenerCaptor.getValue();
        final int offset = ViewConfiguration.get(mContext).getScaledTouchSlop();
        final int previousMode = Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE, 0);
        listener.onTouch(mSpyImageView, MotionEvent.obtain(
                0, 0, ACTION_DOWN, 100, 100, 0));
        verify(mViewPropertyAnimator).cancel();

        listener.onTouch(mSpyImageView, MotionEvent.obtain(
                0, ViewConfiguration.getTapTimeout(), ACTION_MOVE, 100 + offset, 100, 0));
        verify(mWindowManager).updateViewLayout(eq(mSpyImageView),
                any(WindowManager.LayoutParams.class));

        resetMockImageViewAndAnimator();
        listener.onTouch(mSpyImageView, MotionEvent.obtain(
                0, ViewConfiguration.getTapTimeout() + 10, ACTION_UP, 100 + offset, 100, 0));
        assertModeUnchanged(previousMode);
        assertShowFadingAnimation(FADE_OUT_ALPHA);
    }

    @Test
    public void performSingleTapActionCanceled_showButtonAnimation() {
        mMagnificationModeSwitch.showButton(ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);
        resetMockImageViewAndAnimator();

        // Perform single tap
        final View.OnTouchListener listener = mTouchListenerCaptor.getValue();
        final int previousMode = Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE, 0);
        listener.onTouch(mSpyImageView, MotionEvent.obtain(
                0, 0, ACTION_DOWN, 100, 100, 0));

        resetMockImageViewAndAnimator();
        listener.onTouch(mSpyImageView, MotionEvent.obtain(
                0, ViewConfiguration.getTapTimeout(), ACTION_CANCEL, 100, 100, 0));
        assertModeUnchanged(previousMode);
        assertShowFadingAnimation(FADE_OUT_ALPHA);
    }

    @Test
    public void performDraggingActionCanceled_showButtonAnimation() {
        mMagnificationModeSwitch.showButton(ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);
        resetMockImageViewAndAnimator();

        // Perform dragging
        final View.OnTouchListener listener = mTouchListenerCaptor.getValue();
        final int offset = ViewConfiguration.get(mContext).getScaledTouchSlop();
        final int previousMode = Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE, 0);
        listener.onTouch(mSpyImageView, MotionEvent.obtain(
                0, 0, ACTION_DOWN, 100, 100, 0));
        listener.onTouch(mSpyImageView, MotionEvent.obtain(
                0, ViewConfiguration.getTapTimeout(), ACTION_MOVE, 100 + offset, 100, 0));

        resetMockImageViewAndAnimator();
        listener.onTouch(mSpyImageView, MotionEvent.obtain(
                0, ViewConfiguration.getTapTimeout(), ACTION_CANCEL, 100 + offset, 100, 0));
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
        resetMockImageViewAndAnimator();

        mSpyImageView.performAccessibilityAction(
                ACTION_CLICK.getId(), null);

        verifyTapAction(ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);
    }

    @Test
    public void showButton_showFadeOutAnimation_fadeOutAnimationCanceled() {
        mMagnificationModeSwitch.showButton(ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);
        assertShowFadingAnimation(FADE_OUT_ALPHA);
        resetMockImageViewAndAnimator();

        mMagnificationModeSwitch.showButton(ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);

        verify(mViewPropertyAnimator).cancel();
        assertEquals(1f, mSpyImageView.getAlpha());
        assertShowFadingAnimation(FADE_OUT_ALPHA);
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
        resetMockAnimator();

        runnableCaptor.getValue().run();

        verify(mViewPropertyAnimator).setDuration(eq(FADING_ANIMATION_DURATION_MS));
        verify(mViewPropertyAnimator).alpha(alpha);
        verify(mViewPropertyAnimator).start();
    }

    private void resetMockImageViewAndAnimator() {
        Mockito.reset(mSpyImageView);
        doAnswer(invocation -> null).when(mSpyImageView).setOnTouchListener(
                mTouchListenerCaptor.capture());
        resetMockAnimator();
    }

    private void resetMockAnimator() {
        Mockito.reset(mViewPropertyAnimator);
        when(mViewPropertyAnimator.setDuration(anyLong())).thenReturn(mViewPropertyAnimator);
        when(mViewPropertyAnimator.alpha(anyFloat())).thenReturn(mViewPropertyAnimator);
        when(mViewPropertyAnimator.withEndAction(any(Runnable.class))).thenReturn(
                mViewPropertyAnimator);
        when(mSpyImageView.animate()).thenReturn(mViewPropertyAnimator);
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
        final int actualMode = Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE, 0);
        assertEquals(expectedMode, actualMode);
    }
}
