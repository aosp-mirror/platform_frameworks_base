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

import static com.android.systemui.accessibility.MagnificationModeSwitch.getIconResId;

import static junit.framework.Assert.assertEquals;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
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
import android.widget.ImageView;

import androidx.test.filters.SmallTest;

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

    private ImageView mSpyImageView;
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
        mSpyImageView = Mockito.spy(new ImageView(mContext));
        doAnswer(invocation -> null).when(mSpyImageView).setOnTouchListener(
                mTouchListenerCaptor.capture());
        initMockImageViewAndAnimator();

        mMagnificationModeSwitch = new MagnificationModeSwitch(mContext, mSpyImageView);
    }

    @Test
    public void removeButton_removeView() {
        mMagnificationModeSwitch.showButton(ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW);

        mMagnificationModeSwitch.removeButton();

        verify(mWindowManager).removeView(mSpyImageView);
        // First invocation is in showButton.
        verify(mViewPropertyAnimator, times(2)).cancel();
    }

    @Test
    public void showWindowModeButton_fullscreenMode_addViewAndSetImageResource() {
        mMagnificationModeSwitch.showButton(ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW);

        verify(mSpyImageView).setAlpha(1.0f);
        verify(mSpyImageView).setImageResource(
                getIconResId(ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW));
        assertShowButtonAnimation();
        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(mViewPropertyAnimator).withEndAction(captor.capture());
        verify(mWindowManager).addView(eq(mSpyImageView), any(WindowManager.LayoutParams.class));

        captor.getValue().run();

        // First invocation is in showButton.
        verify(mViewPropertyAnimator, times(2)).cancel();
        verify(mWindowManager).removeView(mSpyImageView);
    }

    @Test
    public void onConfigurationChanged_setImageResource() {
        mMagnificationModeSwitch.showButton(ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);
        mMagnificationModeSwitch.onConfigurationChanged(ActivityInfo.CONFIG_DENSITY);

        verify(mSpyImageView, times(2)).setImageResource(
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
        verify(mViewPropertyAnimator).cancel();
        verify(mSpyImageView).setImageResource(
                getIconResId(ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW));
        verify(mWindowManager).removeView(mSpyImageView);
        final int actualMode = Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE, 0);
        assertEquals(ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW, actualMode);
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
        verify(mSpyImageView).setAlpha(1.0f);
        verify(mViewPropertyAnimator).cancel();

        listener.onTouch(mSpyImageView, MotionEvent.obtain(
                0, ViewConfiguration.getTapTimeout(), ACTION_MOVE, 100 + offset, 100, 0));
        verify(mWindowManager).updateViewLayout(eq(mSpyImageView),
                any(WindowManager.LayoutParams.class));

        resetMockImageViewAndAnimator();
        listener.onTouch(mSpyImageView, MotionEvent.obtain(
                0, ViewConfiguration.getTapTimeout() + 10, ACTION_UP, 100 + offset, 100, 0));
        verify(mSpyImageView).setAlpha(1.0f);
        assertModeUnchanged(previousMode);
        assertShowButtonAnimation();
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
        verify(mSpyImageView).setAlpha(1.0f);
        assertModeUnchanged(previousMode);
        assertShowButtonAnimation();
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
        verify(mSpyImageView).setAlpha(1.0f);
        assertModeUnchanged(previousMode);
        assertShowButtonAnimation();
    }

    private void assertModeUnchanged(int expectedMode) {
        final int actualMode = Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE, 0);
        assertEquals(expectedMode, actualMode);
    }

    private void assertShowButtonAnimation() {
        verify(mViewPropertyAnimator).cancel();
        verify(mViewPropertyAnimator).setDuration(anyLong());
        verify(mViewPropertyAnimator).setStartDelay(anyLong());
        verify(mViewPropertyAnimator).alpha(anyFloat());
        verify(mViewPropertyAnimator).start();
    }

    private void initMockImageViewAndAnimator() {
        when(mViewPropertyAnimator.setDuration(anyLong())).thenReturn(mViewPropertyAnimator);
        when(mViewPropertyAnimator.alpha(anyFloat())).thenReturn(mViewPropertyAnimator);
        when(mViewPropertyAnimator.setStartDelay(anyLong())).thenReturn(mViewPropertyAnimator);
        when(mViewPropertyAnimator.withEndAction(any(Runnable.class))).thenReturn(
                mViewPropertyAnimator);

        when(mSpyImageView.animate()).thenReturn(mViewPropertyAnimator);
    }

    private void resetMockImageViewAndAnimator() {
        Mockito.reset(mViewPropertyAnimator);
        Mockito.reset(mSpyImageView);
        initMockImageViewAndAnimator();
    }
}
