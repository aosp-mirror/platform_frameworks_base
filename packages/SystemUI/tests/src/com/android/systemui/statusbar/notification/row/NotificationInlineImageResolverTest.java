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

package com.android.systemui.statusbar.notification.row;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class NotificationInlineImageResolverTest extends SysuiTestCase {

    NotificationInlineImageResolver mResolver;
    Bitmap mBitmap;
    BitmapDrawable mBitmapDrawable;
    Uri mUri;

    @Before
    public void setup() {
        mResolver = spy(new NotificationInlineImageResolver(mContext, null));
        mBitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888);
        mBitmapDrawable = new BitmapDrawable(mContext.getResources(), mBitmap);
        mUri = mock(Uri.class);
    }

    @Test
    public void refreshMaxImageSizes() {
        assertNotEquals("Starts different height", mResolver.mMaxImageHeight, 20);
        assertNotEquals("Starts different width", mResolver.mMaxImageWidth, 15);

        doReturn(20).when(mResolver).getMaxImageHeight();
        doReturn(15).when(mResolver).getMaxImageWidth();

        mResolver.updateMaxImageSizes();

        assertEquals("Height matches new config", mResolver.mMaxImageHeight, 20);
        assertEquals("Width matches new config", mResolver.mMaxImageWidth, 15);
    }

    @Test
    public void resolveImage_sizeTooBig() throws IOException {
        doReturn(mBitmapDrawable).when(mResolver).resolveImageInternal(mUri);
        mResolver.mMaxImageHeight = 5;
        mResolver.mMaxImageWidth = 5;

        // original bitmap size is 10x10
        BitmapDrawable resolved = (BitmapDrawable) mResolver.resolveImage(mUri);
        Bitmap resolvedBitmap = resolved.getBitmap();
        assertEquals("Bitmap width reduced", 5, resolvedBitmap.getWidth());
        assertEquals("Bitmap height reduced", 5, resolvedBitmap.getHeight());
        assertNotSame("Bitmap replaced", resolvedBitmap, mBitmap);
    }

    @Test
    public void resolveImage_sizeOK() throws IOException {
        doReturn(mBitmapDrawable).when(mResolver).resolveImageInternal(mUri);
        mResolver.mMaxImageWidth = 15;
        mResolver.mMaxImageHeight = 15;

        // original bitmap size is 10x10
        BitmapDrawable resolved = (BitmapDrawable) mResolver.resolveImage(mUri);
        Bitmap resolvedBitmap = resolved.getBitmap();
        assertEquals("Bitmap width unchanged", 10, resolvedBitmap.getWidth());
        assertEquals("Bitmap height unchanged", 10, resolvedBitmap.getHeight());
        assertSame("Bitmap not replaced", resolvedBitmap, mBitmap);
    }
}
