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

import static android.app.ActivityManager.FOREGROUND_SERVICE_API_TYPE_AUDIO;
import static android.app.ActivityManager.FOREGROUND_SERVICE_API_TYPE_BLUETOOTH;
import static android.app.ActivityManager.FOREGROUND_SERVICE_API_TYPE_CAMERA;
import static android.app.ActivityManager.FOREGROUND_SERVICE_API_TYPE_CDM;
import static android.app.ActivityManager.FOREGROUND_SERVICE_API_TYPE_LOCATION;
import static android.app.ActivityManager.FOREGROUND_SERVICE_API_TYPE_MEDIA_PLAYBACK;
import static android.app.ActivityManager.FOREGROUND_SERVICE_API_TYPE_MICROPHONE;
import static android.app.ActivityManager.FOREGROUND_SERVICE_API_TYPE_PHONE_CALL;
import static android.app.ActivityManager.FOREGROUND_SERVICE_API_TYPE_USB;
import static android.os.Process.INVALID_UID;

import static com.android.internal.util.FrameworkStatsLog.FOREGROUND_SERVICE_STATE_CHANGED__FGS_START_API__FGSSTARTAPI_NA;

import android.annotation.IntDef;
import android.app.ActivityManager;
import android.app.ActivityManager.ForegroundServiceApiType;
import android.app.ForegroundServiceDelegationOptions;
import android.content.ComponentName;
import android.content.pm.ServiceInfo;
import android.os.Trace;
import android.util.ArrayMap;
import android.util.IntArray;
import android.util.LongArray;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FrameworkStatsLog;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;

/**
 * Responsible for handling logging of API events
 * and associating APIs with currently running FGS.
 * Also tracks FGS that are currently running.
 */
public class ForegroundServiceTypeLoggerModule {

    private static final String TAG = "ForegroundServiceTypeLoggerModule";

    public static final int FGS_STATE_CHANGED_API_CALL = 4;

    public static final int FGS_API_BEGIN_WITH_FGS = 1;
    public static final int FGS_API_END_WITH_FGS = 2;
    public static final int FGS_API_END_WITHOUT_FGS = 3;
    public static final int FGS_API_PAUSE = 4;
    public static final int FGS_API_RESUME = 5;

