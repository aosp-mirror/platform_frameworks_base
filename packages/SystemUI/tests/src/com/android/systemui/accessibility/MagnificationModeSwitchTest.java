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

import static com.android.systemui.accessibility.MagnificationModeSwitch.getIconResId;

import static junit.framework.Assert.assertEquals;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.provider.Settings;
import android.testing.AndroidTestingRunner;
import android.view.View;
import android.view.ViewPropertyAnimator;
import android.view.WindowManager;
import android.widget.ImageView;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class MagnificationModeSwitchTest extends SysuiTestCase {

    @Mock
    private ImageView mMockImageView;
    @Mock
    private WindowManager mWindowManager;
    @Mock
    private ViewPropertyAnimator mViewPropertyAnimator;
    private MagnificationModeSwitch mMagnificationModeSwitch;
    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mContext.addMockSystemService(Context.WINDOW_SERVICE, mWindowManager);

        when(mViewPropertyAnimator.setDuration(anyLong())).thenReturn(mViewPropertyAnimator);
        when(mViewPropertyAnimator.alpha(anyFloat())).thenReturn(mViewPropertyAnimator);
        when(mViewPropertyAnimator.setStartDelay(anyLong())).thenReturn(mViewPropertyAnimator);
        when(mViewPropertyAnimator.withEndAction(any(Runnable.class))).thenReturn(
                mViewPropertyAnimator);

        when(mMockImageView.animate()).thenReturn(mViewPropertyAnimator);

        mMagnificationModeSwitch = new MagnificationModeSwitch(mContext, mMockImageView);
    }

    @Test
    public void removeButton_removeView() {
        mMagnificationModeSwitch.showButton(ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW);

        mMagnificationModeSwitch.removeButton();

        verify(mWindowManager).removeView(mMockImageView);
        // First invocation is in showButton.
        verify(mViewPropertyAnimator, times(2)).cancel();
    }

    @Test
    public void showWindowModeButton_fullscreenMode_addViewAndSetImageResource() {
        mMagnificationModeSwitch.showButton(ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW);

        verify(mMockImageView).setAlpha(1.0f);
        verify(mMockImageView).setImageResource(
                getIconResId(ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW));
        verify(mViewPropertyAnimator).cancel();
        verify(mViewPropertyAnimator).setDuration(anyLong());
        verify(mViewPropertyAnimator).setStartDelay(anyLong());
        verify(mViewPropertyAnimator).alpha(anyFloat());
        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(mViewPropertyAnimator).withEndAction(captor.capture());
        verify(mWindowManager).addView(eq(mMockImageView), any(WindowManager.LayoutParams.class));

        captor.getValue().run();

        // First invocation is in showButton.
        verify(mViewPropertyAnimator, times(2)).cancel();
        verify(mWindowManager).removeView(mMockImageView);
    }

    @Test
    public void performClick_fullscreenMode_removeViewAndChangeSettingsValue() {
        ArgumentCaptor<View.OnClickListener> captor = ArgumentCaptor.forClass(
                View.OnClickListener.class);
        verify(mMockImageView).setOnClickListener(captor.capture());
        mMagnificationModeSwitch.showButton(ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);

        captor.getValue().onClick(mMockImageView);

        // First invocation is in showButton.
        verify(mViewPropertyAnimator, times(2)).cancel();
        verify(mMockImageView).setImageResource(
                getIconResId(ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW));
        verify(mWindowManager).removeView(mMockImageView);
        final int actualMode = Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE, 0);
        assertEquals(ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW, actualMode);
    }
}
