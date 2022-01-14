/*
 * Copyright (C) 2021 The Android Open Source Project
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
 * See the License for the specific language governing per  missions and
 * limitations under the License.
 */

package android.companion;

import android.companion.AssociationInfo;

/** @hide */
interface IOnAssociationsChangedListener {

    /*
     * IMPORTANT: This method is intentionally NOT "oneway".
     *
     * The method is intentionally "blocking" to make sure that the clients of the
     * addOnAssociationsChangedListener() API (@SystemAPI guarded by a "signature" permission) are
     * able to prevent race conditions that may arise if their own clients (applications)
     * effectively get notified about the changes before system services do.
     *
     * This is safe for 2 reasons:
     *  1. The addOnAssociationsChangedListener() is only available to the system components
     *     (guarded by a "signature" permission).
     *     See android.permission.MANAGE_COMPANION_DEVICES.
     *  2. On the Java side addOnAssociationsChangedListener() in CDM takes an Executor, and the
     *     proxy implementation of onAssociationsChanged() simply "post" a Runnable to it.
     *     See CompanionDeviceManager.OnAssociationsChangedListenerProxy class.
     */
    void onAssociationsChanged(in List<AssociationInfo> associations);
}