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
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;

/**
 * {@hide}
 */
public class KeyStore {
    public static final int NO_ERROR = 1;
    public static final int LOCKED = 2;
    public static final int UNINITIALIZED = 3;
    public static final int SYSTEM_ERROR = 4;
    public static final int PROTOCOL_ERROR = 5;
    public static final int PERMISSION_DENIED = 6;
    public static final int KEY_NOT_FOUND = 7;
    public static final int VALUE_CORRUPTED = 8;
    public static final int UNDEFINED_ACTION = 9;
    public static final int WRONG_PASSWORD = 10;

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
        ArrayList<byte[]> values = execute('g', key);
        return (values == null || values.isEmpty()) ? null : values.get(0);
    }

    public String get(String key) {
        byte[] value = get(getBytes(key));
        return (value == null) ? null : toString(value);
    }

    public boolean put(byte[] key, byte[] value) {
        execute('i', key, value);
        return mError == NO_ERROR;
    }

    public boolean put(String key, String value) {
        return put(getBytes(key), getBytes(value));
    }

    public boolean delete(byte[] key) {
        execute('d', key);
        return mError == NO_ERROR;
    }

    public boolean delete(String key) {
        return delete(getBytes(key));
    }

    public boolean contains(byte[] key) {
        execute('e', key);
        return mError == NO_ERROR;
    }

    public boolean contains(String key) {
        return contains(getBytes(key));
    }

    public byte[][] saw(byte[] prefix) {
        ArrayList<byte[]> values = execute('s', prefix);
        return (values == null) ? null : values.toArray(new byte[values.size()][]);
    }

    public String[] saw(String prefix) {
        byte[][] values = saw(getBytes(prefix));
        if (values == null) {
            return null;
        }
        String[] strings = new String[values.length];
        for (int i = 0; i < values.length; ++i) {
            strings[i] = toString(values[i]);
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
        return password(getBytes(oldPassword), getBytes(newPassword));
    }

    public boolean password(byte[] password) {
        return password(password, password);
    }

    public boolean password(String password) {
        return password(getBytes(password));
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
        return unlock(getBytes(password));
    }

    public int getLastError() {
        return mError;
    }

    private ArrayList<byte[]> execute(int code, byte[]... parameters) {
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

            ArrayList<byte[]> values = new ArrayList<byte[]>();
            while (true) {
                int i, j;
                if ((i = in.read()) == -1) {
                    break;
                }
                if ((j = in.read()) == -1) {
                    return null;
                }
                byte[] value = new byte[i << 8 | j];
                for (i = 0; i < value.length; i += j) {
                    if ((j = in.read(value, i, value.length - i)) == -1) {
                        return null;
                    }
                }
                values.add(value);
            }
            mError = NO_ERROR;
            return values;
        } catch (IOException e) {
            // ignore
        } finally {
            try {
                socket.close();
            } catch (IOException e) {}
        }
        return null;
    }

    private static byte[] getBytes(String string) {
        try {
            return string.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            // will never happen
            throw new RuntimeException(e);
        }
    }

    private static String toString(byte[] bytes) {
        try {
            return new String(bytes, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            // will never happen
            throw new RuntimeException(e);
        }
    }
}
