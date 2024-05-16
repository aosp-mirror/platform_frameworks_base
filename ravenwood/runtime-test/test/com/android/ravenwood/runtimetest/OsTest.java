/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.ravenwood.runtimetest;

import static org.junit.Assert.assertEquals;

import android.system.Os;
import android.system.OsConstants;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.ravenwood.common.JvmWorkaround;
import com.android.ravenwood.common.RavenwoodRuntimeNative;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;

@RunWith(AndroidJUnit4.class)
public class OsTest {
    public interface ConsumerWithThrow<T> {
        void accept(T var1) throws Exception;
    }

    private void withTestFile(ConsumerWithThrow<FileDescriptor> consumer) throws Exception {
        File file = File.createTempFile("osTest", "bin");
        try (var raf = new RandomAccessFile(file, "rw")) {
            var fd = raf.getFD();

            try (var os = new FileOutputStream(fd)) {
                os.write(1);
                os.write(2);
                os.write(3);
                os.write(4);

                consumer.accept(fd);
            }
        }
    }

    @Test
    public void testLseek() throws Exception {
        withTestFile((fd) -> {
            assertEquals(4, Os.lseek(fd, 4, OsConstants.SEEK_SET));
            assertEquals(4, Os.lseek(fd, 0, OsConstants.SEEK_CUR));
            assertEquals(6, Os.lseek(fd, 2, OsConstants.SEEK_CUR));
        });
    }

    @Test
    public void testDup() throws Exception {
        withTestFile((fd) -> {
            var dup = Os.dup(fd);

            checkAreDup(fd, dup);
        });
    }

    @Test
    public void testPipe2() throws Exception {
        var fds = Os.pipe2(0);

        write(fds[1], 123);
        assertEquals(123, read(fds[0]));
    }

    @Test
    public void testFcntlInt() throws Exception {
        withTestFile((fd) -> {
            var dupInt = Os.fcntlInt(fd, 0, 0);

            var dup = new FileDescriptor();
            JvmWorkaround.getInstance().setFdInt(dup, dupInt);

            checkAreDup(fd, dup);
        });
    }

    private static void write(FileDescriptor fd, int oneByte)  throws IOException {
        // Create a dup to avoid closing the FD.
        try (var dup = new FileOutputStream(RavenwoodRuntimeNative.dup(fd))) {
            dup.write(oneByte);
        }
    }

    private static int read(FileDescriptor fd) throws IOException {
        // Create a dup to avoid closing the FD.
        try (var dup = new FileInputStream(RavenwoodRuntimeNative.dup(fd))) {
            return dup.read();
        }
    }

    private static void checkAreDup(FileDescriptor fd1, FileDescriptor fd2) throws Exception {
        assertEquals(4, Os.lseek(fd1, 4, OsConstants.SEEK_SET));
        assertEquals(4, Os.lseek(fd1, 0, OsConstants.SEEK_CUR));

        // Dup'ed FD shares the same position.
        assertEquals(4, Os.lseek(fd2, 0, OsConstants.SEEK_CUR));

        assertEquals(6, Os.lseek(fd1, 2, OsConstants.SEEK_CUR));
        assertEquals(6, Os.lseek(fd2, 0, OsConstants.SEEK_CUR));
    }
}
