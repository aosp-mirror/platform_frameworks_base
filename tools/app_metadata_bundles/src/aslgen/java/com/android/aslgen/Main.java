/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.aslgen;

import com.android.asllib.AslConverter;
import com.android.asllib.AslConverter.Format;
import com.android.asllib.util.MalformedXmlException;

import org.xml.sax.SAXException;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;

public class Main {

    /** Takes the options to make file conversion. */
    public static void main(String[] args)
            throws IOException,
                    ParserConfigurationException,
                    SAXException,
                    TransformerException,
                    MalformedXmlException {

        String inFile = null;
        String outFile = null;
        Format inFormat = AslConverter.Format.NULL;
        Format outFormat = AslConverter.Format.NULL;

        // Except for "--help", all arguments require a value currently.
        // So just make sure we have an even number and
        // then process them all two at a time.
        if (args.length == 1 && "--help".equals(args[0])) {
            showUsage();
            return;
        }
        if (args.length % 2 != 0) {
            throw new IllegalArgumentException("Argument is missing corresponding value");
        }
        for (int i = 0; i < args.length - 1; i += 2) {
            final String arg = args[i].trim();
            final String argValue = args[i + 1].trim();
            if ("--in-path".equals(arg)) {
                inFile = argValue;
            } else if ("--out-path".equals(arg)) {
                outFile = argValue;
            } else if ("--in-format".equals(arg)) {
                inFormat = getFormat(argValue);
            } else if ("--out-format".equals(arg)) {
                outFormat = getFormat(argValue);
            } else {
                throw new IllegalArgumentException("Unknown argument: " + arg);
            }
        }

        if (inFile == null) {
            throw new IllegalArgumentException("input file is required");
        }

        if (outFile == null) {
            throw new IllegalArgumentException("output file is required");
        }

        if (inFormat == AslConverter.Format.NULL) {
            throw new IllegalArgumentException("input format is required");
        }

        if (outFormat == AslConverter.Format.NULL) {
            throw new IllegalArgumentException("output format is required");
        }

        System.out.println("in path: " + inFile);
        System.out.println("out path: " + outFile);
        System.out.println("in format: " + inFormat);
        System.out.println("out format: " + outFormat);

        var asl = AslConverter.readFromStream(new FileInputStream(inFile), inFormat);
        AslConverter.writeToStream(new FileOutputStream(outFile), asl, outFormat);
    }

    private static Format getFormat(String argValue) {
        if ("hr".equals(argValue)) {
            return AslConverter.Format.HUMAN_READABLE;
        } else if ("od".equals(argValue)) {
            return AslConverter.Format.ON_DEVICE;
        } else {
            return AslConverter.Format.NULL;
        }
    }

    private static void showUsage() {
        System.err.println(
                "Usage: aslgen --in-path [input-file] --out-path [output-file] --in-format [hr|od]"
                        + " --out-format [hr|od]");
    }
}
