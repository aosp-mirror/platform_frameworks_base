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

import static com.android.internal.util.FrameworkStatsLog.PROCESS_START_TIME__HOSTING_TYPE_ID__HOSTING_TYPE_ACTIVITY;
import static com.android.internal.util.FrameworkStatsLog.PROCESS_START_TIME__HOSTING_TYPE_ID__HOSTING_TYPE_ADDED_APPLICATION;
import static com.android.internal.util.FrameworkStatsLog.PROCESS_START_TIME__HOSTING_TYPE_ID__HOSTING_TYPE_BACKUP;
import static com.android.internal.util.FrameworkStatsLog.PROCESS_START_TIME__HOSTING_TYPE_ID__HOSTING_TYPE_BROADCAST;
import static com.android.internal.util.FrameworkStatsLog.PROCESS_START_TIME__HOSTING_TYPE_ID__HOSTING_TYPE_CONTENT_PROVIDER;
import static com.android.internal.util.FrameworkStatsLog.PROCESS_START_TIME__HOSTING_TYPE_ID__HOSTING_TYPE_EMPTY;
import static com.android.internal.util.FrameworkStatsLog.PROCESS_START_TIME__HOSTING_TYPE_ID__HOSTING_TYPE_LINK_FAIL;
import static com.android.internal.util.FrameworkStatsLog.PROCESS_START_TIME__HOSTING_TYPE_ID__HOSTING_TYPE_NEXT_ACTIVITY;
import static com.android.internal.util.FrameworkStatsLog.PROCESS_START_TIME__HOSTING_TYPE_ID__HOSTING_TYPE_NEXT_TOP_ACTIVITY;
import static com.android.internal.util.FrameworkStatsLog.PROCESS_START_TIME__HOSTING_TYPE_ID__HOSTING_TYPE_ON_HOLD;
import static com.android.internal.util.FrameworkStatsLog.PROCESS_START_TIME__HOSTING_TYPE_ID__HOSTING_TYPE_RESTART;
import static com.android.internal.util.FrameworkStatsLog.PROCESS_START_TIME__HOSTING_TYPE_ID__HOSTING_TYPE_SERVICE;
import static com.android.internal.util.FrameworkStatsLog.PROCESS_START_TIME__HOSTING_TYPE_ID__HOSTING_TYPE_SYSTEM;
import static com.android.internal.util.FrameworkStatsLog.PROCESS_START_TIME__HOSTING_TYPE_ID__HOSTING_TYPE_TOP_ACTIVITY;
import static com.android.internal.util.FrameworkStatsLog.PROCESS_START_TIME__TRIGGER_TYPE__TRIGGER_TYPE_ALARM;
import static com.android.internal.util.FrameworkStatsLog.PROCESS_START_TIME__TRIGGER_TYPE__TRIGGER_TYPE_JOB;
import static com.android.internal.util.FrameworkStatsLog.PROCESS_START_TIME__TRIGGER_TYPE__TRIGGER_TYPE_PUSH_MESSAGE;
import static com.android.internal.util.FrameworkStatsLog.PROCESS_START_TIME__TRIGGER_TYPE__TRIGGER_TYPE_PUSH_MESSAGE_OVER_QUOTA;
import static com.android.internal.util.FrameworkStatsLog.PROCESS_START_TIME__TRIGGER_TYPE__TRIGGER_TYPE_UNKNOWN;
import static com.android.internal.util.FrameworkStatsLog.PROCESS_START_TIME__TYPE__UNKNOWN;

