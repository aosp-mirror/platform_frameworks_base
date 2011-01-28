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
import android.nfc.TransceiveResult;
import android.nfc.Tag;
import android.nfc.ErrorCodes;
import android.nfc.TagLostException;
import android.os.RemoteException;
import android.util.Log;

/**
 * A base class for tag technologies that are built on top of transceive().
 */
/* package */ abstract class BasicTagTechnology implements TagTechnology {
    private static final String TAG = "NFC";

    /*package*/ final Tag mTag;
    /*package*/ boolean mIsConnected;
    /*package*/ int mSelectedTechnology;
    private final NfcAdapter mAdapter;
    /*package*/ final INfcAdapter mService;
    /*package*/ final INfcTag mTagService;

    BasicTagTechnology(NfcAdapter adapter, Tag tag, int tech) throws RemoteException {
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
        mTagService = mAdapter.getTagService();
        mTag = tag;
        mSelectedTechnology = tech;
    }

    BasicTagTechnology(NfcAdapter adapter, Tag tag) throws RemoteException {
        this(adapter, tag, tag.getTechnologyList()[0]);
    }

    @Override
    public Tag getTag() {
        return mTag;
    }

    /** Internal helper to throw IllegalStateException if the technology isn't connected */
    void checkConnected() {
       if ((mTag.getConnectedTechnology() != getTechnologyId()) ||
               (mTag.getConnectedTechnology() == -1)) {
           throw new IllegalStateException("Call connect() first!");
       }
    }

    @Override
    public int getTechnologyId() {
        return mSelectedTechnology;
    }

    /**
     * Helper to indicate if {@link #connect} has succeeded.
     * <p>
     * Does not cause RF activity, and does not block.
     * @return true if {@link #connect} has completed successfully and the {@link Tag} is believed
     * to be within range. Applications must still handle {@link java.io.IOException}
     * while using methods that require a connection in case the connection is lost after this
     * method returns.
     */
    public boolean isConnected() {
        if (!mIsConnected) {
            return false;
        }

        try {
            return mTagService.isPresent(mTag.getServiceHandle());
        } catch (RemoteException e) {
            Log.e(TAG, "NFC service dead", e);
            return false;
        }
    }

    @Override
    public void connect() throws IOException {
        try {
            int errorCode = mTagService.connect(mTag.getServiceHandle(), getTechnologyId());

            if (errorCode == ErrorCodes.SUCCESS) {
                // Store this in the tag object
                mTag.setConnectedTechnology(getTechnologyId());
                mIsConnected = true;
            } else {
                throw new IOException();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "NFC service dead", e);
            throw new IOException("NFC service died");
        }
    }

    @Override
    public void reconnect() throws IOException {
        if (!mIsConnected) {
            throw new IllegalStateException("Technology not connected yet");
        }

        try {
            int errorCode = mTagService.reconnect(mTag.getServiceHandle());

            if (errorCode != ErrorCodes.SUCCESS) {
                mIsConnected = false;
                mTag.setTechnologyDisconnected();
                throw new IOException();
            }
        } catch (RemoteException e) {
            mIsConnected = false;
            mTag.setTechnologyDisconnected();
            Log.e(TAG, "NFC service dead", e);
            throw new IOException("NFC service died");
        }
    }

    @Override
    public void close() {
        try {
            /* Note that we don't want to physically disconnect the tag,
             * but just reconnect to it to reset its state
             */
            mTagService.reconnect(mTag.getServiceHandle());
        } catch (RemoteException e) {
            Log.e(TAG, "NFC service dead", e);
        } finally {
            mIsConnected = false;
            mTag.setTechnologyDisconnected();
        }
    }

    /** Internal transceive */
    /*package*/ byte[] transceive(byte[] data, boolean raw) throws IOException {
        checkConnected();

        try {
            TransceiveResult result = mTagService.transceive(mTag.getServiceHandle(), data, raw);
            if (result == null) {
                throw new IOException("transceive failed");
            } else {
                if (result.isSuccessful()) {
                    return result.getResponseData();
                } else {
                    if (result.isTagLost()) {
                        throw new TagLostException("Tag was lost.");
                    }
                    else {
                        throw new IOException("transceive failed");
                    }
                }
            }
        } catch (RemoteException e) {
            Log.e(TAG, "NFC service dead", e);
            throw new IOException("NFC service died");
        }
    }
}
