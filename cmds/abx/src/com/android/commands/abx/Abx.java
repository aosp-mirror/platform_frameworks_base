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

package com.android.commands.abx;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Utility that offers to convert between human-readable XML and a custom binary
 * XML protocol.
 *
 * @see Xml#newSerializer()
 * @see Xml#newBinarySerializer()
 */
public class Abx {
    private static final String USAGE = "" +
            "usage: abx2xml [-i] input [output]\n" +
            "usage: xml2abx [-i] input [output]\n\n" +
            "Converts between human-readable XML and Android Binary XML.\n\n" +
            "When invoked with the '-i' argument, the output of a successful conversion\n" +
            "will overwrite the original input file. Input can be '-' to use stdin, and\n" +
            "output can be '-' to use stdout.\n";

    private static InputStream openInput(String arg) throws IOException {
        if ("-".equals(arg)) {
            return System.in;
        } else {
            return new FileInputStream(arg);
        }
    }

    private static OutputStream openOutput(String arg) throws IOException {
        if ("-".equals(arg)) {
            return System.out;
        } else {
            return new FileOutputStream(arg);
        }
    }

    private static void mainInternal(String[] args) {
        if (args.length < 2) {
            throw new IllegalArgumentException("Missing arguments");
        }

        final XmlPullParser in;
        final XmlSerializer out;
        if (args[0].endsWith("abx2xml")) {
            in = Xml.newBinaryPullParser();
            out = Xml.newSerializer();
        } else if (args[0].endsWith("xml2abx")) {
            in = Xml.newPullParser();
            out = Xml.newBinarySerializer();
        } else {
            throw new IllegalArgumentException("Unsupported conversion");
        }

        final boolean inPlace = "-i".equals(args[1]);
        final String inputArg = inPlace ? args[2] : args[1];
        final String outputArg = inPlace ? args[2] + ".tmp" : args[2];

        try (InputStream is = openInput(inputArg);
                OutputStream os = openOutput(outputArg)) {
            in.setInput(is, StandardCharsets.UTF_8.name());
            out.setOutput(os, StandardCharsets.UTF_8.name());
            out.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
            Xml.copy(in, out);
            out.flush();
        } catch (Exception e) {
            // Clean up failed output before throwing
            if (inPlace) {
                new File(outputArg).delete();
            }
            throw new IllegalStateException(e);
        }

        // Successful in-place conversion of a file requires a rename
        if (inPlace) {
            if (!new File(outputArg).renameTo(new File(inputArg))) {
                throw new IllegalStateException("Failed rename");
            }
        }
    }

    public static void main(String[] args) {
        try {
            mainInternal(args);
            System.exit(0);
        } catch (Exception e) {
            System.err.println(e.toString());
            System.err.println();
            System.err.println(USAGE);
            System.exit(1);
        }
    }
}
