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

import android.os.Environment;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.regex.Pattern;

public class FsUtils {

    private static final String LOGTAG = "FsUtils";
    static final String EXTERNAL_DIR = Environment.getExternalStorageDirectory().toString();
    static final String HTTP_TESTS_PREFIX =
        EXTERNAL_DIR + "/webkit/layout_tests/http/tests/";
    static final String HTTPS_TESTS_PREFIX =
        EXTERNAL_DIR + "/webkit/layout_tests/http/tests/ssl/";
    static final String HTTP_LOCAL_TESTS_PREFIX =
        EXTERNAL_DIR + "/webkit/layout_tests/http/tests/local/";
    static final String HTTP_MEDIA_TESTS_PREFIX =
        EXTERNAL_DIR + "/webkit/layout_tests/http/tests/media/";
    static final String HTTP_WML_TESTS_PREFIX =
        EXTERNAL_DIR + "/webkit/layout_tests/http/tests/wml/";

    private FsUtils() {
        //no creation of instances
    }

    /**
     * @return the number of tests in the list.
     */
    public static int writeLayoutTestListRecursively(BufferedOutputStream bos,
            String dir, boolean ignoreResultsInDir) throws IOException {

        int testCount = 0;
        Log.v(LOGTAG, "Searching tests under " + dir);

        File d = new File(dir);
        if (!d.isDirectory()) {
            throw new AssertionError("A directory expected, but got " + dir);
        }
        ignoreResultsInDir |= FileFilter.ignoreResult(dir);

        String[] files = d.list();
        for (int i = 0; i < files.length; i++) {
            String s = dir + "/" + files[i];

            File f = new File(s);
            if (f.isDirectory()) {
                // If this is not a test directory, we don't recurse into it.
                if (!FileFilter.isNonTestDir(s)) {
                    Log.v(LOGTAG, "Recursing on " + s);
                    testCount += writeLayoutTestListRecursively(bos, s, ignoreResultsInDir);
                }
                continue;
            }

            // If this test should be ignored, we skip it completely.
            if (FileFilter.ignoreTest(s)) {
                Log.v(LOGTAG, "Ignoring: " + s);
                continue;
            }

            if ((s.toLowerCase().endsWith(".html")
                    || s.toLowerCase().endsWith(".xml")
                    || s.toLowerCase().endsWith(".xhtml"))
                    && !s.endsWith("TEMPLATE.html")) {
                Log.v(LOGTAG, "Recording " + s);
                bos.write(s.getBytes());
                // If the result of this test should be ignored, we still run the test.
                if (ignoreResultsInDir || FileFilter.ignoreResult(s)) {
                    bos.write((" IGNORE_RESULT").getBytes());
                }
                bos.write('\n');
                testCount++;
            }
        }
        return testCount;
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
                url = "http://127.0.0.1:18000/" + path.substring(HTTP_TESTS_PREFIX.length());
            } else {
                url = "file://" + path;
            }
        }
        return url;
    }

    public static boolean diffIgnoreSpaces(String file1, String file2)  throws IOException {
        BufferedReader br1 = new BufferedReader(new FileReader(file1));
        BufferedReader br2 = new BufferedReader(new FileReader(file2));
        boolean same = true;
        Pattern trailingSpace = Pattern.compile("\\s+$");

        while(true) {
            String line1 = br1.readLine();
            String line2 = br2.readLine();

            if (line1 == null && line2 == null)
                break;
            if (line1 != null) {
                line1 = trailingSpace.matcher(line1).replaceAll("");
            } else {
                line1 = "";
            }
            if (line2 != null) {
                line2 = trailingSpace.matcher(line2).replaceAll("");
            } else {
                line2 = "";
            }
            if(!line1.equals(line2)) {
                same = false;
                break;
            }
        }

        br1.close();
        br2.close();

        return same;
    }

    public static boolean isTestPageUrl(String url) {
        int qmPostion = url.indexOf('?');
        int slashPostion = url.lastIndexOf('/');
        if (slashPostion < qmPostion) {
            String fileName = url.substring(slashPostion + 1, qmPostion);
            if ("index.html".equals(fileName)) {
                return true;
            }
        }
        return false;
    }

    public static String getLastSegmentInPath(String path) {
        int endPos = path.lastIndexOf('/');
        path = path.substring(0, endPos);
        endPos = path.lastIndexOf('/');
        return path.substring(endPos + 1);
    }

    public static void writeDrawTime(String fileName, String url, long[] times) {
        StringBuffer lineBuffer = new StringBuffer();
        // grab the last segment of path in url
        lineBuffer.append(getLastSegmentInPath(url));
        for (long time : times) {
            lineBuffer.append('\t');
            lineBuffer.append(time);
        }
        lineBuffer.append('\n');
        String line = lineBuffer.toString();
        Log.v(LOGTAG, "logging draw times: " + line);
        try {
            FileWriter fw = new FileWriter(fileName, true);
            fw.write(line);
            fw.close();
        } catch (IOException ioe) {
            Log.e(LOGTAG, "Failed to log draw times", ioe);
        }
    }

}
