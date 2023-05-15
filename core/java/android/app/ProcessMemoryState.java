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

package android.app;

import android.annotation.IntDef;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * State (oom score) for processes known to activity manager.
 * {@hide}
 */
public final class ProcessMemoryState implements Parcelable {
    /**
     * The type of the component this process is hosting;
     * this means not hosting any components (cached).
     */
    public static final int HOSTING_COMPONENT_TYPE_EMPTY =
            AppProtoEnums.HOSTING_COMPONENT_TYPE_EMPTY;

    /**
     * The type of the component this process is hosting;
     * this means it's a system process.
     */
    public static final int HOSTING_COMPONENT_TYPE_SYSTEM =
            AppProtoEnums.HOSTING_COMPONENT_TYPE_SYSTEM;

    /**
     * The type of the component this process is hosting;
     * this means it's a persistent process.
     */
    public static final int HOSTING_COMPONENT_TYPE_PERSISTENT =
            AppProtoEnums.HOSTING_COMPONENT_TYPE_PERSISTENT;

    /**
     * The type of the component this process is hosting;
     * this means it's hosting a backup/restore agent.
     */
    public static final int HOSTING_COMPONENT_TYPE_BACKUP =
            AppProtoEnums.HOSTING_COMPONENT_TYPE_BACKUP;

    /**
     * The type of the component this process is hosting;
     * this means it's hosting an instrumentation.
     */
    public static final int HOSTING_COMPONENT_TYPE_INSTRUMENTATION =
            AppProtoEnums.HOSTING_COMPONENT_TYPE_INSTRUMENTATION;

    /**
     * The type of the component this process is hosting;
     * this means it's hosting an activity.
     */
    public static final int HOSTING_COMPONENT_TYPE_ACTIVITY =
            AppProtoEnums.HOSTING_COMPONENT_TYPE_ACTIVITY;

    /**
     * The type of the component this process is hosting;
     * this means it's hosting a broadcast receiver.
     */
    public static final int HOSTING_COMPONENT_TYPE_BROADCAST_RECEIVER =
            AppProtoEnums.HOSTING_COMPONENT_TYPE_BROADCAST_RECEIVER;

    /**
     * The type of the component this process is hosting;
     * this means it's hosting a content provider.
     */
    public static final int HOSTING_COMPONENT_TYPE_PROVIDER =
            AppProtoEnums.HOSTING_COMPONENT_TYPE_PROVIDER;

    /**
     * The type of the component this process is hosting;
     * this means it's hosting a started service.
     */
    public static final int HOSTING_COMPONENT_TYPE_STARTED_SERVICE =
            AppProtoEnums.HOSTING_COMPONENT_TYPE_STARTED_SERVICE;

    /**
     * The type of the component this process is hosting;
     * this means it's hosting a foreground service.
     */
    public static final int HOSTING_COMPONENT_TYPE_FOREGROUND_SERVICE =
            AppProtoEnums.HOSTING_COMPONENT_TYPE_FOREGROUND_SERVICE;

    /**
     * The type of the component this process is hosting;
     * this means it's being bound via a service binding.
     */
    public static final int HOSTING_COMPONENT_TYPE_BOUND_SERVICE =
            AppProtoEnums.HOSTING_COMPONENT_TYPE_BOUND_SERVICE;

    /**
     * The type of the component this process is hosting.
     * @hide
     */
    @IntDef(flag = true, prefix = { "HOSTING_COMPONENT_TYPE_" }, value = {
            HOSTING_COMPONENT_TYPE_EMPTY,
            HOSTING_COMPONENT_TYPE_SYSTEM,
            HOSTING_COMPONENT_TYPE_PERSISTENT,
            HOSTING_COMPONENT_TYPE_BACKUP,
            HOSTING_COMPONENT_TYPE_INSTRUMENTATION,
            HOSTING_COMPONENT_TYPE_ACTIVITY,
            HOSTING_COMPONENT_TYPE_BROADCAST_RECEIVER,
            HOSTING_COMPONENT_TYPE_PROVIDER,
            HOSTING_COMPONENT_TYPE_STARTED_SERVICE,
            HOSTING_COMPONENT_TYPE_FOREGROUND_SERVICE,
            HOSTING_COMPONENT_TYPE_BOUND_SERVICE,
    })
    public @interface HostingComponentType {}

    public final int uid;
    public final int pid;
    public final String processName;
    public final int oomScore;
    public final boolean hasForegroundServices;

    /**
     * The types of the components this process is hosting at the moment this snapshot is taken.
     *
     * Its value is the combination of {@link HostingComponentType}.
     */
    public final int mHostingComponentTypes;

    /**
     * The historical types of the components this process is or was hosting since it's born.
     *
     * Its value is the combination of {@link HostingComponentType}.
     */
    public final int mHistoricalHostingComponentTypes;

    public ProcessMemoryState(int uid, int pid, String processName, int oomScore,
            boolean hasForegroundServices, int hostingComponentTypes,
            int historicalHostingComponentTypes) {
        this.uid = uid;
        this.pid = pid;
        this.processName = processName;
        this.oomScore = oomScore;
        this.hasForegroundServices = hasForegroundServices;
        this.mHostingComponentTypes = hostingComponentTypes;
        this.mHistoricalHostingComponentTypes = historicalHostingComponentTypes;
    }

    private ProcessMemoryState(Parcel in) {
        uid = in.readInt();
        pid = in.readInt();
        processName = in.readString();
        oomScore = in.readInt();
        hasForegroundServices = in.readInt() == 1;
        mHostingComponentTypes = in.readInt();
        mHistoricalHostingComponentTypes = in.readInt();
    }

    public static final @android.annotation.NonNull Creator<ProcessMemoryState> CREATOR = new Creator<ProcessMemoryState>() {
        @Override
        public ProcessMemoryState createFromParcel(Parcel in) {
            return new ProcessMemoryState(in);
        }

        @Override
        public ProcessMemoryState[] newArray(int size) {
            return new ProcessMemoryState[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int i) {
        parcel.writeInt(uid);
        parcel.writeInt(pid);
        parcel.writeString(processName);
        parcel.writeInt(oomScore);
        parcel.writeInt(hasForegroundServices ? 1 : 0);
        parcel.writeInt(mHostingComponentTypes);
        parcel.writeInt(mHistoricalHostingComponentTypes);
    }
}
