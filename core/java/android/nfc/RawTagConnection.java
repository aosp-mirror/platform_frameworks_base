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
 * RawTagConnection is a low-level connection to a Tag.
 * <p>
 * The only data transfer method that TagConnection offers is transceive().
 * Applications must implement there own protocol stack on top of transceive().
 * <p>
 * Use NfcAdapter.createRawTagConnection() to create a RawTagConnection object.
 *
 * * <p class="note"><strong>Note:</strong>
 * Most methods require the {@link android.Manifest.permission#BLUETOOTH}
 * permission and some also require the
 * {@link android.Manifest.permission#BLUETOOTH_ADMIN} permission.

 */
public class RawTagConnection {

    /*package*/ final INfcAdapter mService;
    /*package*/ final INfcTag mTagService;
    /*package*/ final Tag mTag;
    /*package*/ boolean mIsConnected;

    private static final String TAG = "NFC";

    /* package private */ RawTagConnection(INfcAdapter service, Tag tag) throws RemoteException {
        mService = service;
        mTagService = service.getNfcTagInterface();
        mService.openTagConnection(tag);  // TODO(nxp): don't connect until connect()
        mTag = tag;
    }

    /**
     * Get the Tag this connection is associated with.
     */
    public Tag getTag() {
        return mTag;
    }

    public String getTagTarget() {
        //TODO
        throw new UnsupportedOperationException();
    }

    /**
     * Helper to indicate if transceive() calls might succeed.
     * <p>
     * Does not cause RF activity, and does not block.
     * <p>
     * Returns true if connect() has completed successfully, and the Tag is not
     * yet known to be out of range. Applications must still handle IOException
     * while using transceive().
     */
    public boolean isConnected() {
        // TODO(nxp): update mIsConnected when tag goes out of range -
        //            but do not do an active prescence check in
        //            isConnected()
        return mIsConnected;
    }

    /**
     * Connect to tag.
     * <p>
     * This method blocks until the connection is established.
     * <p>
     * close() can be called from another thread to cancel this connection
     * attempt.
     *
     * @throws IOException if the target is lost, or connect canceled
     */
    public void connect() throws IOException {
        //TODO(nxp): enforce exclusivity
        mIsConnected = true;
    }

    /**
     * Close tag connection.
     * <p>
     * Causes blocking operations such as transceive() or connect() to
     * be canceled and immediately throw IOException.
     * <p>
     * This object cannot be re-used after calling close(). Further calls
     * to transceive() or connect() will fail.
     */
    public void close() {
        mIsConnected = false;
        try {
            mTagService.close(mTag.mNativeHandle);
        } catch (RemoteException e) {
            Log.e(TAG, "NFC service died", e);
        }
    }

    /**
     * Send data to a tag, and return the response.
     * <p>
     * This method will block until the response is received. It can be canceled
     * with close().
     * <p>
     * Requires NFC_WRITE permission.
     *
     * @param data bytes to send
     * @return bytes received in response
     * @throws IOException if the target is lost or connection closed
     */
    public byte[] transceive(byte[] data) throws IOException {
        try {
            byte[] response = mTagService.transceive(mTag.mNativeHandle, data);
            if (response == null) {
                throw new IOException("transcieve failed");
            }
            return response;
        } catch (RemoteException e) {
            Log.e(TAG, "NFC service died", e);
            throw new IOException("NFC service died");
        }
    }
}
