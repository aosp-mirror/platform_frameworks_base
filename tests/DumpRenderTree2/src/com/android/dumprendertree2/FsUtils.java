/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.dumprendertree2;

import android.util.Log;

import com.android.dumprendertree2.forwarder.ForwarderManager;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.LinkedList;
import java.util.List;

/**
 *
 */
public class FsUtils {
    public static final String LOG_TAG = "FsUtils";

    private static final String SCRIPT_URL = ForwarderManager.getHostSchemePort(false) +
            "WebKitTools/DumpRenderTree/android/get_layout_tests_dir_contents.php";

    public static void writeDataToStorage(File file, byte[] bytes, boolean append) {
        Log.d(LOG_TAG, "writeDataToStorage(): " + file.getAbsolutePath());
        try {
            OutputStream outputStream = null;
            try {
                file.getParentFile().mkdirs();
                file.createNewFile();
                Log.d(LOG_TAG, "writeDataToStorage(): File created: " + file.getAbsolutePath());
                outputStream = new FileOutputStream(file, append);
                outputStream.write(bytes);
            } finally {
                if (outputStream != null) {
                    outputStream.close();
                }
            }
        } catch (IOException e) {
            Log.e(LOG_TAG, "file.getAbsolutePath=" + file.getAbsolutePath() + " append=" + append,
                    e);
        }
    }

    public static byte[] readDataFromStorage(File file) {
        if (!file.exists()) {
            Log.d(LOG_TAG, "readDataFromStorage(): File does not exist: "
                    + file.getAbsolutePath());
            return null;
        }

        byte[] bytes = null;
        try {
            FileInputStream fis = null;
            try {
                fis = new FileInputStream(file);
                bytes = new byte[(int)file.length()];
                fis.read(bytes);
            } finally {
                if (fis != null) {
                    fis.close();
                }
            }
        } catch (IOException e) {
            Log.e(LOG_TAG, "file.getAbsolutePath=" + file.getAbsolutePath(), e);
        }

        return bytes;
    }

    public static byte[] readDataFromUrl(URL url) {
        if (url == null) {
            Log.w(LOG_TAG, "readDataFromUrl(): url is null!");
            return null;
        }

        byte[] bytes = null;
        try {
            InputStream inputStream = null;
            ByteArrayOutputStream outputStream = null;
            try {
                URLConnection urlConnection = url.openConnection();
                inputStream = urlConnection.getInputStream();
                outputStream = new ByteArrayOutputStream();

                byte[] buffer = new byte[4096];
                int length;
                while ((length = inputStream.read(buffer)) > 0) {
                    outputStream.write(buffer, 0, length);
                }

                bytes = outputStream.toByteArray();
            } finally {
                if (inputStream != null) {
                    inputStream.close();
                }
                if (outputStream != null) {
                    outputStream.close();
                }
            }
        } catch (FileNotFoundException e) {
            Log.w(LOG_TAG, "readDataFromUrl(): File not found: " + e.getMessage());
        } catch (IOException e) {
            Log.e(LOG_TAG, "url=" + url, e);
        }

        return bytes;
    }

    public static List<String> getLayoutTestsDirContents(String dirRelativePath, boolean recurse,
            boolean mode) {
        String modeString = (mode ? "folders" : "files");

        List<String> results = new LinkedList<String>();

        URL url = null;
        try {
            url = new URL(SCRIPT_URL +
                    "?path=" + dirRelativePath +
                    "&recurse=" + recurse +
                    "&mode=" + modeString);
        } catch (MalformedURLException e) {
            Log.e(LOG_TAG, "path=" + dirRelativePath + " recurse=" + recurse + " mode=" +
                    modeString, e);
            return results;
        }

        try {
            InputStream inputStream = null;
            BufferedReader bufferedReader = null;
            try {
                URLConnection urlConnection = url.openConnection();
                inputStream = urlConnection.getInputStream();
                bufferedReader = new BufferedReader(new InputStreamReader(inputStream));

                String relativePath;
                while ((relativePath = bufferedReader.readLine()) != null) {
                    results.add(relativePath);
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
            Log.e(LOG_TAG, "path=" + dirRelativePath + " recurse=" + recurse + " mode=" +
                    modeString, e);
        }

        return results;
    }
}