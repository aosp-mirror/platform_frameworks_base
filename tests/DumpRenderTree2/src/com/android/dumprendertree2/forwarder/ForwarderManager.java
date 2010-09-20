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

package com.android.dumprendertree2.forwarder;

import java.net.MalformedURLException;
import java.net.URL;
import android.util.Log;

import java.util.HashSet;
import java.util.Set;

/**
 * A simple class to start and stop Forwarders running on some ports.
 *
 * It uses a singleton pattern and is thread safe.
 */
public class ForwarderManager {
    private static final String LOG_TAG = "ForwarderManager";

    /**
     * The IP address of the server serving the tests.
     */
    private static final String HOST_IP = "127.0.0.1";

    /**
     * We use these ports because other webkit platforms do. They are set up in
     * external/webkit/LayoutTests/http/conf/apache2-debian-httpd.conf
     */
    public static final int HTTP_PORT = 8000;
    public static final int HTTPS_PORT = 8443;

    private static ForwarderManager forwarderManager;

    private Set<Forwarder> mForwarders;
    private boolean mIsStarted;

    private ForwarderManager() {
        mForwarders = new HashSet<Forwarder>(2);
        mForwarders.add(new Forwarder(HTTP_PORT, HOST_IP));
        mForwarders.add(new Forwarder(HTTPS_PORT, HOST_IP));
    }

    /**
     * Returns the main part of the URL with the trailing slash
     *
     * @param isHttps
     * @return
     */
    public static final String getHostSchemePort(boolean isHttps) {
        int port;
        String protocol;
        if (isHttps) {
            protocol = "https";
            port = HTTPS_PORT;
        } else {
            protocol = "http";
            port = HTTP_PORT;
        }

        URL url = null;
        try {
            url = new URL(protocol, HOST_IP, port, "/");
        } catch (MalformedURLException e) {
            assert false : "isHttps=" + isHttps;
        }

        return url.toString();
    }

    public static synchronized ForwarderManager getForwarderManager() {
        if (forwarderManager == null) {
            forwarderManager = new ForwarderManager();
        }
        return forwarderManager;
    }

    @Override
    public Object clone() throws CloneNotSupportedException {
        throw new CloneNotSupportedException();
    }

    public synchronized void start() {
        if (mIsStarted) {
            Log.w(LOG_TAG, "start(): ForwarderManager already running! NOOP.");
            return;
        }

        for (Forwarder forwarder : mForwarders) {
            forwarder.start();
        }

        mIsStarted = true;
        Log.i(LOG_TAG, "ForwarderManager started.");
    }

    public synchronized void stop() {
        if (!mIsStarted) {
            Log.w(LOG_TAG, "stop(): ForwarderManager already stopped! NOOP.");
            return;
        }

        for (Forwarder forwarder : mForwarders) {
            forwarder.finish();
        }

        mIsStarted = false;
        Log.i(LOG_TAG, "ForwarderManager stopped.");
    }
}
