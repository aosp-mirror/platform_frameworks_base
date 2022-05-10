/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.traceinjection;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

import java.io.BufferedInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public class Main {
    public static void main(String[] args) throws IOException {
        String inJar = null;
        String outJar = null;
        String annotation = null;
        String traceStart = null;
        String traceEnd = null;

        // All arguments require a value currently, so just make sure we have an even number and
        // then process them all two at a time.
        if (args.length % 2 != 0) {
            throw new IllegalArgumentException("Argument is missing corresponding value");
        }
        for (int i = 0; i < args.length - 1; i += 2) {
            final String arg = args[i].trim();
            final String argValue = args[i + 1].trim();
            if ("-i".equals(arg)) {
                inJar = argValue;
            } else if ("-o".equals(arg)) {
                outJar = argValue;
            } else if ("--annotation".equals(arg)) {
                annotation = argValue;
            } else if ("--start".equals(arg)) {
                traceStart = argValue;
            } else if ("--end".equals(arg)) {
                traceEnd = argValue;
            } else {
                throw new IllegalArgumentException("Unknown argument: " + arg);
            }
        }

        if (inJar == null) {
            throw new IllegalArgumentException("input jar is required");
        }

        if (outJar == null) {
            throw new IllegalArgumentException("output jar is required");
        }

        if (annotation == null) {
            throw new IllegalArgumentException("trace annotation is required");
        }

        if (traceStart == null) {
            throw new IllegalArgumentException("start trace method is required");
        }

        if (traceEnd == null) {
            throw new IllegalArgumentException("end trace method is required");
        }

        TraceInjectionConfiguration params =
                new TraceInjectionConfiguration(annotation, traceStart, traceEnd);

        try (
                ZipFile zipSrc = new ZipFile(inJar);
                ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(outJar));
        ) {
            Enumeration<? extends ZipEntry> srcEntries = zipSrc.entries();
            while (srcEntries.hasMoreElements()) {
                ZipEntry entry = srcEntries.nextElement();
                ZipEntry newEntry = new ZipEntry(entry.getName());
                newEntry.setTime(entry.getTime());
                zos.putNextEntry(newEntry);
                BufferedInputStream bis = new BufferedInputStream(zipSrc.getInputStream(entry));

                if (entry.getName().endsWith(".class")) {
                    convert(bis, zos, params);
                } else {
                    while (bis.available() > 0) {
                        zos.write(bis.read());
                    }
                    zos.closeEntry();
                    bis.close();
                }
            }
            zos.finish();
        }
    }

    private static void convert(InputStream in, OutputStream out,
            TraceInjectionConfiguration params) throws IOException {
        ClassReader cr = new ClassReader(in);
        ClassWriter cw = new ClassWriter(0);
        TraceInjectionClassVisitor cv = new TraceInjectionClassVisitor(cw, params);
        cr.accept(cv, ClassReader.EXPAND_FRAMES);
        byte[] data = cw.toByteArray();
        out.write(data);
    }
}
