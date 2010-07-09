/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.dumprendertree2;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A utility to filter out some files/directories from the views and tests that run.
 */
public class FileFilter {
    private static final String LOG_TAG = "FileFilter";

    private static final String TEST_EXPECTATIONS_TXT_PATH =
            "platform/android/test_expectations.txt";

    private static final String TOKEN_SKIP = "SKIP";
    private static final String TOKEN_IGNORE_RESULT = "IGNORE_RESULT";
    private static final String TOKEN_SLOW = "SLOW";

    private final Set<String> mSkipList = new HashSet<String>();
    private final Set<String> mIgnoreResultList = new HashSet<String>();
    private final Set<String> mSlowList = new HashSet<String>();

    private final String mRootDirPath;

    public FileFilter(String rootDirPath) {
        /** It may or may not contain a trailing slash */
        this.mRootDirPath = rootDirPath;

        reloadConfiguration();
    }

    private static final String trimTrailingSlashIfPresent(String path) {
        File file = new File(path);
        return file.getPath();
    }

    public void reloadConfiguration() {
        Log.d(LOG_TAG + "::reloadConfiguration", "Begin.");

        File txt_exp = new File(mRootDirPath, TEST_EXPECTATIONS_TXT_PATH);

        BufferedReader bufferedReader;
        try {
            bufferedReader =
                    new BufferedReader(new FileReader(txt_exp));

            String line;
            String entry;
            String[] parts;
            String path;
            Set<String> tokens;
            Boolean skipped;
            while (true) {
                line = bufferedReader.readLine();
                if (line == null) {
                    break;
                }

                /** Remove the comment and trim */
                entry = line.split("//", 2)[0].trim();

                /** Omit empty lines, advance to next line */
                if (entry.isEmpty()) {
                    continue;
                }

                /** Split on whitespace into path part and the rest */
                parts = entry.split("\\s", 2);

                /** At this point parts.length >= 1 */
                if (parts.length == 1) {
                    Log.w(LOG_TAG + "::reloadConfiguration",
                            "There are no options specified for the test!");
                    continue;
                }

                path = trimTrailingSlashIfPresent(parts[0]);

                /** Split on whitespace */
                tokens = new HashSet<String>(Arrays.asList(parts[1].split("\\s", 0)));

                /** Chose the right collections to add to */
                skipped = false;
                if (tokens.contains(TOKEN_SKIP)) {
                    mSkipList.add(path);
                    skipped = true;
                }

                /** If test is on skip list we ignore any further options */
                if (skipped) {
                    continue;
                }

                if (tokens.contains(TOKEN_IGNORE_RESULT)) {
                    mIgnoreResultList.add(path);
                }

                if (tokens.contains(TOKEN_SLOW)) {
                    mSlowList.add(path);
                }
            }
        } catch (FileNotFoundException e) {
            Log.w(LOG_TAG + "::reloadConfiguration", "File not found: " + txt_exp.getPath());
        } catch (IOException e) {
            Log.e(LOG_TAG + "::reloadConfiguration", "IOException: " + e.getMessage());
        }
    }

    /**
     * Checks if test is supposed to be skipped.
     *
     * <p>
     * Path given should relative within LayoutTests folder, e.g. fast/dom/foo.html
     *
     * @param testPath
     *            - a relative path within LayoutTests folder
     * @return if the test is supposed to be skipped
     */
    public boolean isSkip(String testPath) {
        for (String prefix : getPrefixes(testPath)) {
            if (mSkipList.contains(prefix)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Checks if test result is supposed to be ignored.
     *
     * <p>
     * Path given should relative within LayoutTests folder, e.g. fast/dom/foo.html
     *
     * @param testPath
     *            - a relative path within LayoutTests folder
     * @return if the test result is supposed to be ignored
     */
    public boolean isIgnoreRes(String testPath) {
        for (String prefix : getPrefixes(testPath)) {
            if (mIgnoreResultList.contains(prefix)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Checks if test is slow and should have timeout increased.
     *
     * <p>
     * Path given should relative within LayoutTests folder, e.g. fast/dom/foo.html
     *
     * @param testPath
     *            - a relative path within LayoutTests folder
     * @return if the test is slow and should have timeout increased.
     */
    public boolean isSlow(String testPath) {
        for (String prefix : getPrefixes(testPath)) {
            if (mSlowList.contains(prefix)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Returns the list of all path prefixes of the given path.
     *
     * <p>
     * e.g. this/is/a/path returns the list: this this/is this/is/a this/is/a/path
     *
     * @param path
     * @return the list of all path prefixes of the given path.
     */
    private static List<String> getPrefixes(String path) {
        File file = new File(path);
        List<String> prefixes = new ArrayList<String>(8);

        do {
            prefixes.add(file.getPath());
            file = file.getParentFile();
        } while (file != null);

        return prefixes;
    }

    /**
     * Checks if the directory may contain tests or contains just helper files.
     *
     * @param dirName
     * @return
     *      if the directory may contain tests
     */
    public static boolean isTestDir(String dirName) {
        return (!dirName.equals("script-tests")
                && !dirName.equals("resources")
                && !dirName.startsWith("."));
    }

    /**
     * Checks if the file is a test.
     * Currently we run .html and .xhtml tests.
     *
     * @param testName
     * @return
     *      if the file is a test
     */
    public static boolean isTestFile(String testName) {
        return testName.endsWith(".html") || testName.endsWith(".xhtml");
    }
}
