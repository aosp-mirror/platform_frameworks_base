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

import androidx.annotation.NonNull;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.stream.Stream;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Wrapper around {@link java.nio.file.Files} that can be mocked in tests.
 */
@Singleton
public class Files {
    @Inject
    public Files() { }

    /** See {@link java.nio.file.Files#newBufferedWriter} */
    public BufferedWriter newBufferedWriter(Path path, OpenOption... options) throws IOException {
        return java.nio.file.Files.newBufferedWriter(path, StandardCharsets.UTF_8, options);
    }

    /** See {@link java.nio.file.Files#lines} */
    public Stream<String> lines(Path path) throws IOException {
        return java.nio.file.Files.lines(path);
    }

    /** See {@link java.nio.file.Files#readAttributes} */
    public <A extends BasicFileAttributes> A readAttributes(
            @NonNull Path path,
            @NonNull Class<A> type,
            @NonNull LinkOption... options) throws IOException {
        return java.nio.file.Files.readAttributes(path, type, options);
    }
}
