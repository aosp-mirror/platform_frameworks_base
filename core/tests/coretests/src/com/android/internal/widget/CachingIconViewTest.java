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

import static com.google.common.truth.Truth.assertThat;

import android.annotation.Nullable;
import android.content.Context;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.graphics.drawable.InsetDrawable;
import android.net.Uri;
import android.util.TypedValue;
import android.view.LayoutInflater;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.frameworks.coretests.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class CachingIconViewTest {

    private Context mContext;

    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    @Test
    public void invalidIcon_skipsLoadSuccessfully() {
        CachingIconView view = (CachingIconView) LayoutInflater.from(mContext).inflate(
                R.layout.caching_icon_view_test_max_size, null);
        view.setImageIcon(Icon.createWithResource(mContext, 0x85743222));
        Drawable drawable = view.getDrawable();
        assertThat(drawable).isNull();
    }

    @Test
    public void customDrawable_setImageIcon_skipsResizeSuccessfully() {
        CachingIconView view = (CachingIconView) LayoutInflater.from(mContext).inflate(
                R.layout.caching_icon_view_test_max_size, null);
        view.setImageIcon(Icon.createWithResource(mContext, R.drawable.custom_drawable));
        Drawable drawable = view.getDrawable();
        assertThat(drawable).isInstanceOf(InsetDrawable.class);
    }

    @Test
    public void customDrawable_setImageIconAsync_skipsResizeSuccessfully() {
        CachingIconView view = (CachingIconView) LayoutInflater.from(mContext).inflate(
                R.layout.caching_icon_view_test_max_size, null);
        view.setImageIconAsync(Icon.createWithResource(mContext, R.drawable.custom_drawable)).run();
        Drawable drawable = view.getDrawable();
        assertThat(drawable).isInstanceOf(InsetDrawable.class);
    }

    @Test
    public void customDrawable_setImageResource_skipsResizeSuccessfully() {
        CachingIconView view = (CachingIconView) LayoutInflater.from(mContext).inflate(
                R.layout.caching_icon_view_test_max_size, null);
        view.setImageResource(R.drawable.custom_drawable);
        Drawable drawable = view.getDrawable();
        assertThat(drawable).isInstanceOf(InsetDrawable.class);
    }

    @Test
    public void customDrawable_setImageResourceAsync_skipsResizeSuccessfully() {
        CachingIconView view = (CachingIconView) LayoutInflater.from(mContext).inflate(
                R.layout.caching_icon_view_test_max_size, null);
        view.setImageResourceAsync(R.drawable.custom_drawable).run();
        Drawable drawable = view.getDrawable();
        assertThat(drawable).isInstanceOf(InsetDrawable.class);
    }

    @Test
    public void customDrawable_setImageUri_skipsResizeSuccessfully() {
        CachingIconView view = (CachingIconView) LayoutInflater.from(mContext).inflate(
                R.layout.caching_icon_view_test_max_size, null);
        view.setImageURI(Uri.parse(
                "android.resource://com.android.frameworks.coretests/"
                        + R.drawable.custom_drawable));
        Drawable drawable = view.getDrawable();
        assertThat(drawable).isInstanceOf(InsetDrawable.class);
    }

    @Test
    public void customDrawable_setImageUriAsync_skipsResizeSuccessfully() {
        CachingIconView view = (CachingIconView) LayoutInflater.from(mContext).inflate(
                R.layout.caching_icon_view_test_max_size, null);
        view.setImageURIAsync(Uri.parse(
                "android.resource://com.android.frameworks.coretests/"
                        + R.drawable.custom_drawable)).run();
        Drawable drawable = view.getDrawable();
        assertThat(drawable).isInstanceOf(InsetDrawable.class);
    }

    @Test
    public void maxDrawableDimensionsSet_setImageIcon_resizesImageIcon() {
        CachingIconView view = (CachingIconView) LayoutInflater.from(mContext).inflate(
                R.layout.caching_icon_view_test_max_size, null);
        view.setImageIcon(Icon.createWithResource(mContext, R.drawable.big_a));

        assertDrawableResized(view);
    }

    @Test
    public void maxDrawableWithNoDimensionsSet_setImageIcon_doesNotResizeImageIcon() {
        CachingIconView view = (CachingIconView) LayoutInflater.from(mContext).inflate(
                R.layout.caching_icon_view_test_no_max_size, null);
        view.setImageIcon(Icon.createWithResource(mContext, R.drawable.big_a));

        assertDrawableNotResized(view);
    }

    @Test
    public void maxDrawableDimensionsSet_setImageIconAsync_resizesImageIcon() {
        CachingIconView view = (CachingIconView) LayoutInflater.from(mContext).inflate(
                R.layout.caching_icon_view_test_max_size, null);
        view.setImageIconAsync(Icon.createWithResource(mContext, R.drawable.big_a)).run();

        assertDrawableResized(view);
    }

    @Test
    public void maxDrawableWithNoDimensionsSet_setImageIconAsync_doesNotResizeImageIcon() {
        CachingIconView view = (CachingIconView) LayoutInflater.from(mContext).inflate(
                R.layout.caching_icon_view_test_no_max_size, null);
        view.setImageIconAsync(Icon.createWithResource(mContext, R.drawable.big_a)).run();

        assertDrawableNotResized(view);
    }

    @Test
    public void maxDrawableDimensionsSet_setImageResource_resizesImageIcon() {
        CachingIconView view = (CachingIconView) LayoutInflater.from(mContext).inflate(
                R.layout.caching_icon_view_test_max_size, null);
        view.setImageResource(R.drawable.big_a);

        assertDrawableResized(view);
    }

    @Test
    public void maxDrawableWithNoDimensionsSet_setImageResource_doesNotResizeImageIcon() {
        CachingIconView view = (CachingIconView) LayoutInflater.from(mContext).inflate(
                R.layout.caching_icon_view_test_no_max_size, null);
        view.setImageResource(R.drawable.big_a);

        assertDrawableNotResized(view);
    }

    @Test
    public void maxDrawableDimensionsSet_setImageResourceAsync_resizesImageIcon() {
        CachingIconView view = (CachingIconView) LayoutInflater.from(mContext).inflate(
                R.layout.caching_icon_view_test_max_size, null);
        view.setImageResourceAsync(R.drawable.big_a).run();

        assertDrawableResized(view);
    }

    @Test
    public void maxDrawableWithNoDimensionsSet_setImageResourceAsync_doesNotResizeImageIcon() {
        CachingIconView view = (CachingIconView) LayoutInflater.from(mContext).inflate(
                R.layout.caching_icon_view_test_no_max_size, null);
        view.setImageResourceAsync(R.drawable.big_a).run();

        assertDrawableNotResized(view);
    }

    @Test
    public void maxDrawableDimensionsSet_setImageUri_resizesImageIcon() {
        CachingIconView view = (CachingIconView) LayoutInflater.from(mContext).inflate(
                R.layout.caching_icon_view_test_max_size, null);
        view.setImageURI(Uri.parse(
                "android.resource://com.android.frameworks.coretests/" + R.drawable.big_a));

        assertDrawableResized(view);
    }

    @Test
    public void maxDrawableWithNoDimensionsSet_setImageUri_doesNotResizeImageIcon() {
        CachingIconView view = (CachingIconView) LayoutInflater.from(mContext).inflate(
                R.layout.caching_icon_view_test_no_max_size, null);
        view.setImageURI(Uri.parse(
                "android.resource://com.android.frameworks.coretests/" + R.drawable.big_a));

        assertDrawableNotResized(view);
    }

    @Test
    public void maxDrawableDimensionsSet_setImageUriAsync_resizesImageIcon() {
        CachingIconView view = (CachingIconView) LayoutInflater.from(mContext).inflate(
                R.layout.caching_icon_view_test_max_size, null);
        view.setImageURIAsync(Uri.parse(
                "android.resource://com.android.frameworks.coretests/" + R.drawable.big_a)).run();

        assertDrawableResized(view);
    }

    @Test
    public void maxDrawableWithNoDimensionsSet_setImageUriAsync_doesNotResizeImageIcon() {
        CachingIconView view = (CachingIconView) LayoutInflater.from(mContext).inflate(
                R.layout.caching_icon_view_test_no_max_size, null);
        view.setImageURIAsync(Uri.parse(
                "android.resource://com.android.frameworks.coretests/" + R.drawable.big_a)).run();

        assertDrawableNotResized(view);
    }


    private void assertDrawableResized(@Nullable CachingIconView view) {
        assertThat(view).isNotNull();
        int maxSize =
                (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 80f,
                        mContext.getResources().getDisplayMetrics());
        assertThat(view.getMaxDrawableHeight()).isEqualTo(maxSize);
        assertThat(view.getMaxDrawableWidth()).isEqualTo(maxSize);

        Drawable drawable = view.getDrawable();
        assertThat(drawable).isInstanceOf(BitmapDrawable.class);
        BitmapDrawable bitmapDrawable = (BitmapDrawable) drawable;
        assertThat(bitmapDrawable.getBitmap().getWidth()).isLessThan(maxSize + 1);
        assertThat(bitmapDrawable.getBitmap().getHeight()).isLessThan(maxSize + 1);
    }

    private void assertDrawableNotResized(@Nullable CachingIconView view) {
        assertThat(view).isNotNull();
        int maxSize =
                (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 80f,
                        mContext.getResources().getDisplayMetrics());
        assertThat(view.getMaxDrawableHeight()).isEqualTo(-1);
        assertThat(view.getMaxDrawableWidth()).isEqualTo(-1);

        Drawable drawable = view.getDrawable();
        assertThat(drawable).isInstanceOf(BitmapDrawable.class);
        BitmapDrawable bitmapDrawable = (BitmapDrawable) drawable;
        assertThat(bitmapDrawable.getBitmap().getWidth()).isGreaterThan(maxSize);
        assertThat(bitmapDrawable.getBitmap().getHeight()).isGreaterThan(maxSize);
    }
}
