/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.os;

import static android.os.ParcelFileDescriptor.MODE_READ_ONLY;
import static android.os.ParcelFileDescriptor.MODE_READ_WRITE;
import static android.os.RedactingFileDescriptor.removeRange;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.platform.test.annotations.IgnoreUnderRavenwood;
import android.platform.test.ravenwood.RavenwoodRule;
import android.system.Os;

import androidx.test.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Arrays;

@RunWith(AndroidJUnit4.class)
@IgnoreUnderRavenwood(blockedBy = RedactingFileDescriptor.class)
public class RedactingFileDescriptorTest {
    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule();

    private Context mContext;
    private File mFile;

    @Before
    public void setUp() throws Exception {
        mContext = InstrumentationRegistry.getContext();
        mFile = File.createTempFile("redacting", "dat");
        try (FileOutputStream out = new FileOutputStream(mFile)) {
            final byte[] buf = new byte[1_000_000];
            Arrays.fill(buf, (byte) 64);
            out.write(buf);
        }
    }

    @After
    public void tearDown() throws Exception {
        mFile.delete();
    }

    @Test
    public void testSingleByte() throws Exception {
        final FileDescriptor fd = RedactingFileDescriptor.open(mContext, mFile, MODE_READ_ONLY,
                new long[] { 10, 11 }, new long[] {}).getFileDescriptor();

        final byte[] buf = new byte[1_000];
        assertEquals(buf.length, Os.read(fd, buf, 0, buf.length));
        for (int i = 0; i < buf.length; i++) {
            if (i == 10) {
                assertEquals(0, buf[i]);
            } else {
                assertEquals(64, buf[i]);
            }
        }
    }

    @Test
    public void testRanges() throws Exception {
        final FileDescriptor fd = RedactingFileDescriptor.open(mContext, mFile, MODE_READ_ONLY,
                new long[] { 100, 200, 300, 400 }, new long[] {}).getFileDescriptor();

        final byte[] buf = new byte[10];
        assertEquals(buf.length, Os.pread(fd, buf, 0, 10, 90));
        assertArrayEquals(new byte[] { 64, 64, 64, 64, 64, 64, 64, 64, 64, 64 }, buf);

        assertEquals(buf.length, Os.pread(fd, buf, 0, 10, 95));
        assertArrayEquals(new byte[] { 64, 64, 64, 64, 64, 0, 0, 0, 0, 0 }, buf);

        assertEquals(buf.length, Os.pread(fd, buf, 0, 10, 100));
        assertArrayEquals(new byte[] { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, }, buf);

        assertEquals(buf.length, Os.pread(fd, buf, 0, 10, 195));
        assertArrayEquals(new byte[] { 0, 0, 0, 0, 0, 64, 64, 64, 64, 64 }, buf);

        assertEquals(buf.length, Os.pread(fd, buf, 0, 10, 395));
        assertArrayEquals(new byte[] { 0, 0, 0, 0, 0, 64, 64, 64, 64, 64 }, buf);
    }

    @Test
    public void testEntireFile() throws Exception {
        final FileDescriptor fd = RedactingFileDescriptor.open(mContext, mFile, MODE_READ_ONLY,
                new long[] { 0, 5_000_000 }, new long[] {}).getFileDescriptor();

        try (FileInputStream in = new FileInputStream(fd)) {
            int val;
            while ((val = in.read()) != -1) {
                assertEquals(0, val);
            }
        }
    }

    @Test
    public void testReadWrite() throws Exception {
        final FileDescriptor fd = RedactingFileDescriptor.open(mContext, mFile, MODE_READ_WRITE,
                new long[] { 100, 200, 300, 400 }, new long[] {}).getFileDescriptor();

        // Redacted at first
        final byte[] buf = new byte[10];
        assertEquals(buf.length, Os.pread(fd, buf, 0, 10, 95));
        assertArrayEquals(new byte[] { 64, 64, 64, 64, 64, 0, 0, 0, 0, 0 }, buf);

        // But we can see data that we've written
        Os.pwrite(fd, new byte[] { 32, 32 }, 0, 2, 102);
        assertEquals(buf.length, Os.pread(fd, buf, 0, 10, 95));
        assertArrayEquals(new byte[] { 64, 64, 64, 64, 64, 0, 0, 32, 32, 0 }, buf);
    }

    @Test
    public void testRemoveRange() throws Exception {
        // Removing outside ranges should have no changes
        assertArrayEquals(new long[] { 100, 200, 300, 400 },
                removeRange(new long[] { 100, 200, 300, 400 }, 0, 100));
        assertArrayEquals(new long[] { 100, 200, 300, 400 },
                removeRange(new long[] { 100, 200, 300, 400 }, 200, 300));
        assertArrayEquals(new long[] { 100, 200, 300, 400 },
                removeRange(new long[] { 100, 200, 300, 400 }, 400, 500));

        // Removing full regions
        assertArrayEquals(new long[] { 100, 200 },
                removeRange(new long[] { 100, 200, 300, 400 }, 300, 400));
        assertArrayEquals(new long[] { 100, 200 },
                removeRange(new long[] { 100, 200, 300, 400 }, 250, 450));
        assertArrayEquals(new long[] { 300, 400 },
                removeRange(new long[] { 100, 200, 300, 400 }, 50, 250));
        assertArrayEquals(new long[] { },
                removeRange(new long[] { 100, 200, 300, 400 }, 0, 5_000_000));
    }

