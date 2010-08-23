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
     * Send an ADB command using existing socket connection
     *
     * The streams provided must be from a socket connected to adb already
     *
     * @param is input stream of the socket connection
     * @param os output stream of the socket
     * @param cmd the adb command to send
     * @return if adb gave a success response
     * @throws IOException
     */
    private static boolean sendAdbCmd(InputStream is, OutputStream os, String cmd)
            throws IOException {
        byte[] buf = new byte[ADB_RESPONSE_SIZE];

        cmd = String.format("%04X", cmd.length()) + cmd;
        os.write(cmd.getBytes());
        int read = is.read(buf);
        if (read != ADB_RESPONSE_SIZE || !ADB_OK.equals(new String(buf))) {
            Log.w(LOG_TAG, "adb cmd faild.");
            return false;
        }
        return true;
    }

    /**
     * Get a tcp socket connection to specified IP address and port proxied by adb
     *
     * The proxying is transparent, e.g. if a socket is returned, then it can be written to and
     * read from as if it is directly connected to the target
     *
     * @param remoteAddress IP address of the host to connect to
     * @param remotePort port of the host to connect to
     * @return a valid Socket instance if successful, null otherwise
     */
    public static Socket getSocketToRemoteMachine(String remoteAddress, int remotePort) {
        try {
            Socket socket = new Socket(ADB_HOST, ADB_PORT);
            String cmd = "tcp:" + remotePort + ":" + remoteAddress;
            if (!sendAdbCmd(socket.getInputStream(), socket.getOutputStream(), cmd)) {
                socket.close();
                return null;
            }
            return socket;
        } catch (IOException ioe) {
            Log.w(LOG_TAG, "error creating adb socket", ioe);
            return null;
        }
    }
}