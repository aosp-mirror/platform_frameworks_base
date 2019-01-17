/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.content;

import android.net.Uri;
import android.test.AndroidTestCase;

import androidx.test.filters.LargeTest;
import androidx.test.filters.MediumTest;
import androidx.test.filters.SmallTest;

import java.io.InputStream;
import java.util.Arrays;

/**
 * Tests reading a MemoryFile-based AssestFile from a ContentProvider running
 * in a different process.
 */
public class MemoryFileProviderTest extends AndroidTestCase {

    // reads from a cross-process AssetFileDescriptor for a MemoryFile
    @MediumTest
    public void testRead() throws Exception {
        ContentResolver resolver = getContext().getContentResolver();
        Uri uri = Uri.parse("content://android.content.MemoryFileProvider/data/1/blob");
        byte[] buf = new byte[MemoryFileProvider.TEST_BLOB.length];
        InputStream in = resolver.openInputStream(uri);
        assertNotNull(in);
        int count = in.read(buf);
        assertEquals(buf.length, count);
        assertEquals(-1, in.read());
        in.close();
        assertTrue(Arrays.equals(MemoryFileProvider.TEST_BLOB, buf));
    }

    // tests that we don't leak file descriptors or virtual address space
    @LargeTest
    public void testClose() throws Exception {
        ContentResolver resolver = getContext().getContentResolver();
        // open enough file descriptors that we will crash something if we leak FDs
        // or address space
        for (int i = 0; i < 1025; i++) {
            Uri uri = Uri.parse("content://android.content.MemoryFileProvider/huge");
            InputStream in = resolver.openInputStream(uri);
            assertNotNull("Failed to open stream number " + i, in);
            byte[] buf = new byte[MemoryFileProvider.TEST_BLOB.length];
            int count = in.read(buf);
            assertEquals(buf.length, count);
            assertTrue(Arrays.equals(MemoryFileProvider.TEST_BLOB, buf));
            in.close();
        }
    }

    // tests that we haven't broken AssestFileDescriptors for normal files.
    @SmallTest
    public void testFile() throws Exception {
        ContentResolver resolver = getContext().getContentResolver();
        Uri uri = Uri.parse("content://android.content.MemoryFileProvider/file");
        byte[] buf = new byte[MemoryFileProvider.TEST_BLOB.length];
        InputStream in = resolver.openInputStream(uri);
        assertNotNull(in);
        int count = in.read(buf);
        assertEquals(buf.length, count);
        assertEquals(-1, in.read());
        in.close();
        assertTrue(Arrays.equals(MemoryFileProvider.TEST_BLOB, buf));
    }

}
