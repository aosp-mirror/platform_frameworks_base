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

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.conn.scheme.PlainSocketFactory;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.tsccm.ThreadSafeClientConnManager;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;
import org.apache.http.util.EntityUtils;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 *
 */
public class FsUtils {
    public static final String LOG_TAG = "FsUtils";

    private static final String SCRIPT_URL = ForwarderManager.getHostSchemePort(false) +
            "Tools/DumpRenderTree/android/get_layout_tests_dir_contents.php";

    private static final int HTTP_TIMEOUT_MS = 5000;

    private static HttpClient sHttpClient;

    private static HttpClient getHttpClient() {
        if (sHttpClient == null) {
            HttpParams params = new BasicHttpParams();

            SchemeRegistry schemeRegistry = new SchemeRegistry();
            schemeRegistry.register(new Scheme("http", PlainSocketFactory.getSocketFactory(),
                    ForwarderManager.HTTP_PORT));
            schemeRegistry.register(new Scheme("https", SSLSocketFactory.getSocketFactory(),
                    ForwarderManager.HTTPS_PORT));

            ClientConnectionManager connectionManager = new ThreadSafeClientConnManager(params,
                    schemeRegistry);
            sHttpClient = new DefaultHttpClient(connectionManager, params);
            HttpConnectionParams.setSoTimeout(sHttpClient.getParams(), HTTP_TIMEOUT_MS);
            HttpConnectionParams.setConnectionTimeout(sHttpClient.getParams(), HTTP_TIMEOUT_MS);
        }
        return sHttpClient;
    }

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

    static class UrlDataGetter extends Thread {
        private URL mUrl;
        private byte[] mBytes;
        private boolean mGetComplete;
        public UrlDataGetter(URL url) {
            mUrl = url;
        }
        public byte[] get() {
            start();
            synchronized(this) {
                while (!mGetComplete) {
                    try{
                        wait();
                    } catch(InterruptedException e) {
                    }
                }
            }
            return mBytes;
        }
        public synchronized void run() {
            mGetComplete = false;
            HttpGet httpRequest = new HttpGet(mUrl.toString());
            ResponseHandler<byte[]> handler = new ResponseHandler<byte[]>() {
                @Override
                public byte[] handleResponse(HttpResponse response) throws IOException {
                    if (response.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
                        return null;
                    }
                    HttpEntity entity = response.getEntity();
                    return (entity == null ? null : EntityUtils.toByteArray(entity));
                }
            };

            mBytes = null;
            try {
                /**
                 * TODO: Not exactly sure why some requests hang indefinitely, but adding this
                 * timeout (in static getter for http client) in loop helps.
                 */
                boolean timedOut;
                do {
                    timedOut = false;
                    try {
                        mBytes = getHttpClient().execute(httpRequest, handler);
                    } catch (SocketTimeoutException e) {
                        timedOut = true;
                        Log.w(LOG_TAG, "Expected SocketTimeoutException: " + mUrl, e);
                    }
                } while (timedOut);
            } catch (IOException e) {
                Log.e(LOG_TAG, "url=" + mUrl, e);
            }

            mGetComplete = true;
            notify();
        }
    }

    public static byte[] readDataFromUrl(URL url) {
        if (url == null) {
            Log.w(LOG_TAG, "readDataFromUrl(): url is null!");
            return null;
        }

        UrlDataGetter getter = new UrlDataGetter(url);
        return getter.get();
    }

    public static List<String> getLayoutTestsDirContents(String dirRelativePath, boolean recurse,
            boolean mode) {
        String modeString = (mode ? "folders" : "files");

        URL url = null;
        try {
            url = new URL(SCRIPT_URL +
                    "?path=" + dirRelativePath +
                    "&recurse=" + recurse +
                    "&mode=" + modeString);
        } catch (MalformedURLException e) {
            Log.e(LOG_TAG, "path=" + dirRelativePath + " recurse=" + recurse + " mode=" +
                    modeString, e);
            return new LinkedList<String>();
        }

        HttpGet httpRequest = new HttpGet(url.toString());
        ResponseHandler<LinkedList<String>> handler = new ResponseHandler<LinkedList<String>>() {
            @Override
            public LinkedList<String> handleResponse(HttpResponse response)
                    throws IOException {
                LinkedList<String> lines = new LinkedList<String>();

                if (response.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
                    return lines;
                }
                HttpEntity entity = response.getEntity();
                if (entity == null) {
                    return lines;
                }

                BufferedReader reader =
                        new BufferedReader(new InputStreamReader(entity.getContent()));
                String line;
                try {
                    while ((line = reader.readLine()) != null) {
                        lines.add(line);
                    }
                } finally {
                    if (reader != null) {
                        reader.close();
                    }
                }

                return lines;
            }
        };

        try {
            return getHttpClient().execute(httpRequest, handler);
        } catch (IOException e) {
            Log.e(LOG_TAG, "getLayoutTestsDirContents(): HTTP GET failed for URL " + url);
            return null;
        }
    }

    public static void closeInputStream(InputStream inputStream) {
        try {
            if (inputStream != null) {
                inputStream.close();
            }
        } catch (IOException e) {
            Log.e(LOG_TAG, "Couldn't close stream!", e);
        }
    }

    public static void closeOutputStream(OutputStream outputStream) {
        try {
            if (outputStream != null) {
                outputStream.close();
            }
        } catch (IOException e) {
            Log.e(LOG_TAG, "Couldn't close stream!", e);
        }
    }

    public static List<String> loadTestListFromStorage(String path) {
        List<String> list = new ArrayList<String>();
        if (path != null && !path.isEmpty()) {
            try {
                File file = new File(path);
                Log.d(LOG_TAG, "test list loaded from " + path);
                BufferedReader reader = new BufferedReader(new FileReader(file));
                String line = null;
                while ((line = reader.readLine()) != null) {
                    list.add(line);
                }
                reader.close();
            } catch (IOException ioe) {
                Log.e(LOG_TAG, "failed to load test list", ioe);
            }
        }
        return list;
    }

    public static void saveTestListToStorage(File file, int start, List<String> testList) {
        try {
            BufferedWriter writer = new BufferedWriter(
                    new FileWriter(file));
            for (String line : testList.subList(start, testList.size())) {
                writer.write(line + '\n');
            }
            writer.flush();
            writer.close();
        } catch (IOException e) {
            Log.e(LOG_TAG, "failed to write test list", e);
        }
    }
}
