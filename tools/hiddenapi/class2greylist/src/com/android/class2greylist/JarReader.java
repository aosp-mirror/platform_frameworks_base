/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.class2greylist;

import org.apache.bcel.classfile.ClassParser;
import org.apache.bcel.classfile.JavaClass;

import java.io.IOException;
import java.util.Objects;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads {@link JavaClass} members from a zip/jar file, providing a stream of them for processing.
 * Any errors are reported via {@link Status#error(Throwable)}.
 */
public class JarReader {

    private final Status mStatus;
    private final String mFileName;
    private final ZipFile mZipFile;

    public JarReader(Status s, String filename) throws IOException {
        mStatus = s;
        mFileName = filename;
        mZipFile = new ZipFile(mFileName);
    }

    private JavaClass openZipEntry(ZipEntry e) {
        try {
            mStatus.debug("Reading %s from %s", e.getName(), mFileName);
            return new ClassParser(mZipFile.getInputStream(e), e.getName()).parse();
        } catch (IOException ioe) {
            mStatus.error(ioe);
            return null;
        }
    }


    public Stream<JavaClass> stream() {
        return mZipFile.stream()
                .filter(zipEntry -> zipEntry.getName().endsWith(".class"))
                .map(zipEntry -> openZipEntry(zipEntry))
                .filter(Objects::nonNull);
    }

    public void close() throws IOException {
        mZipFile.close();
    }
}
