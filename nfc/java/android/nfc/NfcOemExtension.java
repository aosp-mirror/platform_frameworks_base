/*
 * Copyright (C) 2024 The Android Open Source Project
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

import android.annotation.CallbackExecutor;
import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.content.Context;
import android.os.Binder;
import android.os.RemoteException;
import android.util.Log;

import java.util.concurrent.Executor;

/**
 * Used for OEM extension APIs.
 * This class holds all the APIs and callbacks defined for OEMs/vendors to extend the NFC stack
 * for their proprietary features.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_NFC_OEM_EXTENSION)
@SystemApi
public final class NfcOemExtension {
    private static final String TAG = "NfcOemExtension";
    private static final int OEM_EXTENSION_RESPONSE_THRESHOLD_MS = 2000;
    private final NfcAdapter mAdapter;
    private final NfcOemExtensionCallback mOemNfcExtensionCallback;
    private final Context mContext;
    private Executor mExecutor = null;
    private Callback mCallback = null;
    private final Object mLock = new Object();

    /**
     * Interface for Oem extensions for NFC.
     */
    public interface Callback {
        /**
         * Notify Oem to tag is connected or not
         * ex - if tag is connected  notify cover and Nfctest app if app is in testing mode
         *
         * @param connected status of the tag true if tag is connected otherwise false
         * @param tag Tag details
         */
        void onTagConnected(boolean connected, @NonNull Tag tag);
    }


    /**
     * Constructor to be used only by {@link NfcAdapter}.
     * @hide
     */
    public NfcOemExtension(@NonNull Context context, @NonNull NfcAdapter adapter) {
        mContext = context;
        mAdapter = adapter;
        mOemNfcExtensionCallback = new NfcOemExtensionCallback();
    }

    /**
     * Register an {@link Callback} to listen for UWB oem extension callbacks
     * <p>The provided callback will be invoked by the given {@link Executor}.
     *
     * @param executor an {@link Executor} to execute given callback
     * @param callback oem implementation of {@link Callback}
     */
    @FlaggedApi(Flags.FLAG_NFC_OEM_EXTENSION)
    @RequiresPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
    public void registerCallback(@NonNull @CallbackExecutor Executor executor,
            @NonNull Callback callback) {
        synchronized (mLock) {
            if (mCallback != null) {
                Log.e(TAG, "Callback already registered. Unregister existing callback before"
                        + "registering");
                throw new IllegalArgumentException();
            }
            try {
                NfcAdapter.sService.registerOemExtensionCallback(mOemNfcExtensionCallback);
                mCallback = callback;
                mExecutor = executor;
            } catch (RemoteException e) {
                mAdapter.attemptDeadServiceRecovery(e);
            }
        }
    }

    /**
     * Unregister the specified {@link Callback}
     *
     * <p>The same {@link Callback} object used when calling
     * {@link #registerCallback(Executor, Callback)} must be used.
     *
     * <p>Callbacks are automatically unregistered when an application process goes away
     *
     * @param callback oem implementation of {@link Callback}
     */
    @FlaggedApi(Flags.FLAG_NFC_OEM_EXTENSION)
    @RequiresPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
    public void unregisterCallback(@NonNull Callback callback) {
        synchronized (mLock) {
            if (mCallback == null || mCallback != callback) {
                Log.e(TAG, "Callback not registered");
                throw new IllegalArgumentException();
            }
            try {
                NfcAdapter.sService.unregisterOemExtensionCallback(mOemNfcExtensionCallback);
                mCallback = null;
                mExecutor = null;
            } catch (RemoteException e) {
                mAdapter.attemptDeadServiceRecovery(e);
            }
        }
    }

    /**
     * Clear NfcService preference, interface method to clear NFC preference values on OEM specific
     * events. For ex: on soft reset, Nfc default values needs to be overridden by OEM defaults.
     */
    @FlaggedApi(Flags.FLAG_NFC_OEM_EXTENSION)
    @RequiresPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
    public void clearPreference() {
        try {
            NfcAdapter.sService.clearPreference();
        } catch (RemoteException e) {
            mAdapter.attemptDeadServiceRecovery(e);
        }
    }

    /**
     * Get the screen state from system and set it to current screen state.
     */
    @FlaggedApi(Flags.FLAG_NFC_OEM_EXTENSION)
    @RequiresPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
    public void synchronizeScreenState() {
        try {
            NfcAdapter.sService.setScreenState();
        } catch (RemoteException e) {
            mAdapter.attemptDeadServiceRecovery(e);
        }
    }

    /**
     * Check if the firmware needs updating.
     *
     * <p>If an update is needed, a firmware will be triggered when NFC is disabled.
     */
    @FlaggedApi(Flags.FLAG_NFC_OEM_EXTENSION)
    @RequiresPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
    public void maybeTriggerFirmwareUpdate() {
        try {
            NfcAdapter.sService.checkFirmware();
        } catch (RemoteException e) {
            mAdapter.attemptDeadServiceRecovery(e);
        }
    }

    private final class NfcOemExtensionCallback extends INfcOemExtensionCallback.Stub {
        @Override
        public void onTagConnected(boolean connected, Tag tag) throws RemoteException {
            synchronized (mLock) {
                if (mCallback == null || mExecutor == null) {
                    return;
                }
                final long identity = Binder.clearCallingIdentity();
                try {
                    mExecutor.execute(() -> mCallback.onTagConnected(connected, tag));
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
        }
    }
}
