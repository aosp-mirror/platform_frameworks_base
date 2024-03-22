/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.companion;

import android.app.PendingIntent;
import android.companion.IAssociationRequestCallback;
import android.companion.IOnAssociationsChangedListener;
import android.companion.IOnMessageReceivedListener;
import android.companion.IOnTransportsChangedListener;
import android.companion.ISystemDataTransferCallback;
import android.companion.AssociationInfo;
import android.companion.AssociationRequest;
import android.companion.ObservingDevicePresenceRequest;
import android.companion.datatransfer.PermissionSyncRequest;
import android.content.ComponentName;
import android.os.ParcelUuid;


/**
 * Interface for communication with the core companion device manager service.
 *
 * @hide
 */
interface ICompanionDeviceManager {
    void associate(in AssociationRequest request, in IAssociationRequestCallback callback,
        in String callingPackage, int userId);

    List<AssociationInfo> getAssociations(String callingPackage, int userId);

    @EnforcePermission("MANAGE_COMPANION_DEVICES")
    List<AssociationInfo> getAllAssociationsForUser(int userId);

    /** @deprecated */
    void legacyDisassociate(String deviceMacAddress, String callingPackage, int userId);

    void disassociate(int associationId);

    /** @deprecated */
    boolean hasNotificationAccess(in ComponentName component);

    PendingIntent requestNotificationAccess(in ComponentName component, int userId);

    @EnforcePermission("MANAGE_COMPANION_DEVICES")
    boolean isDeviceAssociatedForWifiConnection(in String packageName, in String macAddress,
        int userId);

    @EnforcePermission("REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE")
    void registerDevicePresenceListenerService(in String deviceAddress, in String callingPackage,
        int userId);

    @EnforcePermission("REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE")
    void unregisterDevicePresenceListenerService(in String deviceAddress, in String callingPackage,
        int userId);

    boolean canPairWithoutPrompt(in String packageName, in String deviceMacAddress, int userId);

    @EnforcePermission("ASSOCIATE_COMPANION_DEVICES")
    void createAssociation(in String packageName, in String macAddress, int userId,
        in byte[] certificate);

    @EnforcePermission("MANAGE_COMPANION_DEVICES")
    void addOnAssociationsChangedListener(IOnAssociationsChangedListener listener, int userId);

    @EnforcePermission("MANAGE_COMPANION_DEVICES")
    void removeOnAssociationsChangedListener(IOnAssociationsChangedListener listener, int userId);

    @EnforcePermission("USE_COMPANION_TRANSPORTS")
    void addOnTransportsChangedListener(IOnTransportsChangedListener listener);

    @EnforcePermission("USE_COMPANION_TRANSPORTS")
    void removeOnTransportsChangedListener(IOnTransportsChangedListener listener);

    @EnforcePermission("USE_COMPANION_TRANSPORTS")
    void sendMessage(int messageType, in byte[] data, in int[] associationIds);

    @EnforcePermission("USE_COMPANION_TRANSPORTS")
    void addOnMessageReceivedListener(int messageType, IOnMessageReceivedListener listener);

    @EnforcePermission("USE_COMPANION_TRANSPORTS")
    void removeOnMessageReceivedListener(int messageType, IOnMessageReceivedListener listener);

    void notifyDeviceAppeared(int associationId);

    void notifyDeviceDisappeared(int associationId);

    PendingIntent buildPermissionTransferUserConsentIntent(String callingPackage, int userId,
        int associationId);

    boolean isPermissionTransferUserConsented(String callingPackage, int userId, int associationId);

    void startSystemDataTransfer(String packageName, int userId, int associationId,
        in ISystemDataTransferCallback callback);

    @EnforcePermission("DELIVER_COMPANION_MESSAGES")
    void attachSystemDataTransport(String packageName, int userId, int associationId, in ParcelFileDescriptor fd);

    @EnforcePermission("DELIVER_COMPANION_MESSAGES")
    void detachSystemDataTransport(String packageName, int userId, int associationId);

    boolean isCompanionApplicationBound(String packageName, int userId);

    PendingIntent buildAssociationCancellationIntent(in String callingPackage, int userId);

    void enableSystemDataSync(int associationId, int flags);

    void disableSystemDataSync(int associationId, int flags);

    void enablePermissionsSync(int associationId);

    void disablePermissionsSync(int associationId);

    PermissionSyncRequest getPermissionSyncRequest(int associationId);

    @EnforcePermission("MANAGE_COMPANION_DEVICES")
    void enableSecureTransport(boolean enabled);

    void setAssociationTag(int associationId, String tag);

    void clearAssociationTag(int associationId);

    byte[] getBackupPayload(int userId);

    void applyRestoredPayload(in byte[] payload, int userId);

    @EnforcePermission("REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE")
    void startObservingDevicePresence(in ObservingDevicePresenceRequest request, in String packageName, int userId);

    @EnforcePermission("REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE")
    void stopObservingDevicePresence(in ObservingDevicePresenceRequest request, in String packageName, int userId);
}
