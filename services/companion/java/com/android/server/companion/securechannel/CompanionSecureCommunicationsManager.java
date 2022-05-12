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
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.companion.AssociationInfo;
import android.util.Base64;
import android.util.Log;
import android.util.Slog;

import com.android.server.companion.AssociationStore;
import com.android.server.companion.CompanionApplicationController;

/** Secure Comms Manager */
@SuppressLint("LongLogTag")
public class CompanionSecureCommunicationsManager {
    static final String TAG = "CompanionDevice_SecureComms";
    static final boolean DEBUG = false;

    /** Listener for incoming decrypted messages. */
    public interface Listener {
        /** When an incoming message is decrypted. */
        void onDecryptedMessageReceived(int messageId, int associationId, byte[] message);
    }

    private final AssociationStore mAssociationStore;
    private final CompanionApplicationController mCompanionAppController;

    @Nullable
    private Listener mListener;

    /** Constructor */
    public CompanionSecureCommunicationsManager(AssociationStore associationStore,
            CompanionApplicationController companionApplicationController) {
        mAssociationStore = associationStore;
        mCompanionAppController = companionApplicationController;
    }

    public void setListener(@NonNull Listener listener) {
        mListener = listener;
    }

    /**
     * Send a data to the associated companion device via secure channel (establishing one if
     * needed).
     * @param associationId associationId of the "recipient" companion device.
     * @param messageId id of the message
     * @param message data to be sent securely.
     */
    public void sendSecureMessage(int associationId, int messageId, @NonNull byte[] message) {
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

        // Bind to the app if it hasn't been bound.
        if (!mCompanionAppController.isCompanionApplicationBound(userId, packageName)) {
            Slog.d(TAG, "userId [" + userId + "] packageName [" + packageName
                    + "] is not bound. Binding it now to send a secure message.");
            mCompanionAppController.bindCompanionApplication(userId, packageName,
                    association.isSelfManaged());

            // TODO(b/202926196): implement: encrypt and pass on the companion application for
            //  transporting
            mCompanionAppController.dispatchMessage(userId, packageName, associationId, messageId,
                    message);

            Slog.d(TAG, "Unbinding userId [" + userId + "] packageName [" + packageName
                    + "]");
            mCompanionAppController.unbindCompanionApplication(userId, packageName);
        }

        // TODO(b/202926196): implement: encrypt and pass on the companion application for
        //  transporting
        mCompanionAppController.dispatchMessage(userId, packageName, associationId, messageId,
                message);
    }

    /**
     * Decrypt and dispatch message received from an associated companion device.
     * @param associationId associationId of the "sender" companion device.
     * @param encryptedMessage data.
     */
    public void receiveSecureMessage(int messageId, int associationId,
            @NonNull byte[] encryptedMessage) {
        if (DEBUG) {
            Log.d(TAG, "sendSecureMessage() associationId=" + associationId + "\n"
                    + "   message (Base64)=\"" + Base64.encodeToString(encryptedMessage, 0) + "\"");
        }

        // TODO(b/202926196): implement: decrypt and dispatch

        mListener.onDecryptedMessageReceived(messageId, associationId, encryptedMessage);
    }
}
