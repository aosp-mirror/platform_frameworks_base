/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.util.io;

import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.TimeUnit;

/**
 * Fake implementation of {@link BasicFileAttributes} (for use in tests)
 */
public class FakeBasicFileAttributes implements BasicFileAttributes {
    private FileTime mLastModifiedTime = FileTime.from(0, TimeUnit.MILLISECONDS);
    private FileTime mLastAccessTime = FileTime.from(0, TimeUnit.MILLISECONDS);
    private FileTime mCreationTime = FileTime.from(0, TimeUnit.MILLISECONDS);
    private boolean mIsRegularFile = true;
    private boolean mIsDirectory = false;
    private boolean mIsSymbolicLink = false;
    private boolean mIsOther = false;
    private long mSize = 0;
    private Object mFileKey = null;

    @Override
    public FileTime lastModifiedTime() {
        return mLastModifiedTime;
    }

    @Override
    public FileTime lastAccessTime() {
        return mLastAccessTime;
    }

    @Override
    public FileTime creationTime() {
        return mCreationTime;
    }

    @Override
    public boolean isRegularFile() {
        return mIsRegularFile;
    }

    @Override
    public boolean isDirectory() {
        return mIsDirectory;
    }

    @Override
    public boolean isSymbolicLink() {
        return mIsSymbolicLink;
    }

    @Override
    public boolean isOther() {
        return mIsOther;
    }

    @Override
    public long size() {
        return mSize;
    }

    @Override
    public Object fileKey() {
        return mFileKey;
    }

    public FakeBasicFileAttributes setLastModifiedTime(long millis) {
        mLastModifiedTime = FileTime.from(millis, TimeUnit.MILLISECONDS);
        return this;
    }

    public FakeBasicFileAttributes setLastAccessTime(long millis) {
        mLastAccessTime = FileTime.from(millis, TimeUnit.MILLISECONDS);
        return this;
    }

    public FakeBasicFileAttributes setCreationTime(long millis) {
        mCreationTime = FileTime.from(millis, TimeUnit.MILLISECONDS);
        return this;
    }

    public FakeBasicFileAttributes setRegularFile(boolean regularFile) {
        mIsRegularFile = regularFile;
        return this;
    }

    public FakeBasicFileAttributes setDirectory(boolean directory) {
        mIsDirectory = directory;
        return this;
    }

    public FakeBasicFileAttributes setSymbolicLink(boolean symbolicLink) {
        mIsSymbolicLink = symbolicLink;
        return this;
    }

    public FakeBasicFileAttributes setOther(boolean other) {
        mIsOther = other;
        return this;
    }

    public FakeBasicFileAttributes setSize(long size) {
        mSize = size;
        return this;
    }

    public FakeBasicFileAttributes setFileKey(Object fileKey) {
        mFileKey = fileKey;
        return this;
    }
}
