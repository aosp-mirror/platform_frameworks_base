/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.server.wm;


import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.util.Slog;

import java.net.ServerSocket;
import java.net.Socket;
import java.net.InetAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.BufferedWriter;
import java.io.OutputStreamWriter;

/**
 * The ViewServer is local socket server that can be used to communicate with the
 * views of the opened windows. Communication with the views is ensured by the
 * {@link com.android.server.wm.WindowManagerService} and is a cross-process operation.
 *
 * {@hide}
 */
class ViewServer implements Runnable {
    /**
     * The default port used to start view servers.
     */
    public static final int VIEW_SERVER_DEFAULT_PORT = 4939;

    private static final int VIEW_SERVER_MAX_CONNECTIONS = 10;

    // Debug facility
    private static final String LOG_TAG = TAG_WITH_CLASS_NAME ? "ViewServer" : TAG_WM;

    private static final String VALUE_PROTOCOL_VERSION = "4";
    private static final String VALUE_SERVER_VERSION = "4";

    // Protocol commands
    // Returns the protocol version
    private static final String COMMAND_PROTOCOL_VERSION = "PROTOCOL";
    // Returns the server version
    private static final String COMMAND_SERVER_VERSION = "SERVER";
    // Lists all of the available windows in the system
    private static final String COMMAND_WINDOW_MANAGER_LIST = "LIST";
    // Keeps a connection open and notifies when the list of windows changes
    private static final String COMMAND_WINDOW_MANAGER_AUTOLIST = "AUTOLIST";
    // Returns the focused window
    private static final String COMMAND_WINDOW_MANAGER_GET_FOCUS = "GET_FOCUS";

    private ServerSocket mServer;
    private Thread mThread;

    private final WindowManagerService mWindowManager;
    private final int mPort;

    private ExecutorService mThreadPool;

    /**
     * Creates a new ViewServer associated with the specified window manager on the
     * specified local port. The server is not started by default.
     *
     * @param windowManager The window manager used to communicate with the views.
     * @param port The port for the server to listen to.
     *
     * @see #start()
     */
    ViewServer(WindowManagerService windowManager, int port) {
        mWindowManager = windowManager;
        mPort = port;
    }

    /**
     * Starts the server.
     *
     * @return True if the server was successfully created, or false if it already exists.
     * @throws IOException If the server cannot be created.
     *
     * @see #stop()
     * @see #isRunning()
     * @see WindowManagerService#startViewServer(int)
     */
    boolean start() throws IOException {
        if (mThread != null) {
            return false;
        }

        mServer = new ServerSocket(mPort, VIEW_SERVER_MAX_CONNECTIONS, InetAddress.getLocalHost());
        mThread = new Thread(this, "Remote View Server [port=" + mPort + "]");
        mThreadPool = Executors.newFixedThreadPool(VIEW_SERVER_MAX_CONNECTIONS);
        mThread.start();

        return true;
    }

    /**
     * Stops the server.
     *
     * @return True if the server was stopped, false if an error occured or if the
     *         server wasn't started.
     *
     * @see #start()
     * @see #isRunning()
     * @see WindowManagerService#stopViewServer()
     */
    boolean stop() {
        if (mThread != null) {

            mThread.interrupt();
            if (mThreadPool != null) {
                try {
                    mThreadPool.shutdownNow();
                } catch (SecurityException e) {
                    Slog.w(LOG_TAG, "Could not stop all view server threads");
                }
            }
            mThreadPool = null;
            mThread = null;
            try {
                mServer.close();
                mServer = null;
                return true;
            } catch (IOException e) {
                Slog.w(LOG_TAG, "Could not close the view server");
            }
        }
        return false;
    }

    /**
     * Indicates whether the server is currently running.
     *
     * @return True if the server is running, false otherwise.
     *
     * @see #start()
     * @see #stop()
     * @see WindowManagerService#isViewServerRunning()
     */
    boolean isRunning() {
        return mThread != null && mThread.isAlive();
    }

