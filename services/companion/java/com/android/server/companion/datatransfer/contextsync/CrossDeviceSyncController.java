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
import android.companion.CompanionDeviceManager;
import android.companion.ContextSyncMessage;
import android.companion.IOnMessageReceivedListener;
import android.companion.IOnTransportsChangedListener;
import android.companion.Telecom;
import android.content.ComponentName;
import android.content.Context;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.UserHandle;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.util.Slog;
import android.util.proto.ProtoInputStream;
import android.util.proto.ProtoOutputStream;
import android.util.proto.ProtoParseException;
import android.util.proto.ProtoUtils;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.companion.CompanionDeviceConfig;
import com.android.server.companion.transport.CompanionTransportManager;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Monitors connections and sending / receiving of synced data.
 */
public class CrossDeviceSyncController {

    private static final String TAG = "CrossDeviceSyncController";

    public static final String EXTRA_CALL_ID =
            "com.android.companion.datatransfer.contextsync.extra.CALL_ID";
    static final String EXTRA_FACILITATOR_ICON =
            "com.android.companion.datatransfer.contextsync.extra.FACILITATOR_ICON";
    static final String EXTRA_IS_REMOTE_ORIGIN =
            "com.android.companion.datatransfer.contextsync.extra.IS_REMOTE_ORIGIN";

    static final String EXTRA_ASSOCIATION_ID =
            "com.android.server.companion.datatransfer.contextsync.extra.ASSOCIATION_ID";
    static final String EXTRA_CALL =
            "com.android.server.companion.datatransfer.contextsync.extra.CALL";
    static final String EXTRA_CALL_FACILITATOR_ID =
            "com.android.server.companion.datatransfer.contextsync.extra.CALL_FACILITATOR_ID";
    // Special facilitator id corresponding to TelecomManager#placeCall usage (with address of
    // schema tel:). All other facilitators use Intent#actionCall.
    public static final String FACILITATOR_ID_SYSTEM = "system";

    private static final int VERSION_1 = 1;
    private static final int CURRENT_VERSION = VERSION_1;

    private final Context mContext;
    private final CompanionTransportManager mCompanionTransportManager;
    private final PhoneAccountManager mPhoneAccountManager;
    private final CallManager mCallManager;
    private final List<AssociationInfo> mConnectedAssociations = new ArrayList<>();
    private final Set<Integer> mBlocklist = new HashSet<>();
    private final List<CallMetadataSyncData.CallFacilitator> mCallFacilitators = new ArrayList<>();

