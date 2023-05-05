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
import android.telecom.PhoneAccountHandle;

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

    final Map<String, CallMetadataSyncData.Call> mCalls = new HashMap<>();
    final List<CallMetadataSyncData.CallCreateRequest> mCallCreateRequests = new ArrayList<>();
    final List<CallMetadataSyncData.CallControlRequest> mCallControlRequests = new ArrayList<>();
    final List<CallMetadataSyncData.CallFacilitator> mCallFacilitators = new ArrayList<>();

    public void addCall(CallMetadataSyncData.Call call) {
        mCalls.put(call.getId(), call);
    }

    public boolean hasCall(String id) {
        return mCalls.containsKey(id);
    }

    public Collection<CallMetadataSyncData.Call> getCalls() {
        return mCalls.values();
    }

    public void addCallCreateRequest(CallMetadataSyncData.CallCreateRequest request) {
        mCallCreateRequests.add(request);
    }

    public List<CallMetadataSyncData.CallCreateRequest> getCallCreateRequests() {
        return mCallCreateRequests;
    }

    public void addCallControlRequest(CallMetadataSyncData.CallControlRequest request) {
        mCallControlRequests.add(request);
    }

    public List<CallMetadataSyncData.CallControlRequest> getCallControlRequests() {
        return mCallControlRequests;
    }

    public void addFacilitator(CallFacilitator facilitator) {
        mCallFacilitators.add(facilitator);
    }

    public List<CallFacilitator> getFacilitators() {
        return mCallFacilitators;
    }

    public static class CallFacilitator implements Parcelable {
        private String mName;
        private String mIdentifier;

        CallFacilitator() {}

        CallFacilitator(String name, String identifier) {
            mName = name;
            mIdentifier = identifier;
        }

        CallFacilitator(Parcel parcel) {
            this(parcel.readString(), parcel.readString());
        }

        @Override
        public void writeToParcel(Parcel parcel, int parcelableFlags) {
            parcel.writeString(mName);
            parcel.writeString(mIdentifier);
        }

        public String getName() {
            return mName;
        }

        public String getIdentifier() {
            return mIdentifier;
        }

        public void setName(String name) {
            mName = name;
        }

        public void setIdentifier(String identifier) {
            mIdentifier = identifier;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @NonNull
        public static final Parcelable.Creator<CallFacilitator> CREATOR =
                new Parcelable.Creator<>() {

                    @Override
                    public CallFacilitator createFromParcel(Parcel source) {
                        return new CallFacilitator(source);
                    }

                    @Override
                    public CallFacilitator[] newArray(int size) {
                        return new CallFacilitator[size];
                    }
                };
    }

    public static class CallControlRequest {
        private String mId;
        private int mControl;

        public void setId(String id) {
            mId = id;
        }

        public void setControl(int control) {
            mControl = control;
        }

        public String getId() {
            return mId;
        }

        public int getControl() {
            return mControl;
        }
    }

    public static class CallCreateRequest {
        private String mId;
        private String mAddress;
        private CallFacilitator mFacilitator;

        public void setId(String id) {
            mId = id;
        }

        public void setAddress(String address) {
            mAddress = address;
        }

        public void setFacilitator(CallFacilitator facilitator) {
            mFacilitator = facilitator;
        }

        public String getId() {
            return mId;
        }

        public String getAddress() {
            return mAddress;
        }

        public CallFacilitator getFacilitator() {
            return mFacilitator;
        }
    }

    public static class Call implements Parcelable {
        private String mId;
        private String mCallerId;
        private byte[] mAppIcon;
        private CallFacilitator mFacilitator;
        private PhoneAccountHandle mPhoneAccountHandle;
        private int mStatus;
        private final Set<Integer> mControls = new HashSet<>();

        public static Call fromParcel(Parcel parcel) {
            final Call call = new Call();
            call.setId(parcel.readString());
            call.setCallerId(parcel.readString());
            call.setAppIcon(parcel.readBlob());
            call.setFacilitator(parcel.readParcelable(CallFacilitator.class.getClassLoader(),
                    CallFacilitator.class));
            call.setPhoneAccountHandle(
                    parcel.readParcelable(PhoneAccountHandle.class.getClassLoader(),
                            android.telecom.PhoneAccountHandle.class));
            call.setStatus(parcel.readInt());
            final int numberOfControls = parcel.readInt();
            for (int i = 0; i < numberOfControls; i++) {
                call.addControl(parcel.readInt());
            }
            return call;
        }

        @Override
        public void writeToParcel(Parcel parcel, int parcelableFlags) {
            parcel.writeString(mId);
            parcel.writeString(mCallerId);
            parcel.writeBlob(mAppIcon);
            parcel.writeParcelable(mFacilitator, parcelableFlags);
            parcel.writeParcelable(mPhoneAccountHandle, parcelableFlags);
            parcel.writeInt(mStatus);
            parcel.writeInt(mControls.size());
            for (int control : mControls) {
                parcel.writeInt(control);
            }
        }

        void setId(String id) {
            mId = id;
        }

        void setCallerId(String callerId) {
            mCallerId = callerId;
        }

        void setAppIcon(byte[] appIcon) {
            mAppIcon = appIcon;
        }

        void setFacilitator(CallFacilitator facilitator) {
            mFacilitator = facilitator;
        }

        void setPhoneAccountHandle(PhoneAccountHandle phoneAccountHandle) {
            mPhoneAccountHandle = phoneAccountHandle;
        }

        void setStatus(int status) {
            mStatus = status;
        }

        void addControl(int control) {
            mControls.add(control);
        }

        String getId() {
            return mId;
        }

        String getCallerId() {
            return mCallerId;
        }

        byte[] getAppIcon() {
            return mAppIcon;
        }

        CallFacilitator getFacilitator() {
            return mFacilitator;
        }

        PhoneAccountHandle getPhoneAccountHandle() {
            return mPhoneAccountHandle;
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
                return mId != null && mId.equals(((Call) other).getId());
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
