/*
 * Copyright (C) 2014 Google Inc. All Rights Reserved.
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

package com.example.android.musicservicedemo;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class Utils {

    private static final String TAG = "Utils";

    /**
     * Utility method to check that parameters are not null
     *
     * @param object
     */
    public static final void checkNotNull(Object object) {
        if (object == null) {
            throw new NullPointerException();
        }
    }

    /**
     * Utility to download a bitmap
     *
     * @param source
     * @return
     */
    public static Bitmap getBitmapFromURL(String source) {
        try {
            URL url = new URL(source);
            HttpURLConnection httpConnection = (HttpURLConnection) url.openConnection();
            httpConnection.setDoInput(true);
            httpConnection.connect();
            InputStream inputStream = httpConnection.getInputStream();
            return BitmapFactory.decodeStream(inputStream);
        } catch (IOException e) {
            Log.e(TAG, "getBitmapFromUrl: " + source, e);
        }
        return null;
    }

    /**
     * Utility method to wrap an index
     *
     * @param i
     * @param size
     * @return
     */
    public static int wrapIndex(int i, int size) {
        int m = i % size;
        if (m < 0) { // java modulus can be negative
            m += size;
        }
        return m;
    }
}
