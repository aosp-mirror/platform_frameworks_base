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
package com.android.server;

import android.annotation.Nullable;
import android.os.UEventObserver;
import android.util.ArrayMap;
import android.util.Slog;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;

/**
 * A specialized UEventObserver that receives UEvents from the kernel for devices in the {@code
 * /sys/class/extcon}. directory
 *
 * <p>Subclass ExtconUEventObserver, implementing {@link #onUEvent(ExtconInfo, UEvent)}, then call
 * startObserving() with a ExtconInfo to observe. The UEvent thread will then call your onUEvent()
 * method when a UEvent occurs that matches the path of your ExtconInfos.
 *
 * <p>Call stopObserving() to stop receiving UEvents.
 *
 * <p>There is only one UEvent thread per process, even if that process has multiple UEventObserver
 * subclass instances. The UEvent thread starts when the startObserving() is called for the first
 * time in that process. Once started the UEvent thread will not stop (although it can stop
 * notifying UEventObserver's via stopObserving()).
 *
 * <p>
 *
 * @hide
 */
public abstract class ExtconUEventObserver extends UEventObserver {
    private static final String TAG = "ExtconUEventObserver";
    private static final boolean LOG = false;
    private final Map<String, ExtconInfo> mExtconInfos = new ArrayMap<>();

    @Override
    public final void onUEvent(UEvent event) {
        String devPath = event.get("DEVPATH");
        ExtconInfo info = mExtconInfos.get(devPath);
        if (info != null) {
            onUEvent(info, event);
        } else {
            Slog.w(TAG, "No match found for DEVPATH of " + event + " in " + mExtconInfos);
        }
    }

    /**
     * Subclasses of ExtconUEventObserver should override this method to handle UEvents.
     *
     * @param extconInfo that matches the {@code DEVPATH} of {@code event}
     * @param event the event
     */
    protected abstract void onUEvent(ExtconInfo extconInfo, UEvent event);

    /** Starts observing {@link ExtconInfo#getDevicePath()}. */
    public void startObserving(ExtconInfo extconInfo) {
        mExtconInfos.put(extconInfo.getDevicePath(), extconInfo);
        if (LOG) Slog.v(TAG, "Observing  " + extconInfo.getDevicePath());
        startObserving("DEVPATH=" + extconInfo.getDevicePath());
    }

    /** An External Connection to watch. */
    public static final class ExtconInfo {
        private static final String TAG = "ExtconInfo";

        private final String mName;

        public ExtconInfo(String name) {
            mName = name;
        }

        /** The name of the external connection */
        public String getName() {
            return mName;
        }

        /**
         * The path to the device for this external connection.
         *
         * <p><b>NOTE</b> getting this path involves resolving a symlink.
         *
         * @return the device path, or null if it not found.
         */
        @Nullable
        public String getDevicePath() {
            try {
                String extconPath = String.format(Locale.US, "/sys/class/extcon/%s", mName);
                File devPath = new File(extconPath);
                if (devPath.exists()) {
                    String canonicalPath = devPath.getCanonicalPath();
                    int start = canonicalPath.indexOf("/devices");
                    return canonicalPath.substring(start);
                }
                return null;
            } catch (IOException e) {
                Slog.e(TAG, "Could not get the extcon device path for " + mName, e);
                return null;
            }
        }

        /** The path to the state file */
        public String getStatePath() {
            return String.format(Locale.US, "/sys/class/extcon/%s/state", mName);
        }
    }

    /** Does the {@link /sys/class/extcon} directory exist */
    public static boolean extconExists() {
        File extconDir = new File("/sys/class/extcon");
        return extconDir.exists() && extconDir.isDirectory();
    }
}
