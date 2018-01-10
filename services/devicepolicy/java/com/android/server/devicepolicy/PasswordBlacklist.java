/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.devicepolicy;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.AtomicFile;
import android.util.Slog;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;

/**
 * Manages the blacklisted passwords.
 *
 * This caller must ensure synchronized access.
 */
public class PasswordBlacklist {
    private static final String TAG = "PasswordBlacklist";

    private final AtomicFile mFile;

    /**
     * Create an object to manage the password blacklist.
     *
     * This is a lightweight operation to prepare variables but not perform any IO.
     */
    public PasswordBlacklist(File file) {
        mFile = new AtomicFile(file, "device-policy");
    }

    /**
     * Atomically replace the blacklist.
     *
     * Pass {@code null} for an empty list.
     */
    public boolean savePasswordBlacklist(@NonNull String name, @NonNull List<String> blacklist) {
        FileOutputStream fos = null;
        try {
            fos = mFile.startWrite();
            final DataOutputStream out = buildStreamForWriting(fos);
            final Header header = new Header(Header.VERSION_1, name, blacklist.size());
            header.write(out);
            final int blacklistSize = blacklist.size();
            for (int i = 0; i < blacklistSize; ++i) {
                out.writeUTF(blacklist.get(i));
            }
            out.flush();
            mFile.finishWrite(fos);
            return true;
        } catch (IOException e) {
            mFile.failWrite(fos);
            return false;
        }
    }

    /** @return the name of the blacklist or {@code null} if none set. */
    public String getName() {
        try (DataInputStream in = openForReading()) {
            return Header.read(in).mName;
        } catch (IOException e) {
            Slog.wtf(TAG, "Failed to read blacklist file", e);
        }
        return null;
    }

    /** @return the number of blacklisted passwords. */
    public int getSize() {
        final int blacklistSize;
        try (DataInputStream in = openForReading()) {
            return Header.read(in).mSize;
        } catch (IOException e) {
            Slog.wtf(TAG, "Failed to read blacklist file", e);
        }
        return 0;
    }

    /** @return whether the password matches an blacklisted item. */
    public boolean isPasswordBlacklisted(@NonNull String password) {
        final int blacklistSize;
        try (DataInputStream in = openForReading()) {
            final Header header = Header.read(in);
            for (int i = 0; i < header.mSize; ++i) {
                if (in.readUTF().equals(password)) {
                    return true;
                }
            }
        } catch (IOException e) {
            Slog.wtf(TAG, "Failed to read blacklist file", e);
            // Fail safe and block all passwords. Setting a new blacklist should resolve this
            // problem which can be identified by examining the log.
            return true;
        }
        return false;
    }

    /** Delete the blacklist completely from disk. */
    public void delete() {
        mFile.delete();
    }

    /** Get the file the blacklist is stored in. */
    public File getFile() {
        return mFile.getBaseFile();
    }

    private DataOutputStream buildStreamForWriting(FileOutputStream fos) {
        return new DataOutputStream(new BufferedOutputStream(fos));
    }

    private DataInputStream openForReading() throws IOException {
        return new DataInputStream(new BufferedInputStream(mFile.openRead()));
    }

    /**
     * Helper to read and write the header of the blacklist file.
     */
    private static class Header {
        static final int VERSION_1 = 1;

        final int mVersion; // File format version
        final String mName;
        final int mSize;

        Header(int version, String name, int size) {
            mVersion = version;
            mName = name;
            mSize = size;
        }

        void write(DataOutputStream out) throws IOException {
            out.writeInt(mVersion);
            out.writeUTF(mName);
            out.writeInt(mSize);
        }

        static Header read(DataInputStream in) throws IOException {
            final int version = in.readInt();
            final String name = in.readUTF();
            final int size = in.readInt();
            return new Header(version, name, size);
        }
    }
}
