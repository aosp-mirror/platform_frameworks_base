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

package com.android.platform.test.ravenwood.nativesubstitution;

import static android.os.ParcelFileDescriptor.MODE_APPEND;
import static android.os.ParcelFileDescriptor.MODE_CREATE;
import static android.os.ParcelFileDescriptor.MODE_READ_ONLY;
import static android.os.ParcelFileDescriptor.MODE_READ_WRITE;
import static android.os.ParcelFileDescriptor.MODE_TRUNCATE;
import static android.os.ParcelFileDescriptor.MODE_WORLD_READABLE;
import static android.os.ParcelFileDescriptor.MODE_WORLD_WRITEABLE;
import static android.os.ParcelFileDescriptor.MODE_WRITE_ONLY;

import com.android.internal.annotations.GuardedBy;
import com.android.ravenwood.common.JvmWorkaround;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.HashMap;
import java.util.Map;

public class ParcelFileDescriptor_host {
    /**
     * Since we don't have a great way to keep an unmanaged {@code FileDescriptor} reference
     * alive, we keep a strong reference to the {@code RandomAccessFile} we used to open it. This
     * gives us a way to look up the original parent object when closing later.
     */
    @GuardedBy("sActive")
    private static final Map<FileDescriptor, RandomAccessFile> sActive = new HashMap<>();

    public static void native_setFdInt$ravenwood(FileDescriptor fd, int fdInt) {
        JvmWorkaround.getInstance().setFdInt(fd, fdInt);
    }

    public static int native_getFdInt$ravenwood(FileDescriptor fd) {
        return JvmWorkaround.getInstance().getFdInt(fd);
    }

    public static FileDescriptor native_open$ravenwood(File file, int pfdMode) throws IOException {
        if ((pfdMode & MODE_CREATE) != 0 && !file.exists()) {
            throw new FileNotFoundException();
        }

        final String modeString;
        if ((pfdMode & MODE_READ_WRITE) == MODE_READ_WRITE) {
            modeString = "rw";
        } else if ((pfdMode & MODE_WRITE_ONLY) == MODE_WRITE_ONLY) {
            modeString = "rw";
        } else if ((pfdMode & MODE_READ_ONLY) == MODE_READ_ONLY) {
            modeString = "r";
        } else {
            throw new IllegalArgumentException();
        }

        final RandomAccessFile raf = new RandomAccessFile(file, modeString);

        // Now that we have a real file on disk, match requested flags
        if ((pfdMode & MODE_TRUNCATE) != 0) {
            raf.setLength(0);
        }
        if ((pfdMode & MODE_APPEND) != 0) {
            raf.seek(raf.length());
        }
        if ((pfdMode & MODE_WORLD_READABLE) != 0) {
            file.setReadable(true, false);
        }
        if ((pfdMode & MODE_WORLD_WRITEABLE) != 0) {
            file.setWritable(true, false);
        }

        final FileDescriptor fd = raf.getFD();
        synchronized (sActive) {
            sActive.put(fd, raf);
        }
        return fd;
    }

    public static void native_close$ravenwood(FileDescriptor fd) {
        final RandomAccessFile raf;
        synchronized (sActive) {
            raf = sActive.remove(fd);
        }
        try {
            if (raf != null) {
                raf.close();
            } else {
                // Odd, we don't remember opening this ourselves, but let's release the
                // underlying resource as requested
                System.err.println("Closing unknown FileDescriptor: " + fd);
                new FileOutputStream(fd).close();
            }
        } catch (IOException ignored) {
        }
    }
}