import android.annotation.NonNull;
import android.annotation.Nullable;
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
 * The {@code mTriggerType} field describes the trigger that started this processs. This could be
 * an alarm or a push-message for a broadcast, for example. This is purely for logging and stats.
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

    public static final String TRIGGER_TYPE_UNKNOWN = "unknown";
    public static final String TRIGGER_TYPE_ALARM = "alarm";
    public static final String TRIGGER_TYPE_PUSH_MESSAGE = "push_message";
    public static final String TRIGGER_TYPE_PUSH_MESSAGE_OVER_QUOTA = "push_message_over_quota";
    public static final String TRIGGER_TYPE_JOB = "job";

    @NonNull private final String mHostingType;
    private final String mHostingName;
    private final int mHostingZygote;
    private final String mDefiningPackageName;
    private final int mDefiningUid;
    private final boolean mIsTopApp;
    private final String mDefiningProcessName;
    @Nullable private final String mAction;
    @NonNull private final String mTriggerType;

    public HostingRecord(@NonNull String hostingType) {
        this(hostingType, null /* hostingName */, REGULAR_ZYGOTE, null /* definingPackageName */,
                -1 /* mDefiningUid */, false /* isTopApp */, null /* definingProcessName */,
                null /* action */, TRIGGER_TYPE_UNKNOWN);
    }

    public HostingRecord(@NonNull String hostingType, ComponentName hostingName) {
        this(hostingType, hostingName, REGULAR_ZYGOTE);
    }

    public HostingRecord(@NonNull String hostingType, ComponentName hostingName,
            @Nullable String action, @Nullable String triggerType) {
        this(hostingType, hostingName.toShortString(), REGULAR_ZYGOTE,
                null /* definingPackageName */, -1 /* mDefiningUid */, false /* isTopApp */,
                null /* definingProcessName */, action, triggerType);
    }

    public HostingRecord(@NonNull String hostingType, ComponentName hostingName,
            String definingPackageName, int definingUid, String definingProcessName,
            String triggerType) {
        this(hostingType, hostingName.toShortString(), REGULAR_ZYGOTE, definingPackageName,
                definingUid, false /* isTopApp */, definingProcessName, null /* action */,
                triggerType);
    }

    public HostingRecord(@NonNull String hostingType, ComponentName hostingName, boolean isTopApp) {
        this(hostingType, hostingName.toShortString(), REGULAR_ZYGOTE,
                null /* definingPackageName */, -1 /* mDefiningUid */, isTopApp /* isTopApp */,
                null /* definingProcessName */, null /* action */, TRIGGER_TYPE_UNKNOWN);
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
                null /* action */, TRIGGER_TYPE_UNKNOWN);
    }

    private HostingRecord(@NonNull String hostingType, String hostingName, int hostingZygote,
            String definingPackageName, int definingUid, boolean isTopApp,
            String definingProcessName, @Nullable String action, String triggerType) {
        mHostingType = hostingType;
        mHostingName = hostingName;
        mHostingZygote = hostingZygote;
        mDefiningPackageName = definingPackageName;
        mDefiningUid = definingUid;
        mIsTopApp = isTopApp;
        mDefiningProcessName = definingProcessName;
        mAction = action;
        mTriggerType = triggerType;
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

    /** Returns the type of trigger that led to this process start. */
    public @NonNull String getTriggerType() {
        return mTriggerType;
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
                definingProcessName, null /* action */, TRIGGER_TYPE_UNKNOWN);
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
                definingProcessName, null /* action */, TRIGGER_TYPE_UNKNOWN);
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
                return PROCESS_START_TIME__HOSTING_TYPE_ID__HOSTING_TYPE_ACTIVITY;
            case HOSTING_TYPE_ADDED_APPLICATION:
                return PROCESS_START_TIME__HOSTING_TYPE_ID__HOSTING_TYPE_ADDED_APPLICATION;
            case HOSTING_TYPE_BACKUP:
                return PROCESS_START_TIME__HOSTING_TYPE_ID__HOSTING_TYPE_BACKUP;
            case HOSTING_TYPE_BROADCAST:
                return PROCESS_START_TIME__HOSTING_TYPE_ID__HOSTING_TYPE_BROADCAST;
            case HOSTING_TYPE_CONTENT_PROVIDER:
                return PROCESS_START_TIME__HOSTING_TYPE_ID__HOSTING_TYPE_CONTENT_PROVIDER;
            case HOSTING_TYPE_LINK_FAIL:
                return PROCESS_START_TIME__HOSTING_TYPE_ID__HOSTING_TYPE_LINK_FAIL;
            case HOSTING_TYPE_ON_HOLD:
                return PROCESS_START_TIME__HOSTING_TYPE_ID__HOSTING_TYPE_ON_HOLD;
            case HOSTING_TYPE_NEXT_ACTIVITY:
                return PROCESS_START_TIME__HOSTING_TYPE_ID__HOSTING_TYPE_NEXT_ACTIVITY;
            case HOSTING_TYPE_NEXT_TOP_ACTIVITY:
                return PROCESS_START_TIME__HOSTING_TYPE_ID__HOSTING_TYPE_NEXT_TOP_ACTIVITY;
            case HOSTING_TYPE_RESTART:
                return PROCESS_START_TIME__HOSTING_TYPE_ID__HOSTING_TYPE_RESTART;
            case HOSTING_TYPE_SERVICE:
                return PROCESS_START_TIME__HOSTING_TYPE_ID__HOSTING_TYPE_SERVICE;
            case HOSTING_TYPE_SYSTEM:
                return PROCESS_START_TIME__HOSTING_TYPE_ID__HOSTING_TYPE_SYSTEM;
            case HOSTING_TYPE_TOP_ACTIVITY:
                return PROCESS_START_TIME__HOSTING_TYPE_ID__HOSTING_TYPE_TOP_ACTIVITY;
            case HOSTING_TYPE_EMPTY:
                return PROCESS_START_TIME__HOSTING_TYPE_ID__HOSTING_TYPE_EMPTY;
            default:
                return PROCESS_START_TIME__TYPE__UNKNOWN;
        }
    }

    /**
     * Map the string triggerType to enum TriggerType defined in ProcessStartTime proto.
     * @param triggerType
     * @return enum TriggerType defined in ProcessStartTime proto
     */
    public static int getTriggerTypeForStatsd(@NonNull String triggerType) {
        switch(triggerType) {
            case TRIGGER_TYPE_ALARM:
                return PROCESS_START_TIME__TRIGGER_TYPE__TRIGGER_TYPE_ALARM;
            case TRIGGER_TYPE_PUSH_MESSAGE:
                return PROCESS_START_TIME__TRIGGER_TYPE__TRIGGER_TYPE_PUSH_MESSAGE;
            case TRIGGER_TYPE_PUSH_MESSAGE_OVER_QUOTA:
                return PROCESS_START_TIME__TRIGGER_TYPE__TRIGGER_TYPE_PUSH_MESSAGE_OVER_QUOTA;
            case TRIGGER_TYPE_JOB:
                return PROCESS_START_TIME__TRIGGER_TYPE__TRIGGER_TYPE_JOB;
            default:
                return PROCESS_START_TIME__TRIGGER_TYPE__TRIGGER_TYPE_UNKNOWN;
        }
    }
}
