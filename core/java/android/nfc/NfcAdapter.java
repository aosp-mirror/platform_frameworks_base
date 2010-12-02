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

import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.app.ActivityThread;
import android.content.Context;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

/**
 * Represents the device's local NFC adapter.
 * <p>
 * Use the helper {@link #getDefaultAdapter(Context)} to get the default NFC
 * adapter for this Android device.
 */
public final class NfcAdapter {
    private static final String TAG = "NFC";

    /**
     * Intent to start an activity when a tag with NDEF payload is discovered.
     * If the tag has and NDEF payload this intent is started before
     * {@link #ACTION_TECHNOLOGY_DISCOVERED}.
     *
     * If any activities respond to this intent neither
     * {@link #ACTION_TECHNOLOGY_DISCOVERED} or {@link #ACTION_TAG_DISCOVERED} will be started.
     * @hide
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_NDEF_DISCOVERED = "android.nfc.action.NDEF_DISCOVERED";

    /**
     * Intent to started when a tag is discovered. The data URI is formated as
     * {@code vnd.android.nfc://tag/} with the path having a directory entry for each technology
     * in the {@link Tag#getTechnologyList()} is ascending order.
     *
     * This intent is started after {@link #ACTION_NDEF_DISCOVERED} and before
     * {@link #ACTION_TAG_DISCOVERED}
     *
     * If any activities respond to this intent {@link #ACTION_TAG_DISCOVERED} will not be started.
     * @hide
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_TECHNOLOGY_DISCOVERED = "android.nfc.action.TECH_DISCOVERED";

    /**
     * Intent to start an activity when a tag is discovered.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_TAG_DISCOVERED = "android.nfc.action.TAG_DISCOVERED";

    /**
     * Broadcast to only the activity that handles ACTION_TAG_DISCOVERED
     * @hide
     */
    public static final String ACTION_TAG_LEFT_FIELD = "android.nfc.action.TAG_LOST";

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


    // Guarded by NfcAdapter.class
    private static boolean sIsInitialized = false;

    // Final after first constructor, except for
    // attemptDeadServiceRecovery() when NFC crashes - we accept a best effort
    // recovery
    private static INfcAdapter sService;

    private final Context mContext;

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

    private static synchronized INfcAdapter setupService() {
        if (!sIsInitialized) {
            sIsInitialized = true;

            /* is this device meant to have NFC */
            if (!hasNfcFeature()) {
                Log.v(TAG, "this device does not have NFC support");
                return null;
            }

            sService = getServiceInterface();
            if (sService == null) {
                Log.e(TAG, "could not retrieve NFC service");
                return null;
            }
        }
        return sService;
    }

    /** get handle to NFC service interface */
    private static INfcAdapter getServiceInterface() {
        /* get a handle to NFC service */
        IBinder b = ServiceManager.getService("nfc");
        if (b == null) {
            return null;
        }
        return INfcAdapter.Stub.asInterface(b);
    }

    /**
     * Helper to get the default NFC Adapter.
     * <p>
     * Most Android devices will only have one NFC Adapter (NFC Controller).
     * <p>
     * This helper is the equivalent of:
     * <pre>{@code
     * NfcManager manager = (NfcManager) context.getSystemService(Context.NFC_SERVICE);
     * NfcAdapter adapter = manager.getDefaultAdapter();
     * }</pre>
     * @param context the calling application's context
     *
     * @return the default NFC adapter, or null if no NFC adapter exists
     */
    public static NfcAdapter getDefaultAdapter(Context context) {
        /* use getSystemService() instead of just instantiating to take
         * advantage of the context's cached NfcManager & NfcAdapter */
        NfcManager manager = (NfcManager) context.getSystemService(Context.NFC_SERVICE);
        return manager.getDefaultAdapter();
    }

    /**
     * Get a handle to the default NFC Adapter on this Android device.
     * <p>
     * Most Android devices will only have one NFC Adapter (NFC Controller).
     *
     * @return the default NFC adapter, or null if no NFC adapter exists
     * @deprecated use {@link #getDefaultAdapter(Context)}
     */
    @Deprecated
    public static NfcAdapter getDefaultAdapter() {
        Log.w(TAG, "WARNING: NfcAdapter.getDefaultAdapter() is deprecated, use " +
                "NfcAdapter.getDefaultAdapter(Context) instead", new Exception());
        return new NfcAdapter(null);
    }

    /*package*/ NfcAdapter(Context context) {
        if (setupService() == null) {
            throw new UnsupportedOperationException();
        }
        mContext = context;
    }

    /**
     * Returns the binder interface to the service.
     * @hide
     */
    public INfcAdapter getService() {
        return sService;
    }

    /**
     * NFC service dead - attempt best effort recovery
     * @hide
     */
    public void attemptDeadServiceRecovery(Exception e) {
        Log.e(TAG, "NFC service dead - attempting to recover", e);
        INfcAdapter service = getServiceInterface();
        if (service == null) {
            Log.e(TAG, "could not retrieve NFC service during service recovery");
            return;
        }
        /* assigning to sService is not thread-safe, but this is best-effort code
         * and on a well-behaved system should never happen */
        sService = service;
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
            return sService.isEnabled();
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
            return sService.enable();
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
            return sService.disable();
        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
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
     * @hide
     */
    public void setLocalNdefMessage(NdefMessage message) {
        try {
            sService.localSet(message);
        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
        }
    }

    /**
     * Get the NDEF Message that this adapter appears as to Tag readers.
     * <p>Requires {@link android.Manifest.permission#NFC} permission.
     *
     * @return NDEF Message that is publicly readable
     * @hide
     */
    public NdefMessage getLocalNdefMessage() {
        try {
            return sService.localGet();
        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
            return null;
        }
    }

    /**
     * Create an Nfc Secure Element Connection
     * @hide
     */
    public NfcSecureElement createNfcSecureElementConnection() {
        try {
            return new NfcSecureElement(sService.getNfcSecureElementInterface());
        } catch (RemoteException e) {
            Log.e(TAG, "createNfcSecureElementConnection failed", e);
            return null;
        }
    }
}
