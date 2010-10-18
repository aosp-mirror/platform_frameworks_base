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
 * <p>
 * {@link NfcAdapter} can be used to create {@link RawTagConnection} or
 * {@link NdefTagConnection} connections to modify or perform low level access
 * to NFC Tags.
 * <p class="note">
 * <strong>Note:</strong> Some methods require the
 * {@link android.Manifest.permission#NFC} permission.
 */
public final class NfcAdapter {
    /**
     * Intent to start an activity when a non-NDEF tag is discovered.
     * TODO(npelly) finalize decision on using CATEGORY or DATA URI to provide a
     * hint for applications to filter the tag type.
     * TODO(npelly) probably combine these two intents since tags aren't that simple
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_TAG_DISCOVERED = "android.nfc.action.TAG_DISCOVERED";

    /**
     * Intent to start an activity when a NDEF tag is discovered. TODO(npelly)
     * finalize decision on using CATEGORY or DATA URI to provide a hint for
     * applications to filter the tag type.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_NDEF_TAG_DISCOVERED =
            "android.nfc.action.NDEF_TAG_DISCOVERED";

    /**
     * Mandatory Tag extra for the ACTION_TAG and ACTION_NDEF_TAG intents.
     */
    public static final String EXTRA_TAG = "android.nfc.extra.TAG";

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

    private static boolean sIsInitialized = false;
    private static NfcAdapter sAdapter;

    private final INfcAdapter mService;

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

            /* get a handle to NFC service */
            IBinder b = ServiceManager.getService("nfc");
            if (b == null) {
                Log.e(TAG, "could not retrieve NFC service");
                return null;
            }

            sAdapter = new NfcAdapter(INfcAdapter.Stub.asInterface(b));
            return sAdapter;
        }
    }

    /**
     * Return true if this NFC Adapter is enabled to discover new tags.
     * <p>
     * If this method returns false, then applications should request the user
     * turn on NFC tag discovery in Settings.
     *
     * @return true if this NFC Adapter is enabled to discover new tags
     */
    public boolean isTagDiscoveryEnabled() {
        try {
            return mService.isEnabled();
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in isEnabled()", e);
            return false;
        }
    }

    /**
     * @hide
     */
    public boolean enableTagDiscovery() {
        try {
            return mService.enable();
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in enable()", e);
            return false;
        }
    }

    /**
     * @hide
     */
    public boolean disableTagDiscovery() {
        try {
            return mService.disable();
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in disable()", e);
            return false;
        }
    }

    /**
     * Set the NDEF Message that this NFC adapter should appear as to Tag
     * readers.
     * <p>
     * Any Tag reader can read the contents of the local tag when it is in
     * proximity, without any further user confirmation.
     * <p>
     * The implementation of this method must either
     * <ul>
     * <li>act as a passive tag containing this NDEF message
     * <li>provide the NDEF message on over LLCP to peer NFC adapters
     * </ul>
     * The NDEF message is preserved across reboot.
     * <p>Requires {@link android.Manifest.permission#NFC} permission.
     *
     * @param message NDEF message to make public
     */
    public void setLocalNdefMessage(NdefMessage message) {
        try {
            mService.localSet(message);
        } catch (RemoteException e) {
            Log.e(TAG, "NFC service died", e);
        }
    }

    /**
     * Get the NDEF Message that this adapter appears as to Tag readers.
     * <p>Requires {@link android.Manifest.permission#NFC} permission.
     *
     * @return NDEF Message that is publicly readable
     */
    public NdefMessage getLocalNdefMessage() {
        try {
            return mService.localGet();
        } catch (RemoteException e) {
            Log.e(TAG, "NFC service died", e);
            return null;
        }
    }

    /**
     * Create a raw tag connection to the default Target
     * <p>Requires {@link android.Manifest.permission#NFC} permission.
     */
    public RawTagConnection createRawTagConnection(Tag tag) {
        try {
            return new RawTagConnection(mService, tag);
        } catch (RemoteException e) {
            Log.e(TAG, "NFC service died", e);
            return null;
        }
    }

    /**
     * Create a raw tag connection to the specified Target
     * <p>Requires {@link android.Manifest.permission#NFC} permission.
     */
    public RawTagConnection createRawTagConnection(Tag tag, String target) {
        try {
            return new RawTagConnection(mService, tag, target);
        } catch (RemoteException e) {
            Log.e(TAG, "NFC service died", e);
            return null;
        }
    }

    /**
     * Create an NDEF tag connection to the default Target
     * <p>Requires {@link android.Manifest.permission#NFC} permission.
     */
    public NdefTagConnection createNdefTagConnection(NdefTag tag) {
        try {
            return new NdefTagConnection(mService, tag);
        } catch (RemoteException e) {
            Log.e(TAG, "NFC service died", e);
            return null;
        }
    }

    /**
     * Create an NDEF tag connection to the specified Target
     * <p>Requires {@link android.Manifest.permission#NFC} permission.
     */
    public NdefTagConnection createNdefTagConnection(NdefTag tag, String target) {
        try {
            return new NdefTagConnection(mService, tag, target);
        } catch (RemoteException e) {
            Log.e(TAG, "NFC service died", e);
            return null;
        }
    }
}