    @IntDef(flag = false, prefix = { "FGS_API_" }, value = {
            FGS_API_BEGIN_WITH_FGS,
            FGS_API_END_WITH_FGS,
            FGS_API_END_WITHOUT_FGS,
            FGS_API_PAUSE,
            FGS_API_RESUME,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface FgsApiState {}


    private static class UidState {
        // A stack that keeps a list of API calls by type.
        // This represents the ongoing open APIs
        // that are running on the system for each
        // app in the system. They are keyed
        // by the API type (represented as a number).
        final SparseArray<FgsApiRecord> mApiOpenCalls = new SparseArray<>();

        // This stack represents the last close call made per type
        // We only care about the last call made so we track the last close
        // call made per type. This way, once the FGS closes
        // we simply log the last API call made.
        final SparseArray<FgsApiRecord> mApiClosedCalls = new SparseArray<>();

        // Here we track how many APIs are opened before any FGS is running.
        // These counts will only be added to the open call count below if
        // an FGS is started. If an FGS is NOT started, then this count should
        // gradually hit zero as close calls are decremented.
        final SparseIntArray mOpenedWithoutFgsCount = new SparseIntArray();

        // Here we keep track of the count of in-flight calls.
        // We only want to log the first open call and the last
        // close call so that we get the largest duration
        // possible.
        final SparseIntArray mOpenWithFgsCount = new SparseIntArray();

        // A stack that keeps a list of API calls in the order
        // that they were called. This represents the ongoing
        // open APIs that are running on the system for each
        // app in the system. They are keyed by FGS Type
        // to another ordered map, keyed by the component name
        // to facilitate removing the record from the structure
        final SparseArray<ArrayMap<ComponentName, ServiceRecord>> mRunningFgs = new SparseArray<>();

        // A map of API types to last FGS stop call timestamps
        // We use this to get the duration an API was active after
        // the stop call.
        final SparseArray<Long> mLastFgsTimeStamp = new SparseArray<>();

        // A map of API types to first FGS start call timestamps
        // We use this to get the duration an API was active after
        // the stop call.
        final SparseArray<Long> mFirstFgsTimeStamp = new SparseArray<>();
    }

    // SparseArray that tracks all UIDs that have made various
    // API calls. Keyed by UID.
    private final SparseArray<UidState> mUids = new SparseArray<>();

    public ForegroundServiceTypeLoggerModule() {
    }

    /**
     * Used to log the start of a Foreground Service. The first API
     * call of the right type will also be associated and logged
     */
    public void logForegroundServiceStart(int uid, int pid, ServiceRecord record) {
        if (record.getComponentName() != null) {
            final String traceTag = record.getComponentName().flattenToString() + ":" + uid;
            Trace.asyncTraceForTrackBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, traceTag,
                    "foregroundService", record.foregroundServiceType);
        }
        // initialize the UID stack
        UidState uidState = mUids.get(uid);
        if (uidState == null) {
            uidState = new UidState();
            mUids.put(uid, uidState);
        }
        // grab the appropriate types
        final IntArray apiTypes =
                convertFgsTypeToApiTypes(record.foregroundServiceType);
        if (apiTypes.size() == 0) {
            Slog.w(TAG, "Foreground service start for UID: "
                    + uid + " does not have any types");
        }
        // now we need to iterate through the types
        // and insert the new record as needed
        final IntArray apiTypesFound = new IntArray();
        final LongArray timestampsFound = new LongArray();
        for (int i = 0, size = apiTypes.size(); i < size; i++) {
            final int apiType = apiTypes.get(i);
            int fgsIndex = uidState.mRunningFgs.indexOfKey(apiType);
            if (fgsIndex < 0) {
                uidState.mRunningFgs.put(apiType, new ArrayMap<>());
                fgsIndex = uidState.mRunningFgs.indexOfKey(apiType);
                uidState.mFirstFgsTimeStamp.put(apiType, System.currentTimeMillis());
            }
            final ArrayMap<ComponentName, ServiceRecord> fgsList =
                    uidState.mRunningFgs.valueAt(fgsIndex);
            fgsList.put(record.getComponentName(), record);
            // now we want to figure out if this FGS is associated with any currently open API

            // retrieve the last API call for the type if there is one
            if (uidState.mApiOpenCalls.contains(apiType)) {
                // update the open call count associated with an FGS
                // we want to dump the previously opened call count into the
                // opened with an FGS call count
                // then zero out the old count
                uidState.mOpenWithFgsCount
                        .put(apiType, uidState.mOpenedWithoutFgsCount.get(apiType));
                uidState.mOpenedWithoutFgsCount.put(apiType, 0);
                apiTypesFound.add(apiType);
                final FgsApiRecord call = uidState.mApiOpenCalls.get(apiType);
                timestampsFound.add(call.mTimeStart);
                // associate the call
                call.mIsAssociatedWithFgs = true;
                call.mAssociatedFgsRecord = record;

                // remove the APIs, since we've logged the API starts
                // so we don't need to log them again
                uidState.mApiOpenCalls.remove(apiType);
            }
        }
        if (apiTypesFound.size() != 0) {
            // log a state change
            for (int i = 0, size = apiTypesFound.size(); i < size; i++) {
                logFgsApiEvent(record,
                        FGS_STATE_CHANGED_API_CALL,
                        FGS_API_BEGIN_WITH_FGS,
                        apiTypesFound.get(i),
                        timestampsFound.get(i));
            }
        }
    }

    /**
     * Logs when an FGS stops. The last associated closed API event
     * will also be logged
     */
    public void logForegroundServiceStop(int uid, ServiceRecord record) {
        // we need to log all the API end events and remove the start events
        // then we remove the FGS from the various stacks
        // and also clean up the start calls stack by UID
        if (record.getComponentName() != null) {
            final String traceTag = record.getComponentName().flattenToString() + ":" + uid;
            Trace.asyncTraceForTrackEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER,
                    traceTag, record.hashCode());
        }
        final IntArray apiTypes = convertFgsTypeToApiTypes(record.foregroundServiceType);
        final UidState uidState = mUids.get(uid);
        if (apiTypes.size() == 0) {
            Slog.w(TAG, "FGS stop call for: " + uid + " has no types!");
        }
        if (uidState == null) {
            Slog.w(TAG, "FGS stop call being logged with no start call for UID for UID "
                    + uid
                    + " in package " + record.packageName);
            return;
        }
        final ArrayList<Integer> apisFound = new ArrayList<>();
        final ArrayList<Long> timestampsFound = new ArrayList<>();
        for (int i = 0, size = apiTypes.size(); i < size; i++) {
            final int apiType = apiTypes.get(i);

            // remove the FGS record from the stack
            final ArrayMap<ComponentName, ServiceRecord> runningFgsOfType =
                    uidState.mRunningFgs.get(apiType);
            if (runningFgsOfType == null) {
                Slog.w(TAG, "Could not find appropriate running FGS for FGS stop for UID " + uid
                        + " in package " + record.packageName);
                continue;
            }

            runningFgsOfType.remove(record.getComponentName());
            if (runningFgsOfType.size() == 0) {
                // there's no more FGS running for this type, just get rid of it
                uidState.mRunningFgs.remove(apiType);
                // but we need to keep track of the timestamp in case an API stops
                uidState.mLastFgsTimeStamp.put(apiType, System.currentTimeMillis());
            }

            final int apiTypeIndex = uidState.mOpenWithFgsCount.indexOfKey(apiType);
            if (apiTypeIndex < 0) {
                Slog.w(TAG, "Logger should be tracking FGS types correctly for UID " + uid
                        + " in package " + record.packageName);
                continue;
            }
            // retrieve the eligible closed call
            // we only want to log if this is the only
            // open in flight call. If there are other calls,
            // we just skip logging
            final FgsApiRecord closedApi = uidState.mApiClosedCalls.get(apiType);
            if (closedApi != null
                    && uidState.mOpenWithFgsCount.valueAt(apiTypeIndex) == 0) {
                apisFound.add(apiType);
                timestampsFound.add(closedApi.mTimeStart);
                // remove the last API close call
                uidState.mApiClosedCalls.remove(apiType);
            }
        }
        if (!apisFound.isEmpty()) {
            // time to log the call
            for (int i = 0; i < apisFound.size(); i++) {
                logFgsApiEvent(record,
                        FGS_STATE_CHANGED_API_CALL,
                        FGS_API_END_WITH_FGS, apisFound.get(i), timestampsFound.get(i));
            }
        }
    }

