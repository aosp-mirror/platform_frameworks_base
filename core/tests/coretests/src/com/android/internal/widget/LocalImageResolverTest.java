/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.internal.widget;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;

import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.frameworks.coretests.R;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;

@RunWith(AndroidJUnit4ClassRunner.class)
public class LocalImageResolverTest {

    private final Context mContext = InstrumentationRegistry.getInstrumentation().getContext();

    @Test
    public void resolveImage_largeBitmapIcon_defaultSize_resizeToDefaultSize() throws
            IOException {
        Icon icon = Icon.createWithBitmap(
                BitmapFactory.decodeResource(mContext.getResources(), R.drawable.big_a));
        Drawable d = LocalImageResolver.resolveImage(icon, mContext);

        assertThat(d).isInstanceOf(BitmapDrawable.class);
        BitmapDrawable bd = (BitmapDrawable) d;
        // No isLessOrEqualThan sadly.
        assertThat(bd.getBitmap().getWidth()).isLessThan(
                LocalImageResolver.DEFAULT_MAX_SAFE_ICON_SIZE_PX + 1);
        assertThat(bd.getBitmap().getHeight()).isLessThan(
                LocalImageResolver.DEFAULT_MAX_SAFE_ICON_SIZE_PX + 1);
    }

    @Test
    public void resolveImage_largeAdaptiveBitmapIcon_defaultSize_resizeToDefaultSize() throws
            IOException {
        Icon icon = Icon.createWithAdaptiveBitmap(
                BitmapFactory.decodeResource(mContext.getResources(), R.drawable.big_a));
        Drawable d = LocalImageResolver.resolveImage(icon, mContext);

        assertThat(d).isInstanceOf(AdaptiveIconDrawable.class);
        BitmapDrawable bd = (BitmapDrawable) ((AdaptiveIconDrawable) d).getForeground();
        // No isLessOrEqualThan sadly.
        assertThat(bd.getBitmap().getWidth()).isLessThan(
                LocalImageResolver.DEFAULT_MAX_SAFE_ICON_SIZE_PX + 1);
        assertThat(bd.getBitmap().getHeight()).isLessThan(
                LocalImageResolver.DEFAULT_MAX_SAFE_ICON_SIZE_PX + 1);
    }

    @Test
    public void resolveImage_largeResourceIcon_defaultSize_resizeToDefaultSize() throws
            IOException {
        Icon icon = Icon.createWithResource(mContext, R.drawable.big_a);
        Drawable d = LocalImageResolver.resolveImage(icon, mContext);

        assertThat(d).isInstanceOf(BitmapDrawable.class);
        BitmapDrawable bd = (BitmapDrawable) d;
        // No isLessOrEqualThan sadly.
        assertThat(bd.getBitmap().getWidth()).isLessThan(
                LocalImageResolver.DEFAULT_MAX_SAFE_ICON_SIZE_PX + 1);
        assertThat(bd.getBitmap().getHeight()).isLessThan(
                LocalImageResolver.DEFAULT_MAX_SAFE_ICON_SIZE_PX + 1);
    }

    @Test
    public void resolveImage_largeResourceIcon_passedSize_resizeToDefinedSize() throws
            IOException {
        Icon icon = Icon.createWithResource(mContext, R.drawable.big_a);
        Drawable d = LocalImageResolver.resolveImage(icon, mContext, 100, 50);

        assertThat(d).isInstanceOf(BitmapDrawable.class);
        BitmapDrawable bd = (BitmapDrawable) d;
        assertThat(bd.getBitmap().getWidth()).isLessThan(101);
        assertThat(bd.getBitmap().getHeight()).isLessThan(51);
    }

    @Test
    public void resolveImage_largeBitmapIcon_passedSize_resizeToDefinedSize() throws
            IOException {
        Icon icon = Icon.createWithBitmap(
                BitmapFactory.decodeResource(mContext.getResources(), R.drawable.big_a));
        Drawable d = LocalImageResolver.resolveImage(icon, mContext, 100, 50);

        assertThat(d).isInstanceOf(BitmapDrawable.class);
        BitmapDrawable bd = (BitmapDrawable) d;
        assertThat(bd.getBitmap().getWidth()).isLessThan(101);
        assertThat(bd.getBitmap().getHeight()).isLessThan(51);
    }

    @Test
    public void resolveImage_largeAdaptiveBitmapIcon_passedSize_resizeToDefinedSize() throws
            IOException {
        Icon icon = Icon.createWithAdaptiveBitmap(
                BitmapFactory.decodeResource(mContext.getResources(), R.drawable.big_a));
        Drawable d = LocalImageResolver.resolveImage(icon, mContext, 100, 50);

        assertThat(d).isInstanceOf(AdaptiveIconDrawable.class);
        BitmapDrawable bd = (BitmapDrawable) ((AdaptiveIconDrawable) d).getForeground();
        assertThat(bd.getBitmap().getWidth()).isLessThan(101);
        assertThat(bd.getBitmap().getHeight()).isLessThan(51);
    }


    @Test
    public void resolveImage_smallResourceIcon_defaultSize_untouched() throws IOException {
        Icon icon = Icon.createWithResource(mContext, R.drawable.test32x24);
        Drawable d = LocalImageResolver.resolveImage(icon, mContext);

        assertThat(d).isInstanceOf(BitmapDrawable.class);
        BitmapDrawable bd = (BitmapDrawable) d;
        assertThat(bd.getBitmap().getWidth()).isEqualTo(32);
        assertThat(bd.getBitmap().getHeight()).isEqualTo(24);
    }

    @Test
    public void resolveImage_smallBitmapIcon_defaultSize_untouched() throws IOException {
        Icon icon = Icon.createWithBitmap(
                BitmapFactory.decodeResource(mContext.getResources(), R.drawable.test32x24));
        final int originalWidth = icon.getBitmap().getWidth();
        final int originalHeight = icon.getBitmap().getHeight();

        Drawable d = LocalImageResolver.resolveImage(icon, mContext);

        assertThat(d).isInstanceOf(BitmapDrawable.class);
        BitmapDrawable bd = (BitmapDrawable) d;
        assertThat(bd.getBitmap().getWidth()).isEqualTo(originalWidth);
        assertThat(bd.getBitmap().getHeight()).isEqualTo(originalHeight);
    }

    @Test
    public void resolveImage_smallAdaptiveBitmapIcon_defaultSize_untouched() throws IOException {
        Icon icon = Icon.createWithAdaptiveBitmap(
                BitmapFactory.decodeResource(mContext.getResources(), R.drawable.test32x24));
        final int originalWidth = icon.getBitmap().getWidth();
        final int originalHeight = icon.getBitmap().getHeight();

        Drawable d = LocalImageResolver.resolveImage(icon, mContext);
        assertThat(d).isInstanceOf(AdaptiveIconDrawable.class);
        BitmapDrawable bd = (BitmapDrawable) ((AdaptiveIconDrawable) d).getForeground();
        assertThat(bd.getBitmap().getWidth()).isEqualTo(originalWidth);
        assertThat(bd.getBitmap().getHeight()).isEqualTo(originalHeight);

    }
}
