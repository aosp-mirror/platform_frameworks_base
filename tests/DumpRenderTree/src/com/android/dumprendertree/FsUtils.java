/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.dumprendertree;

import com.android.dumprendertree.forwarder.ForwardService;

import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;

public class FsUtils {

    private static final String LOGTAG = "FsUtils";
    static final String HTTP_TESTS_PREFIX = "/sdcard/android/layout_tests/http/tests/";
    static final String HTTPS_TESTS_PREFIX = "/sdcard/android/layout_tests/http/tests/ssl/";
    static final String HTTP_LOCAL_TESTS_PREFIX = "/sdcard/android/layout_tests/http/tests/local/";
    static final String HTTP_MEDIA_TESTS_PREFIX = "/sdcard/android/layout_tests/http/tests/media/";
    static final String HTTP_WML_TESTS_PREFIX = "/sdcard/android/layout_tests/http/tests/wml/";

    private FsUtils() {
        //no creation of instances
    }

    public static void findLayoutTestsRecursively(BufferedOutputStream bos,
            String dir) throws IOException {
        Log.v(LOGTAG, "Searching tests under " + dir);

        File d = new File(dir);
        if (!d.isDirectory()) {
            throw new AssertionError("A directory expected, but got " + dir);
        }

        String[] files = d.list();
        for (int i = 0; i < files.length; i++) {
            String s = dir + "/" + files[i];
            if (FileFilter.ignoreTest(s)) {
                Log.v(LOGTAG, "  Ignoring: " + s);
                continue;
            }
            if (s.toLowerCase().endsWith(".html")
                    || s.toLowerCase().endsWith(".xml")) {
                bos.write(s.getBytes());
                bos.write('\n');
                continue;
            }

            File f = new File(s);
            if (f.isDirectory()) {
                findLayoutTestsRecursively(bos, s);
                continue;
            }

            Log.v(LOGTAG, "Skipping " + s);
        }
    }

    public static void updateTestStatus(String statusFile, String s) {
        try {
            BufferedOutputStream bos = new BufferedOutputStream(
                    new FileOutputStream(statusFile));
            bos.write(s.getBytes());
            bos.close();
        } catch (Exception e) {
            Log.e(LOGTAG, "Cannot update file " + statusFile);
        }
    }

    public static String readTestStatus(String statusFile) {
        // read out the test name it stopped last time.
        String status = null;
        File testStatusFile = new File(statusFile);
        if(testStatusFile.exists()) {
            try {
                BufferedReader inReader = new BufferedReader(
                        new FileReader(testStatusFile));
                status = inReader.readLine();
                inReader.close();
            } catch (IOException e) {
                Log.e(LOGTAG, "Error reading test status.", e);
            }
        }
        return status;
    }

    public static String getTestUrl(String path) {
        String url = null;
        if (!path.startsWith(HTTP_TESTS_PREFIX)) {
            url = "file://" + path;
        } else {
            ForwardService.getForwardService().startForwardService();
            if (path.startsWith(HTTPS_TESTS_PREFIX)) {
                // still cut the URL after "http/tests/"
                url = "https://127.0.0.1:8443/" + path.substring(HTTP_TESTS_PREFIX.length());
            } else if (!path.startsWith(HTTP_LOCAL_TESTS_PREFIX)
                    && !path.startsWith(HTTP_MEDIA_TESTS_PREFIX)
                    && !path.startsWith(HTTP_WML_TESTS_PREFIX)) {
                url = "http://127.0.0.1:8000/" + path.substring(HTTP_TESTS_PREFIX.length());
            } else {
                url = "file://" + path;
            }
        }
        return url;
    }

}