    /**
     * Called to log an API start call. If any associated FGS
     * is running and this is the first open API call, then
     * the event is logged.
     */
    public long logForegroundServiceApiEventBegin(@ForegroundServiceApiType int apiType,
            int uid, int pid, String packageName) {
        final FgsApiRecord callStart =
                new FgsApiRecord(uid, pid, packageName, apiType, System.currentTimeMillis());
        UidState uidState = mUids.get(uid);
        if (uidState == null) {
            uidState = new UidState();
            mUids.put(uid, uidState);
        }
        // now we want to figure out if this call is associated with any FGS
        // is there an FGS?
        if (!hasValidActiveFgs(uid, apiType)) {
            // no FGS running currently, so this API
            // started without an FGS
            // initialize the started without FGS count if it isn't already
            int openWithoutFgsCountIndex =
                    uidState.mOpenedWithoutFgsCount.indexOfKey(apiType);

            if (openWithoutFgsCountIndex < 0) {
                uidState.mOpenedWithoutFgsCount.put(apiType, 0);
                openWithoutFgsCountIndex =
                        uidState.mOpenedWithoutFgsCount.indexOfKey(apiType);
            }
            // insert this record as the first open API call
            // IF we do not have one
            if (!uidState.mApiOpenCalls.contains(apiType)
                    || uidState.mOpenedWithoutFgsCount.valueAt(openWithoutFgsCountIndex) == 0) {
                uidState.mApiOpenCalls.put(apiType, callStart);
            }
            // now update the count of the open API calls
            // started without an FGS
            uidState.mOpenedWithoutFgsCount
                    .put(apiType, uidState.mOpenedWithoutFgsCount.get(apiType) + 1);
            return callStart.mTimeStart;
        }
        // so there is an FGS running
        // that we can associate with
        // we now need to update the count
        // for open calls that started
        // with an FGS
        int openWithFgsIndex = uidState.mOpenWithFgsCount.indexOfKey(apiType);

        if (openWithFgsIndex < 0) {
            uidState.mOpenWithFgsCount.put(apiType, 0);
            openWithFgsIndex = uidState.mOpenWithFgsCount.indexOfKey(apiType);
        }
        uidState.mOpenWithFgsCount
                .put(apiType, uidState.mOpenWithFgsCount.valueAt(openWithFgsIndex) + 1);
        final ArrayMap<ComponentName, ServiceRecord> fgsListMap = uidState.mRunningFgs.get(apiType);

        // now we get the relevant FGS to log with
        final int apiTypes = apiType;
        final long timestamps = callStart.mTimeStart;
        if (uidState.mOpenWithFgsCount.valueAt(openWithFgsIndex) == 1) {
            for (ServiceRecord record : fgsListMap.values()) {
                logFgsApiEvent(record,
                        FGS_STATE_CHANGED_API_CALL,
                        FGS_API_BEGIN_WITH_FGS,
                        apiTypes,
                        timestamps);
            }
        }
        return callStart.mTimeStart;
    }

