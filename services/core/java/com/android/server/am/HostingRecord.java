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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.os.ProcessStartTime;

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
 *
 *  {@code mAction} the broadcast's intent action if the process is started for a broadcast
 *  receiver.
 */

public final class HostingRecord {
    private static final int REGULAR_ZYGOTE = 0;
    private static final int WEBVIEW_ZYGOTE = 1;
    private static final int APP_ZYGOTE = 2;

    public static final String HOSTING_TYPE_ACTIVITY = "activity";
    public static final String HOSTING_TYPE_ADDED_APPLICATION = "added application";
    public static final String HOSTING_TYPE_BACKUP = "backup";
    public static final String HOSTING_TYPE_BROADCAST = "broadcast";
    public static final String HOSTING_TYPE_CONTENT_PROVIDER = "content provider";
    public static final String HOSTING_TYPE_LINK_FAIL = "link fail";
    public static final String HOSTING_TYPE_ON_HOLD = "on-hold";
    public static final String HOSTING_TYPE_NEXT_ACTIVITY = "next-activity";
    public static final String HOSTING_TYPE_NEXT_TOP_ACTIVITY = "next-top-activity";
    public static final String HOSTING_TYPE_RESTART = "restart";
    public static final String HOSTING_TYPE_SERVICE = "service";
    public static final String HOSTING_TYPE_SYSTEM = "system";
    public static final String HOSTING_TYPE_TOP_ACTIVITY = "top-activity";
    public static final String HOSTING_TYPE_EMPTY = "";

    private @NonNull final String mHostingType;
    private final String mHostingName;
    private final int mHostingZygote;
    private final String mDefiningPackageName;
    private final int mDefiningUid;
    private final boolean mIsTopApp;
    private final String mDefiningProcessName;
    @Nullable private final String mAction;

    public HostingRecord(@NonNull String hostingType) {
        this(hostingType, null /* hostingName */, REGULAR_ZYGOTE, null /* definingPackageName */,
                -1 /* mDefiningUid */, false /* isTopApp */, null /* definingProcessName */,
                null /* action */);
    }

    public HostingRecord(@NonNull String hostingType, ComponentName hostingName) {
        this(hostingType, hostingName, REGULAR_ZYGOTE);
    }

    public HostingRecord(@NonNull String hostingType, ComponentName hostingName,
            @Nullable String action) {
        this(hostingType, hostingName.toShortString(), REGULAR_ZYGOTE,
                null /* definingPackageName */, -1 /* mDefiningUid */, false /* isTopApp */,
                null /* definingProcessName */, action);
    }

    public HostingRecord(@NonNull String hostingType, ComponentName hostingName,
            String definingPackageName, int definingUid, String definingProcessName) {
        this(hostingType, hostingName.toShortString(), REGULAR_ZYGOTE, definingPackageName,
                definingUid, false /* isTopApp */, definingProcessName, null /* action */);
    }

    public HostingRecord(@NonNull String hostingType, ComponentName hostingName, boolean isTopApp) {
        this(hostingType, hostingName.toShortString(), REGULAR_ZYGOTE,
                null /* definingPackageName */, -1 /* mDefiningUid */, isTopApp /* isTopApp */,
                null /* definingProcessName */, null /* action */);
    }

    public HostingRecord(@NonNull String hostingType, String hostingName) {
        this(hostingType, hostingName, REGULAR_ZYGOTE);
    }

    private HostingRecord(@NonNull String hostingType, ComponentName hostingName,
            int hostingZygote) {
        this(hostingType, hostingName.toShortString(), hostingZygote);
    }

    private HostingRecord(@NonNull String hostingType, String hostingName, int hostingZygote) {
        this(hostingType, hostingName, hostingZygote, null /* definingPackageName */,
                -1 /* mDefiningUid */, false /* isTopApp */, null /* definingProcessName */,
                null /* action */);
    }

    private HostingRecord(@NonNull String hostingType, String hostingName, int hostingZygote,
            String definingPackageName, int definingUid, boolean isTopApp,
            String definingProcessName, @Nullable String action) {
        mHostingType = hostingType;
        mHostingName = hostingName;
        mHostingZygote = hostingZygote;
        mDefiningPackageName = definingPackageName;
        mDefiningUid = definingUid;
        mIsTopApp = isTopApp;
        mDefiningProcessName = definingProcessName;
        mAction = action;
    }