    /**
     * Main server loop.
     */
    public void run() {
        while (Thread.currentThread() == mThread) {
            // Any uncaught exception will crash the system process
            try {
                Socket client = mServer.accept();
                if (mThreadPool != null) {
                    mThreadPool.submit(new ViewServerWorker(client));
                } else {
                    try {
                        client.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            } catch (Exception e) {
                Slog.w(LOG_TAG, "Connection error: ", e);
            }
        }
    }

    private static boolean writeValue(Socket client, String value) {
        boolean result;
        BufferedWriter out = null;
        try {
            OutputStream clientStream = client.getOutputStream();
            out = new BufferedWriter(new OutputStreamWriter(clientStream), 8 * 1024);
            out.write(value);
            out.write("\n");
            out.flush();
            result = true;
        } catch (Exception e) {
            result = false;
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException e) {
                    result = false;
                }
            }
        }
        return result;
    }

    class ViewServerWorker implements Runnable, WindowManagerService.WindowChangeListener {
        private Socket mClient;
        private boolean mNeedWindowListUpdate;
        private boolean mNeedFocusedWindowUpdate;

        public ViewServerWorker(Socket client) {
            mClient = client;
            mNeedWindowListUpdate = false;
            mNeedFocusedWindowUpdate = false;
        }

        public void run() {

            BufferedReader in = null;
            try {
                in = new BufferedReader(new InputStreamReader(mClient.getInputStream()), 1024);

                final String request = in.readLine();

                String command;
                String parameters;

                int index = request.indexOf(' ');
                if (index == -1) {
                    command = request;
                    parameters = "";
                } else {
                    command = request.substring(0, index);
                    parameters = request.substring(index + 1);
                }

                boolean result;
                if (COMMAND_PROTOCOL_VERSION.equalsIgnoreCase(command)) {
                    result = writeValue(mClient, VALUE_PROTOCOL_VERSION);
                } else if (COMMAND_SERVER_VERSION.equalsIgnoreCase(command)) {
                    result = writeValue(mClient, VALUE_SERVER_VERSION);
                } else if (COMMAND_WINDOW_MANAGER_LIST.equalsIgnoreCase(command)) {
                    result = mWindowManager.viewServerListWindows(mClient);
                } else if (COMMAND_WINDOW_MANAGER_GET_FOCUS.equalsIgnoreCase(command)) {
                    result = mWindowManager.viewServerGetFocusedWindow(mClient);
                } else if (COMMAND_WINDOW_MANAGER_AUTOLIST.equalsIgnoreCase(command)) {
                    result = windowManagerAutolistLoop();
                } else {
                    result = mWindowManager.viewServerWindowCommand(mClient,
                            command, parameters);
                }

                if (!result) {
                    Slog.w(LOG_TAG, "An error occurred with the command: " + command);
                }
            } catch(IOException e) {
                Slog.w(LOG_TAG, "Connection error: ", e);
            } finally {
                if (in != null) {
                    try {
                        in.close();

                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                if (mClient != null) {
                    try {
                        mClient.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }

        public void windowsChanged() {
            synchronized(this) {
                mNeedWindowListUpdate = true;
                notifyAll();
            }
        }

        public void focusChanged() {
            synchronized(this) {
                mNeedFocusedWindowUpdate = true;
                notifyAll();
            }
        }

        private boolean windowManagerAutolistLoop() {
            mWindowManager.addWindowChangeListener(this);
            BufferedWriter out = null;
            try {
                out = new BufferedWriter(new OutputStreamWriter(mClient.getOutputStream()));
                while (!Thread.interrupted()) {
                    boolean needWindowListUpdate = false;
                    boolean needFocusedWindowUpdate = false;
                    synchronized (this) {
                        while (!mNeedWindowListUpdate && !mNeedFocusedWindowUpdate) {
                            wait();
                        }
                        if (mNeedWindowListUpdate) {
                            mNeedWindowListUpdate = false;
                            needWindowListUpdate = true;
                        }
                        if (mNeedFocusedWindowUpdate) {
                            mNeedFocusedWindowUpdate = false;
                            needFocusedWindowUpdate = true;
                        }
                    }
                    if (needWindowListUpdate) {
                        out.write("LIST UPDATE\n");
                        out.flush();
                    }
                    if (needFocusedWindowUpdate) {
                        out.write("ACTION_FOCUS UPDATE\n");
                        out.flush();
                    }
                }
            } catch (Exception e) {
                // Ignore
            } finally {
                if (out != null) {
                    try {
                        out.close();
                    } catch (IOException e) {
                        // Ignore
                    }
                }
                mWindowManager.removeWindowChangeListener(this);
            }
            return true;
        }
    }
}
