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

import android.app.admin.DevicePolicyManager;
import android.companion.AssociationInfo;
import android.companion.ContextSyncMessage;
import android.companion.Telecom;
import android.companion.Telecom.Call;
import android.content.Context;
import android.os.UserHandle;
import android.util.Pair;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Monitors connections and sending / receiving of synced data.
 */
public class CrossDeviceSyncController {

    private static final String TAG = "CrossDeviceSyncController";
    private static final int BYTE_ARRAY_SIZE = 64;

    private final Context mContext;
    private final Callback mCdmCallback;
    private final Map<Integer, List<AssociationInfo>> mUserIdToAssociationInfo = new HashMap<>();
    private final Map<Integer, Pair<InputStream, OutputStream>> mAssociationIdToStreams =
            new HashMap<>();
    private final Set<Integer> mBlocklist = new HashSet<>();

    private CallMetadataSyncCallback mInCallServiceCallMetadataSyncCallback;

    public CrossDeviceSyncController(Context context, Callback callback) {
        mContext = context;
        mCdmCallback = callback;
    }

    /** Registers the call metadata callback. */
    public void registerCallMetadataSyncCallback(CallMetadataSyncCallback callback) {
        mInCallServiceCallMetadataSyncCallback = callback;
    }

    /** Allow specific associated devices to enable / disable syncing. */
    public void setSyncEnabled(AssociationInfo associationInfo, boolean enabled) {
        if (enabled) {
            if (mBlocklist.contains(associationInfo.getId())) {
                mBlocklist.remove(associationInfo.getId());
                openChannel(associationInfo);
            }
        } else {
            if (!mBlocklist.contains(associationInfo.getId())) {
                mBlocklist.add(associationInfo.getId());
                closeChannel(associationInfo);
            }
        }
    }

    /**
     * Opens channels to newly associated devices, and closes channels to newly disassociated
     * devices.
     *
     * TODO(b/265466098): this needs to be limited to just connected devices
     */
    public void onAssociationsChanged(int userId, List<AssociationInfo> newAssociationInfoList) {
        final List<AssociationInfo> existingAssociationInfoList = mUserIdToAssociationInfo.get(
                userId);
        // Close channels to newly-disconnected devices.
        for (AssociationInfo existingAssociationInfo : existingAssociationInfoList) {
            if (!newAssociationInfoList.contains(existingAssociationInfo) && !mBlocklist.contains(
                    existingAssociationInfo.getId())) {
                closeChannel(existingAssociationInfo);
            }
        }
        // Open channels to newly-connected devices.
        for (AssociationInfo newAssociationInfo : newAssociationInfoList) {
            if (!existingAssociationInfoList.contains(newAssociationInfo) && !mBlocklist.contains(
                    newAssociationInfo.getId())) {
                openChannel(newAssociationInfo);
            }
        }
        mUserIdToAssociationInfo.put(userId, newAssociationInfoList);
    }

    private boolean isAdminBlocked(int userId) {
        return mContext.getSystemService(DevicePolicyManager.class)
                .getBluetoothContactSharingDisabled(UserHandle.of(userId));
    }

    /** Stop reading, close streams, and close secure channel. */
    private void closeChannel(AssociationInfo associationInfo) {
        // TODO(b/265466098): stop reading from secure channel
        final Pair<InputStream, OutputStream> streams = mAssociationIdToStreams.get(
                associationInfo.getId());
        if (streams != null) {
            try {
                if (streams.first != null) {
                    streams.first.close();
                }
                if (streams.second != null) {
                    streams.second.close();
                }
            } catch (IOException e) {
                Slog.e(TAG, "Could not close streams for association " + associationInfo.getId(),
                        e);
            }
        }
        mCdmCallback.closeSecureChannel(associationInfo.getId());
    }

    /** Sync initial snapshot and start reading. */
    private void openChannel(AssociationInfo associationInfo) {
        final InputStream is = new ByteArrayInputStream(new byte[BYTE_ARRAY_SIZE]);
        final OutputStream os = new ByteArrayOutputStream(BYTE_ARRAY_SIZE);
        mAssociationIdToStreams.put(associationInfo.getId(), new Pair<>(is, os));
        mCdmCallback.createSecureChannel(associationInfo.getId(), is, os);
        // TODO(b/265466098): only requestSync for this specific association / connection?
        mInCallServiceCallMetadataSyncCallback.requestCrossDeviceSync(associationInfo.getUserId());
        // TODO(b/265466098): start reading from secure channel
    }

    /**
     * Sync data to associated devices.
     *
     * @param userId The user whose data should be synced.
     * @param calls The full list of current calls for all users.
     */
    public void crossDeviceSync(int userId, Collection<CrossDeviceCall> calls) {
        final boolean isAdminBlocked = isAdminBlocked(userId);
        for (AssociationInfo associationInfo : mUserIdToAssociationInfo.get(userId)) {
            final Pair<InputStream, OutputStream> streams = mAssociationIdToStreams.get(
                    associationInfo.getId());
            final ProtoOutputStream pos = new ProtoOutputStream(streams.second);
            final long telecomToken = pos.start(ContextSyncMessage.TELECOM);
            for (CrossDeviceCall call : calls) {
                final long callsToken = pos.start(Telecom.CALLS);
                pos.write(Call.ID, call.getId());
                final long originToken = pos.start(Call.ORIGIN);
                pos.write(Call.Origin.CALLER_ID, call.getReadableCallerId(isAdminBlocked));
                pos.write(Call.Origin.APP_ICON, call.getCallingAppIcon());
                pos.write(Call.Origin.APP_NAME, call.getCallingAppName());
                pos.end(originToken);
                pos.write(Call.STATUS, call.getStatus());
                for (int control : call.getControls()) {
                    pos.write(Call.CONTROLS_AVAILABLE, control);
                }
                pos.end(callsToken);
            }
            pos.end(telecomToken);
            pos.flush();
        }
    }

    /**
     * Callback to be implemented by CompanionDeviceManagerService.
     */
    public interface Callback {
        /**
         * Create a secure channel to send messages.
         */
        void createSecureChannel(int associationId, InputStream input, OutputStream output);

        /**
         * Close the secure channel created previously.
         */
        void closeSecureChannel(int associationId);
    }
}
