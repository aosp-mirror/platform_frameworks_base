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

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;

/**
 * TODO: Enable this test after adding SELinux policies for appfuse.
 */
@MediumTest
public class AppFuseTest extends AndroidTestCase {

    public void disabled_testMount() throws ErrnoException, InterruptedException {
        final StorageManager storageManager = getContext().getSystemService(StorageManager.class);
        final AppFuse appFuse = new AppFuse("test", new TestCallback());
        appFuse.mount(storageManager);
        final File file = appFuse.getMountPoint();
        assertTrue(file.isDirectory());
        assertEquals(1, Os.stat(file.getPath()).st_ino);
        appFuse.close();
        assertTrue(1 != Os.stat(file.getPath()).st_ino);
    }

    public void disabled_testOpenFile() throws IOException {
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
        final ParcelFileDescriptor fd = appFuse.openFile(INODE);
        fd.close();
        appFuse.close();
    }

    public void disabled_testOpenFile_error() {
        final StorageManager storageManager = getContext().getSystemService(StorageManager.class);
        final int INODE = 10;
        final AppFuse appFuse = new AppFuse("test", new TestCallback());
        appFuse.mount(storageManager);
        try {
            appFuse.openFile(INODE);
            fail();
        } catch (Throwable t) {
            assertTrue(t instanceof FileNotFoundException);
        }
        appFuse.close();
    }

    public void disabled_testReadFile() throws IOException {
        final StorageManager storageManager = getContext().getSystemService(StorageManager.class);
        final int INODE = 10;
        final byte[] BYTES = new byte[] { 'a', 'b', 'c', 'd', 'e' };
        final AppFuse appFuse = new AppFuse(
                "test",
                new TestCallback() {
                    @Override
                    public long getFileSize(int inode) throws FileNotFoundException {
                        if (inode == INODE) {
                            return BYTES.length;
                        }
                        return super.getFileSize(inode);
                    }

                    @Override
                    public byte[] getObjectBytes(int inode, long offset, int size)
                            throws IOException {
                        if (inode == INODE) {
                            return Arrays.copyOfRange(BYTES, (int) offset, (int) offset + size);
                        }
                        return super.getObjectBytes(inode, offset, size);
                    }
                });
        appFuse.mount(storageManager);
        final ParcelFileDescriptor fd = appFuse.openFile(INODE);
        try (final ParcelFileDescriptor.AutoCloseInputStream stream =
                new ParcelFileDescriptor.AutoCloseInputStream(fd)) {
            final byte[] buffer = new byte[1024];
            final int size = stream.read(buffer, 0, buffer.length);
            assertEquals(5, size);
        }
        appFuse.close();
    }

    private static class TestCallback implements AppFuse.Callback {
        @Override
        public long getFileSize(int inode) throws FileNotFoundException {
            throw new FileNotFoundException();
        }

        @Override
        public byte[] getObjectBytes(int inode, long offset, int size)
                throws IOException {
            throw new IOException();
        }
    }
}
