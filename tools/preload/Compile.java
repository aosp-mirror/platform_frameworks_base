/*
 * Copyright (C) 2008 The Android Open Source Project
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

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses and analyzes a log, pulling our PRELOAD information. If you have
 * an emulator or device running in the background, this class will use it
 * to measure and record the memory usage of each class.
 * 
 * TODO: Should analyze lines and select substring dynamically (instead of hardcoded 19)
 */
public class Compile {

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("Usage: Compile [log file] [output file]");
            System.exit(0);
        }

        Root root = new Root();

        List<Record> records = new ArrayList<Record>();

        BufferedReader in = new BufferedReader(new InputStreamReader(
                new FileInputStream(args[0])));

        String line;
        int lineNumber = 0;
        while ((line = in.readLine()) != null) {
            lineNumber++;
            if (line.startsWith("I/PRELOAD")) {
                try {
                    String clipped = line.substring(19);
                    records.add(new Record(clipped, lineNumber));
                } catch (RuntimeException e) {
                    throw new RuntimeException(
                            "Exception while recording line " + lineNumber + ": " + line, e);
                }
            }
        }

        for (Record record : records) {
            root.indexProcess(record);
        }

        for (Record record : records) {
            root.indexClassOperation(record);
        }

        in.close();

        root.toFile(args[1]);
    }
}
