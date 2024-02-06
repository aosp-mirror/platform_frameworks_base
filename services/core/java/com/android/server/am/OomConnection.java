/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.am;

import android.os.OomKillRecord;
import android.util.Slog;

/** Connection to the out-of-memory (OOM) events' file */
public final class OomConnection {
    private static final String TAG = "OomConnection";

    /** Connection listener interface */
    public interface OomConnectionListener {

        /**
         * Callback function to handle the newest OOM kills.
         *
         * @param oomKills List of oom kills received from `waitOom()`
         */
        void handleOomEvent(OomKillRecord[] oomKills);
    }

    private final OomConnectionListener mOomListener;

    private final OomConnectionThread mOomConnectionThread;

    private static native OomKillRecord[] waitOom();

    public OomConnection(OomConnectionListener listener) {
        mOomListener = listener;
        mOomConnectionThread = new OomConnectionThread();
        mOomConnectionThread.start();
    }

    private final class OomConnectionThread extends Thread {
        public void run() {
            while (true) {
                OomKillRecord[] oom_kills = null;
                try {
                    oom_kills = waitOom();
                    mOomListener.handleOomEvent(oom_kills);
                } catch (RuntimeException e) {
                    Slog.e(TAG, "failed waiting for OOM events: " + e);
                    break;
                }
            }
        }
    }
}
