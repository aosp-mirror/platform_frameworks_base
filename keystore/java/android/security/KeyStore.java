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

import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;

/**
 * {@hide}
 */
public class KeyStore {
    public static int NO_ERROR = 1;
    public static int LOCKED = 2;
    public static int UNINITIALIZED = 3;
    public static int SYSTEM_ERROR = 4;
    public static int PROTOCOL_ERROR = 5;
    public static int PERMISSION_DENIED = 6;
    public static int KEY_NOT_FOUND = 7;
    public static int VALUE_CORRUPTED = 8;
    public static int UNDEFINED_ACTION = 9;
    public static int WRONG_PASSWORD = 10;

    private static final LocalSocketAddress sAddress = new LocalSocketAddress(
            "keystore", LocalSocketAddress.Namespace.RESERVED);

    private int mError = NO_ERROR;

    private KeyStore() {}

    public static KeyStore getInstance() {
        return new KeyStore();
    }

    public int test() {
        execute('t');
        return mError;
    }

    public byte[] get(byte[] key) {
        byte[][] values = execute('g', key);
        return (values == null) ? null : values[0];
    }

    public String get(String key) {
        byte[] value = get(key.getBytes());
        return (value == null) ? null : new String(value);
    }

    public boolean put(byte[] key, byte[] value) {
        execute('i', key, value);
        return mError == NO_ERROR;
    }

    public boolean put(String key, String value) {
        return put(key.getBytes(), value.getBytes());
    }

    public boolean delete(byte[] key) {
        execute('d', key);
        return mError == NO_ERROR;
    }

    public boolean delete(String key) {
        return delete(key.getBytes());
    }

    public boolean contains(byte[] key) {
        execute('e', key);
        return mError == NO_ERROR;
    }

    public boolean contains(String key) {
        return contains(key.getBytes());
    }

    public byte[][] saw(byte[] prefix) {
        return execute('s', prefix);
    }

    public String[] saw(String prefix) {
        byte[][] values = saw(prefix.getBytes());
        if (values == null) {
            return null;
        }
        String[] strings = new String[values.length];
        for (int i = 0; i < values.length; ++i) {
            strings[i] = new String(values[i]);
        }
        return strings;
    }

    public boolean reset() {
        execute('r');
        return mError == NO_ERROR;
    }

    public boolean password(byte[] oldPassword, byte[] newPassword) {
        execute('p', oldPassword, newPassword);
        return mError == NO_ERROR;
    }

    public boolean password(String oldPassword, String newPassword) {
        return password(oldPassword.getBytes(), newPassword.getBytes());
    }

    public boolean password(byte[] password) {
        return password(password, password);
    }

    public boolean password(String password) {
        return password(password.getBytes());
    }

    public boolean lock() {
        execute('l');
        return mError == NO_ERROR;
    }

    public boolean unlock(byte[] password) {
        execute('u', password);
        return mError == NO_ERROR;
    }

    public boolean unlock(String password) {
        return unlock(password.getBytes());
    }

    public int getLastError() {
        return mError;
    }

    private byte[][] execute(int code, byte[]... parameters) {
        mError = PROTOCOL_ERROR;

        for (byte[] parameter : parameters) {
            if (parameter == null || parameter.length > 65535) {
                return null;
            }
        }

        LocalSocket socket = new LocalSocket();
        try {
            socket.connect(sAddress);

            OutputStream out = socket.getOutputStream();
            out.write(code);
            for (byte[] parameter : parameters) {
                out.write(parameter.length >> 8);
                out.write(parameter.length);
                out.write(parameter);
            }
            out.flush();
            socket.shutdownOutput();

            InputStream in = socket.getInputStream();
            if ((code = in.read()) != NO_ERROR) {
                if (code != -1) {
                    mError = code;
                }
                return null;
            }

            ArrayList<byte[]> results = new ArrayList<byte[]>();
            while (true) {
                int i, j;
                if ((i = in.read()) == -1) {
                    break;
                }
                if ((j = in.read()) == -1) {
                    return null;
                }
                byte[] result = new byte[i << 8 | j];
                for (i = 0; i < result.length; i += j) {
                    if ((j = in.read(result, i, result.length - i)) == -1) {
                        return null;
                    }
                }
                results.add(result);
            }
            mError = NO_ERROR;
            return results.toArray(new byte[results.size()][]);
        } catch (IOException e) {
            // ignore
        } finally {
            try {
                socket.close();
            } catch (IOException e) {}
        }
        return null;
    }
}
