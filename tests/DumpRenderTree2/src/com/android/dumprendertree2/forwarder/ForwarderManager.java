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

import java.util.HashSet;
import java.util.Set;

/**
 * A simple class to start and stop Forwarders running on some ports.
 *
 * It uses a singleton pattern and is thread safe.
 */
public class ForwarderManager {
    /**
     * The IP address of the server serving the tests.
     */
    private static final String HOST_IP = "127.0.0.1";

    /**
     * We use these ports because other webkit platforms do. They are set up in
     * external/webkit/LayoutTests/http/conf/apache2-debian-httpd.conf
     */
    public static final int HTTP_PORT = 8080;
    public static final int HTTPS_PORT = 8443;

    private static ForwarderManager forwarderManager;

    private Set<Forwarder> mServers;

    private ForwarderManager() {
        mServers = new HashSet<Forwarder>(2);
        mServers.add(new Forwarder(HTTP_PORT, HOST_IP));
        mServers.add(new Forwarder(HTTPS_PORT, HOST_IP));
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
        for (Forwarder server : mServers) {
            server.start();
        }
    }

    public synchronized void stop() {
        for (Forwarder server : mServers) {
            server.finish();
        }
    }
}