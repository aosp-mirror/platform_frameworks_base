/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.telephony;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemService;
import android.app.ActivityThread;
import android.app.PendingIntent;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.ServiceManager;

import com.android.internal.telephony.IMms;

/**
 * Manages MMS operations such as sending multimedia messages.
 * Get this object by calling Context#getSystemService(Context#MMS_SERVICE).
 * @hide
 */
@SystemService(Context.MMS_SERVICE)
public class MmsManager {
    private static final String TAG = "MmsManager";
    private final Context mContext;

    /**
     * @hide
     */
    public MmsManager(@NonNull Context context) {
        mContext = context;
    }

    /**
     * Send an MMS message
     *
     * @param subId the subscription id
     * @param contentUri the content Uri from which the message pdu will be read
     * @param locationUrl the optional location url where message should be sent to
     * @param configOverrides the carrier-specific messaging configuration values to override for
     *                        sending the message.
     * @param sentIntent if not NULL this <code>PendingIntent</code> is broadcast when the message
     *                   is successfully sent, or failed
     * @param messageId an id that uniquely identifies the message requested to be sent.
     *                  Used for logging and diagnostics purposes. The id may be 0. The messageId
     *                  can be found in radio logs from logcat.
     */
    public void sendMultimediaMessage(int subId, @NonNull Uri contentUri,
            @Nullable String locationUrl, @Nullable Bundle configOverrides,
            @Nullable PendingIntent sentIntent, long messageId) {
        try {
            final IMms iMms = IMms.Stub.asInterface(ServiceManager.getService("imms"));
            if (iMms == null) {
                return;
            }

            iMms.sendMessage(subId, ActivityThread.currentPackageName(), contentUri,
                    locationUrl, configOverrides, sentIntent, messageId,
                    mContext.getAttributionTag());
        } catch (RemoteException e) {
            // Ignore it
        }
    }

    /**
     * Download an MMS message from carrier by a given location URL
     *
     * @param subId the subscription id
     * @param locationUrl the location URL of the MMS message to be downloaded, usually obtained
     *  from the MMS WAP push notification
     * @param contentUri the content uri to which the downloaded pdu will be written
     * @param configOverrides the carrier-specific messaging configuration values to override for
     *  downloading the message.
     * @param downloadedIntent if not NULL this <code>PendingIntent</code> is
     *  broadcast when the message is downloaded, or the download is failed
     * @param messageId an id that uniquely identifies the message requested to be downloaded.
     *                  Used for logging and diagnostics purposes. The id may be 0. The messageId
     *                  can be found in radio logs from logcat.
     * @throws IllegalArgumentException if locationUrl or contentUri is empty
     */
    public void downloadMultimediaMessage(int subId, @NonNull String locationUrl,
            @NonNull Uri contentUri, @Nullable Bundle configOverrides,
            @Nullable PendingIntent downloadedIntent, long messageId) {
        try {
            final IMms iMms = IMms.Stub.asInterface(ServiceManager.getService("imms"));
            if (iMms == null) {
                return;
            }
            iMms.downloadMessage(subId, ActivityThread.currentPackageName(),
                    locationUrl, contentUri, configOverrides, downloadedIntent,
                    messageId, mContext.getAttributionTag());
        } catch (RemoteException e) {
            // Ignore it
        }
    }
}
