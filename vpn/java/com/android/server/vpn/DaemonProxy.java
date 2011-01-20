/*
 * Copyright (C) 2009, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.vpn;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.net.vpn.VpnManager;
import android.os.SystemProperties;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;

/**
 * Proxy to start, stop and interact with a VPN daemon.
 * The daemon is expected to accept connection through Unix domain socket.
 * When the proxy successfully starts the daemon, it will establish a socket
 * connection with the daemon, to both send commands to the daemon and receive
 * response and connecting error code from the daemon.
 */
class DaemonProxy implements Serializable {
    private static final long serialVersionUID = 1L;
    private static final boolean DBG = true;

    private static final int WAITING_TIME = 15; // sec

    private static final String SVC_STATE_CMD_PREFIX = "init.svc.";
    private static final String SVC_START_CMD = "ctl.start";
    private static final String SVC_STOP_CMD = "ctl.stop";
    private static final String SVC_STATE_RUNNING = "running";
    private static final String SVC_STATE_STOPPED = "stopped";

    private static final int END_OF_ARGUMENTS = 255;

    private String mName;
    private String mTag;
    private transient LocalSocket mControlSocket;

    /**
     * Creates a proxy of the specified daemon.
     * @param daemonName name of the daemon
     */
    DaemonProxy(String daemonName) {
        mName = daemonName;
        mTag = "SProxy_" + daemonName;
    }

    String getName() {
        return mName;
    }

    void start() throws IOException {
        String svc = mName;

        Log.i(mTag, "Start VPN daemon: " + svc);
        SystemProperties.set(SVC_START_CMD, svc);

        if (!blockUntil(SVC_STATE_RUNNING, WAITING_TIME)) {
            throw new IOException("cannot start service: " + svc);
        } else {
            mControlSocket = createServiceSocket();
        }
    }

    void sendCommand(String ...args) throws IOException {
        OutputStream out = getControlSocketOutput();
        for (String arg : args) outputString(out, arg);
        out.write(END_OF_ARGUMENTS);
        out.flush();

        int result = getResultFromSocket(true);
        if (result != args.length) {
            throw new IOException("socket error, result from service: "
                    + result);
        }
    }

    // returns 0 if nothing is in the receive buffer
    int getResultFromSocket() throws IOException {
        return getResultFromSocket(false);
    }

    void closeControlSocket() {
        if (mControlSocket == null) return;
        try {
            mControlSocket.close();
        } catch (IOException e) {
            Log.w(mTag, "close control socket", e);
        } finally {
            mControlSocket = null;
        }
    }

    void stop() {
        String svc = mName;
        Log.i(mTag, "Stop VPN daemon: " + svc);
        SystemProperties.set(SVC_STOP_CMD, svc);
        boolean success = blockUntil(SVC_STATE_STOPPED, 5);
        if (DBG) Log.d(mTag, "stopping " + svc + ", success? " + success);
    }

    boolean isStopped() {
        String cmd = SVC_STATE_CMD_PREFIX + mName;
        return SVC_STATE_STOPPED.equals(SystemProperties.get(cmd));
    }

    private int getResultFromSocket(boolean blocking) throws IOException {
        LocalSocket s = mControlSocket;
        if (s == null) return 0;
        InputStream in = s.getInputStream();
        if (!blocking && in.available() == 0) return 0;

        int data = in.read();
        Log.i(mTag, "got data from control socket: " + data);

        return data;
    }

    private LocalSocket createServiceSocket() throws IOException {
        LocalSocket s = new LocalSocket();
        LocalSocketAddress a = new LocalSocketAddress(mName,
                LocalSocketAddress.Namespace.RESERVED);

        // try a few times in case the service has not listen()ed
        IOException excp = null;
        for (int i = 0; i < 10; i++) {
            try {
                s.connect(a);
                return s;
            } catch (IOException e) {
                if (DBG) Log.d(mTag, "service not yet listen()ing; try again");
                excp = e;
                sleep(500);
            }
        }
        throw excp;
    }

    private OutputStream getControlSocketOutput() throws IOException {
        if (mControlSocket != null) {
            return mControlSocket.getOutputStream();
        } else {
            throw new IOException("no control socket available");
        }
    }

    /**
     * Waits for the process to be in the expected state. The method returns
     * false if after the specified duration (in seconds), the process is still
     * not in the expected state.
     */
    private boolean blockUntil(String expectedState, int waitTime) {
        String cmd = SVC_STATE_CMD_PREFIX + mName;
        int sleepTime = 200; // ms
        int n = waitTime * 1000 / sleepTime;
        for (int i = 0; i < n; i++) {
            if (expectedState.equals(SystemProperties.get(cmd))) {
                if (DBG) {
                    Log.d(mTag, mName + " is " + expectedState + " after "
                            + (i * sleepTime) + " msec");
                }
                break;
            }
            sleep(sleepTime);
        }
        return expectedState.equals(SystemProperties.get(cmd));
    }

    private void outputString(OutputStream out, String s) throws IOException {
        byte[] bytes = s.getBytes();
        out.write(bytes.length);
        out.write(bytes);
        out.flush();
    }

    private void sleep(int msec) {
        try {
            Thread.currentThread().sleep(msec);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
