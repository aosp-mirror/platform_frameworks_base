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

import android.content.ContentProviderClient;
import android.content.ContentValues;
import android.content.IContentProvider;
import android.media.MediaInserter;
import android.net.Uri;
import android.provider.MediaStore.Audio;
import android.provider.MediaStore.Files;
import android.provider.MediaStore.Images;
import android.provider.MediaStore.Video;
import android.test.InstrumentationTestCase;
import android.test.suitebuilder.annotation.SmallTest;

import org.easymock.EasyMock;
import org.easymock.IArgumentMatcher;

public class MediaInserterTest extends InstrumentationTestCase {

    private MediaInserter mMediaInserter;
    private static final int TEST_BUFFER_SIZE = 10;
    private IContentProvider mMockProvider;
    private String mPackageName;

    private int mFilesCounter;
    private int mAudioCounter;
    private int mVideoCounter;
    private int mImagesCounter;

    private static final String sVolumeName = "external";
    private static final Uri sAudioUri = Audio.Media.getContentUri(sVolumeName);
    private static final Uri sVideoUri = Video.Media.getContentUri(sVolumeName);
    private static final Uri sImagesUri = Images.Media.getContentUri(sVolumeName);
    private static final Uri sFilesUri = Files.getContentUri(sVolumeName);

    private static class MediaUriMatcher implements IArgumentMatcher {
        private Uri mUri;

        private MediaUriMatcher(Uri uri) {
            mUri = uri;
        }

        @Override
        public boolean matches(Object argument) {
            if (!(argument instanceof Uri)) {
                return false;
            }

            Uri actualUri = (Uri) argument;
            if (actualUri == mUri) return true;
            return false;
        }

        @Override
        public void appendTo(StringBuffer buffer) {
            buffer.append("expected a TableUri '").append(mUri).append("'");
        }

        private static Uri expectMediaUri(Uri in) {
            EasyMock.reportMatcher(new MediaUriMatcher(in));
            return null;
        }
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mMockProvider = EasyMock.createMock(IContentProvider.class);
        final ContentProviderClient client = new ContentProviderClient(
                getInstrumentation().getContext().getContentResolver(), mMockProvider, true);
        mMediaInserter = new MediaInserter(client, TEST_BUFFER_SIZE);
        mPackageName = getInstrumentation().getContext().getPackageName();
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
        EasyMock.replay(mMockProvider);

        fillBuffer(sFilesUri, TEST_BUFFER_SIZE - 4);
        fillBuffer(sAudioUri, TEST_BUFFER_SIZE - 3);
        fillBuffer(sVideoUri, TEST_BUFFER_SIZE - 2);
        fillBuffer(sImagesUri, TEST_BUFFER_SIZE - 1);

        EasyMock.verify(mMockProvider);
    }

    @SmallTest
    public void testInsertContentsEqualToBufferSize() throws Exception {
        EasyMock.expect(mMockProvider.bulkInsert(mPackageName,
                (Uri) EasyMock.anyObject(), (ContentValues[]) EasyMock.anyObject())).andReturn(1);
        EasyMock.expectLastCall().times(4);
        EasyMock.replay(mMockProvider);

        fillBuffer(sFilesUri, TEST_BUFFER_SIZE);
        fillBuffer(sAudioUri, TEST_BUFFER_SIZE);
        fillBuffer(sVideoUri, TEST_BUFFER_SIZE);
        fillBuffer(sImagesUri, TEST_BUFFER_SIZE);

        EasyMock.verify(mMockProvider);
    }

    @SmallTest
    public void testInsertContentsMoreThanBufferSize() throws Exception {
        EasyMock.expect(mMockProvider.bulkInsert(mPackageName,
                (Uri) EasyMock.anyObject(), (ContentValues[]) EasyMock.anyObject())).andReturn(1);
        EasyMock.expectLastCall().times(4);
        EasyMock.replay(mMockProvider);

        fillBuffer(sFilesUri, TEST_BUFFER_SIZE + 1);
        fillBuffer(sAudioUri, TEST_BUFFER_SIZE + 2);
        fillBuffer(sVideoUri, TEST_BUFFER_SIZE + 3);
        fillBuffer(sImagesUri, TEST_BUFFER_SIZE + 4);

        EasyMock.verify(mMockProvider);
    }

