/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.graphics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.os.ParcelFileDescriptor;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;

@RunWith(AndroidJUnit4.class)
public class BitmapFactoryTest {

    // tests that we can decode bitmaps from MemoryFiles
    @SmallTest
    @Test
    public void testBitmapParcelFileDescriptor() throws Exception {
        Bitmap bitmap1 = Bitmap.createBitmap(
                new int[] { Color.BLUE }, 1, 1, Bitmap.Config.RGB_565);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap1.compress(Bitmap.CompressFormat.PNG, 100, out);
        ParcelFileDescriptor pfd = ParcelFileDescriptor.fromData(out.toByteArray(), null);
        FileDescriptor fd = pfd.getFileDescriptor();
        assertNotNull("Got null FileDescriptor", fd);
        assertTrue("Got invalid FileDescriptor", fd.valid());
        Bitmap bitmap = BitmapFactory.decodeFileDescriptor(fd);
        assertNotNull("BitmapFactory returned null", bitmap);
        assertEquals("Bitmap width", 1, bitmap.getWidth());
        assertEquals("Bitmap height", 1, bitmap.getHeight());
    }

}
