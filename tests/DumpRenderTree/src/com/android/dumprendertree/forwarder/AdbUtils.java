/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.dumprendertree.forwarder;

import android.util.Log;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

public class AdbUtils {

    private static final String ADB_OK = "OKAY";
    private static final int ADB_PORT = 5037;
    private static final String ADB_HOST = "127.0.0.1";
    private static final int ADB_RESPONSE_SIZE = 4;

    private static final String LOGTAG = "AdbUtils";

    /**
     *
     * Convert integer format IP into xxx.xxx.xxx.xxx format
     *
     * @param host IP address in integer format
     * @return human readable format
     */
    public static String convert(int host) {
        return ((host >> 24) & 0xFF) + "."
        + ((host >> 16) & 0xFF) + "."
        + ((host >> 8) & 0xFF) + "."
        + (host & 0xFF);
    }

    /**
     *
     * Resolve DNS name into IP address
     *
     * @param host DNS name
     * @return IP address in integer format
     * @throws IOException
     */
    public static int resolve(String host)  throws IOException {
        Socket localSocket = new Socket(ADB_HOST, ADB_PORT);
        DataInputStream dis = new DataInputStream(localSocket.getInputStream());
        OutputStream os = localSocket.getOutputStream();
        int count_read = 0;

        if (localSocket == null || dis == null || os == null)
            return -1;
        String cmd = "dns:" + host;

        if(!sendAdbCmd(dis, os, cmd))
            return -1;

        count_read = dis.readInt();
        localSocket.close();
        return count_read;
    }

    /**
     *
     * Send an ADB command using existing socket connection
     *
     * the streams provided must be from a socket connected to adbd already
     *
     * @param is input stream of the socket connection
     * @param os output stream of the socket
     * @param cmd the adb command to send
     * @return if adb gave a success response
     * @throws IOException
     */
    public static boolean sendAdbCmd(InputStream is, OutputStream os,
            String cmd) throws IOException {
        byte[] buf = new byte[ADB_RESPONSE_SIZE];

        cmd = String.format("%04X", cmd.length()) + cmd;
        os.write(cmd.getBytes());
        int read = is.read(buf);
        if(read != ADB_RESPONSE_SIZE || !ADB_OK.equals(new String(buf))) {
            Log.w(LOGTAG, "adb cmd faild.");
            return false;
        }
        return true;
    }

    /**
     *
     * Get a tcp socket connection to specified IP address and port proxied by adb
     *
     * The proxying is transparent, e.g. if a socket is returned, then it can be written to and
     * read from as if it is directly connected to the target
     *
     * @param remoteAddress IP address of the host to connect to
     * @param remotePort port of the host to connect to
     * @return a valid Socket instance if successful, null otherwise
     */
    public static Socket getForwardedSocket(int remoteAddress, int remotePort) {
        try {
            Socket socket = new Socket(ADB_HOST, ADB_PORT);
            String cmd = "tcp:" + remotePort + ":" + convert(remoteAddress);
            if(!sendAdbCmd(socket.getInputStream(), socket.getOutputStream(), cmd)) {
                socket.close();
                return null;
            }
            return socket;
        } catch (IOException ioe) {
            Log.w(LOGTAG, "error creating adb socket", ioe);
            return null;
        }
    }
}
