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

import static com.android.server.companion.transport.Transport.MESSAGE_REQUEST_CONTEXT_SYNC;

import android.app.admin.DevicePolicyManager;
import android.companion.AssociationInfo;
import android.companion.ContextSyncMessage;
import android.companion.IOnMessageReceivedListener;
import android.companion.IOnTransportsChangedListener;
import android.companion.Telecom;
import android.content.Context;
import android.os.Binder;
import android.os.UserHandle;
import android.util.Slog;
import android.util.proto.ProtoInputStream;
import android.util.proto.ProtoOutputStream;
import android.util.proto.ProtoParseException;
import android.util.proto.ProtoUtils;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.companion.CompanionDeviceConfig;
import com.android.server.companion.transport.CompanionTransportManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Monitors connections and sending / receiving of synced data.
 */
public class CrossDeviceSyncController {

    private static final String TAG = "CrossDeviceSyncController";

    private static final int VERSION_1 = 1;
    private static final int CURRENT_VERSION = VERSION_1;

    private final Context mContext;
    private final CompanionTransportManager mCompanionTransportManager;
    private final List<AssociationInfo> mConnectedAssociations = new ArrayList<>();
    private final Set<Integer> mBlocklist = new HashSet<>();

    private CrossDeviceSyncControllerCallback mCrossDeviceSyncControllerCallback;