    /**
     * Called to log the end of an API call. If this
     * is the last API close call, it will be logged
     * as an event.
     */
    public long logForegroundServiceApiEventEnd(@ForegroundServiceApiType int apiType,
            int uid, int pid) {
        // are there even FGS that we want to associate with?
        // if there's even an entry in the open call count,
        // then we should care, otherwise we assume
        // it's not related to any FGS
        UidState uidState = mUids.get(uid);
        if (uidState == null) {
            Slog.w(TAG, "API event end called before start!");
            return -1;
        }
        final int apiIndex = uidState.mOpenWithFgsCount.indexOfKey(apiType);
        if (apiIndex >= 0) {
            // are there any calls that started with an FGS?
            if (uidState.mOpenWithFgsCount.get(apiType) != 0) {
                // we should decrement the count, since we only
                // want to log the last close call
                uidState.mOpenWithFgsCount.put(apiType,
                        uidState.mOpenWithFgsCount.get(apiType) - 1);
            }
            // is there no FGS running and this is the last close call?
            if (!hasValidActiveFgs(uid, apiType)
                    && uidState.mOpenWithFgsCount.get(apiType) == 0) {
                // we just log that an event happened w/ no
                // FGS associated. This is to avoid dangling
                // events
                final long timestamp = System.currentTimeMillis();
                final int apiTypes = apiType;
                logFgsApiEventWithNoFgs(uid, FGS_API_END_WITHOUT_FGS, apiTypes, timestamp);
                // we should now remove the count, so as to signal that
                // there was never an FGS called that can be associated
                uidState.mOpenWithFgsCount.removeAt(apiIndex);
                return timestamp;
            }
        }
        // we know now that this call is not coming from an
        // open FGS associated API call. So it is likely
        // a part of an unassociated call that has now been
        // closed. So we decrement that count
        if (uidState.mOpenedWithoutFgsCount.indexOfKey(apiType) < 0) {
            // initialize if we don't contain
            uidState.mOpenedWithoutFgsCount.put(apiType, 0);
        }
        int apiOpenWithoutFgsCount = uidState.mOpenedWithoutFgsCount.get(apiType);
        if (apiOpenWithoutFgsCount != 0) {
            apiOpenWithoutFgsCount -= 1;
            if (apiOpenWithoutFgsCount == 0) {
                uidState.mApiOpenCalls.remove(apiType);
            }
            uidState.mOpenedWithoutFgsCount
                    .put(apiType, apiOpenWithoutFgsCount);
            return System.currentTimeMillis();
        }
        // This is a part of a valid active FGS
        // we should definitely update the pointer to the
        // last closed API call
        final SparseArray<FgsApiRecord> callsByUid = uidState.mApiClosedCalls;
        final FgsApiRecord closedCall =
                new FgsApiRecord(uid, pid, "", apiType, System.currentTimeMillis());
        uidState.mApiClosedCalls.put(apiType, closedCall);
        return closedCall.mTimeStart;
    }

