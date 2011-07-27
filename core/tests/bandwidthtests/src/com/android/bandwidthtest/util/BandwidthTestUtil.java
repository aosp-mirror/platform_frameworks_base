/*
 * Copyright (C) 2011, The Android Open Source Project
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

package com.android.bandwidthtest.util;

import android.util.Log;

import org.apache.http.util.ByteArrayBuffer;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLConnection;

public class BandwidthTestUtil {
    private static final String LOG_TAG = "BandwidthTestUtil";
    /**
     * Parses the first line in a file if exists.
     *
     * @param file {@link File} the input
     * @return the integer value of the first line of the file.
     */
    public static int parseIntValueFromFile(File file) {
        int value = 0;
        if (file.exists()) {
            try {
                FileInputStream fstream = new FileInputStream(file);
                DataInputStream in = new DataInputStream(fstream);
                BufferedReader br = new BufferedReader(new InputStreamReader(in));
                String strLine = br.readLine();
                if (strLine != null) {
                    value = Integer.parseInt(strLine);
                }
                // Close the input stream
                in.close();
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
            }
        }
        return value;
    }

    /**
     * Creates the Download string for the test server.
     *
     * @param server url of the test server
     * @param size in bytes of the file to download
     * @param deviceId the device id that is downloading
     * @param timestamp
     * @return download url
     */
    public static String buildDownloadUrl(String server, int size, String deviceId,
            String timestamp) {
        String downloadUrl = server + "/download?size=" + size + "&device_id=" + deviceId +
                "&timestamp=" + timestamp;
        return downloadUrl;
    }

    /**
     * Download a given file from a target url to a given destination file.
     * @param targetUrl the url to download
     * @param file the {@link File} location where to save to
     * @return true if it succeeded.
     */
    public static boolean DownloadFromUrl(String targetUrl, File file) {
        try {
            URL url = new URL(targetUrl);
            Log.d(LOG_TAG, "Download begining");
            Log.d(LOG_TAG, "Download url:" + url);
            Log.d(LOG_TAG, "Downloaded file name:" + file.getAbsolutePath());
            URLConnection ucon = url.openConnection();
            InputStream is = ucon.getInputStream();
            BufferedInputStream bis = new BufferedInputStream(is);
            ByteArrayBuffer baf = new ByteArrayBuffer(50);
            int current = 0;
            while ((current = bis.read()) != -1) {
                baf.append((byte) current);
            }
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(baf.toByteArray());
            fos.close();
        } catch (IOException e) {
            Log.d(LOG_TAG, "Failed to download file with error: " + e);
            return false;
        }
        return true;
    }

}
