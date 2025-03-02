/*
 * Copyright (C) 2025 The Android Open Source Project
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

package libcore.io;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;

public class IoBridge {

    public static void closeAndSignalBlockedThreads(FileDescriptor fd) throws IOException {
        if (fd == null) {
            return;
        }
        try {
            Os.close(fd);
        } catch (ErrnoException errnoException) {
            throw errnoException.rethrowAsIOException();
        }
    }

    public static FileDescriptor open(String path, int flags) throws FileNotFoundException {
        FileDescriptor fd = null;
        try {
            fd = Os.open(path, flags, 0666);
            // Posix open(2) fails with EISDIR only if you ask for write permission.
            // Java disallows reading directories too.f
            if (OsConstants.S_ISDIR(Os.fstat(fd).st_mode)) {
                throw new ErrnoException("open", OsConstants.EISDIR);
            }
            return fd;
        } catch (ErrnoException errnoException) {
            try {
                if (fd != null) {
                    closeAndSignalBlockedThreads(fd);
                }
            } catch (IOException ignored) {
            }
            FileNotFoundException ex = new FileNotFoundException(path + ": "
                    + errnoException.getMessage());
            ex.initCause(errnoException);
            throw ex;
        }
    }
}
