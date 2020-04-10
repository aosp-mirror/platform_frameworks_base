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
package com.android.server.textclassifier;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.net.Uri;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Sanity test for {@link IconsContentProvider}.
 */
@RunWith(AndroidJUnit4.class)
public final class IconsContentProviderTest {

    @Test
    public void testLoadResource() {
        final Context context = ApplicationProvider.getApplicationContext();
        // Testing with the android package name because this is the only package name
        // that returns the same uri across multiple classloaders.
        final String packageName = "android";
        final int resId = android.R.drawable.btn_star;
        final Uri uri = IconsUriHelper.getInstance().getContentUri(packageName, resId);

        final Drawable expected = Icon.createWithResource(packageName, resId).loadDrawable(context);
        // Ensure we are testing with a non-empty image.
        assertThat(expected.getIntrinsicWidth()).isGreaterThan(0);
        assertThat(expected.getIntrinsicHeight()).isGreaterThan(0);

        final Drawable actual = Icon.createWithContentUri(uri).loadDrawable(context);
        assertThat(actual).isNotNull();
        assertThat(IconsContentProvider.getBitmapData(actual))
                .isEqualTo(IconsContentProvider.getBitmapData(expected));
    }

    @Test
    public void testLoadResource_badUri() {
        final Uri badUri = new Uri.Builder()
                .scheme("content")
                .authority(IconsUriHelper.AUTHORITY)
                .path("badPackageId")
                .appendPath("1234")
                .build();

        final Context context = ApplicationProvider.getApplicationContext();
        assertThat(Icon.createWithContentUri(badUri).loadDrawable(context)).isNull();
    }
}

