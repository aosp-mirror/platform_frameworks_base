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

package com.android.server.companion.datatransfer.contextsync;

import android.annotation.NonNull;
import android.companion.ContextSyncMessage;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** A read-only snapshot of an {@link ContextSyncMessage}. */
class CallMetadataSyncData {

    final Map<Long, CallMetadataSyncData.Call> mCalls = new HashMap<>();
    final List<CallMetadataSyncData.Call> mRequests = new ArrayList<>();

    public void addCall(CallMetadataSyncData.Call call) {
        mCalls.put(call.getId(), call);
    }

    public boolean hasCall(long id) {
        return mCalls.containsKey(id);
    }

    public Collection<CallMetadataSyncData.Call> getCalls() {
        return mCalls.values();
    }

    public void addRequest(CallMetadataSyncData.Call call) {
        mRequests.add(call);
    }

    public List<CallMetadataSyncData.Call> getRequests() {
        return mRequests;
    }

    public static class Call implements Parcelable {
        private long mId;
        private String mCallerId;
        private byte[] mAppIcon;
        private String mAppName;
        private String mAppIdentifier;
        private int mStatus;
        private final Set<Integer> mControls = new HashSet<>();

        public static Call fromParcel(Parcel parcel) {
            final Call call = new Call();
            call.setId(parcel.readLong());
            call.setCallerId(parcel.readString());
            call.setAppIcon(parcel.readBlob());
            call.setAppName(parcel.readString());
            call.setAppIdentifier(parcel.readString());
            call.setStatus(parcel.readInt());
            final int numberOfControls = parcel.readInt();
            for (int i = 0; i < numberOfControls; i++) {
                call.addControl(parcel.readInt());
            }
            return call;
        }

        @Override
        public void writeToParcel(Parcel parcel, int parcelableFlags) {
            parcel.writeLong(mId);
            parcel.writeString(mCallerId);
            parcel.writeBlob(mAppIcon);
            parcel.writeString(mAppName);
            parcel.writeString(mAppIdentifier);
            parcel.writeInt(mStatus);
            parcel.writeInt(mControls.size());
            for (int control : mControls) {
                parcel.writeInt(control);
            }
        }

        void setId(long id) {
            mId = id;
        }

        void setCallerId(String callerId) {
            mCallerId = callerId;
        }

        void setAppIcon(byte[] appIcon) {
            mAppIcon = appIcon;
        }

        void setAppName(String appName) {
            mAppName = appName;
        }

        void setAppIdentifier(String appIdentifier) {
            mAppIdentifier = appIdentifier;
        }

        void setStatus(int status) {
            mStatus = status;
        }

        void addControl(int control) {
            mControls.add(control);
        }

        long getId() {
            return mId;
        }

        String getCallerId() {
            return mCallerId;
        }

        byte[] getAppIcon() {
            return mAppIcon;
        }

        String getAppName() {
            return mAppName;
        }

        String getAppIdentifier() {
            return mAppIdentifier;
        }

        int getStatus() {
            return mStatus;
        }

        Set<Integer> getControls() {
            return mControls;
        }

        boolean hasControl(int control) {
            return mControls.contains(control);
        }

        @Override
        public boolean equals(Object other) {
            if (other instanceof CallMetadataSyncData.Call) {
                return ((Call) other).getId() == getId();
            }
            return false;
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(mId);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @NonNull public static final Parcelable.Creator<Call> CREATOR = new Parcelable.Creator<>() {

            @Override
            public Call createFromParcel(Parcel source) {
                return Call.fromParcel(source);
            }

            @Override
            public Call[] newArray(int size) {
                return new Call[size];
            }
        };
    }
}
