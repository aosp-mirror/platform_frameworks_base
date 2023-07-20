/*
 * Copyright (C) 2018 Potato Open Sauce Project
 * Copyright (C) 2021 Jyotiraditya Panda <jyotiraditya@aospa.co>
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

package com.android.internal.util;

import android.os.Handler;
import android.os.HandlerThread;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

import javax.net.ssl.HttpsURLConnection;

/**
 * Helper functions for uploading to StagBin (https://stagbin.tk).
 */
public final class StagBinUtils {

    private static Handler mHandler;

    /**
     * Uploads {@code content} to StagBin
     *
     * @param data     the content to upload to StagBin
     * @param callback the callback to call on success / failure
     */
    public static void upload(String data, UploadResultCallback callback) {
        getHandler().post(() -> {
            try {
                URL url = new URL("https://api.stagbin.tk/dev/content");
                HttpsURLConnection urlConnection = (HttpsURLConnection) url.openConnection();
                urlConnection.setRequestProperty("Content-Type", "application/json");
                urlConnection.setRequestProperty("Accept-Charset", "UTF-8");
                urlConnection.setDoOutput(true);

                JSONObject dataBody = new JSONObject();
                dataBody.put("data", data);

                OutputStreamWriter streamWriter = new OutputStreamWriter(urlConnection.getOutputStream());
                streamWriter.write(dataBody.toString());
                streamWriter.flush();

                String responseBody;
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(urlConnection.getInputStream(),
                        StandardCharsets.UTF_8))) {
                    responseBody = reader.lines().map(String::trim).collect(Collectors.joining());
                }

                String id = new JSONObject(responseBody).getString("id");
                if (!id.isEmpty()) {
                    callback.onSuccess(String.format("https://stagbin.tk/%s", id));
                } else {
                    String msg = "Failed to upload to StagBin: No id retrieved";
                    callback.onFail(msg, new Exception(msg));
                }
                urlConnection.disconnect();
            } catch (Exception e) {
                String msg = "Failed to upload to StagBin";
                callback.onFail(msg, e);
            }
        });
    }

    private static Handler getHandler() {
        if (mHandler == null) {
            HandlerThread stagBinThread = new HandlerThread("StagBinThread");
            if (!stagBinThread.isAlive()) {
                stagBinThread.start();
            }
            mHandler = new Handler(stagBinThread.getLooper());
        }
        return mHandler;
    }

    public interface UploadResultCallback {
        void onSuccess(String url);

        void onFail(String message, Exception e);
    }
} 

