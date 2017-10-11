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
import android.companion.IFindDeviceCallback;
import android.companion.AssociationRequest;
import android.content.ComponentName;

/**
 * Interface for communication with the core companion device manager service.
 *
 * @hide
 */
interface ICompanionDeviceManager {
    void associate(in AssociationRequest request,
        in IFindDeviceCallback callback,
        in String callingPackage);
    void stopScan(in AssociationRequest request,
        in IFindDeviceCallback callback,
        in String callingPackage);

    List<String> getAssociations(String callingPackage, int userId);
    void disassociate(String deviceMacAddress, String callingPackage);

    boolean hasNotificationAccess(in ComponentName component);
    PendingIntent requestNotificationAccess(in ComponentName component);
}
