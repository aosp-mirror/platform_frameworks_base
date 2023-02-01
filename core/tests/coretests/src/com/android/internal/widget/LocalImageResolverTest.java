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
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.BitmapFactory;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.net.Uri;

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
    public void resolveImage_invalidResource_returnsNull() throws IOException {
        // We promise IOException in case of errors - but ImageDecode will throw NotFoundException
        // in case of wrong resource. This test verifies that we throw IOException for API users.
        Icon icon = Icon.createWithResource(mContext, 0x85849454);
        Drawable d = LocalImageResolver.resolveImage(icon, mContext);
        assertThat(d).isNull();
    }

    @Test
    public void resolveImage_invalidIconUri_returnsNull() throws IOException {
        // We promise IOException in case of errors - but ImageDecode will throw NotFoundException
        // in case of wrong resource. This test verifies that we throw IOException for API users.
        Icon icon = Icon.createWithContentUri(Uri.parse("bogus://uri"));
        Drawable d = LocalImageResolver.resolveImage(icon, mContext);
        assertThat(d).isNull();
    }

    @Test(expected = IOException.class)
    public void resolveImage_invalidUri_throwsException() throws IOException {
        Drawable d = LocalImageResolver.resolveImage(Uri.parse("bogus://uri"), mContext);
        assertThat(d).isNull();
    }

    @Test
    public void resolveImage_nonBitmapResourceIcon_fallsBackToNonResizingLoad() throws IOException {
        Icon icon = Icon.createWithResource(mContext, R.drawable.blue);
        Drawable d = LocalImageResolver.resolveImage(icon, mContext);
        assertThat(d).isInstanceOf(ColorDrawable.class);
    }

    @Test(expected = IOException.class)
    public void resolveImage_nonBitmapResourceUri_throwsIoException() throws IOException {
        LocalImageResolver.resolveImage(
                Uri.parse("android.resource://com.android.frameworks.coretests/" + R.drawable.blue),
                mContext);
    }

    @Test
    public void resolveImageWithResId_nonBitmapResourceIcon_returnsNull() {
        Drawable d = LocalImageResolver.resolveImage(R.drawable.blue, mContext, 480, 480);
        assertThat(d).isNull();
    }

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
    public void resolveImage_largeResourceIcon_passedSize_resizeToDefinedSize() {
        Icon icon = Icon.createWithResource(mContext, R.drawable.big_a);
        Drawable d = LocalImageResolver.resolveImage(icon, mContext, 100, 50);

        assertThat(d).isInstanceOf(BitmapDrawable.class);
        BitmapDrawable bd = (BitmapDrawable) d;
        assertThat(bd.getBitmap().getWidth()).isLessThan(101);
        assertThat(bd.getBitmap().getHeight()).isLessThan(51);
    }

    @Test
    public void resolveImage_largeResourceIcon_negativeWidth_dontResize() {
        Icon icon = Icon.createWithResource(mContext, R.drawable.big_a);
        Drawable d = LocalImageResolver.resolveImage(icon, mContext, LocalImageResolver.NO_MAX_SIZE,
                50);

        assertThat(d).isInstanceOf(BitmapDrawable.class);
        BitmapDrawable bd = (BitmapDrawable) d;
        assertThat(bd.getBitmap().getWidth()).isGreaterThan(101);
        assertThat(bd.getBitmap().getHeight()).isGreaterThan(51);
    }

    @Test
    public void resolveImage_largeResourceIcon_negativeHeight_dontResize() {
        Icon icon = Icon.createWithResource(mContext, R.drawable.big_a);
        Drawable d = LocalImageResolver.resolveImage(icon, mContext, 100,
                LocalImageResolver.NO_MAX_SIZE);

        assertThat(d).isInstanceOf(BitmapDrawable.class);
        BitmapDrawable bd = (BitmapDrawable) d;
        assertThat(bd.getBitmap().getWidth()).isGreaterThan(101);
        assertThat(bd.getBitmap().getHeight()).isGreaterThan(51);
    }

    @Test
    public void resolveImage_largeBitmapIcon_passedNegativeWidth_dontResize() {
        Icon icon = Icon.createWithBitmap(
                BitmapFactory.decodeResource(mContext.getResources(), R.drawable.big_a));
        Drawable d = LocalImageResolver.resolveImage(icon, mContext, LocalImageResolver.NO_MAX_SIZE,
                50);

        assertThat(d).isInstanceOf(BitmapDrawable.class);
        BitmapDrawable bd = (BitmapDrawable) d;
        assertThat(bd.getBitmap().getWidth()).isGreaterThan(101);
        assertThat(bd.getBitmap().getHeight()).isGreaterThan(51);
    }

    @Test
    public void resolveImage_largeBitmapIcon_passedNegativeHeight_dontResize() {
        Icon icon = Icon.createWithBitmap(
                BitmapFactory.decodeResource(mContext.getResources(), R.drawable.big_a));
        Drawable d = LocalImageResolver.resolveImage(icon, mContext, LocalImageResolver.NO_MAX_SIZE,
                50);

        assertThat(d).isInstanceOf(BitmapDrawable.class);
        BitmapDrawable bd = (BitmapDrawable) d;
        assertThat(bd.getBitmap().getWidth()).isGreaterThan(101);
        assertThat(bd.getBitmap().getHeight()).isGreaterThan(51);
    }

    @Test
    public void resolveImage_smallBitmapIcon_passedSmallerSize_dontResize() {
        Icon icon = Icon.createWithResource(mContext.getResources(), R.drawable.test32x24);
        Drawable d = LocalImageResolver.resolveImage(icon, mContext, 600, 450);

        assertThat(d).isInstanceOf(BitmapDrawable.class);
        BitmapDrawable bd = (BitmapDrawable) d;
        assertThat(bd.getBitmap().getWidth()).isEqualTo(32);
        assertThat(bd.getBitmap().getHeight()).isEqualTo(24);
    }

    @Test
    public void resolveImage_largeBitmapIcon_passedSize_resizeToDefinedSize() {
        Icon icon = Icon.createWithBitmap(
                BitmapFactory.decodeResource(mContext.getResources(), R.drawable.big_a));
        Drawable d = LocalImageResolver.resolveImage(icon, mContext, 100, 50);

        assertThat(d).isInstanceOf(BitmapDrawable.class);
        BitmapDrawable bd = (BitmapDrawable) d;
        assertThat(bd.getBitmap().getWidth()).isLessThan(101);
        assertThat(bd.getBitmap().getHeight()).isLessThan(51);
    }

    @Test
    public void resolveImage_largeAdaptiveBitmapIcon_passedSize_resizeToDefinedSize() {
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

    @Test
    public void resolveImage_iconWithOtherPackageResource_usesPackageContextDefinition()
            throws IOException {
        Icon icon = Icon.createWithResource("this_is_invalid", R.drawable.test32x24);
        Drawable d = LocalImageResolver.resolveImage(icon, mContext);
        // This drawable must not be loaded - if it was, the code ignored the package specification.
        assertThat(d).isNull();
    }

    @Test
    public void resolveResourcesForIcon_notAResourceIcon_returnsNull() {
        Icon icon = Icon.createWithContentUri(Uri.parse("some_uri"));
        assertThat(LocalImageResolver.resolveResourcesForIcon(mContext, icon)).isNull();
    }

    @Test
    public void resolveResourcesForIcon_localPackageIcon_returnsPackageResources() {
        Icon icon = Icon.createWithResource(mContext, R.drawable.test32x24);
        assertThat(LocalImageResolver.resolveResourcesForIcon(mContext, icon))
                .isSameInstanceAs(mContext.getResources());
    }

    @Test
    public void resolveResourcesForIcon_iconWithoutPackageSpecificed_returnsPackageResources() {
        Icon icon = Icon.createWithResource("", R.drawable.test32x24);
        assertThat(LocalImageResolver.resolveResourcesForIcon(mContext, icon))
                .isSameInstanceAs(mContext.getResources());
    }

    @Test
    public void resolveResourcesForIcon_systemPackageSpecified_returnsSystemPackage() {
        Icon icon = Icon.createWithResource("android", R.drawable.test32x24);
        assertThat(LocalImageResolver.resolveResourcesForIcon(mContext, icon)).isSameInstanceAs(
                Resources.getSystem());
    }

    @Test
    public void resolveResourcesForIcon_differentPackageSpecified_returnsPackageResources() throws
            PackageManager.NameNotFoundException {
        String pkg = "com.android.settings";
        Resources res = mContext.getPackageManager().getResourcesForApplication(pkg);
        int resId = res.getIdentifier("ic_android", "drawable", pkg);
        Icon icon = Icon.createWithResource(pkg, resId);

        assertThat(LocalImageResolver.resolveResourcesForIcon(mContext, icon).getDrawable(resId,
                mContext.getTheme())).isNotNull();
    }

    @Test
    public void resolveResourcesForIcon_invalidPackageSpecified_returnsNull() {
        Icon icon = Icon.createWithResource("invalid.package", R.drawable.test32x24);
        assertThat(LocalImageResolver.resolveResourcesForIcon(mContext, icon)).isNull();
    }
}
