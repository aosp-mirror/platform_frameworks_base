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

package com.android.asllib;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;

public class AndroidSafetyLabel {

    public enum Format {
        NULL, HUMAN_READABLE, ON_DEVICE;
    }

    /**
     * Reads a {@link AndroidSafetyLabel} from an {@link InputStream}.
     */
    public static AndroidSafetyLabel readFromStream(InputStream in, Format format)
            throws IOException {
        System.out.println(format);
        var br = new BufferedReader(new InputStreamReader(in));
        String line;
        while ((line = br.readLine()) != null) {
            System.out.println(line);
        }
        return new AndroidSafetyLabel();
    }

    /**
     * Write the content of the {@link AndroidSafetyLabel} to a {@link OutputStream}.
     */
    public void writeToStream(OutputStream out, Format format) throws IOException {
        var bw = new BufferedWriter(new OutputStreamWriter(out, "UTF-8"));
        bw.write("Just a Test");
        bw.close();
    }

    public static void test() {
        System.out.println("test lib");
    }
}
