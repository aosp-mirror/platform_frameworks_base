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

import com.android.dumprendertree2.forwarder.ForwarderManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
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

    private static final String HTTP_TESTS_PATH = "http/tests/";
    private static final String SSL_PATH = "ssl/";

    private static final String TOKEN_CRASH = "CRASH";
    private static final String TOKEN_FAIL = "FAIL";
    private static final String TOKEN_SLOW = "SLOW";

    private final Set<String> mCrashList = new HashSet<String>();
    private final Set<String> mFailList = new HashSet<String>();
    private final Set<String> mSlowList = new HashSet<String>();

    public FileFilter() {
        loadTestExpectations();
    }

    private static final String trimTrailingSlashIfPresent(String path) {
        File file = new File(path);
        return file.getPath();
    }

    public void loadTestExpectations() {
        URL url = null;
        try {
            url = new URL(ForwarderManager.getHostSchemePort(false) +
                    "LayoutTests/" + TEST_EXPECTATIONS_TXT_PATH);
        } catch (MalformedURLException e) {
            assert false;
        }

        try {
            InputStream inputStream = null;
            BufferedReader bufferedReader = null;
            try {
                byte[] httpAnswer = FsUtils.readDataFromUrl(url);
                if (httpAnswer == null) {
                    Log.w(LOG_TAG, "loadTestExpectations(): File not found: " +
                            TEST_EXPECTATIONS_TXT_PATH);
                    return;
                }
                bufferedReader = new BufferedReader(new StringReader(
                        new String(httpAnswer)));
                String line;
                String entry;
                String[] parts;
                String path;
                Set<String> tokens;
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
                    tokens = new HashSet<String>(Arrays.asList(
                            parts[1].split("\\s", 0)));

                    /** Chose the right collections to add to */
                    if (tokens.contains(TOKEN_CRASH)) {
                        mCrashList.add(path);

                        /** If test is on skip list we ignore any further options */
                        continue;
                    }

                    if (tokens.contains(TOKEN_FAIL)) {
                        mFailList.add(path);
                    }
                    if (tokens.contains(TOKEN_SLOW)) {
                        mSlowList.add(path);
                    }
                }
            } finally {
                if (inputStream != null) {
                    inputStream.close();
                }
                if (bufferedReader != null) {
                    bufferedReader.close();
                }
            }
        } catch (IOException e) {
            Log.e(LOG_TAG, "url=" + url, e);
        }
    }

    /**
     * Checks if test is expected to crash.
     *
     * <p>
     * Path given should relative within LayoutTests folder, e.g. fast/dom/foo.html
     *
     * @param testPath
     *            - a relative path within LayoutTests folder
     * @return if the test is supposed to be skipped
     */
    public boolean isCrash(String testPath) {
        for (String prefix : getPrefixes(testPath)) {
            if (mCrashList.contains(prefix)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Checks if test result is supposed to be "failed".
     *
     * <p>
     * Path given should relative within LayoutTests folder, e.g. fast/dom/foo.html
     *
     * @param testPath
     *            - a relative path within LayoutTests folder
     * @return if the test result is supposed to be "failed"
     */
    public boolean isFail(String testPath) {
        for (String prefix : getPrefixes(testPath)) {
            if (mFailList.contains(prefix)) {
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
                && !dirName.equals("resources") && !dirName.startsWith("."));
    }

    /**
     * Checks if the file is a test.
     * Currently we run .html, .xhtml and .php tests.
     *
     * @warning You MUST also call isTestDir() on the parent directory before
     * assuming that a file is a test.
     *
     * @param testName
     * @return if the file is a test
     */
    public static boolean isTestFile(String testName) {
        return testName.endsWith(".html")
            || testName.endsWith(".xhtml")
            || testName.endsWith(".php");
    }

    /**
     * Return a URL of the test on the server.
     *
     * @param relativePath
     * @param allowHttps Whether to allow the use of HTTPS, even if the file is in the SSL
     *     directory.
     * @return a URL of the test on the server
     */
    public static URL getUrl(String relativePath, boolean allowHttps) {
        String urlBase = ForwarderManager.getHostSchemePort(false);

        /**
         * URL is formed differently for HTTP vs non-HTTP tests, because HTTP tests
         * expect different document root. See run_apache2.py and .conf file for details
         */
        if (relativePath.startsWith(HTTP_TESTS_PATH)) {
            relativePath = relativePath.substring(HTTP_TESTS_PATH.length());
            if (relativePath.startsWith(SSL_PATH) && allowHttps) {
                urlBase = ForwarderManager.getHostSchemePort(true);
            }
        } else {
            relativePath = "LayoutTests/" + relativePath;
        }

        try {
            return new URL(urlBase + relativePath);
        } catch (MalformedURLException e) {
            Log.e(LOG_TAG, "Malformed URL!", e);
        }

        return null;
    }

    /**
     * If the path contains extension (e.g .foo at the end of the file) then it changes
     * this (.foo) into newEnding (so it has to contain the dot if we want to preserve it).
     *
     * <p>If the path doesn't contain an extension, it adds the ending to the path.
     *
     * @param relativePath
     * @param newEnding
     * @return
     *      a new path, containing the newExtension
     */
    public static String setPathEnding(String relativePath, String newEnding) {
        int dotPos = relativePath.lastIndexOf('.');
        if (dotPos == -1) {
            return relativePath + newEnding;
        }

        return relativePath.substring(0, dotPos) + newEnding;
    }
}
