/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.nfc;

import java.io.IOException;

import android.os.RemoteException;
import android.util.Log;

/**
 * A low-level connection to a {@link Tag} target.
 * <p>You can acquire this kind of connection with {@link NfcAdapter#createRawTagConnection
 * createRawTagConnection()}. Use the connection to send and receive data with {@link #transceive
 * transceive()}.
 * <p>
 * Applications must implement their own protocol stack on top of {@link #transceive transceive()}.
 *
 * <p class="note"><strong>Note:</strong>
 * Use of this class requires the {@link android.Manifest.permission#NFC}
 * permission.
 * @hide
 */
public class RawTagConnection {

    /*package*/ final Tag mTag;
    /*package*/ boolean mIsConnected;
    /*package*/ String mSelectedTarget;
    private final NfcAdapter mAdapter;

    // Following fields are final after construction, except for
    // during attemptDeadServiceRecovery() when NFC crashes.
    // Not locked - we accept a best effort attempt when NFC crashes.
    /*package*/ INfcAdapter mService;
    /*package*/ INfcTag mTagService;

    private static final String TAG = "NFC";

    /*package*/ RawTagConnection(NfcAdapter adapter, Tag tag, String target) throws RemoteException {
        String[] targets = tag.getRawTargets();
        int i;

        // Check target validity
        for (i=0;i<targets.length;i++) {
            if (target.equals(targets[i])) {
                break;
            }
        }
        if (i >= targets.length) {
            // Target not found
            throw new IllegalArgumentException();
        }

        mAdapter = adapter;
        mService = mAdapter.mService;
        mTagService = mService.getNfcTagInterface();
        mTag = tag;
        mSelectedTarget = target;
    }

    /*package*/ RawTagConnection(NfcAdapter adapter, Tag tag) throws RemoteException {
        this(adapter, tag, tag.getRawTargets()[0]);
    }

    /** NFC service dead - attempt best effort recovery */
    /*package*/ void attemptDeadServiceRecovery(Exception e) {
        mAdapter.attemptDeadServiceRecovery(e);
        /* assigning to mService is not thread-safe, but this is best-effort code
         * and on a well-behaved system should never happen */
        mService = mAdapter.mService;
        try {
            mTagService = mService.getNfcTagInterface();
        } catch (RemoteException e2) {
            Log.e(TAG, "second RemoteException trying to recover from dead NFC service", e2);
        }
    }

    /**
     * Get the {@link Tag} this connection is associated with.
     * <p>Requires {@link android.Manifest.permission#NFC} permission.
     */
    public Tag getTag() {
        return mTag;
    }

    /**
     * <p>Requires {@link android.Manifest.permission#NFC} permission.
     */
    public String getTagTarget() {
        return mSelectedTarget;
    }

    /**
     * Helper to indicate if {@link #transceive transceive()} calls might succeed.
     * <p>
     * Does not cause RF activity, and does not block.
     * <p>Requires {@link android.Manifest.permission#NFC} permission.
     * @return true if {@link #connect} has completed successfully and the {@link Tag} is believed
     * to be within range. Applications must still handle {@link java.io.IOException}
     * while using {@link #transceive transceive()}, in case connection is lost after this method
     * returns true.
     */
    public boolean isConnected() {
        if (!mIsConnected) {
            return false;
        }

        try {
            return mTagService.isPresent(mTag.mServiceHandle);
        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
            return false;
        }
    }

    /**
     * Connect to the {@link Tag} associated with this connection.
     * <p>
     * This method blocks until the connection is established.
     * <p>
     * {@link #close} can be called from another thread to cancel this connection
     * attempt.
     * <p>Requires {@link android.Manifest.permission#NFC} permission.
     * @throws IOException if the target is lost, or connect canceled
     */
    public void connect() throws IOException {
        //TODO(nxp): enforce exclusivity
        mIsConnected = true;
    }

    /**
     * Close this connection.
     * <p>
     * Causes blocking operations such as {@link #transceive transceive()} or {@link #connect} to
     * be canceled and immediately throw {@link java.io.IOException}.
     * <p>
     * Once this method is called, this object cannot be re-used and should be discarded. Further
     * calls to {@link #transceive transceive()} or {@link #connect} will fail.
     * <p>Requires {@link android.Manifest.permission#NFC} permission.
     */
    public void close() {
        mIsConnected = false;
        try {
            mTagService.close(mTag.mServiceHandle);
        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
        }
    }

    /**
     * Send data to a tag and receive the response.
     * <p>
     * This method will block until the response is received. It can be canceled
     * with {@link #close}.
     * <p>Requires {@link android.Manifest.permission#NFC} permission.
     *
     * @param data bytes to send
     * @return bytes received in response
     * @throws IOException if the target is lost or connection closed
     */
    public byte[] transceive(byte[] data) throws IOException {
        try {
            byte[] response = mTagService.transceive(mTag.mServiceHandle, data);
            if (response == null) {
                throw new IOException("transcieve failed");
            }
            return response;
        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
            throw new IOException("NFC service died");
        }
    }
}