    public CrossDeviceSyncController(Context context,
            CompanionTransportManager companionTransportManager) {
        mContext = context;
        mCompanionTransportManager = companionTransportManager;
        mCompanionTransportManager.addListener(new IOnTransportsChangedListener.Stub() {
            @Override
            public void onTransportsChanged(List<AssociationInfo> newAssociations) {
                final long token = Binder.clearCallingIdentity();
                try {
                    if (!CompanionDeviceConfig.isEnabled(
                            CompanionDeviceConfig.ENABLE_CONTEXT_SYNC_TELECOM)) {
                        return;
                    }
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
                final List<AssociationInfo> existingAssociations = new ArrayList<>(
                        mConnectedAssociations);
                mConnectedAssociations.clear();
                mConnectedAssociations.addAll(newAssociations);

                if (mCrossDeviceSyncControllerCallback == null) {
                    Slog.w(TAG, "No callback to report transports changed");
                    return;
                }
                for (AssociationInfo associationInfo : newAssociations) {
                    if (!existingAssociations.contains(associationInfo)
                            && !isAssociationBlocked(associationInfo.getId())) {
                        mCrossDeviceSyncControllerCallback.updateNumberOfActiveSyncAssociations(
                                associationInfo.getUserId(), /* added= */ true);
                        mCrossDeviceSyncControllerCallback.requestCrossDeviceSync(associationInfo);
                    }
                }
                for (AssociationInfo associationInfo : existingAssociations) {
                    if (!newAssociations.contains(associationInfo)) {
                        if (isAssociationBlocked(associationInfo.getId())) {
                            mBlocklist.remove(associationInfo.getId());
                        } else {
                            mCrossDeviceSyncControllerCallback.updateNumberOfActiveSyncAssociations(
                                    associationInfo.getUserId(), /* added= */ false);
                        }
                    }
                }
            }
        });
        mCompanionTransportManager.addListener(MESSAGE_REQUEST_CONTEXT_SYNC,
                new IOnMessageReceivedListener.Stub() {
                    @Override
                    public void onMessageReceived(int associationId, byte[] data) {
                        if (mCrossDeviceSyncControllerCallback == null) {
                            Slog.w(TAG, "No callback to process context sync message");
                            return;
                        }
                        mCrossDeviceSyncControllerCallback.processContextSyncMessage(associationId,
                                processTelecomDataFromSync(data));
                    }
                });
    }

    private boolean isAssociationBlocked(int associationId) {
        return mBlocklist.contains(associationId);
    }

    /** Registers the call metadata callback. */
    public void registerCallMetadataSyncCallback(CrossDeviceSyncControllerCallback callback) {
        mCrossDeviceSyncControllerCallback = callback;
        for (AssociationInfo associationInfo : mConnectedAssociations) {
            if (!isAssociationBlocked(associationInfo.getId())) {
                mCrossDeviceSyncControllerCallback.updateNumberOfActiveSyncAssociations(
                        associationInfo.getUserId(), /* added= */ true);
                mCrossDeviceSyncControllerCallback.requestCrossDeviceSync(associationInfo);
            }
        }
    }

    /** Allow specific associated devices to enable / disable syncing. */
    public void setSyncEnabled(AssociationInfo associationInfo, boolean enabled) {
        if (enabled) {
            if (isAssociationBlocked(associationInfo.getId())) {
                mBlocklist.remove(associationInfo.getId());
                mCrossDeviceSyncControllerCallback.updateNumberOfActiveSyncAssociations(
                        associationInfo.getUserId(), /* added= */ true);
                mCrossDeviceSyncControllerCallback.requestCrossDeviceSync(associationInfo);
            }
        } else {
            if (!isAssociationBlocked(associationInfo.getId())) {
                mBlocklist.add(associationInfo.getId());
                mCrossDeviceSyncControllerCallback.updateNumberOfActiveSyncAssociations(
                        associationInfo.getUserId(), /* added= */ false);
                // Send empty message to device to clear its data (otherwise it will get stale)
                syncMessageToDevice(associationInfo.getId(), createEmptyMessage());
            }
        }
    }

    private boolean isAdminBlocked(int userId) {
        return mContext.getSystemService(DevicePolicyManager.class)
                .getBluetoothContactSharingDisabled(UserHandle.of(userId));
    }

    /**
     * Sync data to associated devices.
     *
     * @param userId The user whose data should be synced.
     * @param calls The full list of current calls for all users.
     */
    public void syncToAllDevicesForUserId(int userId, Collection<CrossDeviceCall> calls) {
        final Set<Integer> associationIds = new HashSet<>();
        for (AssociationInfo associationInfo : mConnectedAssociations) {
            if (associationInfo.getUserId() == userId && !isAssociationBlocked(
                    associationInfo.getId())) {
                associationIds.add(associationInfo.getId());
            }
        }
        if (associationIds.isEmpty()) {
            Slog.w(TAG, "No eligible devices to sync to");
            return;
        }

        mCompanionTransportManager.sendMessage(MESSAGE_REQUEST_CONTEXT_SYNC,
                createCallUpdateMessage(calls, userId),
                associationIds.stream().mapToInt(Integer::intValue).toArray());
    }

    /**
     * Sync data to associated devices.
     *
     * @param associationInfo The association whose data should be synced.
     * @param calls           The full list of current calls for all users.
     */
    public void syncToSingleDevice(AssociationInfo associationInfo,
            Collection<CrossDeviceCall> calls) {
        if (isAssociationBlocked(associationInfo.getId())) {
            Slog.e(TAG, "Cannot sync to requested device; connection is blocked");
            return;
        }

        mCompanionTransportManager.sendMessage(MESSAGE_REQUEST_CONTEXT_SYNC,
                createCallUpdateMessage(calls, associationInfo.getUserId()),
                new int[]{associationInfo.getId()});
    }

    /**
     * Sync data to associated devices.
     *
     * @param associationId   The association whose data should be synced.
     * @param message         The message to sync.
     */
    public void syncMessageToDevice(int associationId, byte[] message) {
        if (isAssociationBlocked(associationId)) {
            Slog.e(TAG, "Cannot sync to requested device; connection is blocked");
            return;
        }

        mCompanionTransportManager.sendMessage(MESSAGE_REQUEST_CONTEXT_SYNC, message,
                new int[]{associationId});
    }

    @VisibleForTesting
    CallMetadataSyncData processTelecomDataFromSync(byte[] data) {
        final CallMetadataSyncData callMetadataSyncData = new CallMetadataSyncData();
        final ProtoInputStream pis = new ProtoInputStream(data);
        try {
            int version = -1;
            while (pis.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
                switch (pis.getFieldNumber()) {
                    case (int) ContextSyncMessage.VERSION:
                        version = pis.readInt(ContextSyncMessage.VERSION);
                        Slog.e(TAG, "Processing context sync message version " + version);
                        break;
                    case (int) ContextSyncMessage.TELECOM:
                        if (version == VERSION_1) {
                            final long telecomToken = pis.start(ContextSyncMessage.TELECOM);
                            while (pis.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
                                if (pis.getFieldNumber() == (int) Telecom.CALLS) {
                                    final long callsToken = pis.start(Telecom.CALLS);
                                    callMetadataSyncData.addCall(processCallDataFromSync(pis));
                                    pis.end(callsToken);
                                } else if (pis.getFieldNumber() == (int) Telecom.REQUESTS) {
                                    final long requestsToken = pis.start(Telecom.REQUESTS);
                                    callMetadataSyncData.addRequest(processCallDataFromSync(pis));
                                    pis.end(requestsToken);
                                } else {
                                    Slog.e(TAG, "Unhandled field in Telecom:"
                                            + ProtoUtils.currentFieldToString(pis));
                                }
                            }
                            pis.end(telecomToken);
                        } else {
                            Slog.e(TAG, "Cannot process unsupported version " + version);
                        }
                        break;
                    default:
                        Slog.e(TAG, "Unhandled field in ContextSyncMessage:"
                                + ProtoUtils.currentFieldToString(pis));
                }
            }
        } catch (IOException | ProtoParseException e) {
            throw new RuntimeException(e);
        }
        return callMetadataSyncData;
    }

    @VisibleForTesting
    CallMetadataSyncData.Call processCallDataFromSync(ProtoInputStream pis) throws IOException {
        final CallMetadataSyncData.Call call = new CallMetadataSyncData.Call();
        while (pis.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            switch (pis.getFieldNumber()) {
                case (int) Telecom.Call.ID:
                    call.setId(pis.readString(Telecom.Call.ID));
                    break;
                case (int) Telecom.Call.ORIGIN:
                    final long originToken = pis.start(Telecom.Call.ORIGIN);
                    while (pis.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
                        switch (pis.getFieldNumber()) {
                            case (int) Telecom.Call.Origin.APP_ICON:
                                call.setAppIcon(pis.readBytes(Telecom.Call.Origin.APP_ICON));
                                break;
                            case (int) Telecom.Call.Origin.APP_NAME:
                                call.setAppName(pis.readString(Telecom.Call.Origin.APP_NAME));
                                break;
                            case (int) Telecom.Call.Origin.CALLER_ID:
                                call.setCallerId(pis.readString(Telecom.Call.Origin.CALLER_ID));
                                break;
                            case (int) Telecom.Call.Origin.APP_IDENTIFIER:
                                call.setAppIdentifier(
                                        pis.readString(Telecom.Call.Origin.APP_IDENTIFIER));
                                break;
                            default:
                                Slog.e(TAG, "Unhandled field in Origin:"
                                        + ProtoUtils.currentFieldToString(pis));
                        }
                    }
                    pis.end(originToken);
                    break;
                case (int) Telecom.Call.STATUS:
                    call.setStatus(pis.readInt(Telecom.Call.STATUS));
                    break;
                case (int) Telecom.Call.CONTROLS:
                    call.addControl(pis.readInt(Telecom.Call.CONTROLS));
                    break;
                default:
                    Slog.e(TAG,
                            "Unhandled field in Telecom:" + ProtoUtils.currentFieldToString(pis));
            }
        }
        return call;
    }

    @VisibleForTesting
    byte[] createCallUpdateMessage(Collection<CrossDeviceCall> calls, int userId) {
        final ProtoOutputStream pos = new ProtoOutputStream();
        pos.write(ContextSyncMessage.VERSION, CURRENT_VERSION);
        final long telecomToken = pos.start(ContextSyncMessage.TELECOM);
        for (CrossDeviceCall call : calls) {
            final long callsToken = pos.start(Telecom.CALLS);
            pos.write(Telecom.Call.ID, call.getId());
            final long originToken = pos.start(Telecom.Call.ORIGIN);
            pos.write(Telecom.Call.Origin.CALLER_ID,
                    call.getReadableCallerId(isAdminBlocked(userId)));
            pos.write(Telecom.Call.Origin.APP_ICON, call.getCallingAppIcon());
            pos.write(Telecom.Call.Origin.APP_NAME, call.getCallingAppName());
            pos.write(Telecom.Call.Origin.APP_IDENTIFIER, call.getCallingAppPackageName());
            pos.end(originToken);
            pos.write(Telecom.Call.STATUS, call.getStatus());
            for (int control : call.getControls()) {
                pos.write(Telecom.Call.CONTROLS, control);
            }
            pos.end(callsToken);
        }
        pos.end(telecomToken);
        return pos.getBytes();
    }

    /** Create a call control message. */
    public static byte[] createCallControlMessage(String callId, int control) {
        final ProtoOutputStream pos = new ProtoOutputStream();
        pos.write(ContextSyncMessage.VERSION, CURRENT_VERSION);
        final long telecomToken = pos.start(ContextSyncMessage.TELECOM);
        final long requestsToken = pos.start(Telecom.REQUESTS);
        pos.write(Telecom.Call.ID, callId);
        pos.write(Telecom.Call.CONTROLS, control);
        pos.end(requestsToken);
        pos.end(telecomToken);
        return pos.getBytes();
    }

    /** Create an empty context sync message, used to clear state. */
    public static byte[] createEmptyMessage() {
        final ProtoOutputStream pos = new ProtoOutputStream();
        pos.write(ContextSyncMessage.VERSION, CURRENT_VERSION);
        return pos.getBytes();
    }
}
