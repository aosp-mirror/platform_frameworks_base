/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.wallpaper;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.hardware.display.DisplayManager;
import android.support.test.runner.AndroidJUnit4;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.FeatureFlagUtils;
import android.view.DisplayInfo;
import android.view.WindowManager;

import com.android.systemui.SysuiTestCase;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class AodMaskViewTest extends SysuiTestCase {
    private AodMaskView mMaskView;
    private DisplayInfo mDisplayInfo;
    private ImageWallpaperTransformer mTransformer;

    @Before
    public void setUp() throws Exception {
        DisplayManager displayManager =
                spy((DisplayManager) mContext.getSystemService(Context.DISPLAY_SERVICE));
        doNothing().when(displayManager).registerDisplayListener(any(), any());
        mContext.addMockSystemService(DisplayManager.class, displayManager);

        WallpaperManager wallpaperManager =
                spy((WallpaperManager) mContext.getSystemService(Context.WALLPAPER_SERVICE));
        doReturn(null).when(wallpaperManager).getWallpaperInfo();
        mContext.addMockSystemService(WallpaperManager.class, wallpaperManager);

        mTransformer = spy(new ImageWallpaperTransformer(null /* listener */));
        mMaskView = spy(new AodMaskView(getContext(), null /* attrs */, mTransformer));
        mDisplayInfo = new DisplayInfo();

        ((WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE))
                .getDefaultDisplay().getDisplayInfo(mDisplayInfo);

        FeatureFlagUtils.setEnabled(
                mContext, FeatureFlagUtils.AOD_IMAGEWALLPAPER_ENABLED, true);
    }

    @After
    public void tearDown() {
        FeatureFlagUtils.setEnabled(
                mContext, FeatureFlagUtils.AOD_IMAGEWALLPAPER_ENABLED, false);
    }

    @Test
    public void testCreateMaskView_TransformerIsNotNull() {
        assertNotNull("mTransformer should not be null", mTransformer);
    }

    @Test
    public void testAodMaskView_ShouldNotClickable() {
        assertFalse("MaskView should not be clickable", mMaskView.isClickable());
    }

    @Test
    public void testAodMaskView_OnSizeChange_ShouldUpdateTransformerOffsets() {
        mMaskView.onSizeChanged(mDisplayInfo.logicalWidth, mDisplayInfo.logicalHeight, 0, 0);
        verify(mTransformer, times(1)).updateOffsets();
    }

    @Test
    public void testAodMaskView_OnDraw_ShouldDrawTransformedImage() {
        Canvas c = new Canvas();
        RectF bounds = new RectF(0, 0, mDisplayInfo.logicalWidth, mDisplayInfo.logicalHeight);
        mMaskView.onSizeChanged(mDisplayInfo.logicalWidth, mDisplayInfo.logicalHeight, 0, 0);
        mMaskView.onStatePreChange(0, 1);
        mMaskView.onDraw(c);
        verify(mTransformer, times(1)).drawTransformedImage(c, null, null, bounds);
    }

    @Test
    public void testAodMaskView_IsDozing_ShouldUpdateAmbientModeState() {
        doNothing().when(mMaskView).setAnimatorProperty(anyBoolean());
        mMaskView.onStatePreChange(0, 1);
        mMaskView.onDozingChanged(true);
        verify(mTransformer, times(1)).updateAmbientModeState(true);
    }

    @Test
    public void testAodMaskView_IsDozing_ShouldDoTransitionOrDrawFinalFrame() {
        doNothing().when(mMaskView).setAnimatorProperty(anyBoolean());
        mMaskView.onStatePreChange(0, 1);
        mMaskView.onDozingChanged(true);
        mMaskView.onStatePostChange();
        mMaskView.onDozingChanged(false);
        verify(mMaskView, times(1)).invalidate();
        verify(mMaskView, times(1)).setAnimatorProperty(false);
    }

}
