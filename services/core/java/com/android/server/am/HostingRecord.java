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
 * The {@code mHostingType} field describes the reason why we started a process, and
 * is only used for logging and stats.
 *
 * The {@code mHostingName} field describes the Component for which we are starting the
 * process, and is only used for logging and stats.
 *
 * The {@code mHostingZygote} field describes from which Zygote the new process should be spawned.
 *
 * {@code mDefiningPackageName} contains the packageName of the package that defines the
 * component we want to start; this can be different from the packageName and uid in the
 * ApplicationInfo that we're creating the process with, in case the service is a
 * {@link android.content.Context#BIND_EXTERNAL_SERVICE} service. In that case, the packageName
 * and uid in the ApplicationInfo will be set to those of the caller, not of the defining package.
 *
 * {@code mDefiningUid} contains the uid of the application that defines the component we want to
 * start; this can be different from the packageName and uid in the ApplicationInfo that we're
 * creating the process with, in case the service is a
 * {@link android.content.Context#BIND_EXTERNAL_SERVICE} service. In that case, the packageName
 * and uid in the ApplicationInfo will be set to those of the caller, not of the defining package.
 *
 * {@code mIsTopApp} will be passed to {@link android.os.Process#start}. So Zygote will initialize
 * the process with high priority.
 */

public final class HostingRecord {
    private static final int REGULAR_ZYGOTE = 0;
    private static final int WEBVIEW_ZYGOTE = 1;
    private static final int APP_ZYGOTE = 2;

    private final String mHostingType;
    private final String mHostingName;
    private final int mHostingZygote;
    private final String mDefiningPackageName;
    private final int mDefiningUid;
    private final boolean mIsTopApp;

    public HostingRecord(String hostingType) {
        this(hostingType, null /* hostingName */, REGULAR_ZYGOTE, null /* definingPackageName */,
                -1 /* mDefiningUid */, false /* isTopApp */);
    }

    public HostingRecord(String hostingType, ComponentName hostingName) {
        this(hostingType, hostingName, REGULAR_ZYGOTE);
    }

    public HostingRecord(String hostingType, ComponentName hostingName, boolean isTopApp) {
        this(hostingType, hostingName.toShortString(), REGULAR_ZYGOTE,
                null /* definingPackageName */, -1 /* mDefiningUid */, isTopApp /* isTopApp */);
    }

    public HostingRecord(String hostingType, String hostingName) {
        this(hostingType, hostingName, REGULAR_ZYGOTE);
    }

    private HostingRecord(String hostingType, ComponentName hostingName, int hostingZygote) {
        this(hostingType, hostingName.toShortString(), hostingZygote);
    }

    private HostingRecord(String hostingType, String hostingName, int hostingZygote) {
        this(hostingType, hostingName, hostingZygote, null /* definingPackageName */,
                -1 /* mDefiningUid */, false /* isTopApp */);
    }

    private HostingRecord(String hostingType, String hostingName, int hostingZygote,
            String definingPackageName, int definingUid, boolean isTopApp) {
        mHostingType = hostingType;
        mHostingName = hostingName;
        mHostingZygote = hostingZygote;
        mDefiningPackageName = definingPackageName;
        mDefiningUid = definingUid;
        mIsTopApp = isTopApp;
    }

    public String getType() {
        return mHostingType;
    }

    public String getName() {
        return mHostingName;
    }

    public boolean isTopApp() {
        return mIsTopApp;
    }

    /**
     * Returns the UID of the package defining the component we want to start. Only valid
     * when {@link #usesAppZygote()} returns true.
     *
     * @return the UID of the hosting application
     */
    public int getDefiningUid() {
        return mDefiningUid;
    }

    /**
     * Returns the packageName of the package defining the component we want to start. Only valid
     * when {@link #usesAppZygote()} returns true.
     *
     * @return the packageName of the hosting application
     */
    public String getDefiningPackageName() {
        return mDefiningPackageName;
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
     * @param definingPackageName name of the package defining the service
     * @param definingUid uid of the package defining the service
     * @return The constructed HostingRecord
     */
    public static HostingRecord byAppZygote(ComponentName hostingName, String definingPackageName,
            int definingUid) {
        return new HostingRecord("", hostingName.toShortString(), APP_ZYGOTE,
                definingPackageName, definingUid, false /* isTopApp */);
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
