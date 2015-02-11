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
package com.android.databinding.writer;

import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.io.Writer;

import javax.annotation.processing.ProcessingEnvironment;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

public class AnnotationJavaFileWriter implements JavaFileWriter {
    private final ProcessingEnvironment mProcessingEnvironment;

    public AnnotationJavaFileWriter(ProcessingEnvironment processingEnvironment) {
        mProcessingEnvironment = processingEnvironment;
    }

    @Override
    public void writeToFile(String canonicalName, String contents) {
        Writer writer = null;
        try {
            JavaFileObject javaFileObject =
                    mProcessingEnvironment.getFiler().createSourceFile(canonicalName);
            writer = javaFileObject.openWriter();
            writer.write(contents);
        } catch (IOException e) {
            mProcessingEnvironment.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Could not write to " + canonicalName + ": " + e.getLocalizedMessage());
        } finally {
            if (writer != null) {
                IOUtils.closeQuietly(writer);
            }
        }
    }
}
