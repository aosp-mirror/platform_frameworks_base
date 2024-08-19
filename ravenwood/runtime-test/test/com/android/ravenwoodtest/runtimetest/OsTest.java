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
package com.android.ravenwoodtest.runtimetest;

import static android.system.OsConstants.S_ISBLK;
import static android.system.OsConstants.S_ISCHR;
import static android.system.OsConstants.S_ISDIR;
import static android.system.OsConstants.S_ISFIFO;
import static android.system.OsConstants.S_ISLNK;
import static android.system.OsConstants.S_ISREG;
import static android.system.OsConstants.S_ISSOCK;

import static org.junit.Assert.assertEquals;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.system.StructTimespec;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.ravenwood.common.JvmWorkaround;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public class OsTest {
    public interface ConsumerWithThrow<T> {
        void accept(T var1) throws Exception;
    }

    private void withTestFileFD(ConsumerWithThrow<FileDescriptor> consumer) throws Exception {
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

    private void withTestFile(ConsumerWithThrow<Path> consumer) throws Exception {
        var path = Files.createTempFile("osTest", "bin");
        try (var os = Files.newOutputStream(path)) {
            os.write(1);
            os.write(2);
            os.write(3);
            os.write(4);
        }
        consumer.accept(path);
    }

    @Test
    public void testLseek() throws Exception {
        withTestFileFD((fd) -> {
            assertEquals(4, Os.lseek(fd, 4, OsConstants.SEEK_SET));
            assertEquals(4, Os.lseek(fd, 0, OsConstants.SEEK_CUR));
            assertEquals(6, Os.lseek(fd, 2, OsConstants.SEEK_CUR));
        });
    }

    @Test
    public void testDup() throws Exception {
        withTestFileFD((fd) -> {
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
        withTestFileFD((fd) -> {
            var dupInt = Os.fcntlInt(fd, 0, 0);

            var dup = new FileDescriptor();
            JvmWorkaround.getInstance().setFdInt(dup, dupInt);

            checkAreDup(fd, dup);
        });
    }

    @Test
    public void testStat() throws Exception {
        withTestFile(path -> {
            var attr = Files.readAttributes(path, PosixFileAttributes.class);
            var stat = Os.stat(path.toAbsolutePath().toString());
            assertAttributesEqual(attr, stat);
        });
    }

    @Test
    public void testLstat() throws Exception {
        withTestFile(path -> {
            // Create a symbolic link
            var lnk = Files.createTempFile("osTest", "lnk");
            Files.delete(lnk);
            Files.createSymbolicLink(lnk, path);

            // Test lstat
            var attr = Files.readAttributes(lnk, PosixFileAttributes.class, NOFOLLOW_LINKS);
            var stat = Os.lstat(lnk.toAbsolutePath().toString());
            assertAttributesEqual(attr, stat);

            // Test stat
            var followAttr = Files.readAttributes(lnk, PosixFileAttributes.class);
            var followStat = Os.stat(lnk.toAbsolutePath().toString());
            assertAttributesEqual(followAttr, followStat);
        });
    }

    @Test
    public void testFstat() throws Exception {
        withTestFile(path -> {
            var attr = Files.readAttributes(path, PosixFileAttributes.class);
            try (var raf = new RandomAccessFile(path.toFile(), "r")) {
                var fd = raf.getFD();
                var stat = Os.fstat(fd);
                assertAttributesEqual(attr, stat);
            }
        });
    }

    // Verify StructStat values from libcore against native JVM PosixFileAttributes
    private static void assertAttributesEqual(PosixFileAttributes attr, StructStat stat) {
        assertEquals(attr.lastModifiedTime(), convertTimespecToFileTime(stat.st_mtim));
        assertEquals(attr.size(), stat.st_size);
        assertEquals(attr.isDirectory(), S_ISDIR(stat.st_mode));
        assertEquals(attr.isRegularFile(), S_ISREG(stat.st_mode));
        assertEquals(attr.isSymbolicLink(), S_ISLNK(stat.st_mode));
        assertEquals(attr.isOther(), S_ISCHR(stat.st_mode)
                || S_ISBLK(stat.st_mode) || S_ISFIFO(stat.st_mode) || S_ISSOCK(stat.st_mode));
        assertEquals(attr.permissions(), convertModeToPosixPerms(stat.st_mode));

    }

    private static FileTime convertTimespecToFileTime(StructTimespec ts) {
        var nanos = TimeUnit.SECONDS.toNanos(ts.tv_sec);
        nanos += ts.tv_nsec;
        return FileTime.from(nanos, TimeUnit.NANOSECONDS);
    }

    private static Set<PosixFilePermission> convertModeToPosixPerms(int mode) {
        var set = new HashSet<PosixFilePermission>();
        if ((mode & OsConstants.S_IRUSR) != 0) set.add(PosixFilePermission.OWNER_READ);
        if ((mode & OsConstants.S_IWUSR) != 0) set.add(PosixFilePermission.OWNER_WRITE);
        if ((mode & OsConstants.S_IXUSR) != 0) set.add(PosixFilePermission.OWNER_EXECUTE);
        if ((mode & OsConstants.S_IRGRP) != 0) set.add(PosixFilePermission.GROUP_READ);
        if ((mode & OsConstants.S_IWGRP) != 0) set.add(PosixFilePermission.GROUP_WRITE);
        if ((mode & OsConstants.S_IXGRP) != 0) set.add(PosixFilePermission.GROUP_EXECUTE);
        if ((mode & OsConstants.S_IROTH) != 0) set.add(PosixFilePermission.OTHERS_READ);
        if ((mode & OsConstants.S_IWOTH) != 0) set.add(PosixFilePermission.OTHERS_WRITE);
        if ((mode & OsConstants.S_IXOTH) != 0) set.add(PosixFilePermission.OTHERS_EXECUTE);
        return set;
    }

    private static void write(FileDescriptor fd, int oneByte) throws Exception {
        // Create a dup to avoid closing the FD.
        try (var dup = new FileOutputStream(Os.dup(fd))) {
            dup.write(oneByte);
        }
    }

    private static int read(FileDescriptor fd) throws Exception {
        // Create a dup to avoid closing the FD.
        try (var dup = new FileInputStream(Os.dup(fd))) {
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