    public @NonNull String getType() {
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
     * Returns the processName of the component we want to start as specified in the defining app's
     * manifest.
     *
     * @return the processName of the process in the hosting application
     */
    public String getDefiningProcessName() {
        return mDefiningProcessName;
    }

    /**
     * Returns the broadcast's intent action if the process is started for a broadcast receiver.
     *
     * @return the intent action of the broadcast.
     */
    public @Nullable String getAction() {
        return mAction;
    }

    /**
     * Creates a HostingRecord for a process that must spawn from the webview zygote
     * @param hostingName name of the component to be hosted in this process
     * @return The constructed HostingRecord
     */
    public static HostingRecord byWebviewZygote(ComponentName hostingName,
            String definingPackageName, int definingUid, String definingProcessName) {
        return new HostingRecord(HostingRecord.HOSTING_TYPE_EMPTY, hostingName.toShortString(),
                WEBVIEW_ZYGOTE, definingPackageName, definingUid, false /* isTopApp */,
                definingProcessName, null /* action */);
    }

    /**
     * Creates a HostingRecord for a process that must spawn from the application zygote
     * @param hostingName name of the component to be hosted in this process
     * @param definingPackageName name of the package defining the service
     * @param definingUid uid of the package defining the service
     * @return The constructed HostingRecord
     */
    public static HostingRecord byAppZygote(ComponentName hostingName, String definingPackageName,
            int definingUid, String definingProcessName) {
        return new HostingRecord(HostingRecord.HOSTING_TYPE_EMPTY, hostingName.toShortString(),
                APP_ZYGOTE, definingPackageName, definingUid, false /* isTopApp */,
                definingProcessName, null /* action */);
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

    /**
     * Map the string hostingType to enum HostingType defined in ProcessStartTime proto.
     * @param hostingType
     * @return enum HostingType defined in ProcessStartTime proto
     */
    public static int getHostingTypeIdStatsd(@NonNull String hostingType) {
        switch(hostingType) {
            case HOSTING_TYPE_ACTIVITY:
                return ProcessStartTime.HOSTING_TYPE_ACTIVITY;
            case HOSTING_TYPE_ADDED_APPLICATION:
                return ProcessStartTime.HOSTING_TYPE_ADDED_APPLICATION;
            case HOSTING_TYPE_BACKUP:
                return ProcessStartTime.HOSTING_TYPE_BACKUP;
            case HOSTING_TYPE_BROADCAST:
                return ProcessStartTime.HOSTING_TYPE_BROADCAST;
            case HOSTING_TYPE_CONTENT_PROVIDER:
                return ProcessStartTime.HOSTING_TYPE_CONTENT_PROVIDER;
            case HOSTING_TYPE_LINK_FAIL:
                return ProcessStartTime.HOSTING_TYPE_LINK_FAIL;
            case HOSTING_TYPE_ON_HOLD:
                return ProcessStartTime.HOSTING_TYPE_ON_HOLD;
            case HOSTING_TYPE_NEXT_ACTIVITY:
                return ProcessStartTime.HOSTING_TYPE_NEXT_ACTIVITY;
            case HOSTING_TYPE_NEXT_TOP_ACTIVITY:
                return ProcessStartTime.HOSTING_TYPE_NEXT_TOP_ACTIVITY;
            case HOSTING_TYPE_RESTART:
                return ProcessStartTime.HOSTING_TYPE_RESTART;
            case HOSTING_TYPE_SERVICE:
                return ProcessStartTime.HOSTING_TYPE_SERVICE;
            case HOSTING_TYPE_SYSTEM:
                return ProcessStartTime.HOSTING_TYPE_SYSTEM;
            case HOSTING_TYPE_TOP_ACTIVITY:
                return ProcessStartTime.HOSTING_TYPE_TOP_ACTIVITY;
            case HOSTING_TYPE_EMPTY:
                return ProcessStartTime.HOSTING_TYPE_EMPTY;
            default:
                return ProcessStartTime.HOSTING_TYPE_UNKNOWN;
        }
    }
}
