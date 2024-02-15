/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.util;

import android.annotation.NonNull;

import java.io.IOException;
import java.io.Writer;
import java.util.Objects;

/**
 * Writer that offers to "tee" identical output to multiple underlying
 * {@link Writer} instances.
 *
 * @see https://man7.org/linux/man-pages/man1/tee.1.html
 * @hide
 */
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public class TeeWriter extends Writer {
    private final @NonNull Writer[] mWriters;

    public TeeWriter(@NonNull Writer... writers) {
        for (Writer writer : writers) {
            Objects.requireNonNull(writer);
        }
        mWriters = writers;
    }

    @Override
    public void write(char[] cbuf, int off, int len) throws IOException {
        for (Writer writer : mWriters) {
            writer.write(cbuf, off, len);
        }
    }

    @Override
    public void flush() throws IOException {
        for (Writer writer : mWriters) {
            writer.flush();
        }
    }

    @Override
    public void close() throws IOException {
        for (Writer writer : mWriters) {
            writer.close();
        }
    }
}
