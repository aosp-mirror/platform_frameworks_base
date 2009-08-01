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

    // Opcodes for keystore commands.
    public static final int LOCK = 0;
    public static final int UNLOCK = 1;
    public static final int PASSWD = 2;
    public static final int GET_STATE = 3;
    public static final int LIST_KEYS = 4;
    public static final int GET_KEY = 5;
    public static final int PUT_KEY = 6;
    public static final int REMOVE_KEY = 7;
    public static final int RESET = 8;
    public static final int MAX_CMD_INDEX = 9;

    public static final int BUFFER_LENGTH = 4096;

    private static final boolean DBG = true;

    private String mServiceName;
    private String mTag;
    private InputStream mIn;
    private OutputStream mOut;
    private LocalSocket mSocket;

    private boolean connect() {
        if (mSocket != null) {
            return true;
        }
        if (DBG) Log.d(mTag, "connecting...");
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
        if (DBG) Log.d(mTag,"disconnecting...");
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
                Log.e(mTag,"read exception", ex);
                break;
            }
        }
        if (off == len) return true;
        disconnect();
        return false;
    }

    private Reply readReply() {
        byte buf[] = new byte[4];
        Reply reply = new Reply();

        if (!readBytes(buf, 4)) return null;
        reply.len = (((int) buf[0]) & 0xff) | ((((int) buf[1]) & 0xff) << 8) |
                ((((int) buf[2]) & 0xff) << 16) |
                ((((int) buf[3]) & 0xff) << 24);

        if (!readBytes(buf, 4)) return null;
        reply.returnCode = (((int) buf[0]) & 0xff) |
                ((((int) buf[1]) & 0xff) << 8) |
                ((((int) buf[2]) & 0xff) << 16) |
                ((((int) buf[3]) & 0xff) << 24);

        if (reply.len > BUFFER_LENGTH) {
            Log.e(mTag,"invalid reply length (" + reply.len + ")");
            disconnect();
            return null;
        }
        if (!readBytes(reply.data, reply.len)) return null;
        return reply;
    }

    private boolean writeCommand(int cmd, String _data) {
        byte buf[] = new byte[8];
        byte[] data = (_data == null) ? new byte[0] : _data.getBytes();
        int len = data.length;
        // the length of data
        buf[0] = (byte) (len & 0xff);
        buf[1] = (byte) ((len >> 8) & 0xff);
        buf[2] = (byte) ((len >> 16) & 0xff);
        buf[3] = (byte) ((len >> 24) & 0xff);
        // the opcode of the command
        buf[4] = (byte) (cmd & 0xff);
        buf[5] = (byte) ((cmd >> 8) & 0xff);
        buf[6] = (byte) ((cmd >> 16) & 0xff);
        buf[7] = (byte) ((cmd >> 24) & 0xff);
        try {
            mOut.write(buf, 0, 8);
            mOut.write(data, 0, len);
        } catch (IOException ex) {
            Log.e(mTag,"write error", ex);
            disconnect();
            return false;
        }
        return true;
    }

    private Reply executeCommand(int cmd, String data) {
        if (!writeCommand(cmd, data)) {
            /* If service died and restarted in the background
             * (unlikely but possible) we'll fail on the next
             * write (this one).  Try to reconnect and write
             * the command one more time before giving up.
             */
            Log.e(mTag, "write command failed? reconnect!");
            if (!connect() || !writeCommand(cmd, data)) {
                return null;
            }
        }
        return readReply();
    }

    public synchronized Reply execute(int cmd, String data) {
      Reply result;
      if (!connect()) {
          Log.e(mTag, "connection failed");
          return null;
      }
      result = executeCommand(cmd, data);
      disconnect();
      return result;
    }

    public ServiceCommand(String service) {
        mServiceName = service;
        mTag = service;
    }
}
