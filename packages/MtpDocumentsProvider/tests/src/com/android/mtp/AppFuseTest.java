/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.mtp;

import android.os.ParcelFileDescriptor;
import android.os.storage.StorageManager;
import android.system.ErrnoException;
import android.system.Os;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.MediumTest;

import libcore.io.IoUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;

@MediumTest
public class AppFuseTest extends AndroidTestCase {
    public void testMount() throws ErrnoException, IOException {
        final StorageManager storageManager = getContext().getSystemService(StorageManager.class);
        final AppFuse appFuse = new AppFuse("test", new TestCallback());
        appFuse.mount(storageManager);
        final File file = appFuse.getMountPoint();
        assertTrue(file.isDirectory());
        assertEquals(1, Os.stat(file.getPath()).st_ino);
        appFuse.close();
        assertTrue(1 != Os.stat(file.getPath()).st_ino);
    }

    public void testOpenFile() throws IOException {
        final StorageManager storageManager = getContext().getSystemService(StorageManager.class);
        final int INODE = 10;
        final AppFuse appFuse = new AppFuse(
                "test",
                new TestCallback() {
                    @Override
                    public long getFileSize(int inode) throws FileNotFoundException {
                        if (INODE == inode) {
                            return 1024;
                        }
                        throw new FileNotFoundException();
                    }
                });
        appFuse.mount(storageManager);
        final ParcelFileDescriptor fd = appFuse.openFile(
                INODE, ParcelFileDescriptor.MODE_READ_ONLY);
        fd.close();
        appFuse.close();
    }

    public void testOpenFile_fileNotFound() throws IOException {
        final StorageManager storageManager = getContext().getSystemService(StorageManager.class);
        final int INODE = 10;
        final AppFuse appFuse = new AppFuse("test", new TestCallback());
        appFuse.mount(storageManager);
        try {
            appFuse.openFile(INODE, ParcelFileDescriptor.MODE_READ_ONLY);
            fail();
        } catch (FileNotFoundException exp) {}
        appFuse.close();
    }

    public void testOpenFile_illegalMode() throws IOException {
        final StorageManager storageManager = getContext().getSystemService(StorageManager.class);
        final int INODE = 10;
        final AppFuse appFuse = new AppFuse("test", new TestCallback());
        appFuse.mount(storageManager);
        try {
            appFuse.openFile(INODE, ParcelFileDescriptor.MODE_READ_WRITE);
            fail();
        } catch (IllegalArgumentException exp) {}
        appFuse.close();
    }

    public void testReadFile() throws IOException {
        final StorageManager storageManager = getContext().getSystemService(StorageManager.class);
        final int fileInode = 10;
        final byte[] fileBytes = new byte[] { 'a', 'b', 'c', 'd', 'e' };
        final AppFuse appFuse = new AppFuse(
                "test",
                new TestCallback() {
                    @Override
                    public long getFileSize(int inode) throws FileNotFoundException {
                        if (inode == fileInode) {
                            return fileBytes.length;
                        }
                        return super.getFileSize(inode);
                    }

                    @Override
                    public long readObjectBytes(int inode, long offset, long size, byte[] bytes)
                            throws IOException {
                        if (inode == fileInode) {
                            int i = 0;
                            while (i < size && i + offset < fileBytes.length)  {
                                bytes[i] = fileBytes[(int) (i + offset)];
                                i++;
                            }
                            return i;
                        }
                        return super.readObjectBytes(inode, offset, size, bytes);
                    }
                });
        appFuse.mount(storageManager);
        final ParcelFileDescriptor fd = appFuse.openFile(
                fileInode, ParcelFileDescriptor.MODE_READ_ONLY);
        try (final ParcelFileDescriptor.AutoCloseInputStream stream =
                new ParcelFileDescriptor.AutoCloseInputStream(fd)) {
            final byte[] buffer = new byte[1024];
            final int size = stream.read(buffer, 0, buffer.length);
            assertEquals(5, size);
        }
        appFuse.close();
    }

