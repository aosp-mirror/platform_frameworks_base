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

package com.android.preload.check;

import dalvik.system.DexFile;

import java.util.Collection;
import java.util.Enumeration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Test boot classpath classes that satisfy a given regular expression to be not initialized.
 * Optionally check that at least one class was matched.
 */
public class NotInitializedRegex {
    /**
     * First arg (mandatory): regular exception. Second arg (optional): boolean to denote a
     * required match.
     */
    public static void main(String[] args) throws Exception {
        Matcher m = Pattern.compile(args[0]).matcher("");
        boolean requiresMatch = args.length > 1 ? Boolean.parseBoolean(args[1]) : false;

        Collection<DexFile> dexFiles = Util.getBootDexFiles();
        int matched = 0, notMatched = 0;
        for (DexFile dexFile : dexFiles) {
            Enumeration<String> entries = dexFile.entries();
            while (entries.hasMoreElements()) {
                String entry = entries.nextElement();
                m.reset(entry);
                if (m.matches()) {
                    System.out.println(entry + ": match");
                    matched++;
                    check(entry);
                } else {
                    System.out.println(entry + ": no match");
                    notMatched++;
                }
            }
        }
        System.out.println("Matched: " + matched + " Not-Matched: " + notMatched);
        if (requiresMatch && matched == 0) {
            throw new RuntimeException("Did not find match");
        }
        System.out.println("OK");
    }

    private static void check(String name) {
        Util.assertNotInitialized(name, null);
    }
}
