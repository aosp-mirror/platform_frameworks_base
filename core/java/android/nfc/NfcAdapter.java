/*
 * Copyright (C) 2010 The Android Open Source Project Licensed under the Apache
 * License, Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0 Unless required by applicable law
 * or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */

package android.nfc;

import java.lang.UnsupportedOperationException;

import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.app.ActivityThread;
import android.content.Context;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.nfc.INfcAdapter;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

/**
 * Represents the device's local NFC adapter.
 * <p>
 * Use the static {@link #getDefaultAdapter} method to get the default NFC
 * Adapter for this Android device. Most Android devices will have only one NFC
 * Adapter, and {@link #getDefaultAdapter} returns the singleton object.
 */
public final class NfcAdapter {
    /**
     * Intent to start an activity when a tag is discovered.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_TAG_DISCOVERED = "android.nfc.action.TAG_DISCOVERED";

    /**
     * Mandatory Tag extra for the ACTION_TAG intents.
     * @hide
     */
    public static final String EXTRA_TAG = "android.nfc.extra.TAG";

    /**
     * Optional NdefMessage[] extra for the ACTION_TAG intents.
     */
    public static final String EXTRA_NDEF_MESSAGES = "android.nfc.extra.NDEF_MESSAGES";

    /**
     * Optional byte[] extra for the tag identifier.
     */
    public static final String EXTRA_ID = "android.nfc.extra.ID";

    /**
     * Broadcast Action: a transaction with a secure element has been detected.
     * <p>
     * Always contains the extra field
     * {@link android.nfc.NfcAdapter#EXTRA_AID}
     * @hide
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_TRANSACTION_DETECTED =
            "android.nfc.action.TRANSACTION_DETECTED";

    /**
     * Broadcast Action: an adapter's state changed between enabled and disabled.
     *
     * The new value is stored in the extra EXTRA_NEW_BOOLEAN_STATE and just contains
     * whether it's enabled or disabled, not including any information about whether it's
     * actively enabling or disabling.
     *
     * @hide
     */
    public static final String ACTION_ADAPTER_STATE_CHANGE =
            "android.nfc.action.ADAPTER_STATE_CHANGE";

    /**
     * The Intent extra for ACTION_ADAPTER_STATE_CHANGE, saying what the new state is.
     *
     * @hide
     */
    public static final String EXTRA_NEW_BOOLEAN_STATE = "android.nfc.isEnabled";

    /**
     * Mandatory byte array extra field in
     * {@link android.nfc.NfcAdapter#ACTION_TRANSACTION_DETECTED}.
     * <p>
     * Contains the AID of the applet involved in the transaction.
     * @hide
     */
    public static final String EXTRA_AID = "android.nfc.extra.AID";

    /**
     * LLCP link status: The LLCP link is activated.
     * @hide
     */
    public static final int LLCP_LINK_STATE_ACTIVATED = 0;

    /**
     * LLCP link status: The LLCP link is deactivated.
     * @hide
     */
    public static final int LLCP_LINK_STATE_DEACTIVATED = 1;

    /**
     * Broadcast Action: the LLCP link state changed.
     * <p>
     * Always contains the extra field
     * {@link android.nfc.NfcAdapter#EXTRA_LLCP_LINK_STATE_CHANGED}.
     * @hide
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_LLCP_LINK_STATE_CHANGED =
            "android.nfc.action.LLCP_LINK_STATE_CHANGED";

    /**
     * Used as int extra field in
     * {@link android.nfc.NfcAdapter#ACTION_LLCP_LINK_STATE_CHANGED}.
     * <p>
     * It contains the new state of the LLCP link.
     * @hide
     */
    public static final String EXTRA_LLCP_LINK_STATE_CHANGED = "android.nfc.extra.LLCP_LINK_STATE";

    /**
     * Tag Reader Discovery mode
     * @hide
     */
    private static final int DISCOVERY_MODE_TAG_READER = 0;

    /**
     * NFC-IP1 Peer-to-Peer mode Enables the manager to act as a peer in an
     * NFC-IP1 communication. Implementations should not assume that the
     * controller will end up behaving as an NFC-IP1 target or initiator and
     * should handle both cases, depending on the type of the remote peer type.
     * @hide
     */
    private static final int DISCOVERY_MODE_NFCIP1 = 1;

    /**
     * Card Emulation mode Enables the manager to act as an NFC tag. Provided
     * that a Secure Element (an UICC for instance) is connected to the NFC
     * controller through its SWP interface, it can be exposed to the outside
     * NFC world and be addressed by external readers the same way they would
     * with a tag.
     * <p>
     * Which Secure Element is exposed is implementation-dependent.
     *
     * @hide
     */
    private static final int DISCOVERY_MODE_CARD_EMULATION = 2;

    private static final String TAG = "NFC";

    // Both guarded by NfcAdapter.class:
    private static boolean sIsInitialized = false;
    private static NfcAdapter sAdapter;

    // Final after construction, except for attemptDeadServiceRecovery()
    // when NFC crashes.
    // Not locked - we accept a best effort attempt when NFC crashes.
    /*package*/ INfcAdapter mService;

