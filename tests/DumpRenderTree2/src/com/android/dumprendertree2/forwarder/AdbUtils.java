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

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

/**
 * The utility class that can setup a socket allowing the device to communicate with remote
 * machines through the machine that the device is connected to via adb.
 */
public class AdbUtils {
    private static final String LOG_TAG = "AdbUtils";

    private static final String ADB_OK = "OKAY";
    private static final int ADB_PORT = 5037;
    private static final String ADB_HOST = "127.0.0.1";
    private static final int ADB_RESPONSE_SIZE = 4;

    /**
     * Creates a new socket that can be configured to serve as a transparent proxy to a
     * remote machine. This can be achieved by calling configureSocket()
     *
     * @return a socket that can be configured to link to remote machine
     * @throws IOException
     */
    public static Socket createSocket() throws IOException{
        return new Socket(ADB_HOST, ADB_PORT);
    }

    /**
     * Configures the connection to serve as a transparent proxy to a remote machine.
     * The given streams must belong to a socket created by createSocket().
     *
     * @param inputStream inputStream of the socket we want to configure
     * @param outputStream outputStream of the socket we want to configure
     * @param remoteAddress address of the remote machine (as you would type in a browser
     *      in a machine that the device is connected to via adb)
     * @param remotePort port on which to connect
     * @return if the configuration suceeded
     * @throws IOException
     */
    public static boolean configureConnection(InputStream inputStream, OutputStream outputStream,
            String remoteAddress, int remotePort) throws IOException {
        String cmd = "tcp:" + remotePort + ":" + remoteAddress;
        cmd = String.format("%04X", cmd.length()) + cmd;

        byte[] buf = new byte[ADB_RESPONSE_SIZE];
        outputStream.write(cmd.getBytes());
        int read = inputStream.read(buf);
        if (read != ADB_RESPONSE_SIZE || !ADB_OK.equals(new String(buf))) {
            Log.w(LOG_TAG, "adb cmd failed.");
            return false;
        }
        return true;
    }
}
