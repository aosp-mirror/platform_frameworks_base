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
/*
 * Copyright (c) 2015-2017, The Linux Foundation.
 */
/*
 * Contributed by: Giesecke & Devrient GmbH.
 */

package android.se.omapi;

import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.util.Log;

import java.io.IOException;

/**
 * Instances of this class represent Secure Element Readers supported to this
 * device. These Readers can be physical devices or virtual devices. They can be
 * removable or not. They can contain Secure Element that can or cannot be
 * removed.
 *
 * @see <a href="http://globalplatform.org">GlobalPlatform Open Mobile API</a>
 */
public final class Reader {

    private static final String TAG = "OMAPI.Reader";
    private final String mName;
    private final SEService mService;
    private ISecureElementReader mReader;
    private final Object mLock = new Object();


    Reader(@NonNull SEService service, @NonNull String name, @NonNull ISecureElementReader reader) {
        if (reader == null || service == null || name == null) {
            throw new IllegalArgumentException("Parameters cannot be null");
        }
        mName = name;
        mService = service;
        mReader = reader;
    }

    /**
     * Return the name of this reader.
     * <ul>
     * <li>If this reader is a SIM reader, then its name must be "SIM[Slot]".</li>
     * <li>If the reader is a SD or micro SD reader, then its name must be "SD[Slot]"</li>
     * <li>If the reader is a embedded SE reader, then its name must be "eSE[Slot]"</li>
     * </ul>
     * Slot is a decimal number without leading zeros. The Numbering must start with 1
     * (e.g. SIM1, SIM2, ... or SD1, SD2, ... or eSE1, eSE2, ...).
     * The slot number “1” for a reader is optional
     * (SIM and SIM1 are both valid for the first SIM-reader,
     * but if there are two readers then the second reader must be named SIM2).
     * This applies also for other SD or SE readers.
     *
     * @return the reader name, as a String.
     */
    public @NonNull String getName() {
        return mName;
    }

    /**
     * Connects to a Secure Element in this reader. <br>
     * This method prepares (initialises) the Secure Element for communication
     * before the Session object is returned (e.g. powers the Secure Element by
     * ICC ON if its not already on). There might be multiple sessions opened at
     * the same time on the same reader. The system ensures the interleaving of
     * APDUs between the respective sessions.
     *
     * @throws IOException if something went wrong with the communicating to the
     *             Secure Element or the reader.
     * @return a Session object to be used to create Channels.
     */
    public @NonNull Session openSession() throws IOException {
        if (!mService.isConnected()) {
            throw new IllegalStateException("service is not connected");
        }

        synchronized (mLock) {
            ISecureElementSession session;
            try {
                session = mReader.openSession();
            } catch (ServiceSpecificException e) {
                throw new IOException(e.getMessage());
            } catch (RemoteException e) {
                throw new IllegalStateException(e.getMessage());
            }
            if (session == null) {
                throw new IOException("service session is null.");
            }
            return new Session(mService, session, this);
        }
    }

    /**
     * Check if a Secure Element is present in this reader.
     *
     * @throws IllegalStateException if the service is not connected
     * @return <code>true</code> if the SE is present, <code>false</code> otherwise.
     */
    public boolean isSecureElementPresent() {
        if (!mService.isConnected()) {
            throw new IllegalStateException("service is not connected");
        }

        try {
            return mReader.isSecureElementPresent();
        } catch (RemoteException e) {
            throw new IllegalStateException("Error in isSecureElementPresent()");
        }
    }

    /**
     * Return the Secure Element service this reader is bound to.
     *
     * @return the SEService object.
     */
    public @NonNull SEService getSEService() {
        return mService;
    }

    /**
     * Close all the sessions opened on this reader.
     * All the channels opened by all these sessions will be closed.
     */
    public void closeSessions() {
        if (!mService.isConnected()) {
            Log.e(TAG, "service is not connected");
            return;
        }
        synchronized (mLock) {
            try {
                mReader.closeSessions();
            } catch (RemoteException ignore) { }
        }
    }

    /**
     * Close all the sessions opened on this reader and reset the reader.
     * All the channels opened by all these sessions will be closed.
     * @return <code>true</code> if reset success, <code>false</code> otherwise.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.SECURE_ELEMENT_PRIVILEGED_OPERATION)
    public boolean reset() {
        if (!mService.isConnected()) {
            Log.e(TAG, "service is not connected");
            return false;
        }
        synchronized (mLock) {
            try {
                closeSessions();
                return mReader.reset();
            } catch (RemoteException ignore) {return false;}
        }
    }
}