    private WeakReference<CrossDeviceSyncControllerCallback> mInCallServiceCallbackRef;
    private WeakReference<CrossDeviceSyncControllerCallback> mConnectionServiceCallbackRef;

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
                for (AssociationInfo associationInfo : newAssociations) {
                    if (!existingAssociations.contains(associationInfo)) {
                        // New association.
                        if (!isAssociationBlocked(associationInfo)) {
                            final CrossDeviceSyncControllerCallback callback =
                                    mInCallServiceCallbackRef != null
                                            ? mInCallServiceCallbackRef.get() : null;
                            if (callback != null) {
                                callback.updateNumberOfActiveSyncAssociations(
                                        associationInfo.getUserId(), /* added= */ true);
                                callback.requestCrossDeviceSync(associationInfo);
                            } else {
                                Slog.w(TAG, "No callback to report new transport");
                                syncMessageToDevice(associationInfo.getId(),
                                        createFacilitatorMessage());
                            }
                        } else {
                            mBlocklist.add(associationInfo.getId());
                            Slog.i(TAG, "New association was blocked from context syncing");
                        }
                    }
                }
                for (AssociationInfo associationInfo : existingAssociations) {
                    if (!newAssociations.contains(associationInfo)) {
                        // Removed association!
                        mBlocklist.remove(associationInfo.getId());
                        if (!isAssociationBlockedLocal(associationInfo.getId())) {
                            final CrossDeviceSyncControllerCallback callback =
                                    mInCallServiceCallbackRef != null
                                            ? mInCallServiceCallbackRef.get() : null;
                            if (callback != null) {
                                callback.updateNumberOfActiveSyncAssociations(
                                        associationInfo.getUserId(), /* added= */ false);
                            } else {
                                Slog.w(TAG, "No callback to report removed transport");
                            }
                        }
                        clearInProgressCalls(associationInfo.getId());
                    } else {
                        // Stable association!
                        final boolean systemBlocked = isAssociationBlocked(associationInfo);
                        if (isAssociationBlockedLocal(associationInfo.getId()) != systemBlocked) {
                            // Block state has changed.
                            final CrossDeviceSyncControllerCallback callback =
                                    mInCallServiceCallbackRef != null
                                            ? mInCallServiceCallbackRef.get() : null;
                            if (!systemBlocked) {
                                Slog.i(TAG, "Unblocking existing association for context sync");
                                mBlocklist.remove(associationInfo.getId());
                                if (callback != null) {
                                    callback.updateNumberOfActiveSyncAssociations(
                                            associationInfo.getUserId(), /* added= */ true);
                                    callback.requestCrossDeviceSync(associationInfo);
                                } else {
                                    Slog.w(TAG, "No callback to report changed transport");
                                    syncMessageToDevice(associationInfo.getId(),
                                            createFacilitatorMessage());
                                }
                            } else {
                                Slog.i(TAG, "Blocking existing association for context sync");
                                mBlocklist.add(associationInfo.getId());
                                if (callback != null) {
                                    callback.updateNumberOfActiveSyncAssociations(
                                            associationInfo.getUserId(), /* added= */ false);
                                } else {
                                    Slog.w(TAG, "No callback to report changed transport");
                                }
                                // Send empty message to device to clear its data (otherwise it
                                // will get stale)
                                syncMessageToDevice(associationInfo.getId(),
                                        createEmptyMessage());
                                clearInProgressCalls(associationInfo.getId());
                            }
                        }
                    }
                }
            }
        });
        mCompanionTransportManager.addListener(MESSAGE_REQUEST_CONTEXT_SYNC,
                new IOnMessageReceivedListener.Stub() {
                    @Override
                    public void onMessageReceived(int associationId, byte[] data) {
                        if (isAssociationBlockedLocal(associationId)) {
                            return;
                        }
                        final CallMetadataSyncData processedData = processTelecomDataFromSync(data);
                        final boolean isRequest = processedData.getCallControlRequests().size() != 0
                                || processedData.getCallCreateRequests().size() != 0;
                        if (!isRequest) {
                            mPhoneAccountManager.updateFacilitators(associationId, processedData);
                            mCallManager.updateCalls(associationId, processedData);
                        } else {
                            processCallCreateRequests(processedData);
                        }
                        if (mInCallServiceCallbackRef == null
                                && mConnectionServiceCallbackRef == null) {
                            Slog.w(TAG, "No callback to process context sync message");
                            return;
                        }
                        final CrossDeviceSyncControllerCallback inCallServiceCallback =
                                mInCallServiceCallbackRef != null ? mInCallServiceCallbackRef.get()
                                        : null;
                        if (inCallServiceCallback != null) {
                            if (isRequest) {
                                inCallServiceCallback.processContextSyncMessage(associationId,
                                        processedData);
                            }
                        } else {
                            // This is dead; get rid of it lazily
                            mInCallServiceCallbackRef = null;
                        }

                        final CrossDeviceSyncControllerCallback connectionServiceCallback =
                                mConnectionServiceCallbackRef != null
                                        ? mConnectionServiceCallbackRef.get() : null;
                        if (connectionServiceCallback != null) {
                            if (!isRequest) {
                                connectionServiceCallback.processContextSyncMessage(associationId,
                                        processedData);
                            }
                        } else {
                            // This is dead; get rid of it lazily
                            mConnectionServiceCallbackRef = null;
                        }
                    }
                });
        mPhoneAccountManager = new PhoneAccountManager(mContext);
        mCallManager = new CallManager(mContext, mPhoneAccountManager);
    }

    private void clearInProgressCalls(int associationId) {
        final Set<String> removedIds = mCallManager.clearCallIdsForAssociationId(associationId);
        final CrossDeviceSyncControllerCallback connectionServiceCallback =
                mConnectionServiceCallbackRef != null ? mConnectionServiceCallbackRef.get() : null;
        if (connectionServiceCallback != null) {
            connectionServiceCallback.cleanUpCallIds(removedIds);
        }
    }

    private static boolean isAssociationBlocked(AssociationInfo info) {
        return (info.getSystemDataSyncFlags() & CompanionDeviceManager.FLAG_CALL_METADATA)
                != CompanionDeviceManager.FLAG_CALL_METADATA;
    }

    /** Invoke set-up tasks that happen when boot is completed. */
    public void onBootCompleted() {
        if (!CompanionDeviceConfig.isEnabled(CompanionDeviceConfig.ENABLE_CONTEXT_SYNC_TELECOM)) {
            return;
        }

        mPhoneAccountManager.onBootCompleted();

        final TelecomManager telecomManager = mContext.getSystemService(TelecomManager.class);
        if (telecomManager != null && telecomManager.getCallCapablePhoneAccounts().size() != 0) {
            final PhoneAccountHandle defaultOutgoingTelAccountHandle =
                    telecomManager.getDefaultOutgoingPhoneAccount(PhoneAccount.SCHEME_TEL);
            if (defaultOutgoingTelAccountHandle != null) {
                final PhoneAccount defaultOutgoingTelAccount = telecomManager.getPhoneAccount(
                        defaultOutgoingTelAccountHandle);
                if (defaultOutgoingTelAccount != null) {
                    mCallFacilitators.add(
                            new CallMetadataSyncData.CallFacilitator(
                                    defaultOutgoingTelAccount.getLabel().toString(),
                                    FACILITATOR_ID_SYSTEM, FACILITATOR_ID_SYSTEM));
                }
            }
        }
    }

    private void processCallCreateRequests(CallMetadataSyncData callMetadataSyncData) {
        final Iterator<CallMetadataSyncData.CallCreateRequest> iterator =
                callMetadataSyncData.getCallCreateRequests().iterator();
        while (iterator.hasNext()) {
            final CallMetadataSyncData.CallCreateRequest request = iterator.next();
            if (FACILITATOR_ID_SYSTEM.equals(request.getFacilitator().getIdentifier())) {
                if (request.getAddress() != null && request.getAddress().startsWith(
                        PhoneAccount.SCHEME_TEL)) {
                    mCallManager.addSelfOwnedCallId(request.getId());
                    // Remove all the non-numbers (dashes, parens, scheme)
                    final Uri uri = Uri.fromParts(PhoneAccount.SCHEME_TEL,
                            request.getAddress().replaceAll("\\D+", ""), /* fragment= */ null);
                    final Bundle extras = new Bundle();
                    extras.putString(EXTRA_CALL_ID, request.getId());
                    final Bundle outerExtras = new Bundle();
                    outerExtras.putParcelable(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS, extras);
                    mContext.getSystemService(TelecomManager.class).placeCall(uri, outerExtras);
                }
            } else {
                Slog.e(TAG, "Non-system facilitated calls are not supported yet");
            }
            iterator.remove();
        }
    }

    /**
     * This keeps track of "previous" state to calculate deltas. Use {@link #isAssociationBlocked}
     * for all other use cases.
     */
    private boolean isAssociationBlockedLocal(int associationId) {
        return mBlocklist.contains(associationId);
    }

    /** Registers the call metadata callback. */
    public void registerCallMetadataSyncCallback(CrossDeviceSyncControllerCallback callback,
            @CrossDeviceSyncControllerCallback.Type int type) {
        if (type == CrossDeviceSyncControllerCallback.TYPE_IN_CALL_SERVICE) {
            mInCallServiceCallbackRef = new WeakReference<>(callback);
            for (AssociationInfo associationInfo : mConnectedAssociations) {
                if (!isAssociationBlocked(associationInfo)) {
                    mBlocklist.remove(associationInfo.getId());
                    callback.updateNumberOfActiveSyncAssociations(associationInfo.getUserId(),
                            /* added= */ true);
                    callback.requestCrossDeviceSync(associationInfo);
                } else {
                    mBlocklist.add(associationInfo.getId());
                }
            }
        } else if (type == CrossDeviceSyncControllerCallback.TYPE_CONNECTION_SERVICE) {
            mConnectionServiceCallbackRef = new WeakReference<>(callback);
        } else {
            Slog.e(TAG, "Cannot register callback of unknown type: " + type);
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
            if (associationInfo.getUserId() == userId && !isAssociationBlocked(associationInfo)) {
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
        if (isAssociationBlocked(associationInfo)) {
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
        if (isAssociationBlockedLocal(associationId)) {
            Slog.e(TAG, "Cannot sync to requested device; connection is blocked");
            return;
        }

        mCompanionTransportManager.sendMessage(MESSAGE_REQUEST_CONTEXT_SYNC, message,
                new int[]{associationId});
    }

    /** Sync message to all associated devices. */
    public void syncMessageToAllDevicesForUserId(int userId, byte[] message) {
        final Set<Integer> associationIds = new HashSet<>();
        for (AssociationInfo associationInfo : mConnectedAssociations) {
            if (associationInfo.getUserId() == userId && !isAssociationBlocked(associationInfo)) {
                associationIds.add(associationInfo.getId());
            }
        }
        if (associationIds.isEmpty()) {
            Slog.w(TAG, "No eligible devices to sync to");
            return;
        }

        mCompanionTransportManager.sendMessage(MESSAGE_REQUEST_CONTEXT_SYNC, message,
                associationIds.stream().mapToInt(Integer::intValue).toArray());
    }

    /**
     * Mark a call id as owned (i.e. this device owns the canonical call). Note that both sides will
     * own outgoing calls that were placed on behalf of another device.
     */
    public void addSelfOwnedCallId(String callId) {
        mCallManager.addSelfOwnedCallId(callId);
    }

    /** Unmark a call id as owned (i.e. this device no longer owns the canonical call). */
    public void removeSelfOwnedCallId(String callId) {
        if (callId != null) {
            mCallManager.removeSelfOwnedCallId(callId);
        }
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
                                    while (pis.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
                                        switch (pis.getFieldNumber()) {
                                            case (int) Telecom.Request.CREATE_ACTION:
                                                final long createActionToken = pis.start(
                                                        Telecom.Request.CREATE_ACTION);
                                                callMetadataSyncData.addCallCreateRequest(
                                                        processCallCreateRequestDataFromSync(pis));
                                                pis.end(createActionToken);
                                                break;
                                            case (int) Telecom.Request.CONTROL_ACTION:
                                                final long controlActionToken = pis.start(
                                                        Telecom.Request.CONTROL_ACTION);
                                                callMetadataSyncData.addCallControlRequest(
                                                        processCallControlRequestDataFromSync(pis));
                                                pis.end(controlActionToken);
                                                break;
                                            default:
                                                Slog.e(TAG,
                                                        "Unhandled field in Request:"
                                                                + ProtoUtils.currentFieldToString(
                                                                pis));
                                        }
                                    }
                                    pis.end(requestsToken);
                                } else if (pis.getFieldNumber() == (int) Telecom.FACILITATORS) {
                                    final long facilitatorsToken = pis.start(Telecom.FACILITATORS);
                                    final CallMetadataSyncData.CallFacilitator facilitator =
                                            processFacilitatorDataFromSync(pis);
                                    facilitator.setIsTel(true);
                                    callMetadataSyncData.addFacilitator(facilitator);
                                    pis.end(facilitatorsToken);
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

    /** Process an incoming message with a call create request. */
    public static CallMetadataSyncData.CallCreateRequest processCallCreateRequestDataFromSync(
            ProtoInputStream pis) throws IOException {
        CallMetadataSyncData.CallCreateRequest callCreateRequest =
                new CallMetadataSyncData.CallCreateRequest();
        while (pis.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            switch (pis.getFieldNumber()) {
                case (int) Telecom.Request.CreateAction.ID:
                    callCreateRequest.setId(pis.readString(Telecom.Request.CreateAction.ID));
                    break;
                case (int) Telecom.Request.CreateAction.ADDRESS:
                    callCreateRequest.setAddress(
                            pis.readString(Telecom.Request.CreateAction.ADDRESS));
                    break;
                case (int) Telecom.Request.CreateAction.FACILITATOR:
                    final long facilitatorToken = pis.start(
                            Telecom.Request.CreateAction.FACILITATOR);
                    callCreateRequest.setFacilitator(processFacilitatorDataFromSync(pis));
                    pis.end(facilitatorToken);
                    break;
                default:
                    Slog.e(TAG,
                            "Unhandled field in CreateAction:" + ProtoUtils.currentFieldToString(
                                    pis));
            }
        }
        return callCreateRequest;
    }

    /** Process an incoming message with a call control request. */
    public static CallMetadataSyncData.CallControlRequest processCallControlRequestDataFromSync(
            ProtoInputStream pis) throws IOException {
        final CallMetadataSyncData.CallControlRequest callControlRequest =
                new CallMetadataSyncData.CallControlRequest();
        while (pis.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            switch (pis.getFieldNumber()) {
                case (int) Telecom.Request.ControlAction.ID:
                    callControlRequest.setId(pis.readString(Telecom.Request.ControlAction.ID));
                    break;
                case (int) Telecom.Request.ControlAction.CONTROL:
                    callControlRequest.setControl(
                            pis.readInt(Telecom.Request.ControlAction.CONTROL));
                    break;
                default:
                    Slog.e(TAG,
                            "Unhandled field in ControlAction:" + ProtoUtils.currentFieldToString(
                                    pis));
            }
        }
        return callControlRequest;
    }

    /** Process an incoming message with facilitators. */
    public static CallMetadataSyncData.CallFacilitator processFacilitatorDataFromSync(
            ProtoInputStream pis) throws IOException {
        final CallMetadataSyncData.CallFacilitator facilitator =
                new CallMetadataSyncData.CallFacilitator();
        while (pis.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            switch (pis.getFieldNumber()) {
                case (int) Telecom.CallFacilitator.NAME:
                    facilitator.setName(pis.readString(Telecom.CallFacilitator.NAME));
                    break;
                case (int) Telecom.CallFacilitator.IDENTIFIER:
                    facilitator.setIdentifier(pis.readString(Telecom.CallFacilitator.IDENTIFIER));
                    break;
                case (int) Telecom.CallFacilitator.EXTENDED_IDENTIFIER:
                    facilitator.setExtendedIdentifier(
                            pis.readString(Telecom.CallFacilitator.EXTENDED_IDENTIFIER));
                    break;
                default:
                    Slog.e(TAG, "Unhandled field in Facilitator:"
                            + ProtoUtils.currentFieldToString(pis));
            }
        }
        return facilitator;
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
                            case (int) Telecom.Call.Origin.CALLER_ID:
                                call.setCallerId(pis.readString(Telecom.Call.Origin.CALLER_ID));
                                break;
                            case (int) Telecom.Call.Origin.FACILITATOR:
                                final long facilitatorToken = pis.start(
                                        Telecom.Call.Origin.FACILITATOR);
                                call.setFacilitator(processFacilitatorDataFromSync(pis));
                                pis.end(facilitatorToken);
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
                case (int) Telecom.Call.DIRECTION:
                    call.setDirection(pis.readInt(Telecom.Call.DIRECTION));
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
            if (call.isCallPlacedByContextSync() || mCallManager.isExternallyOwned(call.getId())) {
                // Do not sync any of "our" calls, nor external calls, as that would be duplicative.
                continue;
            }
            final long callsToken = pos.start(Telecom.CALLS);
            pos.write(Telecom.Call.ID, call.getId());
            final long originToken = pos.start(Telecom.Call.ORIGIN);
            pos.write(Telecom.Call.Origin.CALLER_ID,
                    call.getReadableCallerId(isAdminBlocked(call.getUserId())));
            pos.write(Telecom.Call.Origin.APP_ICON, call.getCallingAppIcon());
            final long facilitatorToken = pos.start(Telecom.Call.Origin.FACILITATOR);
            pos.write(Telecom.CallFacilitator.NAME, call.getCallingAppName());
            pos.write(Telecom.CallFacilitator.IDENTIFIER, call.getCallingAppPackageName());
            pos.write(Telecom.CallFacilitator.EXTENDED_IDENTIFIER,
                    call.getSerializedPhoneAccountHandle());
            pos.end(facilitatorToken);
            pos.end(originToken);
            pos.write(Telecom.Call.STATUS, call.getStatus());
            pos.write(Telecom.Call.DIRECTION, call.getDirection());
            for (int control : call.getControls()) {
                pos.write(Telecom.Call.CONTROLS, control);
            }
            pos.end(callsToken);
        }
        for (CallMetadataSyncData.CallFacilitator facilitator : mCallFacilitators) {
            final long facilitatorsToken = pos.start(Telecom.FACILITATORS);
            pos.write(Telecom.CallFacilitator.NAME, facilitator.getName());
            pos.write(Telecom.CallFacilitator.IDENTIFIER, facilitator.getIdentifier());
            pos.write(Telecom.CallFacilitator.EXTENDED_IDENTIFIER,
                    facilitator.getExtendedIdentifier());
            pos.end(facilitatorsToken);
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
        final long actionToken = pos.start(Telecom.Request.CONTROL_ACTION);
        pos.write(Telecom.Request.ControlAction.ID, callId);
        pos.write(Telecom.Request.ControlAction.CONTROL, control);
        pos.end(actionToken);
        pos.end(requestsToken);
        pos.end(telecomToken);
        return pos.getBytes();
    }

    /** Create a call creation message (used to place a call). */
    public static byte[] createCallCreateMessage(String id, String callAddress,
            String facilitatorIdentifier) {
        final ProtoOutputStream pos = new ProtoOutputStream();
        pos.write(ContextSyncMessage.VERSION, CURRENT_VERSION);
        final long telecomToken = pos.start(ContextSyncMessage.TELECOM);
        final long requestsToken = pos.start(Telecom.REQUESTS);
        final long actionToken = pos.start(Telecom.Request.CREATE_ACTION);
        pos.write(Telecom.Request.CreateAction.ID, id);
        pos.write(Telecom.Request.CreateAction.ADDRESS, callAddress);
        final long facilitatorToken = pos.start(Telecom.Request.CreateAction.FACILITATOR);
        pos.write(Telecom.CallFacilitator.IDENTIFIER, facilitatorIdentifier);
        pos.end(facilitatorToken);
        pos.end(actionToken);
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

    /** Create a facilitator-only message, used before any calls are available as a call intake. */
    private byte[] createFacilitatorMessage() {
        return createCallUpdateMessage(Collections.emptyList(), -1);
    }

    @VisibleForTesting
    static class CallManager {

        @VisibleForTesting final Set<String> mSelfOwnedCalls = new HashSet<>();
        @VisibleForTesting final Set<String> mExternallyOwnedCalls = new HashSet<>();

        @VisibleForTesting final Map<Integer, Set<String>> mCallIds = new HashMap<>();
        private final TelecomManager mTelecomManager;
        private final PhoneAccountManager mPhoneAccountManager;

        CallManager(Context context, PhoneAccountManager phoneAccountManager) {
            mTelecomManager = context.getSystemService(TelecomManager.class);
            mPhoneAccountManager = phoneAccountManager;
        }

        /** Add any new calls to Telecom. The ConnectionService will handle everything else. */
        void updateCalls(int associationId, CallMetadataSyncData data) {
            final Set<String> oldCallIds = mCallIds.getOrDefault(associationId, new HashSet<>());
            final Set<String> newCallIds = data.getCalls().stream().map(
                    CallMetadataSyncData.Call::getId).collect(Collectors.toSet());
            if (oldCallIds.equals(newCallIds)) {
                return;
            }

            for (CallMetadataSyncData.Call currentCall : data.getCalls()) {
                if (!oldCallIds.contains(currentCall.getId())
                        && currentCall.getFacilitator() != null
                        && !isSelfOwned(currentCall.getId())) {
                    mExternallyOwnedCalls.add(currentCall.getId());
                    final Bundle extras = new Bundle();
                    extras.putInt(EXTRA_ASSOCIATION_ID, associationId);
                    extras.putBoolean(EXTRA_IS_REMOTE_ORIGIN, true);
                    extras.putBundle(EXTRA_CALL, currentCall.writeToBundle());
                    extras.putString(EXTRA_CALL_ID, currentCall.getId());
                    extras.putByteArray(EXTRA_FACILITATOR_ICON, currentCall.getAppIcon());
                    final PhoneAccountHandle handle =
                            mPhoneAccountManager.getPhoneAccountHandle(
                                    associationId,
                                    currentCall.getFacilitator().getIdentifier());
                    if (currentCall.getDirection() == android.companion.Telecom.Call.INCOMING) {
                        mTelecomManager.addNewIncomingCall(handle, extras);
                    } else if (currentCall.getDirection()
                            == android.companion.Telecom.Call.OUTGOING) {
                        final Bundle wrappedExtras = new Bundle();
                        wrappedExtras.putParcelable(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS,
                                extras);
                        wrappedExtras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE,
                                handle);
                        final String address = currentCall.getCallerId();
                        if (address != null) {
                            mTelecomManager.placeCall(Uri.fromParts(PhoneAccount.SCHEME_SIP,
                                    address, /* fragment= */ null), wrappedExtras);
                        }
                    }
                }
            }
            mCallIds.put(associationId, newCallIds);
        }

        Set<String> clearCallIdsForAssociationId(int associationId) {
            return mCallIds.remove(associationId);
        }

        void addSelfOwnedCallId(String callId) {
            mSelfOwnedCalls.add(callId);
        }

        void removeSelfOwnedCallId(String callId) {
            mSelfOwnedCalls.remove(callId);
        }

        boolean isExternallyOwned(String callId) {
            return mExternallyOwnedCalls.contains(callId);
        }

        private boolean isSelfOwned(String currentCallId) {
            for (String selfOwnedCallId : mSelfOwnedCalls) {
                if (currentCallId.endsWith(selfOwnedCallId)) {
                    return true;
                }
            }
            return false;
        }
    }

    static class PhoneAccountManager {
        private final Map<PhoneAccountHandleIdentifier, PhoneAccountHandle> mPhoneAccountHandles =
                new HashMap<>();
        private final TelecomManager mTelecomManager;
        private final ComponentName mConnectionServiceComponentName;

        PhoneAccountManager(Context context) {
            mTelecomManager = context.getSystemService(TelecomManager.class);
            mConnectionServiceComponentName = new ComponentName(context,
                    CallMetadataSyncConnectionService.class);
        }

        void onBootCompleted() {
            mTelecomManager.clearPhoneAccounts();
        }

        PhoneAccountHandle getPhoneAccountHandle(int associationId, String appIdentifier) {
            return mPhoneAccountHandles.get(
                    new PhoneAccountHandleIdentifier(associationId, appIdentifier));
        }

        void updateFacilitators(int associationId, CallMetadataSyncData data) {
            final ArrayList<CallMetadataSyncData.CallFacilitator> facilitators = new ArrayList<>();
            for (CallMetadataSyncData.Call call : data.getCalls()) {
                facilitators.add(call.getFacilitator());
            }
            facilitators.addAll(data.getFacilitators());
            updateFacilitators(associationId, facilitators);
        }

        private void updateFacilitators(int associationId,
                List<CallMetadataSyncData.CallFacilitator> facilitators) {
            final Iterator<PhoneAccountHandleIdentifier> iterator =
                    mPhoneAccountHandles.keySet().iterator();
            while (iterator.hasNext()) {
                final PhoneAccountHandleIdentifier handleIdentifier = iterator.next();
                final String handleAppIdentifier = handleIdentifier.getAppIdentifier();
                final int handleAssociationId = handleIdentifier.getAssociationId();
                if (associationId == handleAssociationId && facilitators.stream().noneMatch(
                        facilitator -> handleAppIdentifier != null && handleAppIdentifier.equals(
                                facilitator.getIdentifier()))) {
                    unregisterPhoneAccount(mPhoneAccountHandles.get(handleIdentifier));
                    iterator.remove();
                }
            }

            for (CallMetadataSyncData.CallFacilitator facilitator : facilitators) {
                final PhoneAccountHandleIdentifier phoneAccountHandleIdentifier =
                        new PhoneAccountHandleIdentifier(associationId,
                                facilitator.getIdentifier());
                if (!mPhoneAccountHandles.containsKey(phoneAccountHandleIdentifier)) {
                    registerPhoneAccount(phoneAccountHandleIdentifier, facilitator.getName(),
                            facilitator.isTel());
                }
            }
        }

        /**
         * Registers a {@link android.telecom.PhoneAccount} for a given call-capable app on the
         * synced device, and records it in the local {@link #mPhoneAccountHandles} map.
         */
        private void registerPhoneAccount(PhoneAccountHandleIdentifier handleIdentifier,
                String humanReadableAppName, boolean isTel) {
            if (mPhoneAccountHandles.containsKey(handleIdentifier)) {
                // Already exists!
                return;
            }
            final PhoneAccountHandle handle = new PhoneAccountHandle(
                    mConnectionServiceComponentName,
                    UUID.randomUUID().toString());
            mPhoneAccountHandles.put(handleIdentifier, handle);
            final PhoneAccount phoneAccount = createPhoneAccount(handle, humanReadableAppName,
                    handleIdentifier.getAppIdentifier(), handleIdentifier.getAssociationId(),
                    isTel);
            mTelecomManager.registerPhoneAccount(phoneAccount);
            mTelecomManager.enablePhoneAccount(mPhoneAccountHandles.get(handleIdentifier), true);
        }

        /**
         * Unregisters a {@link android.telecom.PhoneAccount} for a given call-capable app on the
         * synced device. Does NOT remove it from the {@link #mPhoneAccountHandles} map.
         */
        private void unregisterPhoneAccount(PhoneAccountHandle phoneAccountHandle) {
            mTelecomManager.unregisterPhoneAccount(phoneAccountHandle);
        }

        @VisibleForTesting
        static PhoneAccount createPhoneAccount(PhoneAccountHandle handle,
                String humanReadableAppName,
                String appIdentifier,
                int associationId,
                boolean isTel) {
            final Bundle extras = new Bundle();
            extras.putString(EXTRA_CALL_FACILITATOR_ID, appIdentifier);
            extras.putInt(EXTRA_ASSOCIATION_ID, associationId);
            return new PhoneAccount.Builder(handle, humanReadableAppName)
                    .setExtras(extras)
                    .setSupportedUriSchemes(List.of(isTel ? PhoneAccount.SCHEME_TEL
                            : PhoneAccount.SCHEME_SIP))
                    .setCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER
                            | PhoneAccount.CAPABILITY_CONNECTION_MANAGER).build();
        }
    }

    static final class PhoneAccountHandleIdentifier {
        private final int mAssociationId;
        private final String mAppIdentifier;

        PhoneAccountHandleIdentifier(int associationId, String appIdentifier) {
            mAssociationId = associationId;
            mAppIdentifier = appIdentifier;
        }

        public int getAssociationId() {
            return mAssociationId;
        }

        public String getAppIdentifier() {
            return mAppIdentifier;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mAssociationId, mAppIdentifier);
        }

        @Override
        public boolean equals(Object other) {
            if (other instanceof PhoneAccountHandleIdentifier) {
                return ((PhoneAccountHandleIdentifier) other).getAssociationId() == mAssociationId
                        && mAppIdentifier != null
                        && mAppIdentifier.equals(
                        ((PhoneAccountHandleIdentifier) other).getAppIdentifier());
            }
            return false;
        }
    }
}
