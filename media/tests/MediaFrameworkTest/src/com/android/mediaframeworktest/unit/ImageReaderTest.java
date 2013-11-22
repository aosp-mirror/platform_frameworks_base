/*
 * Copyright (C) 2013 The Android Open Source Project
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

import static org.mockito.Mockito.*;

import android.graphics.ImageFormat;
import android.media.Image;
import android.media.Image.Plane;
import android.media.ImageReader;
import android.media.ImageReader.OnImageAvailableListener;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

public class ImageReaderTest extends AndroidTestCase {

    private static final String TAG = "ImageReaderTest-unit";

    private static final int DEFAULT_WIDTH = 640;
    private static final int DEFAULT_HEIGHT = 480;
    private static final int DEFAULT_FORMAT = ImageFormat.YUV_420_888;
    private static final int DEFAULT_MAX_IMAGES = 3;

    private ImageReader mReader;
    private Image mImage1;
    private Image mImage2;
    private Image mImage3;

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        /**
         * Workaround for mockito and JB-MR2 incompatibility
         *
         * Avoid java.lang.IllegalArgumentException: dexcache == null
         * https://code.google.com/p/dexmaker/issues/detail?id=2
         */
        System.setProperty("dexmaker.dexcache", getContext().getCacheDir().toString());

        // TODO: refactor above into one of the test runners

        mReader = spy(ImageReader.newInstance(DEFAULT_WIDTH, DEFAULT_HEIGHT, DEFAULT_FORMAT,
                DEFAULT_MAX_IMAGES));
        mImage1 = mock(Image.class);
        mImage2 = mock(Image.class);
        mImage3 = mock(Image.class);

        /**
         * Ensure rest of classes are mockable
         */
        {
            mock(Plane.class);
            mock(OnImageAvailableListener.class);
        }

    }

    @Override
    protected void tearDown() throws Exception {
        mReader.close();

        super.tearDown();
    }

    /**
     * Return null when there is nothing in the image queue.
     */
    @SmallTest
    public void testGetLatestImageEmpty() {
        when(mReader.acquireNextImage()).thenReturn(null);
        when(mReader.acquireNextImageNoThrowISE()).thenReturn(null);
        assertEquals(null, mReader.acquireLatestImage());
    }

    /**
     * Return the last image from the image queue, close up the rest.
     */
    @SmallTest
    public void testGetLatestImage1() {
        when(mReader.acquireNextImage()).thenReturn(mImage1);
        when(mReader.acquireNextImageNoThrowISE()).thenReturn(null);
        assertEquals(mImage1, mReader.acquireLatestImage());
        verify(mImage1, never()).close();
    }

    /**
     * Return the last image from the image queue, close up the rest.
     */
    @SmallTest
    public void testGetLatestImage2() {
        when(mReader.acquireNextImage()).thenReturn(mImage1);
        when(mReader.acquireNextImageNoThrowISE()).thenReturn(mImage2).thenReturn(null);
        assertEquals(mImage2, mReader.acquireLatestImage());
        verify(mImage1, atLeastOnce()).close();
        verify(mImage2, never()).close();
    }

    /**
     * Return the last image from the image queue, close up the rest.
     */
    @SmallTest
    public void testGetLatestImage3() {
        when(mReader.acquireNextImage()).thenReturn(mImage1);
        when(mReader.acquireNextImageNoThrowISE()).thenReturn(mImage2).
                                                   thenReturn(mImage3).
                                                   thenReturn(null);
        assertEquals(mImage3, mReader.acquireLatestImage());
        verify(mImage1, atLeastOnce()).close();
        verify(mImage2, atLeastOnce()).close();
        verify(mImage3, never()).close();
    }

    /**
     * Return null if get a IllegalStateException with no images in the queue.
     */
    @SmallTest
    public void testGetLatestImageTooManyBuffersAcquiredEmpty()  {
        when(mReader.acquireNextImage()).thenThrow(new IllegalStateException());
        try {
            mReader.acquireLatestImage();
            fail("Expected IllegalStateException to be thrown");
        } catch(IllegalStateException e) {
        }
    }

    /**
     * All images are cleaned up when we get an unexpected Error.
     */
    @SmallTest
    public void testGetLatestImageExceptionalError() {
        when(mReader.acquireNextImage()).thenReturn(mImage1);
        when(mReader.acquireNextImageNoThrowISE()).thenReturn(mImage2).
                                                   thenReturn(mImage3).
                                                   thenThrow(new OutOfMemoryError());
        try {
            mReader.acquireLatestImage();
            fail("Impossible");
        } catch(OutOfMemoryError e) {
        }

        verify(mImage1, atLeastOnce()).close();
        verify(mImage2, atLeastOnce()).close();
        verify(mImage3, atLeastOnce()).close();
    }

    /**
     * All images are cleaned up when we get an unexpected RuntimeException.
     */
    @SmallTest
    public void testGetLatestImageExceptionalRuntime() {

        when(mReader.acquireNextImage()).thenReturn(mImage1);
        when(mReader.acquireNextImageNoThrowISE()).thenReturn(mImage2).
                                                   thenReturn(mImage3).
                                                   thenThrow(new RuntimeException());
        try {
            mReader.acquireLatestImage();
            fail("Impossible");
        } catch(RuntimeException e) {
        }

        verify(mImage1, atLeastOnce()).close();
        verify(mImage2, atLeastOnce()).close();
        verify(mImage3, atLeastOnce()).close();
    }
}
