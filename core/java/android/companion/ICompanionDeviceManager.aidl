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
import android.companion.AssociationInfo;
import android.companion.AssociationRequest;
import android.content.ComponentName;

/**
 * Interface for communication with the core companion device manager service.
 *
 * @hide
 */
interface ICompanionDeviceManager {
    void associate(in AssociationRequest request, in IAssociationRequestCallback callback,
        in String callingPackage, int userId);

    List<AssociationInfo> getAssociations(String callingPackage, int userId);
    List<AssociationInfo> getAllAssociationsForUser(int userId);

    /** @deprecated */
    void legacyDisassociate(String deviceMacAddress, String callingPackage, int userId);

    void disassociate(int associationId);

    /** @deprecated */
    boolean hasNotificationAccess(in ComponentName component);

    PendingIntent requestNotificationAccess(in ComponentName component, int userId);

    /** @deprecated */
    boolean isDeviceAssociatedForWifiConnection(in String packageName, in String macAddress,
        int userId);

    void registerDevicePresenceListenerService(in String deviceAddress, in String callingPackage,
        int userId);

    void unregisterDevicePresenceListenerService(in String deviceAddress, in String callingPackage,
        int userId);

    /** @deprecated */
    boolean canPairWithoutPrompt(in String packageName, in String deviceMacAddress, int userId);

    /** @deprecated */
    void createAssociation(in String packageName, in String macAddress, int userId,
        in byte[] certificate);

    void dispatchMessage(in int messageId, in int associationId, in byte[] message);

    void addOnAssociationsChangedListener(IOnAssociationsChangedListener listener, int userId);

    void removeOnAssociationsChangedListener(IOnAssociationsChangedListener listener, int userId);

    void notifyDeviceAppeared(int associationId);

    void notifyDeviceDisappeared(int associationId);
}
