/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.keyguard.clock;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.verify;

import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;

import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
public final class ClockOptionsProviderTest extends SysuiTestCase {

    private static final String CONTENT_SCHEME = "content";
    private static final String AUTHORITY = "com.android.keyguard.clock";
    private static final String LIST_OPTIONS = "list_options";
    private static final String PREVIEW = "preview";
    private static final String THUMBNAIL = "thumbnail";
    private static final String MIME_TYPE_LIST_OPTIONS = "vnd.android.cursor.dir/clock_faces";
    private static final String MIME_TYPE_PNG = "image/png";
    private static final String NAME_COLUMN = "name";
    private static final String TITLE_COLUMN = "title";
    private static final String ID_COLUMN = "id";
    private static final String PREVIEW_COLUMN = "preview";
    private static final String THUMBNAIL_COLUMN = "thumbnail";

    private ClockOptionsProvider mProvider;
    private Supplier<List<ClockInfo>> mMockSupplier;
    private List<ClockInfo> mClocks;
    private Uri mListOptionsUri;
    @Mock
    private Supplier<Bitmap> mMockBitmapSupplier;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mClocks = new ArrayList<>();
        mProvider = new ClockOptionsProvider(() -> mClocks);
        mListOptionsUri = new Uri.Builder()
                .scheme(CONTENT_SCHEME)
                .authority(AUTHORITY)
                .appendPath(LIST_OPTIONS)
                .build();
    }

    @Test
    public void testGetType_listOptions() {
        Uri uri = new Uri.Builder()
                .scheme(CONTENT_SCHEME)
                .authority(AUTHORITY)
                .appendPath(LIST_OPTIONS)
                .build();
        assertThat(mProvider.getType(uri)).isEqualTo(MIME_TYPE_LIST_OPTIONS);
    }

    @Test
    public void testGetType_preview() {
        Uri uri = new Uri.Builder()
                .scheme(CONTENT_SCHEME)
                .authority(AUTHORITY)
                .appendPath(PREVIEW)
                .appendPath("id")
                .build();
        assertThat(mProvider.getType(uri)).isEqualTo(MIME_TYPE_PNG);
    }

    @Test
    public void testGetType_thumbnail() {
        Uri uri = new Uri.Builder()
                .scheme(CONTENT_SCHEME)
                .authority(AUTHORITY)
                .appendPath(THUMBNAIL)
                .appendPath("id")
                .build();
        assertThat(mProvider.getType(uri)).isEqualTo(MIME_TYPE_PNG);
    }

    @Test
    public void testQuery_noClocks() {
        Cursor cursor = mProvider.query(mListOptionsUri, null, null, null);
        assertThat(cursor.getCount()).isEqualTo(0);
    }

    @Test
    public void testQuery_listOptions() {
        mClocks.add(ClockInfo.builder()
                .setName("name_a")
                .setTitle("title_a")
                .setId("id_a")
                .build());
        mClocks.add(ClockInfo.builder()
                .setName("name_b")
                .setTitle("title_b")
                .setId("id_b")
                .build());
        Cursor cursor = mProvider.query(mListOptionsUri, null, null, null);
        assertThat(cursor.getCount()).isEqualTo(2);
        cursor.moveToFirst();
        assertThat(cursor.getString(
                cursor.getColumnIndex(NAME_COLUMN))).isEqualTo("name_a");
        assertThat(cursor.getString(
                cursor.getColumnIndex(TITLE_COLUMN))).isEqualTo("title_a");
        assertThat(cursor.getString(
                cursor.getColumnIndex(ID_COLUMN))).isEqualTo("id_a");
        assertThat(cursor.getString(
                cursor.getColumnIndex(PREVIEW_COLUMN)))
                .isEqualTo("content://com.android.keyguard.clock/preview/id_a");
        assertThat(cursor.getString(
                cursor.getColumnIndex(THUMBNAIL_COLUMN)))
                .isEqualTo("content://com.android.keyguard.clock/thumbnail/id_a");
        cursor.moveToNext();
        assertThat(cursor.getString(
                cursor.getColumnIndex(NAME_COLUMN))).isEqualTo("name_b");
        assertThat(cursor.getString(
                cursor.getColumnIndex(TITLE_COLUMN))).isEqualTo("title_b");
        assertThat(cursor.getString(
                cursor.getColumnIndex(ID_COLUMN))).isEqualTo("id_b");
        assertThat(cursor.getString(
                cursor.getColumnIndex(PREVIEW_COLUMN)))
                .isEqualTo("content://com.android.keyguard.clock/preview/id_b");
        assertThat(cursor.getString(
                cursor.getColumnIndex(THUMBNAIL_COLUMN)))
                .isEqualTo("content://com.android.keyguard.clock/thumbnail/id_b");
    }

    @Test
    public void testOpenFile_preview() throws Exception {
        mClocks.add(ClockInfo.builder()
                .setId("id")
                .setPreview(mMockBitmapSupplier)
                .build());
        Uri uri = new Uri.Builder()
                .scheme(CONTENT_SCHEME)
                .authority(AUTHORITY)
                .appendPath(PREVIEW)
                .appendPath("id")
                .build();
        mProvider.openFile(uri, "r").close();
        verify(mMockBitmapSupplier).get();
    }

    @Test
    public void testOpenFile_thumbnail() throws Exception {
        mClocks.add(ClockInfo.builder()
                .setId("id")
                .setThumbnail(mMockBitmapSupplier)
                .build());
        Uri uri = new Uri.Builder()
                .scheme(CONTENT_SCHEME)
                .authority(AUTHORITY)
                .appendPath(THUMBNAIL)
                .appendPath("id")
                .build();
        mProvider.openFile(uri, "r").close();
        verify(mMockBitmapSupplier).get();
    }
}
