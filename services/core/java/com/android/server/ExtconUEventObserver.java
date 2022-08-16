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
import android.annotation.StringDef;
import android.os.FileUtils;
import android.os.UEventObserver;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Slog;
import com.android.internal.annotations.GuardedBy;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
 * @hide
 */
public abstract class ExtconUEventObserver extends UEventObserver {
    private static final String TAG = "ExtconUEventObserver";
    private static final boolean LOG = false;
    private static final String SELINUX_POLICIES_NEED_TO_BE_CHANGED =
            "This probably means the selinux policies need to be changed.";

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
     * @param event      the event
     */
    protected abstract void onUEvent(ExtconInfo extconInfo, UEvent event);

    /** Starts observing {@link ExtconInfo#getDevicePath()}. */
    public void startObserving(ExtconInfo extconInfo) {
        String devicePath = extconInfo.getDevicePath();
        if (devicePath == null) {
            Slog.wtf(TAG, "Unable to start observing  " + extconInfo.getName()
                    + " because the device path is null. " + SELINUX_POLICIES_NEED_TO_BE_CHANGED);
        } else {
            mExtconInfos.put(devicePath, extconInfo);
            if (LOG) Slog.v(TAG, "Observing  " + devicePath);
            startObserving("DEVPATH=" + devicePath);
        }
    }

    /** An External Connection to watch. */
    public static final class ExtconInfo {
        /* Copied from drivers/extcon/extcon.c */

        /* USB external connector */
        public static final String EXTCON_USB = "USB";
        public static final String EXTCON_USB_HOST = "USB-HOST";

        /* Charger external connector */
        public static final String EXTCON_TA = "TA";
        public static final String EXTCON_FAST_CHARGER = "FAST-CHARGER";
        public static final String EXTCON_SLOW_CHARGER = "SLOW-CHARGER";
        public static final String EXTCON_CHARGE_DOWNSTREAM = "CHARGE-DOWNSTREAM";

        /* Audio/Video external connector */
        public static final String EXTCON_LINE_IN = "LINE-IN";
        public static final String EXTCON_LINE_OUT = "LINE-OUT";
        public static final String EXTCON_MICROPHONE = "MICROPHONE";
        public static final String EXTCON_HEADPHONE = "HEADPHONE";

        public static final String EXTCON_HDMI = "HDMI";
        public static final String EXTCON_MHL = "MHL";
        public static final String EXTCON_DVI = "DVI";
        public static final String EXTCON_VGA = "VGA";
        public static final String EXTCON_SPDIF_IN = "SPDIF-IN";
        public static final String EXTCON_SPDIF_OUT = "SPDIF-OUT";
        public static final String EXTCON_VIDEO_IN = "VIDEO-IN";
        public static final String EXTCON_VIDEO_OUT = "VIDEO-OUT";

        /* Etc external connector */
        public static final String EXTCON_DOCK = "DOCK";
        public static final String EXTCON_JIG = "JIG";
        public static final String EXTCON_MECHANICAL = "MECHANICAL";

        @StringDef({
                EXTCON_USB,
                EXTCON_USB_HOST,
                EXTCON_TA,
                EXTCON_FAST_CHARGER,
                EXTCON_SLOW_CHARGER,
                EXTCON_CHARGE_DOWNSTREAM,
                EXTCON_LINE_IN,
                EXTCON_LINE_OUT,
                EXTCON_MICROPHONE,
                EXTCON_HEADPHONE,
                EXTCON_HDMI,
                EXTCON_MHL,
                EXTCON_DVI,
                EXTCON_VGA,
                EXTCON_SPDIF_IN,
                EXTCON_SPDIF_OUT,
                EXTCON_VIDEO_IN,
                EXTCON_VIDEO_OUT,
                EXTCON_DOCK,
                EXTCON_JIG,
                EXTCON_MECHANICAL,
        })

        public @interface ExtconDeviceType {}

        private static final Object sLock = new Object();
        private static ExtconInfo[] sExtconInfos = null;

        private final String mName;
        private final @ExtconDeviceType HashSet<String> mDeviceTypes = new HashSet<>();

        @GuardedBy("sLock")
        private static void initExtconInfos() {
            if (sExtconInfos != null) {
                return;
            }

            File file = new File("/sys/class/extcon");
            File[] files = file.listFiles();
            if (files == null) {
                Slog.w(TAG,
                        file + " exists " + file.exists() + " isDir " + file.isDirectory()
                                + " but listFiles returns null."
                                + SELINUX_POLICIES_NEED_TO_BE_CHANGED);
                sExtconInfos = new ExtconInfo[0];
            } else {
                List<ExtconInfo> list = new ArrayList<>(files.length);
                for (File f : files) {
                    list.add(new ExtconInfo(f.getName()));
                }
                sExtconInfos = list.toArray(new ExtconInfo[0]);
            }
        }

        /**
         * Returns a new list of all external connections for the types given.
         */
        public static List<ExtconInfo> getExtconInfoForTypes(
                @ExtconDeviceType String[] extconTypes) {
            synchronized (sLock) {
                initExtconInfos();
            }

            List<ExtconInfo> extcons = new ArrayList<ExtconInfo>();
            for (ExtconInfo extcon : sExtconInfos) {
                for (String type : extconTypes) {
                    if (extcon.hasCableType(type)) {
                        extcons.add(extcon);
                        break;
                    }
                }
            }

            return extcons;
        }

        /** True if the given type is supported */
        public boolean hasCableType(@ExtconDeviceType String type) {
            return mDeviceTypes.contains(type);
        }

        private ExtconInfo(String extconName) {
            mName = extconName;

            // Retrieve device types from /sys/class/extcon/extcon[X]/cable.[Y]/name
            File[] cableDirs = FileUtils.listFilesOrEmpty(new File("/sys/class/extcon", mName),
                    (dir, cable) -> cable.startsWith("cable."));
            if (cableDirs.length == 0) {
                Slog.d(TAG,
                        "Unable to list cables in /sys/class/extcon/" + mName + ". "
                                + SELINUX_POLICIES_NEED_TO_BE_CHANGED);
            }

            for (File cableDir : cableDirs) {
                String cableCanonicalPath = null;
                try {
                    cableCanonicalPath = cableDir.getCanonicalPath();
                    String name = FileUtils.readTextFile(new File(cableDir, "name"), 0, null);
                    name = name.replace("\n", "").replace("\r", "");
                    if (LOG) {
                        Slog.v(TAG, "Add extcon cable " + cableCanonicalPath);
                    }
                    mDeviceTypes.add(name);
                } catch (IOException ex) {
                    Slog.w(TAG,
                            "Unable to read " + cableCanonicalPath + "/name. "
                                    + SELINUX_POLICIES_NEED_TO_BE_CHANGED,
                            ex);
                }
            }
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
                String extconPath = TextUtils.formatSimple("/sys/class/extcon/%s", mName);
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
            return TextUtils.formatSimple("/sys/class/extcon/%s/state", mName);
        }
    }

    /** Does the {@code /sys/class/extcon} directory exist */
    public static boolean extconExists() {
        File extconDir = new File("/sys/class/extcon");
        return extconDir.exists() && extconDir.isDirectory();
    }
}