    public void testWriteFile() throws IOException {
        final StorageManager storageManager = getContext().getSystemService(StorageManager.class);
        final int INODE = 10;
        final byte[] resultBytes = new byte[5];
        final AppFuse appFuse = new AppFuse(
                "test",
                new TestCallback() {
                    @Override
                    public long getFileSize(int inode) throws FileNotFoundException {
                        if (inode != INODE) {
                            throw new FileNotFoundException();
                        }
                        return resultBytes.length;
                    }

                    @Override
                    public int writeObjectBytes(
                            long fileHandle, int inode, long offset, int size, byte[] bytes) {
                        for (int i = 0; i < size; i++) {
                            resultBytes[(int)(offset + i)] = bytes[i];
                        }
                        return size;
                    }
                });
        appFuse.mount(storageManager);
        final ParcelFileDescriptor fd = appFuse.openFile(
                INODE, ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_TRUNCATE);
        try (final ParcelFileDescriptor.AutoCloseOutputStream stream =
                new ParcelFileDescriptor.AutoCloseOutputStream(fd)) {
            stream.write('a');
            stream.write('b');
            stream.write('c');
            stream.write('d');
            stream.write('e');
        }
        final byte[] BYTES = new byte[] { 'a', 'b', 'c', 'd', 'e' };
        assertTrue(Arrays.equals(BYTES, resultBytes));
        appFuse.close();
    }

    public void testWriteFile_writeError() throws IOException {
        final StorageManager storageManager = getContext().getSystemService(StorageManager.class);
        final int INODE = 10;
        final AppFuse appFuse = new AppFuse(
                "test",
                new TestCallback() {
                    @Override
                    public long getFileSize(int inode) throws FileNotFoundException {
                        if (inode != INODE) {
                            throw new FileNotFoundException();
                        }
                        return 5;
                    }
                });
        appFuse.mount(storageManager);
        final ParcelFileDescriptor fd = appFuse.openFile(
                INODE, ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_TRUNCATE);
        try (final ParcelFileDescriptor.AutoCloseOutputStream stream =
                new ParcelFileDescriptor.AutoCloseOutputStream(fd)) {
            stream.write('a');
            fail();
        } catch (IOException e) {
        }
        appFuse.close();
    }

    public void testWriteFile_flushError() throws IOException {
        final StorageManager storageManager = getContext().getSystemService(StorageManager.class);
        final int INODE = 10;
        final AppFuse appFuse = new AppFuse(
                "test",
                new TestCallback() {
                    @Override
                    public long getFileSize(int inode) throws FileNotFoundException {
                        if (inode != INODE) {
                            throw new FileNotFoundException();
                        }
                        return 5;
                    }

                    @Override
                    public int writeObjectBytes(
                            long fileHandle, int inode, long offset, int size, byte[] bytes) {
                        return size;
                    }

                    @Override
                    public void flushFileHandle(long fileHandle) throws IOException {
                        throw new IOException();
                    }
                });
        appFuse.mount(storageManager);
        final ParcelFileDescriptor fd = appFuse.openFile(
                INODE, ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_TRUNCATE);
        try (final ParcelFileDescriptor.AutoCloseOutputStream stream =
                new ParcelFileDescriptor.AutoCloseOutputStream(fd)) {
            stream.write('a');
            try {
                IoUtils.close(fd.getFileDescriptor());
                fail();
            } catch (IOException e) {
            }
        }
        appFuse.close();
    }

    private static class TestCallback implements AppFuse.Callback {
        @Override
        public long getFileSize(int inode) throws FileNotFoundException {
            throw new FileNotFoundException();
        }

        @Override
        public long readObjectBytes(int inode, long offset, long size, byte[] bytes)
                throws IOException {
            throw new IOException();
        }

        @Override
        public int writeObjectBytes(long fileHandle, int inode, long offset, int size, byte[] bytes)
                throws IOException {
            throw new IOException();
        }

        @Override
        public void flushFileHandle(long fileHandle) throws IOException {}

        @Override
        public void closeFileHandle(long fileHandle) {}
    }
}
