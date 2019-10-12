/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.content.res.loader;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * A {@link ResourceLoader} that searches a directory for assets.
 *
 * Assumes that resource paths are resolvable child paths of the directory passed in.
 */
public class DirectoryResourceLoader implements ResourceLoader {

    @NonNull
    private final File mDirectory;

    public DirectoryResourceLoader(@NonNull File directory) {
        this.mDirectory = directory;
    }

    @Nullable
    @Override
    public InputStream loadAsset(@NonNull String path, int accessMode) throws IOException {
        File file = findFile(path);
        if (file == null || !file.exists()) {
            return null;
        }
        return new FileInputStream(file);
    }

    @Nullable
    @Override
    public ParcelFileDescriptor loadAssetFd(@NonNull String path) throws IOException {
        File file = findFile(path);
        if (file == null || !file.exists()) {
            return null;
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    /**
     * Find the file for the given path encoded into the resource table.
     */
    @Nullable
    public File findFile(@NonNull String path) {
        return mDirectory.toPath().resolve(path).toFile();
    }

    @NonNull
    public File getDirectory() {
        return mDirectory;
    }
}
