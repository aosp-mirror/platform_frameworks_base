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

import android.companion.ContextSyncMessage;
import android.os.Bundle;

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

    public static class CallFacilitator {
        private String mName;
        private String mIdentifier;
        private String mExtendedIdentifier;
        private boolean mIsTel;

        CallFacilitator() {}

        CallFacilitator(String name, String identifier, String extendedIdentifier) {
            mName = name;
            mIdentifier = identifier;
            mExtendedIdentifier = extendedIdentifier;
        }

        public String getName() {
            return mName;
        }

        public String getIdentifier() {
            return mIdentifier;
        }

        public String getExtendedIdentifier() {
            return mExtendedIdentifier;
        }

        public boolean isTel() {
            return mIsTel;
        }

        public void setName(String name) {
            mName = name;
        }

        public void setIdentifier(String identifier) {
            mIdentifier = identifier;
        }

        public void setExtendedIdentifier(String extendedIdentifier) {
            mExtendedIdentifier = extendedIdentifier;
        }

        public void setIsTel(boolean isTel) {
            mIsTel = isTel;
        }
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

    public static class Call {

        private static final String EXTRA_CALLER_ID =
                "com.android.server.companion.datatransfer.contextsync.extra.CALLER_ID";
        private static final String EXTRA_APP_ICON =
                "com.android.server.companion.datatransfer.contextsync.extra.APP_ICON";
        private static final String EXTRA_FACILITATOR_NAME =
                "com.android.server.companion.datatransfer.contextsync.extra.FACILITATOR_NAME";
        private static final String EXTRA_FACILITATOR_ID =
                "com.android.server.companion.datatransfer.contextsync.extra.FACILITATOR_ID";
        private static final String EXTRA_FACILITATOR_EXT_ID =
                "com.android.server.companion.datatransfer.contextsync.extra.FACILITATOR_EXT_ID";
        private static final String EXTRA_STATUS =
                "com.android.server.companion.datatransfer.contextsync.extra.STATUS";
        private static final String EXTRA_DIRECTION =
                "com.android.server.companion.datatransfer.contextsync.extra.DIRECTION";
        private static final String EXTRA_CONTROLS =
                "com.android.server.companion.datatransfer.contextsync.extra.CONTROLS";
        private String mId;
        private String mCallerId;
        private byte[] mAppIcon;
        private CallFacilitator mFacilitator;
        private int mStatus;
        private int mDirection;
        private final Set<Integer> mControls = new HashSet<>();

        public static Call fromBundle(Bundle bundle) {
            final Call call = new Call();
            if (bundle != null) {
                call.setId(bundle.getString(CrossDeviceSyncController.EXTRA_CALL_ID));
                call.setCallerId(bundle.getString(EXTRA_CALLER_ID));
                call.setAppIcon(bundle.getByteArray(EXTRA_APP_ICON));
                final String facilitatorName = bundle.getString(EXTRA_FACILITATOR_NAME);
                final String facilitatorIdentifier = bundle.getString(EXTRA_FACILITATOR_ID);
                final String facilitatorExtendedIdentifier =
                        bundle.getString(EXTRA_FACILITATOR_EXT_ID);
                call.setFacilitator(new CallFacilitator(facilitatorName, facilitatorIdentifier,
                        facilitatorExtendedIdentifier));
                call.setStatus(bundle.getInt(EXTRA_STATUS));
                call.setDirection(bundle.getInt(EXTRA_DIRECTION));
                call.setControls(new HashSet<>(bundle.getIntegerArrayList(EXTRA_CONTROLS)));
            }
            return call;
        }

        public Bundle writeToBundle() {
            final Bundle bundle = new Bundle();
            bundle.putString(CrossDeviceSyncController.EXTRA_CALL_ID, mId);
            bundle.putString(EXTRA_CALLER_ID, mCallerId);
            bundle.putByteArray(EXTRA_APP_ICON, mAppIcon);
            bundle.putString(EXTRA_FACILITATOR_NAME, mFacilitator.getName());
            bundle.putString(EXTRA_FACILITATOR_ID, mFacilitator.getIdentifier());
            bundle.putString(EXTRA_FACILITATOR_EXT_ID, mFacilitator.getExtendedIdentifier());
            bundle.putInt(EXTRA_STATUS, mStatus);
            bundle.putInt(EXTRA_DIRECTION, mDirection);
            bundle.putIntegerArrayList(EXTRA_CONTROLS, new ArrayList<>(mControls));
            return bundle;
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

        void setStatus(int status) {
            mStatus = status;
        }

        void setDirection(int direction) {
            mDirection = direction;
        }

        void addControl(int control) {
            mControls.add(control);
        }

        void setControls(Set<Integer> controls) {
            mControls.clear();
            mControls.addAll(controls);
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

        int getStatus() {
            return mStatus;
        }

        int getDirection() {
            return mDirection;
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
    }
}