    /**
     * Log an API state change. This is only to be used by media playback
     */
    public void logForegroundServiceApiStateChanged(@ForegroundServiceApiType int apiType,
            int uid, int pid, int state) {
        UidState uidState = mUids.get(uid);
        if (!uidState.mRunningFgs.contains(apiType)) {
            // if there is no FGS running for this type that could mean that
            // either the FGS was stopped a while ago or
            // that the API call was never run with an FGS
            return;
        }
        final ArrayMap<ComponentName, ServiceRecord> fgsRecords = uidState.mRunningFgs.get(apiType);
        final int apiTypes = apiType;
        final long timestamp = System.currentTimeMillis();
        for (ServiceRecord record : fgsRecords.values()) {
            logFgsApiEvent(record,
                    FGS_STATE_CHANGED_API_CALL,
                    state,
                    apiTypes,
                    timestamp);
        }
    }

    private IntArray convertFgsTypeToApiTypes(int fgsType) {
        final IntArray types = new IntArray();
        if ((fgsType & ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
                == ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA) {
            types.add(FOREGROUND_SERVICE_API_TYPE_CAMERA);
        }
        if ((fgsType & ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
                == ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) {
            types.add(FOREGROUND_SERVICE_API_TYPE_BLUETOOTH);
            types.add(FOREGROUND_SERVICE_API_TYPE_USB);
            types.add(FOREGROUND_SERVICE_API_TYPE_CDM);
        }
        if ((fgsType & ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
                == ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) {
            types.add(FOREGROUND_SERVICE_API_TYPE_LOCATION);
        }
        if ((fgsType & ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                == ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) {
            types.add(FOREGROUND_SERVICE_API_TYPE_AUDIO);
            types.add(FOREGROUND_SERVICE_API_TYPE_MEDIA_PLAYBACK);
        }
        if ((fgsType & ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                == ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) {
            types.add(FOREGROUND_SERVICE_API_TYPE_MICROPHONE);
        }
        if ((fgsType & ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
                == ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL) {
            types.add(FOREGROUND_SERVICE_API_TYPE_PHONE_CALL);
        }
        return types;
    }

    private boolean hasValidActiveFgs(int uid, @ForegroundServiceApiType int apiType) {
        UidState uidState = mUids.get(uid);
        if (uidState != null) {
            return uidState.mRunningFgs.contains(apiType);
        }
        return false;
    }

    /**
     * Logs an API event that occurred while an FGS was running
     */
    @VisibleForTesting
    public void logFgsApiEvent(ServiceRecord r, int fgsState,
            @FgsApiState int apiState,
            @ForegroundServiceApiType int apiType, long timestamp) {
        long apiDurationBeforeFgsStart = 0;
        long apiDurationAfterFgsEnd = 0;
        UidState uidState = mUids.get(r.appInfo.uid);
        if (uidState == null) {
            return;
        }
        if (uidState.mFirstFgsTimeStamp.contains(apiType)) {
            apiDurationBeforeFgsStart = uidState.mFirstFgsTimeStamp.get(apiType) - timestamp;
        }
        if (uidState.mLastFgsTimeStamp.contains(apiType)) {
            apiDurationAfterFgsEnd = timestamp - uidState.mLastFgsTimeStamp.get(apiType);
        }
        final int[] apiTypes = new int[1];
        apiTypes[0] = apiType;
        final long[] timeStamps = new long[1];
        timeStamps[0] = timestamp;
        FrameworkStatsLog.write(FrameworkStatsLog.FOREGROUND_SERVICE_STATE_CHANGED,
                r.appInfo.uid,
                r.shortInstanceName,
                fgsState, // FGS State
                r.isFgsAllowedWIU(), // allowWhileInUsePermissionInFgs
                r.getFgsAllowStart(), // fgsStartReasonCode
                r.appInfo.targetSdkVersion,
                r.mRecentCallingUid,
                0, // callerTargetSdkVersion
                r.mInfoTempFgsAllowListReason != null
                        ? r.mInfoTempFgsAllowListReason.mCallingUid : INVALID_UID,
                r.mFgsNotificationWasDeferred,
                r.mFgsNotificationShown,
                0, // durationMs
                r.mStartForegroundCount,
                ActivityManagerUtils.hashComponentNameForAtom(r.shortInstanceName),
                r.mFgsHasNotificationPermission,
                r.foregroundServiceType,
                0,
                r.mIsFgsDelegate,
                r.mFgsDelegation != null ? r.mFgsDelegation.mOptions.mClientUid : INVALID_UID,
                r.mFgsDelegation != null ? r.mFgsDelegation.mOptions.mDelegationService
                        : ForegroundServiceDelegationOptions.DELEGATION_SERVICE_DEFAULT,
                apiState,
                apiTypes,
                timeStamps,
                ActivityManager.PROCESS_STATE_UNKNOWN,
                ActivityManager.PROCESS_CAPABILITY_NONE,
                ActivityManager.PROCESS_STATE_UNKNOWN,
                ActivityManager.PROCESS_CAPABILITY_NONE,
                apiDurationBeforeFgsStart,
                apiDurationAfterFgsEnd,
                r.mAllowWhileInUsePermissionInFgsReasonNoBinding,
                r.mAllowWIUInBindService,
                r.mAllowWIUByBindings,
                r.mAllowStartForegroundNoBinding,
                r.mAllowStartInBindService,
                r.mAllowStartByBindings,
                FOREGROUND_SERVICE_STATE_CHANGED__FGS_START_API__FGSSTARTAPI_NA,
                false
        );
    }

    /**
     * Logs an API event that occurred while no FGS was running.
     * Only used to log API exit events
     */
    @VisibleForTesting
    public void logFgsApiEventWithNoFgs(int uid,
            @FgsApiState int apiState,
            @ForegroundServiceApiType int apiType, long timestamp) {
        long apiDurationAfterFgsEnd = 0;
        UidState uidState = mUids.get(uid);
        if (uidState == null) {
            return;
        }
        if (uidState.mLastFgsTimeStamp.contains(apiType)) {
            apiDurationAfterFgsEnd = timestamp - uidState.mLastFgsTimeStamp.get(apiType);
        }
        final int[] apiTypes = new int[1];
        apiTypes[0] = apiType;
        final long[] timeStamps = new long[1];
        timeStamps[0] = timestamp;
        FrameworkStatsLog.write(FrameworkStatsLog.FOREGROUND_SERVICE_STATE_CHANGED,
                uid,
                null,
                FGS_STATE_CHANGED_API_CALL,
                false, // allowWhileInUsePermissionInFgs
                0, // fgsStartReasonCode
                0,
                uid,
                0, // callerTargetSdkVersion
                0,
                false,
                false,
                0, // durationMs
                0,
                0,
                false,
                0,
                0,
                false,
                0,
                0,
                apiState,
                apiTypes,
                timeStamps,
                ActivityManager.PROCESS_STATE_UNKNOWN,
                ActivityManager.PROCESS_CAPABILITY_NONE,
                ActivityManager.PROCESS_STATE_UNKNOWN,
                ActivityManager.PROCESS_CAPABILITY_NONE,
                0,
                apiDurationAfterFgsEnd,
                0,
                0,
                0,
                0,
                0,
                0,
                FOREGROUND_SERVICE_STATE_CHANGED__FGS_START_API__FGSSTARTAPI_NA,
                false
        );
    }

    /**
     * Internal class for tracking open API calls
     */
    private static class FgsApiRecord {
        final int mUid; // the UID from where the API call came from
        final int mPid; // the PID from where the API call came from
        final String mPackageName; // the package name from where the API call came from
        @ForegroundServiceApiType
        int mType; // the type of API call (camera, etc)
        boolean mIsAssociatedWithFgs; // is it associated with an FGS?
        ServiceRecord mAssociatedFgsRecord; // the FGS it is associated with
        final long mTimeStart; // timestamp for the event

        FgsApiRecord(int uid,
                int pid,
                String packageName,
                @ForegroundServiceApiType int type,
                long timeStart) {
            this.mUid = uid;
            this.mPid = pid;
            this.mPackageName = packageName;
            this.mType = type;
            this.mTimeStart = timeStart;
        }
    }
}
