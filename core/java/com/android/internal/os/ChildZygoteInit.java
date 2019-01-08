/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.internal.os;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

/**
 * ChildZygoteInit is shared by both the Application and WebView zygote to initialize
 * and run a (child) Zygote server.
 *
 * @hide
 */
public class ChildZygoteInit {
    private static final String TAG = "ChildZygoteInit";

    static String parseSocketNameFromArgs(String[] argv) {
        for (String arg : argv) {
            if (arg.startsWith(Zygote.CHILD_ZYGOTE_SOCKET_NAME_ARG)) {
                return arg.substring(Zygote.CHILD_ZYGOTE_SOCKET_NAME_ARG.length());
            }
        }

        return null;
    }

    static String parseAbiListFromArgs(String[] argv) {
        for (String arg : argv) {
            if (arg.startsWith(Zygote.CHILD_ZYGOTE_ABI_LIST_ARG)) {
                return arg.substring(Zygote.CHILD_ZYGOTE_ABI_LIST_ARG.length());
            }
        }

        return null;
    }

    /**
     * Starts a ZygoteServer and listens for requests
     *
     * @param server An instance of a ZygoteServer to listen on
     * @param args Passed in arguments for this ZygoteServer
     */
    static void runZygoteServer(ZygoteServer server, String[] args) {
        String socketName = parseSocketNameFromArgs(args);
        if (socketName == null) {
            throw new NullPointerException("No socketName specified");
        }

        String abiList = parseAbiListFromArgs(args);
        if (abiList == null) {
            throw new NullPointerException("No abiList specified");
        }

        try {
            Os.prctl(OsConstants.PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0);
        } catch (ErrnoException ex) {
            throw new RuntimeException("Failed to set PR_SET_NO_NEW_PRIVS", ex);
        }

        final Runnable caller;
        try {
            server.registerServerSocketAtAbstractName(socketName);

            // Add the abstract socket to the FD whitelist so that the native zygote code
            // can properly detach it after forking.
            Zygote.nativeAllowFileAcrossFork("ABSTRACT/" + socketName);

            // The select loop returns early in the child process after a fork and
            // loops forever in the zygote.
            caller = server.runSelectLoop(abiList);
        } catch (RuntimeException e) {
            Log.e(TAG, "Fatal exception:", e);
            throw e;
        } finally {
            server.closeServerSocket();
        }

        // We're in the child process and have exited the select loop. Proceed to execute the
        // command.
        if (caller != null) {
            caller.run();
        }
    }
}