    @SmallTest
    public void testFlushAllWithEmptyContents() throws Exception {
        EasyMock.replay(mMockProvider);

        mMediaInserter.flushAll();

        EasyMock.verify(mMockProvider);
    }

    @SmallTest
    public void testFlushAllWithSomeContents() throws Exception {
        EasyMock.expect(mMockProvider.bulkInsert(mPackageName,
                (Uri) EasyMock.anyObject(), (ContentValues[]) EasyMock.anyObject())).andReturn(1);
        EasyMock.expectLastCall().times(4);
        EasyMock.replay(mMockProvider);

        fillBuffer(sFilesUri, TEST_BUFFER_SIZE - 4);
        fillBuffer(sAudioUri, TEST_BUFFER_SIZE - 3);
        fillBuffer(sVideoUri, TEST_BUFFER_SIZE - 2);
        fillBuffer(sImagesUri, TEST_BUFFER_SIZE - 1);
        mMediaInserter.flushAll();

        EasyMock.verify(mMockProvider);
    }

    @SmallTest
    public void testInsertContentsAfterFlushAll() throws Exception {
        EasyMock.expect(mMockProvider.bulkInsert(mPackageName,
                (Uri) EasyMock.anyObject(), (ContentValues[]) EasyMock.anyObject())).andReturn(1);
        EasyMock.expectLastCall().times(8);
        EasyMock.replay(mMockProvider);

        fillBuffer(sFilesUri, TEST_BUFFER_SIZE - 4);
        fillBuffer(sAudioUri, TEST_BUFFER_SIZE - 3);
        fillBuffer(sVideoUri, TEST_BUFFER_SIZE - 2);
        fillBuffer(sImagesUri, TEST_BUFFER_SIZE - 1);
        mMediaInserter.flushAll();

        fillBuffer(sFilesUri, TEST_BUFFER_SIZE + 1);
        fillBuffer(sAudioUri, TEST_BUFFER_SIZE + 2);
        fillBuffer(sVideoUri, TEST_BUFFER_SIZE + 3);
        fillBuffer(sImagesUri, TEST_BUFFER_SIZE + 4);

        EasyMock.verify(mMockProvider);
    }

    @SmallTest
    public void testInsertContentsWithDifferentSizePerContentType() throws Exception {
        EasyMock.expect(mMockProvider.bulkInsert(mPackageName,
        MediaUriMatcher.expectMediaUri(sFilesUri),
                (ContentValues[]) EasyMock.anyObject())).andReturn(1);
        EasyMock.expectLastCall().times(1);
        EasyMock.expect(mMockProvider.bulkInsert(mPackageName,
        MediaUriMatcher.expectMediaUri(sAudioUri),
                (ContentValues[]) EasyMock.anyObject())).andReturn(1);
        EasyMock.expectLastCall().times(2);
        EasyMock.expect(mMockProvider.bulkInsert(mPackageName,
        MediaUriMatcher.expectMediaUri(sVideoUri),
                (ContentValues[]) EasyMock.anyObject())).andReturn(1);
        EasyMock.expectLastCall().times(3);
        EasyMock.expect(mMockProvider.bulkInsert(mPackageName,
        MediaUriMatcher.expectMediaUri(sImagesUri),
                (ContentValues[]) EasyMock.anyObject())).andReturn(1);
        EasyMock.expectLastCall().times(4);
        EasyMock.replay(mMockProvider);

        for (int i = 0; i < TEST_BUFFER_SIZE; ++i) {
            fillBuffer(sFilesUri, 1);
            fillBuffer(sAudioUri, 2);
            fillBuffer(sVideoUri, 3);
            fillBuffer(sImagesUri, 4);
        }

        EasyMock.verify(mMockProvider);
    }
}
