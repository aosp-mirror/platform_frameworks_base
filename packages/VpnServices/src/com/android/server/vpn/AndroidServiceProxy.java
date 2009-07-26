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
import android.os.SystemProperties;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Proxy to start, stop and interact with an Android service defined in init.rc.
 * The android service is expected to accept connection through Unix domain
 * socket. When the proxy successfully starts the service, it will establish a
 * socket connection with the service. The socket serves two purposes: (1) send
 * commands to the service; (2) for the proxy to know whether the service is
 * alive.
 *
 * After the service receives commands from the proxy, it should return either
 * 0 if the service will close the socket (and the proxy will re-establish
 * another connection immediately after), or 1 if the socket is remained alive.
 */
public class AndroidServiceProxy extends ProcessProxy {
    private static final int WAITING_TIME = 15; // sec

    private static final String SVC_STATE_CMD_PREFIX = "init.svc.";
    private static final String SVC_START_CMD = "ctl.start";
    private static final String SVC_STOP_CMD = "ctl.stop";
    private static final String SVC_STATE_RUNNING = "running";
    private static final String SVC_STATE_STOPPED = "stopped";

    private static final int END_OF_ARGUMENTS = 255;

    private String mServiceName;
    private String mSocketName;
    private LocalSocket mKeepaliveSocket;
    private boolean mControlSocketInUse;
    private Integer mSocketResult = null;
    private String mTag;

    /**
     * Creates a proxy with the service name.
     * @param serviceName the service name
     */
    public AndroidServiceProxy(String serviceName) {
        mServiceName = serviceName;
        mSocketName = serviceName;
        mTag = "SProxy_" + serviceName;
    }

    @Override
    public String getName() {
        return "Service " + mServiceName;
    }

    @Override
    public synchronized void stop() {
        if (isRunning()) setResultAndCloseControlSocket(-1);
        SystemProperties.set(SVC_STOP_CMD, mServiceName);
    }

    /**
     * Sends a command with arguments to the service through the control socket.
     */
    public void sendCommand(String ...args) throws IOException {
        OutputStream out = getControlSocketOutput();
        for (String arg : args) outputString(out, arg);
        out.write(END_OF_ARGUMENTS);
        out.flush();
        checkSocketResult();
    }

    /**
     * {@inheritDoc}
     * The method returns when the service exits.
     */
    @Override
    protected void performTask() throws IOException {
        String svc = mServiceName;
        Log.d(mTag, "+++++  Execute: " + svc);
        SystemProperties.set(SVC_START_CMD, svc);

        boolean success = blockUntil(SVC_STATE_RUNNING, WAITING_TIME);

        if (success) {
            Log.d(mTag, "-----  Running: " + svc + ", create keepalive socket");
            LocalSocket s = mKeepaliveSocket = createServiceSocket();
            setState(ProcessState.RUNNING);

            if (s == null) {
                // no socket connection, stop hosting the service
                stop();
                return;
            }
            try {
                for (;;) {
                    InputStream in = s.getInputStream();
                    int data = in.read();
                    if (data >= 0) {
                        Log.d(mTag, "got data from keepalive socket: " + data);

                        if (data == 0) {
                            // re-establish the connection:
                            // synchronized here so that checkSocketResult()
                            // returns when new mKeepaliveSocket is available for
                            // next cmd
                            synchronized (this) {
                                setResultAndCloseControlSocket((byte) data);
                                s = mKeepaliveSocket = createServiceSocket();
                            }
                        } else {
                            // keep the socket
                            setSocketResult(data);
                        }
                    } else {
                        // service is gone
                        if (mControlSocketInUse) setSocketResult(-1);
                        break;
                    }
                }
                Log.d(mTag, "keepalive connection closed");
            } catch (IOException e) {
                Log.d(mTag, "keepalive socket broken: " + e.getMessage());
            }

            // Wait 5 seconds for the service to exit
            success = blockUntil(SVC_STATE_STOPPED, 5);
            Log.d(mTag, "stopping " + svc + ", success? " + success);
        } else {
            setState(ProcessState.STOPPED);
            throw new IOException("cannot start service: " + svc);
        }
    }

    private LocalSocket createServiceSocket() throws IOException {
        LocalSocket s = new LocalSocket();
        LocalSocketAddress a = new LocalSocketAddress(mSocketName,
                LocalSocketAddress.Namespace.RESERVED);

        // try a few times in case the service has not listen()ed
        IOException excp = null;
        for (int i = 0; i < 10; i++) {
            try {
                s.connect(a);
                return s;
            } catch (IOException e) {
                Log.d(mTag, "service not yet listen()ing; try again");
                excp = e;
                sleep(500);
            }
        }
        throw excp;
    }

    private OutputStream getControlSocketOutput() throws IOException {
        if (mKeepaliveSocket != null) {
            mControlSocketInUse = true;
            mSocketResult = null;
            return mKeepaliveSocket.getOutputStream();
        } else {
            throw new IOException("no control socket available");
        }
    }

    private synchronized void checkSocketResult() throws IOException {
        try {
            // will be notified when the result comes back from service
            if (mSocketResult == null) wait();
        } catch (InterruptedException e) {
            Log.d(mTag, "checkSocketResult(): " + e);
        } finally {
            mControlSocketInUse = false;
            if ((mSocketResult == null) || (mSocketResult < 0)) {
                throw new IOException("socket error, result from service: "
                        + mSocketResult);
            }
        }
    }

    private synchronized void setSocketResult(int result) {
        if (mControlSocketInUse) {
            mSocketResult = result;
            notifyAll();
        }
    }

    private void setResultAndCloseControlSocket(int result) {
        setSocketResult(result);
        try {
            mKeepaliveSocket.shutdownInput();
            mKeepaliveSocket.shutdownOutput();
            mKeepaliveSocket.close();
        } catch (IOException e) {
            Log.e(mTag, "close keepalive socket", e);
        } finally {
            mKeepaliveSocket = null;
        }
    }

    /**
     * Waits for the process to be in the expected state. The method returns
     * false if after the specified duration (in seconds), the process is still
     * not in the expected state.
     */
    private boolean blockUntil(String expectedState, int waitTime) {
        String cmd = SVC_STATE_CMD_PREFIX + mServiceName;
        int sleepTime = 200; // ms
        int n = waitTime * 1000 / sleepTime;
        for (int i = 0; i < n; i++) {
            if (expectedState.equals(SystemProperties.get(cmd))) {
                Log.d(mTag, mServiceName + " is " + expectedState + " after "
                        + (i * sleepTime) + " msec");
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
}