    @Test
    public void testRemoveRange_Partial() throws Exception {
        assertArrayEquals(new long[] { 150, 200, 300, 400 },
                removeRange(new long[] { 100, 200, 300, 400 }, 50, 150));
        assertArrayEquals(new long[] { 100, 150, 300, 400 },
                removeRange(new long[] { 100, 200, 300, 400 }, 150, 250));
        assertArrayEquals(new long[] { 100, 150, 350, 400 },
                removeRange(new long[] { 100, 200, 300, 400 }, 150, 350));
        assertArrayEquals(new long[] { 100, 150 },
                removeRange(new long[] { 100, 200, 300, 400 }, 150, 500));
    }

    @Test
    public void testRemoveRange_Hole() throws Exception {
        assertArrayEquals(new long[] { 100, 125, 175, 200, 300, 400 },
                removeRange(new long[] { 100, 200, 300, 400 }, 125, 175));
        assertArrayEquals(new long[] { 100, 200 },
                removeRange(new long[] { 100, 200 }, 150, 150));
    }

    @Test
    public void testFreeAtStart() throws Exception {
        final FileDescriptor fd = RedactingFileDescriptor.open(mContext, mFile, MODE_READ_WRITE,
                new long[] { 1, 10 }, new long[] {1}).getFileDescriptor();

        final byte[] buf = new byte[10];
        assertEquals(buf.length, Os.pread(fd, buf, 0, 10, 0));
        assertArrayEquals(
                new byte[] { 64, (byte) 'f', (byte) 'r', (byte) 'e', (byte) 'e', 0, 0, 0, 0, 0 },
                buf);
    }

    @Test
    public void testFreeAtOffset() throws Exception {
        final FileDescriptor fd = RedactingFileDescriptor.open(mContext, mFile, MODE_READ_WRITE,
                new long[] { 1, 10 }, new long[] {3}).getFileDescriptor();

        final byte[] buf = new byte[10];
        assertEquals(buf.length, Os.pread(fd, buf, 0, 10, 0));
        assertArrayEquals(
                new byte[] { 64, 0, 0, (byte) 'f', (byte) 'r', (byte) 'e', (byte) 'e', 0, 0, 0 },
                buf);
    }

    @Test
    public void testFreeAcrossRedactionStart() throws Exception {
        final FileDescriptor fd = RedactingFileDescriptor.open(mContext, mFile, MODE_READ_WRITE,
                new long[] { 1, 10 }, new long[] {0}).getFileDescriptor();

        final byte[] buf = new byte[10];
        assertEquals(buf.length, Os.pread(fd, buf, 0, 10, 0));
        assertArrayEquals(
                new byte[] { 64, (byte) 'r', (byte) 'e', (byte) 'e', 0, 0, 0, 0, 0, 0 },
                buf);
    }

    @Test
    public void testFreeAcrossRedactionEnd() throws Exception {
        final FileDescriptor fd = RedactingFileDescriptor.open(mContext, mFile, MODE_READ_WRITE,
                new long[] { 1, 3 }, new long[] {2}).getFileDescriptor();

        final byte[] buf = new byte[10];
        assertEquals(buf.length, Os.pread(fd, buf, 0, 10, 0));
        assertArrayEquals(
                new byte[] { 64, 0, (byte) 'f', 64, 64, 64, 64, 64, 64, 64 },
                buf);
    }

    @Test
    public void testFreeOutsideRedaction() throws Exception {
        final FileDescriptor fd = RedactingFileDescriptor.open(mContext, mFile, MODE_READ_WRITE,
                new long[] { 1, 8 }, new long[] { 8 }).getFileDescriptor();

        final byte[] buf = new byte[10];
        assertEquals(buf.length, Os.pread(fd, buf, 0, 10, 0));
        assertArrayEquals(
                new byte[] { 64, 0, 0, 0, 0, 0, 0, 0, 64, 64 },
                buf);
    }

    @Test
    public void testFreeMultipleRedactions() throws Exception {
        final FileDescriptor fd = RedactingFileDescriptor.open(mContext, mFile, MODE_READ_WRITE,
                new long[] { 1, 2, 3, 4 }, new long[] { 0 }).getFileDescriptor();

        final byte[] buf = new byte[10];
        assertEquals(buf.length, Os.pread(fd, buf, 0, 10, 0));
        assertArrayEquals(
                new byte[] { 64, (byte) 'r', 64, (byte) 'e', 64, 64, 64, 64, 64, 64 },
                buf);
    }
}