    private NfcAdapter(INfcAdapter service) {
        mService = service;
    }

    /**
     * Helper to check if this device has FEATURE_NFC, but without using
     * a context.
     * Equivalent to
     * context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_NFC)
     */
    private static boolean hasNfcFeature() {
        IPackageManager pm = ActivityThread.getPackageManager();
        if (pm == null) {
            Log.e(TAG, "Cannot get package manager, assuming no NFC feature");
            return false;
        }
        try {
            return pm.hasSystemFeature(PackageManager.FEATURE_NFC);
        } catch (RemoteException e) {
            Log.e(TAG, "Package manager query failed, assuming no NFC feature", e);
            return false;
        }
    }

    /** get handle to NFC service interface */
    private static synchronized INfcAdapter getServiceInterface() {
        /* get a handle to NFC service */
        IBinder b = ServiceManager.getService("nfc");
        if (b == null) {
            return null;
        }
        return INfcAdapter.Stub.asInterface(b);
    }

    /**
     * Get a handle to the default NFC Adapter on this Android device.
     * <p>
     * Most Android devices will only have one NFC Adapter (NFC Controller).
     *
     * @return the default NFC adapter, or null if no NFC adapter exists
     */
    public static NfcAdapter getDefaultAdapter() {
        synchronized (NfcAdapter.class) {
            if (sIsInitialized) {
                return sAdapter;
            }
            sIsInitialized = true;

            /* is this device meant to have NFC */
            if (!hasNfcFeature()) {
                Log.v(TAG, "this device does not have NFC support");
                return null;
            }

            INfcAdapter service = getServiceInterface();
            if (service == null) {
                Log.e(TAG, "could not retrieve NFC service");
                return null;
            }

            sAdapter = new NfcAdapter(service);
            return sAdapter;
        }
    }

    /** NFC service dead - attempt best effort recovery */
    /*package*/ void attemptDeadServiceRecovery(Exception e) {
        Log.e(TAG, "NFC service dead - attempting to recover", e);
        INfcAdapter service = getServiceInterface();
        if (service == null) {
            Log.e(TAG, "could not retrieve NFC service during service recovery");
            return;
        }
        /* assigning to mService is not thread-safe, but this is best-effort code
         * and on a well-behaved system should never happen */
        mService = service;
        return;
    }

    /**
     * Return true if this NFC Adapter has any features enabled.
     * <p>
     * If this method returns false, then applications should request the user
     * turn on NFC tag discovery in Settings.
     * <p>
     * If this method returns false, the NFC hardware is guaranteed not to
     * perform or respond to any NFC communication.
     *
     * @return true if this NFC Adapter is enabled to discover new tags
     */
    public boolean isEnabled() {
        try {
            return mService.isEnabled();
        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
            return false;
        }
    }

    /**
     * Enable NFC hardware.
     * <p>
     * NOTE: may block for ~second or more.  Poor API.  Avoid
     * calling from the UI thread.
     *
     * @hide
     */
    public boolean enable() {
        try {
            return mService.enable();
        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
            return false;
        }
    }

    /**
     * Disable NFC hardware.
     * No NFC features will work after this call, and the hardware
     * will not perform or respond to any NFC communication.
     * <p>
     * NOTE: may block for ~second or more.  Poor API.  Avoid
     * calling from the UI thread.
     *
     * @hide
     */
    public boolean disable() {
        try {
            return mService.disable();
        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
            return false;
        }
    }

    /**
     * Create a raw tag connection to the default Target
     * <p>Requires {@link android.Manifest.permission#NFC} permission.
     * @hide
     */
    public RawTagConnection createRawTagConnection(Tag tag) {
        if (tag.mServiceHandle == 0) {
            throw new IllegalArgumentException("mock tag cannot be used for connections");
        }
        try {
            return new RawTagConnection(this, tag);
        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
            return null;
        }
    }

    /**
     * Create a raw tag connection to the specified Target
     * <p>Requires {@link android.Manifest.permission#NFC} permission.
     * @hide
     */
    public RawTagConnection createRawTagConnection(Tag tag, String target) {
        if (tag.mServiceHandle == 0) {
            throw new IllegalArgumentException("mock tag cannot be used for connections");
        }
        try {
            return new RawTagConnection(this, tag, target);
        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
            return null;
        }
    }

    /**
     * Create an NDEF tag connection to the default Target
     * <p>Requires {@link android.Manifest.permission#NFC} permission.
     * @hide
     */
    public NdefTagConnection createNdefTagConnection(NdefTag tag) {
        if (tag.mServiceHandle == 0) {
            throw new IllegalArgumentException("mock tag cannot be used for connections");
        }
        try {
            return new NdefTagConnection(this, tag);
        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
            return null;
        }
    }

    /**
     * Create an NDEF tag connection to the specified Target
     * <p>Requires {@link android.Manifest.permission#NFC} permission.
     * @hide
     */
    public NdefTagConnection createNdefTagConnection(NdefTag tag, String target) {
        if (tag.mServiceHandle == 0) {
            throw new IllegalArgumentException("mock tag cannot be used for connections");
        }
        try {
            return new NdefTagConnection(this, tag, target);
        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
            return null;
        }
    }
}
