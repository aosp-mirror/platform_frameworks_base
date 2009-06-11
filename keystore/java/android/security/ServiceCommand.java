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

package android.security;

import android.net.LocalSocketAddress;
import android.net.LocalSocket;
import android.util.Config;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

/*
 * ServiceCommand is used to connect to a service throught the local socket,
 * and send out the command, return the result to the caller.
 * {@hide}
 */
public class ServiceCommand {
    public static final String SUCCESS = "0";
    public static final String FAILED = "-1";

    private String mServiceName;
    private String mTag;
    private InputStream mIn;
    private OutputStream mOut;
    private LocalSocket mSocket;
    private static final int BUFFER_LENGTH = 1024;

    private byte buf[] = new byte[BUFFER_LENGTH];
    private int buflen = 0;

    private boolean connect() {
        if (mSocket != null) {
            return true;
        }
        Log.i(mTag, "connecting...");
        try {
            mSocket = new LocalSocket();

            LocalSocketAddress address = new LocalSocketAddress(
                    mServiceName, LocalSocketAddress.Namespace.RESERVED);

            mSocket.connect(address);

            mIn = mSocket.getInputStream();
            mOut = mSocket.getOutputStream();
        } catch (IOException ex) {
            disconnect();
            return false;
        }
        return true;
    }

    private void disconnect() {
        Log.i(mTag,"disconnecting...");
        try {
            if (mSocket != null) mSocket.close();
        } catch (IOException ex) { }
        try {
            if (mIn != null) mIn.close();
        } catch (IOException ex) { }
        try {
            if (mOut != null) mOut.close();
        } catch (IOException ex) { }
        mSocket = null;
        mIn = null;
        mOut = null;
    }

    private boolean readBytes(byte buffer[], int len) {
        int off = 0, count;
        if (len < 0) return false;
        while (off != len) {
            try {
                count = mIn.read(buffer, off, len - off);
                if (count <= 0) {
                    Log.e(mTag, "read error " + count);
                    break;
                }
                off += count;
            } catch (IOException ex) {
                Log.e(mTag,"read exception");
                break;
            }
        }
        if (off == len) return true;
        disconnect();
        return false;
    }

    private boolean readReply() {
        int len, ret;
        buflen = 0;

        if (!readBytes(buf, 2)) return false;
        ret = (((int) buf[0]) & 0xff) | ((((int) buf[1]) & 0xff) << 8);
        if (ret != 0) return false;

        if (!readBytes(buf, 2)) return false;
        len = (((int) buf[0]) & 0xff) | ((((int) buf[1]) & 0xff) << 8);
        if (len > BUFFER_LENGTH) {
            Log.e(mTag,"invalid reply length (" + len + ")");
            disconnect();
            return false;
        }
        if (!readBytes(buf, len)) return false;
        buflen = len;
        return true;
    }

    private boolean writeCommand(String _cmd) {
        byte[] cmd = _cmd.getBytes();
        int len = cmd.length;
        if ((len < 1) || (len > BUFFER_LENGTH)) return false;
        buf[0] = (byte) (len & 0xff);
        buf[1] = (byte) ((len >> 8) & 0xff);
        try {
            mOut.write(buf, 0, 2);
            mOut.write(cmd, 0, len);
        } catch (IOException ex) {
            Log.e(mTag,"write error");
            disconnect();
            return false;
        }
        return true;
    }

    private String executeCommand(String cmd) {
        if (!writeCommand(cmd)) {
            /* If service died and restarted in the background
             * (unlikely but possible) we'll fail on the next
             * write (this one).  Try to reconnect and write
             * the command one more time before giving up.
             */
            Log.e(mTag, "write command failed? reconnect!");
            if (!connect() || !writeCommand(cmd)) {
                return null;
            }
        }
        if (readReply()) {
            return new String(buf, 0, buflen);
        } else {
            return null;
        }
    }

    public synchronized String execute(String cmd) {
      String result;
      if (!connect()) {
          Log.e(mTag, "connection failed");
          return null;
      }
      result = executeCommand(cmd);
      disconnect();
      return result;
    }

    public ServiceCommand(String service) {
        mServiceName = service;
        mTag = service;
    }
}
