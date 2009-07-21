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

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;

/**
 * Proxy to perform a command with arguments.
 */
public class NormalProcessProxy extends ProcessProxy {
    private Process mProcess;
    private String[] mArgs;
    private String mTag;

    /**
     * Creates a proxy with the arguments.
     * @param args the argument list with the first one being the command
     */
    public NormalProcessProxy(String ...args) {
        if ((args == null) || (args.length == 0)) {
            throw new IllegalArgumentException();
        }
        mArgs = args;
        mTag = "PProxy_" + getName();
    }

    @Override
    public String getName() {
        return mArgs[0];
    }

    @Override
    public synchronized void stop() {
        if (isStopped()) return;
        getHostThread().interrupt();
        // TODO: not sure how to reliably kill a process
        mProcess.destroy();
        setState(ProcessState.STOPPING);
    }

    @Override
    protected void performTask() throws IOException, InterruptedException {
        String[] args = mArgs;
        Log.d(mTag, "+++++  Execute: " + getEntireCommand());
        ProcessBuilder pb = new ProcessBuilder(args);
        setState(ProcessState.RUNNING);
        Process p = mProcess = pb.start();
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                p.getInputStream()));
        while (true) {
            String line = reader.readLine();
            if ((line == null) || isStopping()) break;
            Log.d(mTag, line);
        }

        Log.d(mTag, "-----  p.waitFor(): " + getName());
        p.waitFor();
        Log.d(mTag, "-----  Done: " + getName());
    }

    private CharSequence getEntireCommand() {
        String[] args = mArgs;
        StringBuilder sb = new StringBuilder(args[0]);
        for (int i = 1; i < args.length; i++) sb.append(' ').append(args[i]);
        return sb;
    }
}
