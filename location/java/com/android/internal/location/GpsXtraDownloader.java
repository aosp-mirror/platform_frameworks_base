/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.internal.location;

import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.StatusLine;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.conn.params.ConnRouteParams;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.Properties;
import java.util.Random;

import android.content.Context;
import android.net.Proxy;
import android.net.http.AndroidHttpClient;
import android.util.Config;
import android.util.Log;

/**
 * A class for downloading GPS XTRA data.
 *
 * {@hide}
 */
public class GpsXtraDownloader {

    private static final String TAG = "GpsXtraDownloader";
    
    private Context mContext;
    private String[] mXtraServers;
    // to load balance our server requests
    private int mNextServerIndex;

    GpsXtraDownloader(Context context, Properties properties) {
        mContext = context;

        // read XTRA servers from the Properties object
        int count = 0;
        String server1 = properties.getProperty("XTRA_SERVER_1");
        String server2 = properties.getProperty("XTRA_SERVER_2");
        String server3 = properties.getProperty("XTRA_SERVER_3");
        if (server1 != null) count++;
        if (server2 != null) count++;
        if (server3 != null) count++;
        
        if (count == 0) {
            Log.e(TAG, "No XTRA servers were specified in the GPS configuration");
        } else {
            mXtraServers = new String[count];
            count = 0;
            if (server1 != null) mXtraServers[count++] = server1;
            if (server2 != null) mXtraServers[count++] = server2;
            if (server3 != null) mXtraServers[count++] = server3;
        }
        
        // randomize first server
        Random random = new Random();
        mNextServerIndex = random.nextInt(count);
    }

    byte[] downloadXtraData() {
        String proxyHost = Proxy.getHost(mContext);
        int proxyPort = Proxy.getPort(mContext);
        boolean useProxy = (proxyHost != null && proxyPort != -1);
        byte[] result = null;
        int startIndex = mNextServerIndex;

        if (mXtraServers == null) {
            return null;
        }

        // load balance our requests among the available servers
        while (result == null) {
            result = doDownload(mXtraServers[mNextServerIndex], useProxy, proxyHost, proxyPort);
            
            // increment mNextServerIndex and wrap around if necessary
            mNextServerIndex++;
            if (mNextServerIndex == mXtraServers.length) {
                mNextServerIndex = 0;
            }
            // break if we have tried all the servers
            if (mNextServerIndex == startIndex) break;
        }
    
        return result;
    }

    protected static byte[] doDownload(String url, boolean isProxySet, 
            String proxyHost, int proxyPort) {
        if (Config.LOGD) Log.d(TAG, "Downloading XTRA data from " + url);

        AndroidHttpClient client = null;
        try {
            client = AndroidHttpClient.newInstance("Android");
            HttpUriRequest req = new HttpGet(url);

            if (isProxySet) {
                HttpHost proxy = new HttpHost(proxyHost, proxyPort);
                ConnRouteParams.setDefaultProxy(req.getParams(), proxy);
            }

            req.addHeader(
                    "Accept",
                    "*/*, application/vnd.wap.mms-message, application/vnd.wap.sic");

            req.addHeader(
                    "x-wap-profile",
                    "http://www.openmobilealliance.org/tech/profiles/UAPROF/ccppschema-20021212#");

            HttpResponse response = client.execute(req);
            StatusLine status = response.getStatusLine();
            if (status.getStatusCode() != 200) { // HTTP 200 is success.
                if (Config.LOGD) Log.d(TAG, "HTTP error: " + status.getReasonPhrase());
                return null;
            }

            HttpEntity entity = response.getEntity();
            byte[] body = null;
            if (entity != null) {
                try {
                    if (entity.getContentLength() > 0) {
                        body = new byte[(int) entity.getContentLength()];
                        DataInputStream dis = new DataInputStream(entity.getContent());
                        try {
                            dis.readFully(body);
                        } finally {
                            try {
                                dis.close();
                            } catch (IOException e) {
                                Log.e(TAG, "Unexpected IOException.", e);
                            }
                        }
                    }
                } finally {
                    if (entity != null) {
                        entity.consumeContent();
                    }
                }
            }
            return body;
        } catch (Exception e) {
            if (Config.LOGD) Log.d(TAG, "error " + e);
        } finally {
            if (client != null) {
                client.close();
            }
        }
        return null;
    }

}

