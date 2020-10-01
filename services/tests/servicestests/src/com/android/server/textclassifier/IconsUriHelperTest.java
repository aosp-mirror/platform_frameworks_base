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

import android.net.Uri;

import androidx.test.runner.AndroidJUnit4;

import com.android.server.textclassifier.IconsUriHelper.ResourceInfo;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for {@link IconsUriHelper}.
 */
@RunWith(AndroidJUnit4.class)
public final class IconsUriHelperTest {

    private IconsUriHelper mIconsUriHelper;

    @Before
    public void setUp() {
        mIconsUriHelper = IconsUriHelper.newInstanceForTesting(null);
    }

    @Test
    public void testGetContentUri() {
        final IconsUriHelper iconsUriHelper = IconsUriHelper.newInstanceForTesting(() -> "pkgId");
        final Uri expected = new Uri.Builder()
                .scheme("content")
                .authority(IconsUriHelper.AUTHORITY)
                .path("pkgId")
                .appendPath("1234")
                .build();

        final Uri actual = iconsUriHelper.getContentUri("com.package.name", 1234);
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    public void testGetContentUri_multiplePackages() {
        final Uri uri1 = mIconsUriHelper.getContentUri("com.package.name1", 1234);
        final Uri uri2 = mIconsUriHelper.getContentUri("com.package.name2", 5678);

        assertThat(uri1.getScheme()).isEqualTo("content");
        assertThat(uri2.getScheme()).isEqualTo("content");

        assertThat(uri1.getAuthority()).isEqualTo(IconsUriHelper.AUTHORITY);
        assertThat(uri2.getAuthority()).isEqualTo(IconsUriHelper.AUTHORITY);

        assertThat(uri1.getPathSegments().get(1)).isEqualTo("1234");
        assertThat(uri2.getPathSegments().get(1)).isEqualTo("5678");
    }

    @Test
    public void testGetContentUri_samePackageIdForSamePackageName() {
        final String packageName = "com.package.name";
        final Uri uri1 = mIconsUriHelper.getContentUri(packageName, 1234);
        final Uri uri2 = mIconsUriHelper.getContentUri(packageName, 5678);

        final String id1 = uri1.getPathSegments().get(0);
        final String id2 = uri2.getPathSegments().get(0);

        assertThat(id1).isEqualTo(id2);
    }

    @Test
    public void testGetResourceInfo() {
        mIconsUriHelper.getContentUri("com.package.name1", 123);
        final Uri uri = mIconsUriHelper.getContentUri("com.package.name2", 456);
        mIconsUriHelper.getContentUri("com.package.name3", 789);

        final ResourceInfo res = mIconsUriHelper.getResourceInfo(uri);
        assertThat(res.packageName).isEqualTo("com.package.name2");
        assertThat(res.id).isEqualTo(456);
    }

    @Test
    public void testGetResourceInfo_unrecognizedUri() {
        final Uri uri = new Uri.Builder()
                .scheme("content")
                .authority(IconsUriHelper.AUTHORITY)
                .path("unrecognized")
                .appendPath("1234")
                .build();
        assertThat(mIconsUriHelper.getResourceInfo(uri)).isNull();
    }

    @Test
    public void testGetResourceInfo_invalidScheme() {
        final IconsUriHelper iconsUriHelper = IconsUriHelper.newInstanceForTesting(() -> "pkgId");
        iconsUriHelper.getContentUri("com.package.name", 1234);

        final Uri uri = new Uri.Builder()
                .scheme("file")
                .authority(IconsUriHelper.AUTHORITY)
                .path("pkgId")
                .appendPath("1234")
                .build();
        assertThat(iconsUriHelper.getResourceInfo(uri)).isNull();
    }

    @Test
    public void testGetResourceInfo_invalidAuthority() {
        final IconsUriHelper iconsUriHelper = IconsUriHelper.newInstanceForTesting(() -> "pkgId");
        iconsUriHelper.getContentUri("com.package.name", 1234);

        final Uri uri = new Uri.Builder()
                .scheme("content")
                .authority("invalid.authority")
                .path("pkgId")
                .appendPath("1234")
                .build();
        assertThat(iconsUriHelper.getResourceInfo(uri)).isNull();
    }
}

