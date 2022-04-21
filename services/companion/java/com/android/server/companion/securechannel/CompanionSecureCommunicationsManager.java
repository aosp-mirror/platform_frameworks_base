/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.companion.securechannel;

import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.companion.AssociationInfo;
import android.util.Base64;
import android.util.Log;

import com.android.server.companion.AssociationStore;
import com.android.server.companion.CompanionApplicationController;

/** Secure Comms Manager */
@SuppressLint("LongLogTag")
public class CompanionSecureCommunicationsManager {
    static final String TAG = "CompanionDevice_SecureComms";
    static final boolean DEBUG = false;

    private final AssociationStore mAssociationStore;
    private final CompanionApplicationController mCompanionAppController;

    /** Constructor */
    public CompanionSecureCommunicationsManager(AssociationStore associationStore,
            CompanionApplicationController companionApplicationController) {
        mAssociationStore = associationStore;
        mCompanionAppController = companionApplicationController;
    }

    /**
     * Send a data to the associated companion device via secure channel (establishing one if
     * needed).
     * @param associationId associationId of the "recipient" companion device.
     * @param message data to be sent securely.
     */
    public void sendSecureMessage(int associationId, @NonNull byte[] message) {
        if (DEBUG) {
            Log.d(TAG, "sendSecureMessage() associationId=" + associationId + "\n"
                    + "   message (Base64)=\"" + Base64.encodeToString(message, 0) + "\"");
        }

        final AssociationInfo association = mAssociationStore.getAssociationById(associationId);
        if (association == null) {
            throw new IllegalArgumentException(
                    "Association with ID " + associationId + " does not exist");
        }
        if (DEBUG) Log.d(TAG, "  association=" + association);

        final int userId = association.getUserId();
        final String packageName = association.getPackageName();
        if (!mCompanionAppController.isCompanionApplicationBound(userId, packageName)) {
            throw new IllegalStateException("u" + userId + "\\" + packageName + " is NOT bound");
        }

        // TODO(b/202926196): implement: encrypt and pass on the companion application for
        //  transporting
        mCompanionAppController.dispatchMessage(userId, packageName, associationId, message);
    }

    /**
     * Decrypt and dispatch message received from an associated companion device.
     * @param associationId associationId of the "sender" companion device.
     * @param encryptedMessage data.
     */
    public void receiveSecureMessage(int associationId, @NonNull byte[] encryptedMessage) {
        if (DEBUG) {
            Log.d(TAG, "sendSecureMessage() associationId=" + associationId + "\n"
                    + "   message (Base64)=\"" + Base64.encodeToString(encryptedMessage, 0) + "\"");
        }

        // TODO(b/202926196): implement: decrypt and dispatch.
    }
}
