/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.content.ComponentName;

/**
 * This class describes various information required to start a process.
 *
 * The {@link #mHostingType} parameter describes the reason why we started a process, and
 * is only used for logging and stats.
 *
 * The {@link #mHostingName} parameter describes the Component for which we are starting the
 * process, and is only used for logging and stats.
 *
 * The {@link #mHostingZygote} describes from which Zygote the new process should be spawned.
 *
 */

public final class HostingRecord {
    private static final int REGULAR_ZYGOTE = 0;
    private static final int WEBVIEW_ZYGOTE = 1;
    private static final int APP_ZYGOTE = 2;

    private final String mHostingType;
    private final String mHostingName;
    private final int mHostingZygote;

    public HostingRecord(String hostingType) {
        this(hostingType, null, REGULAR_ZYGOTE);
    }

    public HostingRecord(String hostingType, ComponentName hostingName) {
        this(hostingType, hostingName.toShortString(), REGULAR_ZYGOTE);
    }

    public HostingRecord(String hostingType, String hostingName) {
        this(hostingType, hostingName, REGULAR_ZYGOTE);
    }

    private HostingRecord(String hostingType, String hostingName, int hostingZygote) {
        mHostingType = hostingType;
        mHostingName = hostingName;
        mHostingZygote = hostingZygote;
    }

    public String getType() {
        return mHostingType;
    }

    public String getName() {
        return mHostingName;
    }

    /**
     * Creates a HostingRecord for a process that must spawn from the webview zygote
     * @param hostingName name of the component to be hosted in this process
     * @return The constructed HostingRecord
     */
    public static HostingRecord byWebviewZygote(ComponentName hostingName) {
        return new HostingRecord("", hostingName.toShortString(), WEBVIEW_ZYGOTE);
    }

    /**
     * Creates a HostingRecord for a process that must spawn from the application zygote
     * @param hostingName name of the component to be hosted in this process
     * @return The constructed HostingRecord
     */
    public static HostingRecord byAppZygote(ComponentName hostingName) {
        return new HostingRecord("", hostingName.toShortString(), APP_ZYGOTE);
    }

    /**
     * @return whether the process should spawn from the application zygote
     */
    public boolean usesAppZygote() {
        return mHostingZygote == APP_ZYGOTE;
    }

    /**
     * @return whether the process should spawn from the webview zygote
     */
    public boolean usesWebviewZygote() {
        return mHostingZygote == WEBVIEW_ZYGOTE;
    }
}
