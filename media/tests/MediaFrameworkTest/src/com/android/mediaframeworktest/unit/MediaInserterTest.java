/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.mediaframeworktest.unit;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.argThat;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.AttributionSource;
import android.content.ContentProviderClient;
import android.content.ContentValues;
import android.content.Context;
import android.content.IContentProvider;
import android.media.MediaInserter;
import android.net.Uri;
import android.provider.MediaStore.Audio;
import android.provider.MediaStore.Files;
import android.provider.MediaStore.Images;
import android.provider.MediaStore.Video;
import android.test.InstrumentationTestCase;
import android.test.suitebuilder.annotation.SmallTest;

import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class MediaInserterTest extends InstrumentationTestCase {

    private static final String TEST_FEATURE_ID = "testFeature";
    private MediaInserter mMediaInserter;
    private static final int TEST_BUFFER_SIZE = 10;
    private @Mock IContentProvider mMockProvider;
    private AttributionSource mAttributionSource;

    private int mFilesCounter;
    private int mAudioCounter;
    private int mVideoCounter;
    private int mImagesCounter;

    private static final String sVolumeName = "external";
    private static final Uri sAudioUri = Audio.Media.getContentUri(sVolumeName);
    private static final Uri sVideoUri = Video.Media.getContentUri(sVolumeName);
    private static final Uri sImagesUri = Images.Media.getContentUri(sVolumeName);
    private static final Uri sFilesUri = Files.getContentUri(sVolumeName);

    private static class MediaUriMatcher implements ArgumentMatcher<Uri> {
        private final Uri mUri;

        private MediaUriMatcher(Uri uri) {
            mUri = uri;
        }

        @Override
        public boolean matches(Uri actualUri) {
            return actualUri == mUri;
        }

        @Override
        public String toString() {
            return "expected a TableUri '" + mUri.toString() + "'";
        }
    }

    private static Uri eqUri(Uri in) {
        return argThat(new MediaUriMatcher(in));
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        MockitoAnnotations.initMocks(this);

        final Context attributionContext = getInstrumentation().getContext()
                .createFeatureContext(TEST_FEATURE_ID);

        final ContentProviderClient client = new ContentProviderClient(attributionContext
                        .getContentResolver(), mMockProvider, true);

        mMediaInserter = new MediaInserter(client, TEST_BUFFER_SIZE);
        mAttributionSource = attributionContext.getAttributionSource();
        mFilesCounter = 0;
        mAudioCounter = 0;
        mVideoCounter = 0;
        mImagesCounter = 0;
    }

    private ContentValues createFileContent() {
        ContentValues values = new ContentValues();
        values.put("_data", "/mnt/sdcard/file" + ++mFilesCounter);
        return values;
    }

    private ContentValues createAudioContent() {
        ContentValues values = new ContentValues();
        values.put("_data", "/mnt/sdcard/audio" + ++mAudioCounter);
        return values;
    }

    private ContentValues createVideoContent() {
        ContentValues values = new ContentValues();
        values.put("_data", "/mnt/sdcard/video" + ++mVideoCounter);
        return values;
    }

    private ContentValues createImageContent() {
        ContentValues values = new ContentValues();
        values.put("_data", "/mnt/sdcard/image" + ++mImagesCounter);
        return values;
    }

    private ContentValues createContent(Uri uri) {
        if (uri == sFilesUri) return createFileContent();
        else if (uri == sAudioUri) return createAudioContent();
        else if (uri == sVideoUri) return createVideoContent();
        else if (uri == sImagesUri) return createImageContent();
        else throw new IllegalArgumentException("Unknown URL: " + uri.toString());
    }

    private void fillBuffer(Uri uri, int numberOfFiles) throws Exception {
        ContentValues values;
        for (int i = 0; i < numberOfFiles; ++i) {
            values = createContent(uri);
            mMediaInserter.insert(uri, values);
        }
    }

    @SmallTest
    public void testInsertContentsLessThanBufferSize() throws Exception {
        fillBuffer(sFilesUri, TEST_BUFFER_SIZE - 4);
        fillBuffer(sAudioUri, TEST_BUFFER_SIZE - 3);
        fillBuffer(sVideoUri, TEST_BUFFER_SIZE - 2);
        fillBuffer(sImagesUri, TEST_BUFFER_SIZE - 1);

        verify(mMockProvider, never()).bulkInsert(eq(mAttributionSource), any(),
                any());
    }

    @SmallTest
    public void testInsertContentsEqualToBufferSize() throws Exception {
        when(mMockProvider.bulkInsert(eq(mAttributionSource), any(),
                any())).thenReturn(1);

        fillBuffer(sFilesUri, TEST_BUFFER_SIZE);
        fillBuffer(sAudioUri, TEST_BUFFER_SIZE);
        fillBuffer(sVideoUri, TEST_BUFFER_SIZE);
        fillBuffer(sImagesUri, TEST_BUFFER_SIZE);

        verify(mMockProvider, times(4)).bulkInsert(eq(mAttributionSource), any(),
                any());
    }

    @SmallTest
    public void testInsertContentsMoreThanBufferSize() throws Exception {
        when(mMockProvider.bulkInsert(eq(mAttributionSource), any(),
                any())).thenReturn(1);

        fillBuffer(sFilesUri, TEST_BUFFER_SIZE + 1);
        fillBuffer(sAudioUri, TEST_BUFFER_SIZE + 2);
        fillBuffer(sVideoUri, TEST_BUFFER_SIZE + 3);
        fillBuffer(sImagesUri, TEST_BUFFER_SIZE + 4);

        verify(mMockProvider, times(4)).bulkInsert(eq(mAttributionSource), any(),
                any());
    }

    @SmallTest
    public void testFlushAllWithEmptyContents() throws Exception {
        mMediaInserter.flushAll();
    }

    @SmallTest
    public void testFlushAllWithSomeContents() throws Exception {
        when(mMockProvider.bulkInsert(eq(mAttributionSource), any(),
                any())).thenReturn(1);

        fillBuffer(sFilesUri, TEST_BUFFER_SIZE - 4);
        fillBuffer(sAudioUri, TEST_BUFFER_SIZE - 3);
        fillBuffer(sVideoUri, TEST_BUFFER_SIZE - 2);
        fillBuffer(sImagesUri, TEST_BUFFER_SIZE - 1);
        mMediaInserter.flushAll();

        verify(mMockProvider, times(4)).bulkInsert(eq(mAttributionSource), any(),
                any());
    }

    @SmallTest
    public void testInsertContentsAfterFlushAll() throws Exception {
        when(mMockProvider.bulkInsert(eq(mAttributionSource), any(),
                any())).thenReturn(1);

        fillBuffer(sFilesUri, TEST_BUFFER_SIZE - 4);
        fillBuffer(sAudioUri, TEST_BUFFER_SIZE - 3);
        fillBuffer(sVideoUri, TEST_BUFFER_SIZE - 2);
        fillBuffer(sImagesUri, TEST_BUFFER_SIZE - 1);
        mMediaInserter.flushAll();

        fillBuffer(sFilesUri, TEST_BUFFER_SIZE + 1);
        fillBuffer(sAudioUri, TEST_BUFFER_SIZE + 2);
        fillBuffer(sVideoUri, TEST_BUFFER_SIZE + 3);
        fillBuffer(sImagesUri, TEST_BUFFER_SIZE + 4);

        verify(mMockProvider, times(8)).bulkInsert(eq(mAttributionSource), any(),
                any());
    }

    @SmallTest
    public void testInsertContentsWithDifferentSizePerContentType() throws Exception {
        when(mMockProvider.bulkInsert(eq(mAttributionSource), eqUri(sFilesUri),
                any())).thenReturn(1);
        when(mMockProvider.bulkInsert(eq(mAttributionSource), eqUri(sAudioUri),
                any())).thenReturn(1);
        when(mMockProvider.bulkInsert(eq(mAttributionSource), eqUri(sVideoUri),
                any())).thenReturn(1);
        when(mMockProvider.bulkInsert(eq(mAttributionSource), eqUri(sImagesUri),
                any())).thenReturn(1);

        for (int i = 0; i < TEST_BUFFER_SIZE; ++i) {
            fillBuffer(sFilesUri, 1);
            fillBuffer(sAudioUri, 2);
            fillBuffer(sVideoUri, 3);
            fillBuffer(sImagesUri, 4);
        }

        verify(mMockProvider, times(1)).bulkInsert(eq(mAttributionSource),
                eqUri(sFilesUri), any());
        verify(mMockProvider, times(2)).bulkInsert(eq(mAttributionSource),
                eqUri(sAudioUri), any());
        verify(mMockProvider, times(3)).bulkInsert(eq(mAttributionSource),
                eqUri(sVideoUri), any());
        verify(mMockProvider, times(4)).bulkInsert(eq(mAttributionSource),
                eqUri(sImagesUri), any());
    }
}
