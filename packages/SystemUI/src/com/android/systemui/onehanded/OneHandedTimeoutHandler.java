/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.onehanded;

import static com.android.systemui.onehanded.OneHandedSettingsUtil.ONE_HANDED_TIMEOUT_MEDIUM_IN_SECONDS;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.android.systemui.Dumpable;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.inject.Singleton;

/**
 * Timeout handler for stop one handed mode operations.
 */
@Singleton
public class OneHandedTimeoutHandler implements Dumpable {
    private static final String TAG = "OneHandedTimeoutHandler";
    private static boolean sIsDragging = false;
    // Default timeout is ONE_HANDED_TIMEOUT_MEDIUM
    private static @OneHandedSettingsUtil.OneHandedTimeout int sTimeout =
            ONE_HANDED_TIMEOUT_MEDIUM_IN_SECONDS;
    private static long sTimeoutMs = TimeUnit.SECONDS.toMillis(sTimeout);
    private static OneHandedTimeoutHandler sInstance;
    private static List<TimeoutListener> sListeners = new ArrayList<>();

    @VisibleForTesting
    static final int ONE_HANDED_TIMEOUT_STOP_MSG = 1;
    @VisibleForTesting
    static Handler sHandler;

    /**
     * Get the current config of timeout
     *
     * @return timeout of current config
     */
    public @OneHandedSettingsUtil.OneHandedTimeout int getTimeout() {
        return sTimeout;
    }

    /**
     * Listens for notify timeout events
     */
    public interface TimeoutListener {
        /**
         * Called whenever the config time out
         *
         * @param timeoutTime The time in seconds to trigger timeout
         */
        void onTimeout(int timeoutTime);
    }

    /**
     * Set the specific timeout of {@link OneHandedSettingsUtil.OneHandedTimeout}
     */
    public static void setTimeout(@OneHandedSettingsUtil.OneHandedTimeout int timeout) {
        sTimeout = timeout;
        sTimeoutMs = TimeUnit.SECONDS.toMillis(sTimeout);
        resetTimer();
    }

    /**
     * Reset the timer when one handed trigger or user is operating in some conditions
     */
    public static void removeTimer() {
        sHandler.removeMessages(ONE_HANDED_TIMEOUT_STOP_MSG);
    }

    /**
     * Reset the timer when one handed trigger or user is operating in some conditions
     */
    public static void resetTimer() {
        removeTimer();
        if (sTimeout == OneHandedSettingsUtil.ONE_HANDED_TIMEOUT_NEVER) {
            return;
        }
        if (sTimeout != OneHandedSettingsUtil.ONE_HANDED_TIMEOUT_NEVER) {
            sHandler.sendEmptyMessageDelayed(ONE_HANDED_TIMEOUT_STOP_MSG, sTimeoutMs);
        }
    }

    /**
     * Register timeout listener to receive time out events
     *
     * @param listener the listener be sent events when times up
     */
    public static void registerTimeoutListener(TimeoutListener listener) {
        sListeners.add(listener);
    }

    /**
     * Private constructor due to Singleton pattern
     */
    private OneHandedTimeoutHandler() {
    }

    /**
     * Singleton pattern to get {@link OneHandedTimeoutHandler} instance
     *
     * @return the static update thread instance
     */
    public static OneHandedTimeoutHandler get() {
        synchronized (OneHandedTimeoutHandler.class) {
            if (sInstance == null) {
                sInstance = new OneHandedTimeoutHandler();
            }
            if (sHandler == null) {
                sHandler = new Handler(Looper.myLooper()) {
                    @Override
                    public void handleMessage(Message msg) {
                        if (msg.what == ONE_HANDED_TIMEOUT_STOP_MSG) {
                            onStop();
                        }
                    }
                };
                if (sTimeout != OneHandedSettingsUtil.ONE_HANDED_TIMEOUT_NEVER) {
                    sHandler.sendEmptyMessageDelayed(ONE_HANDED_TIMEOUT_STOP_MSG, sTimeoutMs);
                }
            }
            return sInstance;
        }
    }

    private static void onStop() {
        for (int i = sListeners.size() - 1; i >= 0; i--) {
            final TimeoutListener listener = sListeners.get(i);
            listener.onTimeout(sTimeout);
        }
    }

    @Override
    public void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter pw, @NonNull String[] args) {
        final String innerPrefix = "  ";
        pw.println(TAG + "states: ");
        pw.print(innerPrefix + "sTimeout=");
        pw.println(sTimeout);
        pw.print(innerPrefix + "sListeners=");
        pw.println(sListeners);
    }

}
