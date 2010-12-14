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

package android.nfc.technology;

import java.io.IOException;

import android.nfc.INfcAdapter;
import android.nfc.INfcTag;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.RemoteException;
import android.util.Log;

/**
 * A base class for tag technologies that are built on top of transceive().
 */
/* package */ abstract class BasicTagTechnology implements TagTechnology {

    /*package*/ final Tag mTag;
    /*package*/ boolean mIsConnected;
    /*package*/ int mSelectedTechnology;
    private final NfcAdapter mAdapter;

    // Following fields are final after construction, except for
    // during attemptDeadServiceRecovery() when NFC crashes.
    // Not locked - we accept a best effort attempt when NFC crashes.
    /*package*/ INfcAdapter mService;
    /*package*/ INfcTag mTagService;

    private static final String TAG = "NFC";

    /**
     * @hide
     */
    public BasicTagTechnology(NfcAdapter adapter, Tag tag, int tech) throws RemoteException {
        int[] techList = tag.getTechnologyList();
        int i;

        // Check target validity
        for (i = 0; i < techList.length; i++) {
            if (tech == techList[i]) {
                break;
            }
        }
        if (i >= techList.length) {
            // Technology not found
            throw new IllegalArgumentException("Technology " + tech + " not present on tag " + tag);
        }

        mAdapter = adapter;
        mService = mAdapter.getService();
        try {
          mTagService = mService.getNfcTagInterface();
        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
        }
        mTag = tag;
        mSelectedTechnology = tech;
    }

    /**
     * @hide
     */
    public BasicTagTechnology(NfcAdapter adapter, Tag tag) throws RemoteException {
        this(adapter, tag, tag.getTechnologyList()[0]);
    }

    /** NFC service dead - attempt best effort recovery */
    /*package*/ void attemptDeadServiceRecovery(Exception e) {
        mAdapter.attemptDeadServiceRecovery(e);
        /* assigning to mService is not thread-safe, but this is best-effort code
         * and on a well-behaved system should never happen */
        mService = mAdapter.getService();
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
    @Override
    public Tag getTag() {
        return mTag;
    }

    /**
     * <p>Requires {@link android.Manifest.permission#NFC} permission.
     */
    @Override
    public int getTechnologyId() {
        return mSelectedTechnology;
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
            return mTagService.isPresent(mTag.getServiceHandle());
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
    @Override
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
    @Override
    public void close() {
        mIsConnected = false;
        try {
            /* Note that we don't want to physically disconnect the tag,
             * but just reconnect to it to reset its state
             */
            mTagService.reconnect(mTag.getServiceHandle());
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
            byte[] response = mTagService.transceive(mTag.getServiceHandle(), data, true);
            if (response == null) {
                throw new IOException("transceive failed");
            }
            return response;
        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
            throw new IOException("NFC service died");
        }
    }
}
