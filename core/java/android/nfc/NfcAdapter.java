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

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.SystemApi;
import android.app.Activity;
import android.app.ActivityThread;
import android.app.OnActivityPausedListener;
import android.app.PendingIntent;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.IntentFilter;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.nfc.tech.MifareClassic;
import android.nfc.tech.Ndef;
import android.nfc.tech.NfcA;
import android.nfc.tech.NfcF;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Represents the local NFC adapter.
 * <p>
 * Use the helper {@link #getDefaultAdapter(Context)} to get the default NFC
 * adapter for this Android device.
 *
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>For more information about using NFC, read the
 * <a href="{@docRoot}guide/topics/nfc/index.html">Near Field Communication</a> developer guide.</p>
 * <p>To perform basic file sharing between devices, read
 * <a href="{@docRoot}training/beam-files/index.html">Sharing Files with NFC</a>.
 * </div>
 */
public final class NfcAdapter {
    static final String TAG = "NFC";

    private final NfcControllerAlwaysOnStateListener mControllerAlwaysOnStateListener;

    /**
     * Intent to start an activity when a tag with NDEF payload is discovered.
     *
     * <p>The system inspects the first {@link NdefRecord} in the first {@link NdefMessage} and
     * looks for a URI, SmartPoster, or MIME record. If a URI or SmartPoster record is found the
     * intent will contain the URI in its data field. If a MIME record is found the intent will
     * contain the MIME type in its type field. This allows activities to register
     * {@link IntentFilter}s targeting specific content on tags. Activities should register the
     * most specific intent filters possible to avoid the activity chooser dialog, which can
     * disrupt the interaction with the tag as the user interacts with the screen.
     *
     * <p>If the tag has an NDEF payload this intent is started before
     * {@link #ACTION_TECH_DISCOVERED}. If any activities respond to this intent neither
     * {@link #ACTION_TECH_DISCOVERED} or {@link #ACTION_TAG_DISCOVERED} will be started.
     *
     * <p>The MIME type or data URI of this intent are normalized before dispatch -
     * so that MIME, URI scheme and URI host are always lower-case.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_NDEF_DISCOVERED = "android.nfc.action.NDEF_DISCOVERED";

    /**
     * Intent to start an activity when a tag is discovered and activities are registered for the
     * specific technologies on the tag.
     *
     * <p>To receive this intent an activity must include an intent filter
     * for this action and specify the desired tech types in a
     * manifest <code>meta-data</code> entry. Here is an example manfiest entry:
     * <pre>
     * &lt;activity android:name=".nfc.TechFilter" android:label="NFC/TechFilter"&gt;
     *     &lt;!-- Add a technology filter --&gt;
     *     &lt;intent-filter&gt;
     *         &lt;action android:name="android.nfc.action.TECH_DISCOVERED" /&gt;
     *     &lt;/intent-filter&gt;
     *
     *     &lt;meta-data android:name="android.nfc.action.TECH_DISCOVERED"
     *         android:resource="@xml/filter_nfc"
     *     /&gt;
     * &lt;/activity&gt;</pre>
     *
     * <p>The meta-data XML file should contain one or more <code>tech-list</code> entries
     * each consisting or one or more <code>tech</code> entries. The <code>tech</code> entries refer
     * to the qualified class name implementing the technology, for example "android.nfc.tech.NfcA".
     *
     * <p>A tag matches if any of the
     * <code>tech-list</code> sets is a subset of {@link Tag#getTechList() Tag.getTechList()}. Each
     * of the <code>tech-list</code>s is considered independently and the
     * activity is considered a match is any single <code>tech-list</code> matches the tag that was
     * discovered. This provides AND and OR semantics for filtering desired techs. Here is an
     * example that will match any tag using {@link NfcF} or any tag using {@link NfcA},
     * {@link MifareClassic}, and {@link Ndef}:
     *
     * <pre>
     * &lt;resources xmlns:xliff="urn:oasis:names:tc:xliff:document:1.2"&gt;
     *     &lt;!-- capture anything using NfcF --&gt;
     *     &lt;tech-list&gt;
     *         &lt;tech&gt;android.nfc.tech.NfcF&lt;/tech&gt;
     *     &lt;/tech-list&gt;
     *
     *     &lt;!-- OR --&gt;
     *
     *     &lt;!-- capture all MIFARE Classics with NDEF payloads --&gt;
     *     &lt;tech-list&gt;
     *         &lt;tech&gt;android.nfc.tech.NfcA&lt;/tech&gt;
     *         &lt;tech&gt;android.nfc.tech.MifareClassic&lt;/tech&gt;
     *         &lt;tech&gt;android.nfc.tech.Ndef&lt;/tech&gt;
     *     &lt;/tech-list&gt;
     * &lt;/resources&gt;</pre>
     *
     * <p>This intent is started after {@link #ACTION_NDEF_DISCOVERED} and before
     * {@link #ACTION_TAG_DISCOVERED}. If any activities respond to {@link #ACTION_NDEF_DISCOVERED}
     * this intent will not be started. If any activities respond to this intent
     * {@link #ACTION_TAG_DISCOVERED} will not be started.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_TECH_DISCOVERED = "android.nfc.action.TECH_DISCOVERED";

    /**
     * Intent to start an activity when a tag is discovered.
     *
     * <p>This intent will not be started when a tag is discovered if any activities respond to
     * {@link #ACTION_NDEF_DISCOVERED} or {@link #ACTION_TECH_DISCOVERED} for the current tag.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_TAG_DISCOVERED = "android.nfc.action.TAG_DISCOVERED";

    /**
     * Broadcast Action: Intent to notify an application that a transaction event has occurred
     * on the Secure Element.
     *
     * <p>This intent will only be sent if the application has requested permission for
     * {@link android.Manifest.permission#NFC_TRANSACTION_EVENT} and if the application has the
     * necessary access to Secure Element which witnessed the particular event.
     */
    @RequiresPermission(android.Manifest.permission.NFC_TRANSACTION_EVENT)
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_TRANSACTION_DETECTED =
            "android.nfc.action.TRANSACTION_DETECTED";

    /**
     * Broadcast Action: Intent to notify if the preferred payment service changed.
     *
     * <p>This intent will only be sent to the application has requested permission for
     * {@link android.Manifest.permission#NFC_PREFERRED_PAYMENT_INFO} and if the application
     * has the necessary access to Secure Element which witnessed the particular event.
     */
    @RequiresPermission(android.Manifest.permission.NFC_PREFERRED_PAYMENT_INFO)
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_PREFERRED_PAYMENT_CHANGED =
            "android.nfc.action.PREFERRED_PAYMENT_CHANGED";

    /**
     * Broadcast to only the activity that handles ACTION_TAG_DISCOVERED
     * @hide
     */
    public static final String ACTION_TAG_LEFT_FIELD = "android.nfc.action.TAG_LOST";

    /**
     * Mandatory extra containing the {@link Tag} that was discovered for the
     * {@link #ACTION_NDEF_DISCOVERED}, {@link #ACTION_TECH_DISCOVERED}, and
     * {@link #ACTION_TAG_DISCOVERED} intents.
     */
    public static final String EXTRA_TAG = "android.nfc.extra.TAG";

    /**
     * Extra containing an array of {@link NdefMessage} present on the discovered tag.<p>
     * This extra is mandatory for {@link #ACTION_NDEF_DISCOVERED} intents,
     * and optional for {@link #ACTION_TECH_DISCOVERED}, and
     * {@link #ACTION_TAG_DISCOVERED} intents.<p>
     * When this extra is present there will always be at least one
     * {@link NdefMessage} element. Most NDEF tags have only one NDEF message,
     * but we use an array for future compatibility.
     */
    public static final String EXTRA_NDEF_MESSAGES = "android.nfc.extra.NDEF_MESSAGES";

    /**
     * Optional extra containing a byte array containing the ID of the discovered tag for
     * the {@link #ACTION_NDEF_DISCOVERED}, {@link #ACTION_TECH_DISCOVERED}, and
     * {@link #ACTION_TAG_DISCOVERED} intents.
     */
    public static final String EXTRA_ID = "android.nfc.extra.ID";

    /**
     * Broadcast Action: The state of the local NFC adapter has been
     * changed.
     * <p>For example, NFC has been turned on or off.
     * <p>Always contains the extra field {@link #EXTRA_ADAPTER_STATE}
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_ADAPTER_STATE_CHANGED =
            "android.nfc.action.ADAPTER_STATE_CHANGED";

    /**
     * Used as an int extra field in {@link #ACTION_ADAPTER_STATE_CHANGED}
     * intents to request the current power state. Possible values are:
     * {@link #STATE_OFF},
     * {@link #STATE_TURNING_ON},
     * {@link #STATE_ON},
     * {@link #STATE_TURNING_OFF},
     */
    public static final String EXTRA_ADAPTER_STATE = "android.nfc.extra.ADAPTER_STATE";

    /**
     * Mandatory byte[] extra field in {@link #ACTION_TRANSACTION_DETECTED}
     */
    public static final String EXTRA_AID = "android.nfc.extra.AID";

    /**
     * Optional byte[] extra field in {@link #ACTION_TRANSACTION_DETECTED}
     */
    public static final String EXTRA_DATA = "android.nfc.extra.DATA";

    /**
     * Mandatory String extra field in {@link #ACTION_TRANSACTION_DETECTED}
     * Indicates the Secure Element on which the transaction occurred.
     * eSE1...eSEn for Embedded Secure Elements, SIM1...SIMn for UICC, etc.
     */
    public static final String EXTRA_SECURE_ELEMENT_NAME = "android.nfc.extra.SECURE_ELEMENT_NAME";

    /**
     * Mandatory String extra field in {@link #ACTION_PREFERRED_PAYMENT_CHANGED}
     * Indicates the condition when trigger this event. Possible values are:
     * {@link #PREFERRED_PAYMENT_LOADED},
     * {@link #PREFERRED_PAYMENT_CHANGED},
     * {@link #PREFERRED_PAYMENT_UPDATED},
     */
    public static final String EXTRA_PREFERRED_PAYMENT_CHANGED_REASON =
            "android.nfc.extra.PREFERRED_PAYMENT_CHANGED_REASON";
    /**
     * Nfc is enabled and the preferred payment aids are registered.
     */
    public static final int PREFERRED_PAYMENT_LOADED = 1;
    /**
     * User selected another payment application as the preferred payment.
     */
    public static final int PREFERRED_PAYMENT_CHANGED = 2;
    /**
     * Current preferred payment has issued an update (registered/unregistered new aids or has been
     * updated itself).
     */
    public static final int PREFERRED_PAYMENT_UPDATED = 3;

    public static final int STATE_OFF = 1;
    public static final int STATE_TURNING_ON = 2;
    public static final int STATE_ON = 3;
    public static final int STATE_TURNING_OFF = 4;

    /**
     * Flag for use with {@link #enableReaderMode(Activity, ReaderCallback, int, Bundle)}.
     * <p>
     * Setting this flag enables polling for Nfc-A technology.
     */
    public static final int FLAG_READER_NFC_A = 0x1;

    /**
     * Flag for use with {@link #enableReaderMode(Activity, ReaderCallback, int, Bundle)}.
     * <p>
     * Setting this flag enables polling for Nfc-B technology.
     */
    public static final int FLAG_READER_NFC_B = 0x2;

    /**
     * Flag for use with {@link #enableReaderMode(Activity, ReaderCallback, int, Bundle)}.
     * <p>
     * Setting this flag enables polling for Nfc-F technology.
     */
    public static final int FLAG_READER_NFC_F = 0x4;

    /**
     * Flag for use with {@link #enableReaderMode(Activity, ReaderCallback, int, Bundle)}.
     * <p>
     * Setting this flag enables polling for Nfc-V (ISO15693) technology.
     */
    public static final int FLAG_READER_NFC_V = 0x8;

    /**
     * Flag for use with {@link #enableReaderMode(Activity, ReaderCallback, int, Bundle)}.
     * <p>
     * Setting this flag enables polling for NfcBarcode technology.
     */
    public static final int FLAG_READER_NFC_BARCODE = 0x10;

    /**
     * Flag for use with {@link #enableReaderMode(Activity, ReaderCallback, int, Bundle)}.
     * <p>
     * Setting this flag allows the caller to prevent the
     * platform from performing an NDEF check on the tags it
     * finds.
     */
    public static final int FLAG_READER_SKIP_NDEF_CHECK = 0x80;

    /**
     * Flag for use with {@link #enableReaderMode(Activity, ReaderCallback, int, Bundle)}.
     * <p>
     * Setting this flag allows the caller to prevent the
     * platform from playing sounds when it discovers a tag.
     */
    public static final int FLAG_READER_NO_PLATFORM_SOUNDS = 0x100;

    /**
     * Int Extra for use with {@link #enableReaderMode(Activity, ReaderCallback, int, Bundle)}.
     * <p>
     * Setting this integer extra allows the calling application to specify
     * the delay that the platform will use for performing presence checks
     * on any discovered tag.
     */
    public static final String EXTRA_READER_PRESENCE_CHECK_DELAY = "presence";

    /** @hide */
    @SystemApi
    public static final int FLAG_NDEF_PUSH_NO_CONFIRM = 0x1;

    /** @hide */
    public static final String ACTION_HANDOVER_TRANSFER_STARTED =
            "android.nfc.action.HANDOVER_TRANSFER_STARTED";

    /** @hide */
    public static final String ACTION_HANDOVER_TRANSFER_DONE =
            "android.nfc.action.HANDOVER_TRANSFER_DONE";

    /** @hide */
    public static final String EXTRA_HANDOVER_TRANSFER_STATUS =
            "android.nfc.extra.HANDOVER_TRANSFER_STATUS";

    /** @hide */
    public static final int HANDOVER_TRANSFER_STATUS_SUCCESS = 0;
    /** @hide */
    public static final int HANDOVER_TRANSFER_STATUS_FAILURE = 1;

    /** @hide */
    public static final String EXTRA_HANDOVER_TRANSFER_URI =
            "android.nfc.extra.HANDOVER_TRANSFER_URI";

    /**
     * Broadcast Action: Notify possible NFC transaction blocked because device is locked.
     * <p>An external NFC field detected when device locked and SecureNfc enabled.
     * @hide
     */
    public static final String ACTION_REQUIRE_UNLOCK_FOR_NFC =
            "android.nfc.action.REQUIRE_UNLOCK_FOR_NFC";

    // Guarded by NfcAdapter.class
    static boolean sIsInitialized = false;
    static boolean sHasNfcFeature;
    static boolean sHasBeamFeature;

    // Final after first constructor, except for
    // attemptDeadServiceRecovery() when NFC crashes - we accept a best effort
    // recovery
    @UnsupportedAppUsage
    static INfcAdapter sService;
    static INfcTag sTagService;
    static INfcCardEmulation sCardEmulationService;
    static INfcFCardEmulation sNfcFCardEmulationService;

    /**
     * The NfcAdapter object for each application context.
     * There is a 1-1 relationship between application context and
     * NfcAdapter object.
     */
    static HashMap<Context, NfcAdapter> sNfcAdapters = new HashMap(); //guard by NfcAdapter.class

    /**
     * NfcAdapter used with a null context. This ctor was deprecated but we have
     * to support it for backwards compatibility. New methods that require context
     * might throw when called on the null-context NfcAdapter.
     */
    static NfcAdapter sNullContextNfcAdapter;  // protected by NfcAdapter.class

    final NfcActivityManager mNfcActivityManager;
    final Context mContext;
    final HashMap<NfcUnlockHandler, INfcUnlockHandler> mNfcUnlockHandlers;
    final Object mLock;

    ITagRemovedCallback mTagRemovedListener; // protected by mLock

    /**
     * A callback to be invoked when the system finds a tag while the foreground activity is
     * operating in reader mode.
     * <p>Register your {@code ReaderCallback} implementation with {@link
     * NfcAdapter#enableReaderMode} and disable it with {@link
     * NfcAdapter#disableReaderMode}.
     * @see NfcAdapter#enableReaderMode
     */
    public interface ReaderCallback {
        public void onTagDiscovered(Tag tag);
    }

    /**
     * A callback to be invoked when NFC controller always on state changes.
     * <p>Register your {@code ControllerAlwaysOnStateCallback} implementation with {@link
     * NfcAdapter#registerControllerAlwaysOnStateCallback} and disable it with {@link
     * NfcAdapter#unregisterControllerAlwaysOnStateCallback}.
     * @see #registerControllerAlwaysOnStateCallback
     * @hide
     */
    @SystemApi
    public interface ControllerAlwaysOnStateCallback {
        /**
         * Called on NFC controller always on state changes
         */
        void onStateChanged(boolean isEnabled);
    }

    /**
     * A callback to be invoked when the system successfully delivers your {@link NdefMessage}
     * to another device.
     * @see #setOnNdefPushCompleteCallback
     * @deprecated this feature is deprecated. File sharing can work using other technology like
     * Bluetooth.
     */
    @java.lang.Deprecated
    public interface OnNdefPushCompleteCallback {
        /**
         * Called on successful NDEF push.
         *
         * <p>This callback is usually made on a binder thread (not the UI thread).
         *
         * @param event {@link NfcEvent} with the {@link NfcEvent#nfcAdapter} field set
         * @see #setNdefPushMessageCallback
         */
        public void onNdefPushComplete(NfcEvent event);
    }

    /**
     * A callback to be invoked when another NFC device capable of NDEF push (Android Beam)
     * is within range.
     * <p>Implement this interface and pass it to {@link
     * NfcAdapter#setNdefPushMessageCallback setNdefPushMessageCallback()} in order to create an
     * {@link NdefMessage} at the moment that another device is within range for NFC. Using this
     * callback allows you to create a message with data that might vary based on the
     * content currently visible to the user. Alternatively, you can call {@link
     * #setNdefPushMessage setNdefPushMessage()} if the {@link NdefMessage} always contains the
     * same data.
     * @deprecated this feature is deprecated. File sharing can work using other technology like
     * Bluetooth.
     */
    @java.lang.Deprecated
    public interface CreateNdefMessageCallback {
        /**
         * Called to provide a {@link NdefMessage} to push.
         *
         * <p>This callback is usually made on a binder thread (not the UI thread).
         *
         * <p>Called when this device is in range of another device
         * that might support NDEF push. It allows the application to
         * create the NDEF message only when it is required.
         *
         * <p>NDEF push cannot occur until this method returns, so do not
         * block for too long.
         *
         * <p>The Android operating system will usually show a system UI
         * on top of your activity during this time, so do not try to request
         * input from the user to complete the callback, or provide custom NDEF
         * push UI. The user probably will not see it.
         *
         * @param event {@link NfcEvent} with the {@link NfcEvent#nfcAdapter} field set
         * @return NDEF message to push, or null to not provide a message
         */
        public NdefMessage createNdefMessage(NfcEvent event);
    }


     /**
     * @deprecated this feature is deprecated. File sharing can work using other technology like
     * Bluetooth.
     */
    @java.lang.Deprecated
    public interface CreateBeamUrisCallback {
        public Uri[] createBeamUris(NfcEvent event);
    }

    /**
     * A callback that is invoked when a tag is removed from the field.
     * @see NfcAdapter#ignore
     */
    public interface OnTagRemovedListener {
        void onTagRemoved();
    }

    /**
     * A callback to be invoked when an application has registered as a
     * handler to unlock the device given an NFC tag at the lockscreen.
     * @hide
     */
    @SystemApi
    public interface NfcUnlockHandler {
        /**
         * Called at the lock screen to attempt to unlock the device with the given tag.
         * @param tag the detected tag, to be used to unlock the device
         * @return true if the device was successfully unlocked
         */
        public boolean onUnlockAttempted(Tag tag);
    }

    /**
     * Helper to check if this device has FEATURE_NFC_BEAM, but without using
     * a context.
     * Equivalent to
     * context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_NFC_BEAM)
     */
    private static boolean hasBeamFeature() {
        IPackageManager pm = ActivityThread.getPackageManager();
        if (pm == null) {
            Log.e(TAG, "Cannot get package manager, assuming no Android Beam feature");
            return false;
        }
        try {
            return pm.hasSystemFeature(PackageManager.FEATURE_NFC_BEAM, 0);
        } catch (RemoteException e) {
            Log.e(TAG, "Package manager query failed, assuming no Android Beam feature", e);
            return false;
        }
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
            return pm.hasSystemFeature(PackageManager.FEATURE_NFC, 0);
        } catch (RemoteException e) {
            Log.e(TAG, "Package manager query failed, assuming no NFC feature", e);
            return false;
        }
    }

    /**
     * Helper to check if this device is NFC HCE capable, by checking for
     * FEATURE_NFC_HOST_CARD_EMULATION and/or FEATURE_NFC_HOST_CARD_EMULATION_NFCF,
     * but without using a context.
     */
    private static boolean hasNfcHceFeature() {
        IPackageManager pm = ActivityThread.getPackageManager();
        if (pm == null) {
            Log.e(TAG, "Cannot get package manager, assuming no NFC feature");
            return false;
        }
        try {
            return pm.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION, 0)
                || pm.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION_NFCF, 0);
        } catch (RemoteException e) {
            Log.e(TAG, "Package manager query failed, assuming no NFC feature", e);
            return false;
        }
    }

    /**
     * Return list of Secure Elements which support off host card emulation.
     *
     * @return List<String> containing secure elements on the device which supports
     *                      off host card emulation. eSE for Embedded secure element,
     *                      SIM for UICC and so on.
     * @hide
     */
    public @NonNull List<String> getSupportedOffHostSecureElements() {
        List<String> offHostSE = new ArrayList<String>();
        IPackageManager pm = ActivityThread.getPackageManager();
        if (pm == null) {
            Log.e(TAG, "Cannot get package manager, assuming no off-host CE feature");
            return offHostSE;
        }
        try {
            if (pm.hasSystemFeature(PackageManager.FEATURE_NFC_OFF_HOST_CARD_EMULATION_UICC, 0)) {
                offHostSE.add("SIM");
            }
            if (pm.hasSystemFeature(PackageManager.FEATURE_NFC_OFF_HOST_CARD_EMULATION_ESE, 0)) {
                offHostSE.add("eSE");
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Package manager query failed, assuming no off-host CE feature", e);
            offHostSE.clear();
            return offHostSE;
        }
        return offHostSE;
    }

    /**
     * Returns the NfcAdapter for application context,
     * or throws if NFC is not available.
     * @hide
     */
    @UnsupportedAppUsage
    public static synchronized NfcAdapter getNfcAdapter(Context context) {
        if (!sIsInitialized) {
            sHasNfcFeature = hasNfcFeature();
            sHasBeamFeature = hasBeamFeature();
            boolean hasHceFeature = hasNfcHceFeature();
            /* is this device meant to have NFC */
            if (!sHasNfcFeature && !hasHceFeature) {
                Log.v(TAG, "this device does not have NFC support");
                throw new UnsupportedOperationException();
            }
            sService = getServiceInterface();
            if (sService == null) {
                Log.e(TAG, "could not retrieve NFC service");
                throw new UnsupportedOperationException();
            }
            if (sHasNfcFeature) {
                try {
                    sTagService = sService.getNfcTagInterface();
                } catch (RemoteException e) {
                    Log.e(TAG, "could not retrieve NFC Tag service");
                    throw new UnsupportedOperationException();
                }
            }
            if (hasHceFeature) {
                try {
                    sNfcFCardEmulationService = sService.getNfcFCardEmulationInterface();
                } catch (RemoteException e) {
                    Log.e(TAG, "could not retrieve NFC-F card emulation service");
                    throw new UnsupportedOperationException();
                }
                try {
                    sCardEmulationService = sService.getNfcCardEmulationInterface();
                } catch (RemoteException e) {
                    Log.e(TAG, "could not retrieve card emulation service");
                    throw new UnsupportedOperationException();
                }
            }

            sIsInitialized = true;
        }
        if (context == null) {
            if (sNullContextNfcAdapter == null) {
                sNullContextNfcAdapter = new NfcAdapter(null);
            }
            return sNullContextNfcAdapter;
        }
        NfcAdapter adapter = sNfcAdapters.get(context);
        if (adapter == null) {
            adapter = new NfcAdapter(context);
            sNfcAdapters.put(context, adapter);
        }
        return adapter;
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
     * <pre>
     * NfcManager manager = (NfcManager) context.getSystemService(Context.NFC_SERVICE);
     * NfcAdapter adapter = manager.getDefaultAdapter();</pre>
     * @param context the calling application's context
     *
     * @return the default NFC adapter, or null if no NFC adapter exists
     */
    public static NfcAdapter getDefaultAdapter(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context cannot be null");
        }
        context = context.getApplicationContext();
        if (context == null) {
            throw new IllegalArgumentException(
                    "context not associated with any application (using a mock context?)");
        }

        if (getServiceInterface() == null) {
            // NFC is not available
            return null;
        }

        /* use getSystemService() for consistency */
        NfcManager manager = (NfcManager) context.getSystemService(Context.NFC_SERVICE);
        if (manager == null) {
            // NFC not available
            return null;
        }
        return manager.getDefaultAdapter();
    }

    /**
     * Legacy NfcAdapter getter, always use {@link #getDefaultAdapter(Context)} instead.<p>
     * This method was deprecated at API level 10 (Gingerbread MR1) because a context is required
     * for many NFC API methods. Those methods will fail when called on an NfcAdapter
     * object created from this method.<p>
     * @deprecated use {@link #getDefaultAdapter(Context)}
     * @hide
     */
    @Deprecated
    @UnsupportedAppUsage
    public static NfcAdapter getDefaultAdapter() {
        // introduced in API version 9 (GB 2.3)
        // deprecated in API version 10 (GB 2.3.3)
        // removed from public API in version 16 (ICS MR2)
        // should maintain as a hidden API for binary compatibility for a little longer
        Log.w(TAG, "WARNING: NfcAdapter.getDefaultAdapter() is deprecated, use " +
                "NfcAdapter.getDefaultAdapter(Context) instead", new Exception());

        return NfcAdapter.getNfcAdapter(null);
    }

    NfcAdapter(Context context) {
        mContext = context;
        mNfcActivityManager = new NfcActivityManager(this);
        mNfcUnlockHandlers = new HashMap<NfcUnlockHandler, INfcUnlockHandler>();
        mTagRemovedListener = null;
        mLock = new Object();
        mControllerAlwaysOnStateListener = new NfcControllerAlwaysOnStateListener(getService());
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage
    public Context getContext() {
        return mContext;
    }

    /**
     * Returns the binder interface to the service.
     * @hide
     */
    @UnsupportedAppUsage
    public INfcAdapter getService() {
        isEnabled();  // NOP call to recover sService if it is stale
        return sService;
    }

    /**
     * Returns the binder interface to the tag service.
     * @hide
     */
    public INfcTag getTagService() {
        isEnabled();  // NOP call to recover sTagService if it is stale
        return sTagService;
    }

    /**
     * Returns the binder interface to the card emulation service.
     * @hide
     */
    public INfcCardEmulation getCardEmulationService() {
        isEnabled();
        return sCardEmulationService;
    }

    /**
     * Returns the binder interface to the NFC-F card emulation service.
     * @hide
     */
    public INfcFCardEmulation getNfcFCardEmulationService() {
        isEnabled();
        return sNfcFCardEmulationService;
    }

    /**
     * Returns the binder interface to the NFC-DTA test interface.
     * @hide
     */
    public INfcDta getNfcDtaInterface() {
        if (mContext == null) {
            throw new UnsupportedOperationException("You need a context on NfcAdapter to use the "
                    + " NFC extras APIs");
        }
        try {
            return sService.getNfcDtaInterface(mContext.getPackageName());
        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
            // Try one more time
            if (sService == null) {
                Log.e(TAG, "Failed to recover NFC Service.");
                return null;
            }
            try {
                return sService.getNfcDtaInterface(mContext.getPackageName());
            } catch (RemoteException ee) {
                Log.e(TAG, "Failed to recover NFC Service.");
            }
            return null;
        }
    }

    /**
     * NFC service dead - attempt best effort recovery
     * @hide
     */
    @UnsupportedAppUsage
    public void attemptDeadServiceRecovery(Exception e) {
        Log.e(TAG, "NFC service dead - attempting to recover", e);
        INfcAdapter service = getServiceInterface();
        if (service == null) {
            Log.e(TAG, "could not retrieve NFC service during service recovery");
            // nothing more can be done now, sService is still stale, we'll hit
            // this recovery path again later
            return;
        }
        // assigning to sService is not thread-safe, but this is best-effort code
        // and on a well-behaved system should never happen
        sService = service;
        try {
            sTagService = service.getNfcTagInterface();
        } catch (RemoteException ee) {
            Log.e(TAG, "could not retrieve NFC tag service during service recovery");
            // nothing more can be done now, sService is still stale, we'll hit
            // this recovery path again later
            return;
        }

        try {
            sCardEmulationService = service.getNfcCardEmulationInterface();
        } catch (RemoteException ee) {
            Log.e(TAG, "could not retrieve NFC card emulation service during service recovery");
        }

        try {
            sNfcFCardEmulationService = service.getNfcFCardEmulationInterface();
        } catch (RemoteException ee) {
            Log.e(TAG, "could not retrieve NFC-F card emulation service during service recovery");
        }

        return;
    }

    /**
     * Return true if this NFC Adapter has any features enabled.
     *
     * <p>If this method returns false, the NFC hardware is guaranteed not to
     * generate or respond to any NFC communication over its NFC radio.
     * <p>Applications can use this to check if NFC is enabled. Applications
     * can request Settings UI allowing the user to toggle NFC using:
     * <p><pre>startActivity(new Intent(Settings.ACTION_NFC_SETTINGS))</pre>
     *
     * @see android.provider.Settings#ACTION_NFC_SETTINGS
     * @return true if this NFC Adapter has any features enabled
     */
    public boolean isEnabled() {
        try {
            return sService.getState() == STATE_ON;
        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
            // Try one more time
            if (sService == null) {
                Log.e(TAG, "Failed to recover NFC Service.");
                return false;
            }
            try {
                return sService.getState() == STATE_ON;
            } catch (RemoteException ee) {
                Log.e(TAG, "Failed to recover NFC Service.");
            }
            return false;
        }
    }

    /**
     * Return the state of this NFC Adapter.
     *
     * <p>Returns one of {@link #STATE_ON}, {@link #STATE_TURNING_ON},
     * {@link #STATE_OFF}, {@link #STATE_TURNING_OFF}.
     *
     * <p>{@link #isEnabled()} is equivalent to
     * <code>{@link #getAdapterState()} == {@link #STATE_ON}</code>
     *
     * @return the current state of this NFC adapter
     *
     * @hide
     */
    @UnsupportedAppUsage
    public int getAdapterState() {
        try {
            return sService.getState();
        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
            // Try one more time
            if (sService == null) {
                Log.e(TAG, "Failed to recover NFC Service.");
                return NfcAdapter.STATE_OFF;
            }
            try {
                return sService.getState();
            } catch (RemoteException ee) {
                Log.e(TAG, "Failed to recover NFC Service.");
            }
            return NfcAdapter.STATE_OFF;
        }
    }

    /**
     * Enable NFC hardware.
     *
     * <p>This call is asynchronous. Listen for
     * {@link #ACTION_ADAPTER_STATE_CHANGED} broadcasts to find out when the
     * operation is complete.
     *
     * <p>If this returns true, then either NFC is already on, or
     * a {@link #ACTION_ADAPTER_STATE_CHANGED} broadcast will be sent
     * to indicate a state transition. If this returns false, then
     * there is some problem that prevents an attempt to turn
     * NFC on (for example we are in airplane mode and NFC is not
     * toggleable in airplane mode on this platform).
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
    public boolean enable() {
        try {
            return sService.enable();
        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
            // Try one more time
            if (sService == null) {
                Log.e(TAG, "Failed to recover NFC Service.");
                return false;
            }
            try {
                return sService.enable();
            } catch (RemoteException ee) {
                Log.e(TAG, "Failed to recover NFC Service.");
            }
            return false;
        }
    }

    /**
     * Disable NFC hardware.
     *
     * <p>No NFC features will work after this call, and the hardware
     * will not perform or respond to any NFC communication.
     *
     * <p>This call is asynchronous. Listen for
     * {@link #ACTION_ADAPTER_STATE_CHANGED} broadcasts to find out when the
     * operation is complete.
     *
     * <p>If this returns true, then either NFC is already off, or
     * a {@link #ACTION_ADAPTER_STATE_CHANGED} broadcast will be sent
     * to indicate a state transition. If this returns false, then
     * there is some problem that prevents an attempt to turn
     * NFC off.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
    public boolean disable() {
        try {
            return sService.disable(true);
        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
            // Try one more time
            if (sService == null) {
                Log.e(TAG, "Failed to recover NFC Service.");
                return false;
            }
            try {
                return sService.disable(true);
            } catch (RemoteException ee) {
                Log.e(TAG, "Failed to recover NFC Service.");
            }
            return false;
        }
    }

    /**
     * Disable NFC hardware.
     * @hide
    */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
    public boolean disable(boolean persist) {
        try {
            return sService.disable(persist);
        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
            // Try one more time
            if (sService == null) {
                Log.e(TAG, "Failed to recover NFC Service.");
                return false;
            }
            try {
                return sService.disable(persist);
            } catch (RemoteException ee) {
                Log.e(TAG, "Failed to recover NFC Service.");
            }
            return false;
        }
    }

    /**
     * Pauses polling for a {@code timeoutInMs} millis. If polling must be resumed before timeout,
     * use {@link #resumePolling()}.
     * @hide
     */
    public void pausePolling(int timeoutInMs) {
        try {
            sService.pausePolling(timeoutInMs);
        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
        }
    }

    /**
     * Resumes default polling for the current device state if polling is paused. Calling
     * this while polling is not paused is a no-op.
     *
     * @hide
     */
    public void resumePolling() {
        try {
            sService.resumePolling();
        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
        }
    }

    /**
     * Set one or more {@link Uri}s to send using Android Beam (TM). Every
     * Uri you provide must have either scheme 'file' or scheme 'content'.
     *
     * <p>For the data provided through this method, Android Beam tries to
     * switch to alternate transports such as Bluetooth to achieve a fast
     * transfer speed. Hence this method is very suitable
     * for transferring large files such as pictures or songs.
     *
     * <p>The receiving side will store the content of each Uri in
     * a file and present a notification to the user to open the file
     * with a {@link android.content.Intent} with action
     * {@link android.content.Intent#ACTION_VIEW}.
     * If multiple URIs are sent, the {@link android.content.Intent} will refer
     * to the first of the stored files.
     *
     * <p>This method may be called at any time before {@link Activity#onDestroy},
     * but the URI(s) are only made available for Android Beam when the
     * specified activity(s) are in resumed (foreground) state. The recommended
     * approach is to call this method during your Activity's
     * {@link Activity#onCreate} - see sample
     * code below. This method does not immediately perform any I/O or blocking work,
     * so is safe to call on your main thread.
     *
     * <p>{@link #setBeamPushUris} and {@link #setBeamPushUrisCallback}
     * have priority over both {@link #setNdefPushMessage} and
     * {@link #setNdefPushMessageCallback}.
     *
     * <p>If {@link #setBeamPushUris} is called with a null Uri array,
     * and/or {@link #setBeamPushUrisCallback} is called with a null callback,
     * then the Uri push will be completely disabled for the specified activity(s).
     *
     * <p>Code example:
     * <pre>
     * protected void onCreate(Bundle savedInstanceState) {
     *     super.onCreate(savedInstanceState);
     *     NfcAdapter nfcAdapter = NfcAdapter.getDefaultAdapter(this);
     *     if (nfcAdapter == null) return;  // NFC not available on this device
     *     nfcAdapter.setBeamPushUris(new Uri[] {uri1, uri2}, this);
     * }</pre>
     * And that is it. Only one call per activity is necessary. The Android
     * OS will automatically release its references to the Uri(s) and the
     * Activity object when it is destroyed if you follow this pattern.
     *
     * <p>If your Activity wants to dynamically supply Uri(s),
     * then set a callback using {@link #setBeamPushUrisCallback} instead
     * of using this method.
     *
     * <p class="note">Do not pass in an Activity that has already been through
     * {@link Activity#onDestroy}. This is guaranteed if you call this API
     * during {@link Activity#onCreate}.
     *
     * <p class="note">If this device does not support alternate transports
     * such as Bluetooth or WiFI, calling this method does nothing.
     *
     * <p class="note">Requires the {@link android.Manifest.permission#NFC} permission.
     *
     * @param uris an array of Uri(s) to push over Android Beam
     * @param activity activity for which the Uri(s) will be pushed
     * @throws UnsupportedOperationException if FEATURE_NFC is unavailable.
     * @deprecated this feature is deprecated. File sharing can work using other technology like
     * Bluetooth.
     */
    @java.lang.Deprecated
    public void setBeamPushUris(Uri[] uris, Activity activity) {
        synchronized (NfcAdapter.class) {
            if (!sHasNfcFeature) {
                throw new UnsupportedOperationException();
            }
            if (!sHasBeamFeature) {
                return;
            }
        }
        if (activity == null) {
            throw new NullPointerException("activity cannot be null");
        }
        if (uris != null) {
            for (Uri uri : uris) {
                if (uri == null) throw new NullPointerException("Uri not " +
                        "allowed to be null");
                String scheme = uri.getScheme();
                if (scheme == null || (!scheme.equalsIgnoreCase("file") &&
                        !scheme.equalsIgnoreCase("content"))) {
                    throw new IllegalArgumentException("URI needs to have " +
                            "either scheme file or scheme content");
                }
            }
        }
        mNfcActivityManager.setNdefPushContentUri(activity, uris);
    }

    /**
     * Set a callback that will dynamically generate one or more {@link Uri}s
     * to send using Android Beam (TM). Every Uri the callback provides
     * must have either scheme 'file' or scheme 'content'.
     *
     * <p>For the data provided through this callback, Android Beam tries to
     * switch to alternate transports such as Bluetooth to achieve a fast
     * transfer speed. Hence this method is very suitable
     * for transferring large files such as pictures or songs.
     *
     * <p>The receiving side will store the content of each Uri in
     * a file and present a notification to the user to open the file
     * with a {@link android.content.Intent} with action
     * {@link android.content.Intent#ACTION_VIEW}.
     * If multiple URIs are sent, the {@link android.content.Intent} will refer
     * to the first of the stored files.
     *
     * <p>This method may be called at any time before {@link Activity#onDestroy},
     * but the URI(s) are only made available for Android Beam when the
     * specified activity(s) are in resumed (foreground) state. The recommended
     * approach is to call this method during your Activity's
     * {@link Activity#onCreate} - see sample
     * code below. This method does not immediately perform any I/O or blocking work,
     * so is safe to call on your main thread.
     *
     * <p>{@link #setBeamPushUris} and {@link #setBeamPushUrisCallback}
     * have priority over both {@link #setNdefPushMessage} and
     * {@link #setNdefPushMessageCallback}.
     *
     * <p>If {@link #setBeamPushUris} is called with a null Uri array,
     * and/or {@link #setBeamPushUrisCallback} is called with a null callback,
     * then the Uri push will be completely disabled for the specified activity(s).
     *
     * <p>Code example:
     * <pre>
     * protected void onCreate(Bundle savedInstanceState) {
     *     super.onCreate(savedInstanceState);
     *     NfcAdapter nfcAdapter = NfcAdapter.getDefaultAdapter(this);
     *     if (nfcAdapter == null) return;  // NFC not available on this device
     *     nfcAdapter.setBeamPushUrisCallback(callback, this);
     * }</pre>
     * And that is it. Only one call per activity is necessary. The Android
     * OS will automatically release its references to the Uri(s) and the
     * Activity object when it is destroyed if you follow this pattern.
     *
     * <p class="note">Do not pass in an Activity that has already been through
     * {@link Activity#onDestroy}. This is guaranteed if you call this API
     * during {@link Activity#onCreate}.
     *
     * <p class="note">If this device does not support alternate transports
     * such as Bluetooth or WiFI, calling this method does nothing.
     *
     * <p class="note">Requires the {@link android.Manifest.permission#NFC} permission.
     *
     * @param callback callback, or null to disable
     * @param activity activity for which the Uri(s) will be pushed
     * @throws UnsupportedOperationException if FEATURE_NFC is unavailable.
     * @deprecated this feature is deprecated. File sharing can work using other technology like
     * Bluetooth.
     */
    @java.lang.Deprecated
    public void setBeamPushUrisCallback(CreateBeamUrisCallback callback, Activity activity) {
        synchronized (NfcAdapter.class) {
            if (!sHasNfcFeature) {
                throw new UnsupportedOperationException();
            }
            if (!sHasBeamFeature) {
                return;
            }
        }
        if (activity == null) {
            throw new NullPointerException("activity cannot be null");
        }
        mNfcActivityManager.setNdefPushContentUriCallback(activity, callback);
    }

    /**
     * Set a static {@link NdefMessage} to send using Android Beam (TM).
     *
     * <p>This method may be called at any time before {@link Activity#onDestroy},
     * but the NDEF message is only made available for NDEF push when the
     * specified activity(s) are in resumed (foreground) state. The recommended
     * approach is to call this method during your Activity's
     * {@link Activity#onCreate} - see sample
     * code below. This method does not immediately perform any I/O or blocking work,
     * so is safe to call on your main thread.
     *
     * <p>Only one NDEF message can be pushed by the currently resumed activity.
     * If both {@link #setNdefPushMessage} and
     * {@link #setNdefPushMessageCallback} are set, then
     * the callback will take priority.
     *
     * <p>If neither {@link #setNdefPushMessage} or
     * {@link #setNdefPushMessageCallback} have been called for your activity, then
     * the Android OS may choose to send a default NDEF message on your behalf,
     * such as a URI for your application.
     *
     * <p>If {@link #setNdefPushMessage} is called with a null NDEF message,
     * and/or {@link #setNdefPushMessageCallback} is called with a null callback,
     * then NDEF push will be completely disabled for the specified activity(s).
     * This also disables any default NDEF message the Android OS would have
     * otherwise sent on your behalf for those activity(s).
     *
     * <p>If you want to prevent the Android OS from sending default NDEF
     * messages completely (for all activities), you can include a
     * {@code <meta-data>} element inside the {@code <application>}
     * element of your AndroidManifest.xml file, like this:
     * <pre>
     * &lt;application ...>
     *     &lt;meta-data android:name="android.nfc.disable_beam_default"
     *         android:value="true" />
     * &lt;/application></pre>
     *
     * <p>The API allows for multiple activities to be specified at a time,
     * but it is strongly recommended to just register one at a time,
     * and to do so during the activity's {@link Activity#onCreate}. For example:
     * <pre>
     * protected void onCreate(Bundle savedInstanceState) {
     *     super.onCreate(savedInstanceState);
     *     NfcAdapter nfcAdapter = NfcAdapter.getDefaultAdapter(this);
     *     if (nfcAdapter == null) return;  // NFC not available on this device
     *     nfcAdapter.setNdefPushMessage(ndefMessage, this);
     * }</pre>
     * And that is it. Only one call per activity is necessary. The Android
     * OS will automatically release its references to the NDEF message and the
     * Activity object when it is destroyed if you follow this pattern.
     *
     * <p>If your Activity wants to dynamically generate an NDEF message,
     * then set a callback using {@link #setNdefPushMessageCallback} instead
     * of a static message.
     *
     * <p class="note">Do not pass in an Activity that has already been through
     * {@link Activity#onDestroy}. This is guaranteed if you call this API
     * during {@link Activity#onCreate}.
     *
     * <p class="note">For sending large content such as pictures and songs,
     * consider using {@link #setBeamPushUris}, which switches to alternate transports
     * such as Bluetooth to achieve a fast transfer rate.
     *
     * <p class="note">Requires the {@link android.Manifest.permission#NFC} permission.
     *
     * @param message NDEF message to push over NFC, or null to disable
     * @param activity activity for which the NDEF message will be pushed
     * @param activities optional additional activities, however we strongly recommend
     *        to only register one at a time, and to do so in that activity's
     *        {@link Activity#onCreate}
     * @throws UnsupportedOperationException if FEATURE_NFC is unavailable.
     * @deprecated this feature is deprecated. File sharing can work using other technology like
     * Bluetooth.
     */
    @java.lang.Deprecated
    public void setNdefPushMessage(NdefMessage message, Activity activity,
            Activity ... activities) {
        synchronized (NfcAdapter.class) {
            if (!sHasNfcFeature) {
                throw new UnsupportedOperationException();
            }
            if (!sHasBeamFeature) {
                return;
            }
        }
        int targetSdkVersion = getSdkVersion();
        try {
            if (activity == null) {
                throw new NullPointerException("activity cannot be null");
            }
            mNfcActivityManager.setNdefPushMessage(activity, message, 0);
            for (Activity a : activities) {
                if (a == null) {
                    throw new NullPointerException("activities cannot contain null");
                }
                mNfcActivityManager.setNdefPushMessage(a, message, 0);
            }
        } catch (IllegalStateException e) {
            if (targetSdkVersion < android.os.Build.VERSION_CODES.JELLY_BEAN) {
                // Less strict on old applications - just log the error
                Log.e(TAG, "Cannot call API with Activity that has already " +
                        "been destroyed", e);
            } else {
                // Prevent new applications from making this mistake, re-throw
                throw(e);
            }
        }
    }

    /**
     * @hide
     */
    @SystemApi
    public void setNdefPushMessage(NdefMessage message, Activity activity, int flags) {
        synchronized (NfcAdapter.class) {
            if (!sHasNfcFeature) {
                throw new UnsupportedOperationException();
            }
        }
        if (activity == null) {
            throw new NullPointerException("activity cannot be null");
        }
        mNfcActivityManager.setNdefPushMessage(activity, message, flags);
    }

    /**
     * Set a callback that dynamically generates NDEF messages to send using Android Beam (TM).
     *
     * <p>This method may be called at any time before {@link Activity#onDestroy},
     * but the NDEF message callback can only occur when the
     * specified activity(s) are in resumed (foreground) state. The recommended
     * approach is to call this method during your Activity's
     * {@link Activity#onCreate} - see sample
     * code below. This method does not immediately perform any I/O or blocking work,
     * so is safe to call on your main thread.
     *
     * <p>Only one NDEF message can be pushed by the currently resumed activity.
     * If both {@link #setNdefPushMessage} and
     * {@link #setNdefPushMessageCallback} are set, then
     * the callback will take priority.
     *
     * <p>If neither {@link #setNdefPushMessage} or
     * {@link #setNdefPushMessageCallback} have been called for your activity, then
     * the Android OS may choose to send a default NDEF message on your behalf,
     * such as a URI for your application.
     *
     * <p>If {@link #setNdefPushMessage} is called with a null NDEF message,
     * and/or {@link #setNdefPushMessageCallback} is called with a null callback,
     * then NDEF push will be completely disabled for the specified activity(s).
     * This also disables any default NDEF message the Android OS would have
     * otherwise sent on your behalf for those activity(s).
     *
     * <p>If you want to prevent the Android OS from sending default NDEF
     * messages completely (for all activities), you can include a
     * {@code <meta-data>} element inside the {@code <application>}
     * element of your AndroidManifest.xml file, like this:
     * <pre>
     * &lt;application ...>
     *     &lt;meta-data android:name="android.nfc.disable_beam_default"
     *         android:value="true" />
     * &lt;/application></pre>
     *
     * <p>The API allows for multiple activities to be specified at a time,
     * but it is strongly recommended to just register one at a time,
     * and to do so during the activity's {@link Activity#onCreate}. For example:
     * <pre>
     * protected void onCreate(Bundle savedInstanceState) {
     *     super.onCreate(savedInstanceState);
     *     NfcAdapter nfcAdapter = NfcAdapter.getDefaultAdapter(this);
     *     if (nfcAdapter == null) return;  // NFC not available on this device
     *     nfcAdapter.setNdefPushMessageCallback(callback, this);
     * }</pre>
     * And that is it. Only one call per activity is necessary. The Android
     * OS will automatically release its references to the callback and the
     * Activity object when it is destroyed if you follow this pattern.
     *
     * <p class="note">Do not pass in an Activity that has already been through
     * {@link Activity#onDestroy}. This is guaranteed if you call this API
     * during {@link Activity#onCreate}.
     * <p class="note">For sending large content such as pictures and songs,
     * consider using {@link #setBeamPushUris}, which switches to alternate transports
     * such as Bluetooth to achieve a fast transfer rate.
     * <p class="note">Requires the {@link android.Manifest.permission#NFC} permission.
     *
     * @param callback callback, or null to disable
     * @param activity activity for which the NDEF message will be pushed
     * @param activities optional additional activities, however we strongly recommend
     *        to only register one at a time, and to do so in that activity's
     *        {@link Activity#onCreate}
     * @throws UnsupportedOperationException if FEATURE_NFC is unavailable.
     * @deprecated this feature is deprecated. File sharing can work using other technology like
     * Bluetooth.
     */
    @java.lang.Deprecated
    public void setNdefPushMessageCallback(CreateNdefMessageCallback callback, Activity activity,
            Activity ... activities) {
        synchronized (NfcAdapter.class) {
            if (!sHasNfcFeature) {
                throw new UnsupportedOperationException();
            }
            if (!sHasBeamFeature) {
                return;
            }
        }
        int targetSdkVersion = getSdkVersion();
        try {
            if (activity == null) {
                throw new NullPointerException("activity cannot be null");
            }
            mNfcActivityManager.setNdefPushMessageCallback(activity, callback, 0);
            for (Activity a : activities) {
                if (a == null) {
                    throw new NullPointerException("activities cannot contain null");
                }
                mNfcActivityManager.setNdefPushMessageCallback(a, callback, 0);
            }
        } catch (IllegalStateException e) {
            if (targetSdkVersion < android.os.Build.VERSION_CODES.JELLY_BEAN) {
                // Less strict on old applications - just log the error
                Log.e(TAG, "Cannot call API with Activity that has already " +
                        "been destroyed", e);
            } else {
                // Prevent new applications from making this mistake, re-throw
                throw(e);
            }
        }
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage
    public void setNdefPushMessageCallback(CreateNdefMessageCallback callback, Activity activity,
            int flags) {
        if (activity == null) {
            throw new NullPointerException("activity cannot be null");
        }
        mNfcActivityManager.setNdefPushMessageCallback(activity, callback, flags);
    }

    /**
     * Set a callback on successful Android Beam (TM).
     *
     * <p>This method may be called at any time before {@link Activity#onDestroy},
     * but the callback can only occur when the
     * specified activity(s) are in resumed (foreground) state. The recommended
     * approach is to call this method during your Activity's
     * {@link Activity#onCreate} - see sample
     * code below. This method does not immediately perform any I/O or blocking work,
     * so is safe to call on your main thread.
     *
     * <p>The API allows for multiple activities to be specified at a time,
     * but it is strongly recommended to just register one at a time,
     * and to do so during the activity's {@link Activity#onCreate}. For example:
     * <pre>
     * protected void onCreate(Bundle savedInstanceState) {
     *     super.onCreate(savedInstanceState);
     *     NfcAdapter nfcAdapter = NfcAdapter.getDefaultAdapter(this);
     *     if (nfcAdapter == null) return;  // NFC not available on this device
     *     nfcAdapter.setOnNdefPushCompleteCallback(callback, this);
     * }</pre>
     * And that is it. Only one call per activity is necessary. The Android
     * OS will automatically release its references to the callback and the
     * Activity object when it is destroyed if you follow this pattern.
     *
     * <p class="note">Do not pass in an Activity that has already been through
     * {@link Activity#onDestroy}. This is guaranteed if you call this API
     * during {@link Activity#onCreate}.
     *
     * <p class="note">Requires the {@link android.Manifest.permission#NFC} permission.
     *
     * @param callback callback, or null to disable
     * @param activity activity for which the NDEF message will be pushed
     * @param activities optional additional activities, however we strongly recommend
     *        to only register one at a time, and to do so in that activity's
     *        {@link Activity#onCreate}
     * @throws UnsupportedOperationException if FEATURE_NFC is unavailable.
     * @deprecated this feature is deprecated. File sharing can work using other technology like
     * Bluetooth.
     */
    @java.lang.Deprecated
    public void setOnNdefPushCompleteCallback(OnNdefPushCompleteCallback callback,
            Activity activity, Activity ... activities) {
        synchronized (NfcAdapter.class) {
            if (!sHasNfcFeature) {
                throw new UnsupportedOperationException();
            }
            if (!sHasBeamFeature) {
                return;
            }
        }
        int targetSdkVersion = getSdkVersion();
        try {
            if (activity == null) {
                throw new NullPointerException("activity cannot be null");
            }
            mNfcActivityManager.setOnNdefPushCompleteCallback(activity, callback);
            for (Activity a : activities) {
                if (a == null) {
                    throw new NullPointerException("activities cannot contain null");
                }
                mNfcActivityManager.setOnNdefPushCompleteCallback(a, callback);
            }
        } catch (IllegalStateException e) {
            if (targetSdkVersion < android.os.Build.VERSION_CODES.JELLY_BEAN) {
                // Less strict on old applications - just log the error
                Log.e(TAG, "Cannot call API with Activity that has already " +
                        "been destroyed", e);
            } else {
                // Prevent new applications from making this mistake, re-throw
                throw(e);
            }
        }
    }

    /**
     * Enable foreground dispatch to the given Activity.
     *
     * <p>This will give priority to the foreground activity when
     * dispatching a discovered {@link Tag} to an application.
     *
     * <p>If any IntentFilters are provided to this method they are used to match dispatch Intents
     * for both the {@link NfcAdapter#ACTION_NDEF_DISCOVERED} and
     * {@link NfcAdapter#ACTION_TAG_DISCOVERED}. Since {@link NfcAdapter#ACTION_TECH_DISCOVERED}
     * relies on meta data outside of the IntentFilter matching for that dispatch Intent is handled
     * by passing in the tech lists separately. Each first level entry in the tech list represents
     * an array of technologies that must all be present to match. If any of the first level sets
     * match then the dispatch is routed through the given PendingIntent. In other words, the second
     * level is ANDed together and the first level entries are ORed together.
     *
     * <p>If you pass {@code null} for both the {@code filters} and {@code techLists} parameters
     * that acts a wild card and will cause the foreground activity to receive all tags via the
     * {@link NfcAdapter#ACTION_TAG_DISCOVERED} intent.
     *
     * <p>This method must be called from the main thread, and only when the activity is in the
     * foreground (resumed). Also, activities must call {@link #disableForegroundDispatch} before
     * the completion of their {@link Activity#onPause} callback to disable foreground dispatch
     * after it has been enabled.
     *
     * <p class="note">Requires the {@link android.Manifest.permission#NFC} permission.
     *
     * @param activity the Activity to dispatch to
     * @param intent the PendingIntent to start for the dispatch
     * @param filters the IntentFilters to override dispatching for, or null to always dispatch
     * @param techLists the tech lists used to perform matching for dispatching of the
     *      {@link NfcAdapter#ACTION_TECH_DISCOVERED} intent
     * @throws IllegalStateException if the Activity is not currently in the foreground
     * @throws UnsupportedOperationException if FEATURE_NFC is unavailable.
     */
    public void enableForegroundDispatch(Activity activity, PendingIntent intent,
            IntentFilter[] filters, String[][] techLists) {
        synchronized (NfcAdapter.class) {
            if (!sHasNfcFeature) {
                throw new UnsupportedOperationException();
            }
        }
        if (activity == null || intent == null) {
            throw new NullPointerException();
        }
        if (!activity.isResumed()) {
            throw new IllegalStateException("Foreground dispatch can only be enabled " +
                    "when your activity is resumed");
        }
        try {
            TechListParcel parcel = null;
            if (techLists != null && techLists.length > 0) {
                parcel = new TechListParcel(techLists);
            }
            ActivityThread.currentActivityThread().registerOnActivityPausedListener(activity,
                    mForegroundDispatchListener);
            sService.setForegroundDispatch(intent, filters, parcel);
        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
        }
    }

    /**
     * Disable foreground dispatch to the given activity.
     *
     * <p>After calling {@link #enableForegroundDispatch}, an activity
     * must call this method before its {@link Activity#onPause} callback
     * completes.
     *
     * <p>This method must be called from the main thread.
     *
     * <p class="note">Requires the {@link android.Manifest.permission#NFC} permission.
     *
     * @param activity the Activity to disable dispatch to
     * @throws IllegalStateException if the Activity has already been paused
     * @throws UnsupportedOperationException if FEATURE_NFC is unavailable.
     */
    public void disableForegroundDispatch(Activity activity) {
        synchronized (NfcAdapter.class) {
            if (!sHasNfcFeature) {
                throw new UnsupportedOperationException();
            }
        }
        ActivityThread.currentActivityThread().unregisterOnActivityPausedListener(activity,
                mForegroundDispatchListener);
        disableForegroundDispatchInternal(activity, false);
    }

    OnActivityPausedListener mForegroundDispatchListener = new OnActivityPausedListener() {
        @Override
        public void onPaused(Activity activity) {
            disableForegroundDispatchInternal(activity, true);
        }
    };

    void disableForegroundDispatchInternal(Activity activity, boolean force) {
        try {
            sService.setForegroundDispatch(null, null, null);
            if (!force && !activity.isResumed()) {
                throw new IllegalStateException("You must disable foreground dispatching " +
                        "while your activity is still resumed");
            }
        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
        }
    }

    /**
     * Limit the NFC controller to reader mode while this Activity is in the foreground.
     *
     * <p>In this mode the NFC controller will only act as an NFC tag reader/writer,
     * thus disabling any peer-to-peer (Android Beam) and card-emulation modes of
     * the NFC adapter on this device.
     *
     * <p>Use {@link #FLAG_READER_SKIP_NDEF_CHECK} to prevent the platform from
     * performing any NDEF checks in reader mode. Note that this will prevent the
     * {@link Ndef} tag technology from being enumerated on the tag, and that
     * NDEF-based tag dispatch will not be functional.
     *
     * <p>For interacting with tags that are emulated on another Android device
     * using Android's host-based card-emulation, the recommended flags are
     * {@link #FLAG_READER_NFC_A} and {@link #FLAG_READER_SKIP_NDEF_CHECK}.
     *
     * @param activity the Activity that requests the adapter to be in reader mode
     * @param callback the callback to be called when a tag is discovered
     * @param flags Flags indicating poll technologies and other optional parameters
     * @param extras Additional extras for configuring reader mode.
     * @throws UnsupportedOperationException if FEATURE_NFC is unavailable.
     */
    public void enableReaderMode(Activity activity, ReaderCallback callback, int flags,
            Bundle extras) {
        synchronized (NfcAdapter.class) {
            if (!sHasNfcFeature) {
                throw new UnsupportedOperationException();
            }
        }
        mNfcActivityManager.enableReaderMode(activity, callback, flags, extras);
    }

    /**
     * Restore the NFC adapter to normal mode of operation: supporting
     * peer-to-peer (Android Beam), card emulation, and polling for
     * all supported tag technologies.
     *
     * @param activity the Activity that currently has reader mode enabled
     * @throws UnsupportedOperationException if FEATURE_NFC is unavailable.
     */
    public void disableReaderMode(Activity activity) {
        synchronized (NfcAdapter.class) {
            if (!sHasNfcFeature) {
                throw new UnsupportedOperationException();
            }
        }
        mNfcActivityManager.disableReaderMode(activity);
    }

    /**
     * Manually invoke Android Beam to share data.
     *
     * <p>The Android Beam animation is normally only shown when two NFC-capable
     * devices come into range.
     * By calling this method, an Activity can invoke the Beam animation directly
     * even if no other NFC device is in range yet. The Beam animation will then
     * prompt the user to tap another NFC-capable device to complete the data
     * transfer.
     *
     * <p>The main advantage of using this method is that it avoids the need for the
     * user to tap the screen to complete the transfer, as this method already
     * establishes the direction of the transfer and the consent of the user to
     * share data. Callers are responsible for making sure that the user has
     * consented to sharing data on NFC tap.
     *
     * <p>Note that to use this method, the passed in Activity must have already
     * set data to share over Beam by using method calls such as
     * {@link #setNdefPushMessageCallback} or
     * {@link #setBeamPushUrisCallback}.
     *
     * @param activity the current foreground Activity that has registered data to share
     * @return whether the Beam animation was successfully invoked
     * @throws UnsupportedOperationException if FEATURE_NFC is unavailable.
     * @deprecated this feature is deprecated. File sharing can work using other technology like
     * Bluetooth.
     */
    @java.lang.Deprecated
    public boolean invokeBeam(Activity activity) {
        synchronized (NfcAdapter.class) {
            if (!sHasNfcFeature) {
                throw new UnsupportedOperationException();
            }
            if (!sHasBeamFeature) {
                return false;
            }
        }
        if (activity == null) {
            throw new NullPointerException("activity may not be null.");
        }
        enforceResumed(activity);
        try {
            sService.invokeBeam();
            return true;
        } catch (RemoteException e) {
            Log.e(TAG, "invokeBeam: NFC process has died.");
            attemptDeadServiceRecovery(e);
            return false;
        }
    }

    /**
     * @hide
     */
    public boolean invokeBeam(BeamShareData shareData) {
        try {
            Log.e(TAG, "invokeBeamInternal()");
            sService.invokeBeamInternal(shareData);
            return true;
        } catch (RemoteException e) {
            Log.e(TAG, "invokeBeam: NFC process has died.");
            attemptDeadServiceRecovery(e);
            return false;
        }
    }

    /**
     * Enable NDEF message push over NFC while this Activity is in the foreground.
     *
     * <p>You must explicitly call this method every time the activity is
     * resumed, and you must call {@link #disableForegroundNdefPush} before
     * your activity completes {@link Activity#onPause}.
     *
     * <p>Strongly recommend to use the new {@link #setNdefPushMessage}
     * instead: it automatically hooks into your activity life-cycle,
     * so you do not need to call enable/disable in your onResume/onPause.
     *
     * <p>For NDEF push to function properly the other NFC device must
     * support either NFC Forum's SNEP (Simple Ndef Exchange Protocol), or
     * Android's "com.android.npp" (Ndef Push Protocol). This was optional
     * on Gingerbread level Android NFC devices, but SNEP is mandatory on
     * Ice-Cream-Sandwich and beyond.
     *
     * <p>This method must be called from the main thread.
     *
     * <p class="note">Requires the {@link android.Manifest.permission#NFC} permission.
     *
     * @param activity foreground activity
     * @param message a NDEF Message to push over NFC
     * @throws IllegalStateException if the activity is not currently in the foreground
     * @throws UnsupportedOperationException if FEATURE_NFC is unavailable.
     * @deprecated use {@link #setNdefPushMessage} instead
     */
    @Deprecated
    public void enableForegroundNdefPush(Activity activity, NdefMessage message) {
        synchronized (NfcAdapter.class) {
            if (!sHasNfcFeature) {
                throw new UnsupportedOperationException();
            }
            if (!sHasBeamFeature) {
                return;
            }
        }
        if (activity == null || message == null) {
            throw new NullPointerException();
        }
        enforceResumed(activity);
        mNfcActivityManager.setNdefPushMessage(activity, message, 0);
    }

    /**
     * Disable NDEF message push over P2P.
     *
     * <p>After calling {@link #enableForegroundNdefPush}, an activity
     * must call this method before its {@link Activity#onPause} callback
     * completes.
     *
     * <p>Strongly recommend to use the new {@link #setNdefPushMessage}
     * instead: it automatically hooks into your activity life-cycle,
     * so you do not need to call enable/disable in your onResume/onPause.
     *
     * <p>This method must be called from the main thread.
     *
     * <p class="note">Requires the {@link android.Manifest.permission#NFC} permission.
     *
     * @param activity the Foreground activity
     * @throws IllegalStateException if the Activity has already been paused
     * @throws UnsupportedOperationException if FEATURE_NFC is unavailable.
     * @deprecated use {@link #setNdefPushMessage} instead
     */
    @Deprecated
    public void disableForegroundNdefPush(Activity activity) {
        synchronized (NfcAdapter.class) {
            if (!sHasNfcFeature) {
                throw new UnsupportedOperationException();
            }
            if (!sHasBeamFeature) {
                return;
            }
        }
        if (activity == null) {
            throw new NullPointerException();
        }
        enforceResumed(activity);
        mNfcActivityManager.setNdefPushMessage(activity, null, 0);
        mNfcActivityManager.setNdefPushMessageCallback(activity, null, 0);
        mNfcActivityManager.setOnNdefPushCompleteCallback(activity, null);
    }

    /**
     * Sets Secure NFC feature.
     * <p>This API is for the Settings application.
     * @return True if successful
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
    public boolean enableSecureNfc(boolean enable) {
        if (!sHasNfcFeature) {
            throw new UnsupportedOperationException();
        }
        try {
            return sService.setNfcSecure(enable);
        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
            // Try one more time
            if (sService == null) {
                Log.e(TAG, "Failed to recover NFC Service.");
                return false;
            }
            try {
                return sService.setNfcSecure(enable);
            } catch (RemoteException ee) {
                Log.e(TAG, "Failed to recover NFC Service.");
            }
            return false;
        }
    }

    /**
     * Checks if the device supports Secure NFC functionality.
     *
     * @return True if device supports Secure NFC, false otherwise
     * @throws UnsupportedOperationException if FEATURE_NFC is unavailable.
     */
    public boolean isSecureNfcSupported() {
        if (!sHasNfcFeature) {
            throw new UnsupportedOperationException();
        }
        try {
            return sService.deviceSupportsNfcSecure();
        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
            // Try one more time
            if (sService == null) {
                Log.e(TAG, "Failed to recover NFC Service.");
                return false;
            }
            try {
                return sService.deviceSupportsNfcSecure();
            } catch (RemoteException ee) {
                Log.e(TAG, "Failed to recover NFC Service.");
            }
            return false;
        }
    }

    /**
     * Checks Secure NFC feature is enabled.
     *
     * @return True if Secure NFC is enabled, false otherwise
     * @throws UnsupportedOperationException if FEATURE_NFC is unavailable.
     * @throws UnsupportedOperationException if device doesn't support
     *         Secure NFC functionality. {@link #isSecureNfcSupported}
     */
    public boolean isSecureNfcEnabled() {
        if (!sHasNfcFeature) {
            throw new UnsupportedOperationException();
        }
        try {
            return sService.isNfcSecureEnabled();
        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
            // Try one more time
            if (sService == null) {
                Log.e(TAG, "Failed to recover NFC Service.");
                return false;
            }
            try {
                return sService.isNfcSecureEnabled();
            } catch (RemoteException ee) {
                Log.e(TAG, "Failed to recover NFC Service.");
            }
            return false;
        }
    }

    /**
     * Enable NDEF Push feature.
     * <p>This API is for the Settings application.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
    public boolean enableNdefPush() {
        if (!sHasNfcFeature) {
            throw new UnsupportedOperationException();
        }
        try {
            return sService.enableNdefPush();
        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
            return false;
        }
    }

    /**
     * Disable NDEF Push feature.
     * <p>This API is for the Settings application.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
    public boolean disableNdefPush() {
        synchronized (NfcAdapter.class) {
            if (!sHasNfcFeature) {
                throw new UnsupportedOperationException();
            }
        }
        try {
            return sService.disableNdefPush();
        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
            return false;
        }
    }

    /**
     * Return true if the NDEF Push (Android Beam) feature is enabled.
     * <p>This function will return true only if both NFC is enabled, and the
     * NDEF Push feature is enabled.
     * <p>Note that if NFC is enabled but NDEF Push is disabled then this
     * device can still <i>receive</i> NDEF messages, it just cannot send them.
     * <p>Applications cannot directly toggle the NDEF Push feature, but they
     * can request Settings UI allowing the user to toggle NDEF Push using
     * <code>startActivity(new Intent(Settings.ACTION_NFCSHARING_SETTINGS))</code>
     * <p>Example usage in an Activity that requires NDEF Push:
     * <p><pre>
     * protected void onResume() {
     *     super.onResume();
     *     if (!nfcAdapter.isEnabled()) {
     *         startActivity(new Intent(Settings.ACTION_NFC_SETTINGS));
     *     } else if (!nfcAdapter.isNdefPushEnabled()) {
     *         startActivity(new Intent(Settings.ACTION_NFCSHARING_SETTINGS));
     *     }
     * }</pre>
     *
     * @see android.provider.Settings#ACTION_NFCSHARING_SETTINGS
     * @return true if NDEF Push feature is enabled
     * @throws UnsupportedOperationException if FEATURE_NFC is unavailable.
     * @deprecated this feature is deprecated. File sharing can work using other technology like
     * Bluetooth.
     */
    @java.lang.Deprecated

    public boolean isNdefPushEnabled() {
        synchronized (NfcAdapter.class) {
            if (!sHasNfcFeature) {
                throw new UnsupportedOperationException();
            }
            if (!sHasBeamFeature) {
                return false;
            }
        }
        try {
            return sService.isNdefPushEnabled();
        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
            return false;
        }
    }

    /**
     * Signals that you are no longer interested in communicating with an NFC tag
     * for as long as it remains in range.
     *
     * All future attempted communication to this tag will fail with {@link IOException}.
     * The NFC controller will be put in a low-power polling mode, allowing the device
     * to save power in cases where it's "attached" to a tag all the time (e.g. a tag in
     * car dock).
     *
     * Additionally the debounceMs parameter allows you to specify for how long the tag needs
     * to have gone out of range, before it will be dispatched again.
     *
     * Note: the NFC controller typically polls at a pretty slow interval (100 - 500 ms).
     * This means that if the tag repeatedly goes in and out of range (for example, in
     * case of a flaky connection), and the controller happens to poll every time the
     * tag is out of range, it *will* re-dispatch the tag after debounceMs, despite the tag
     * having been "in range" during the interval.
     *
     * Note 2: if a tag with another UID is detected after this API is called, its effect
     * will be cancelled; if this tag shows up before the amount of time specified in
     * debounceMs, it will be dispatched again.
     *
     * Note 3: some tags have a random UID, in which case this API won't work reliably.
     *
     * @param tag        the {@link android.nfc.Tag Tag} to ignore.
     * @param debounceMs minimum amount of time the tag needs to be out of range before being
     *                   dispatched again.
     * @param tagRemovedListener listener to be called when the tag is removed from the field.
     *                           Note that this will only be called if the tag has been out of range
     *                           for at least debounceMs, or if another tag came into range before
     *                           debounceMs. May be null in case you don't want a callback.
     * @param handler the {@link android.os.Handler Handler} that will be used for delivering
     *                the callback. if the handler is null, then the thread used for delivering
     *                the callback is unspecified.
     * @return false if the tag couldn't be found (or has already gone out of range), true otherwise
     */
    public boolean ignore(final Tag tag, int debounceMs,
                          final OnTagRemovedListener tagRemovedListener, final Handler handler) {
        ITagRemovedCallback.Stub iListener = null;
        if (tagRemovedListener != null) {
            iListener = new ITagRemovedCallback.Stub() {
                @Override
                public void onTagRemoved() throws RemoteException {
                    if (handler != null) {
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                tagRemovedListener.onTagRemoved();
                            }
                        });
                    } else {
                        tagRemovedListener.onTagRemoved();
                    }
                    synchronized (mLock) {
                        mTagRemovedListener = null;
                    }
                }
            };
        }
        synchronized (mLock) {
            mTagRemovedListener = iListener;
        }
        try {
            return sService.ignore(tag.getServiceHandle(), debounceMs, iListener);
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Inject a mock NFC tag.<p>
     * Used for testing purposes.
     * <p class="note">Requires the
     * {@link android.Manifest.permission#WRITE_SECURE_SETTINGS} permission.
     * @hide
     */
    public void dispatch(Tag tag) {
        if (tag == null) {
            throw new NullPointerException("tag cannot be null");
        }
        try {
            sService.dispatch(tag);
        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
        }
    }

    /**
     * @hide
     */
    public void setP2pModes(int initiatorModes, int targetModes) {
        try {
            sService.setP2pModes(initiatorModes, targetModes);
        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
        }
    }

    /**
     * Registers a new NFC unlock handler with the NFC service.
     *
     * <p />NFC unlock handlers are intended to unlock the keyguard in the presence of a trusted
     * NFC device. The handler should return true if it successfully authenticates the user and
     * unlocks the keyguard.
     *
     * <p /> The parameter {@code tagTechnologies} determines which Tag technologies will be polled for
     * at the lockscreen. Polling for less tag technologies reduces latency, and so it is
     * strongly recommended to only provide the Tag technologies that the handler is expected to
     * receive. There must be at least one tag technology provided, otherwise the unlock handler
     * is ignored.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
    public boolean addNfcUnlockHandler(final NfcUnlockHandler unlockHandler,
                                       String[] tagTechnologies) {
        synchronized (NfcAdapter.class) {
            if (!sHasNfcFeature) {
                throw new UnsupportedOperationException();
            }
        }
        // If there are no tag technologies, don't bother adding unlock handler
        if (tagTechnologies.length == 0) {
            return false;
        }

        try {
            synchronized (mLock) {
                if (mNfcUnlockHandlers.containsKey(unlockHandler)) {
                    // update the tag technologies
                    sService.removeNfcUnlockHandler(mNfcUnlockHandlers.get(unlockHandler));
                    mNfcUnlockHandlers.remove(unlockHandler);
                }

                INfcUnlockHandler.Stub iHandler = new INfcUnlockHandler.Stub() {
                    @Override
                    public boolean onUnlockAttempted(Tag tag) throws RemoteException {
                        return unlockHandler.onUnlockAttempted(tag);
                    }
                };

                sService.addNfcUnlockHandler(iHandler,
                        Tag.getTechCodesFromStrings(tagTechnologies));
                mNfcUnlockHandlers.put(unlockHandler, iHandler);
            }
        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
            return false;
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Unable to register LockscreenDispatch", e);
            return false;
        }

        return true;
    }

    /**
     * Removes a previously registered unlock handler. Also removes the tag technologies
     * associated with the removed unlock handler.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
    public boolean removeNfcUnlockHandler(NfcUnlockHandler unlockHandler) {
        synchronized (NfcAdapter.class) {
            if (!sHasNfcFeature) {
                throw new UnsupportedOperationException();
            }
        }
        try {
            synchronized (mLock) {
                if (mNfcUnlockHandlers.containsKey(unlockHandler)) {
                    sService.removeNfcUnlockHandler(mNfcUnlockHandlers.remove(unlockHandler));
                }

                return true;
            }
        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
            return false;
        }
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public INfcAdapterExtras getNfcAdapterExtrasInterface() {
        if (mContext == null) {
            throw new UnsupportedOperationException("You need a context on NfcAdapter to use the "
                    + " NFC extras APIs");
        }
        try {
            return sService.getNfcAdapterExtrasInterface(mContext.getPackageName());
        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
            // Try one more time
            if (sService == null) {
                Log.e(TAG, "Failed to recover NFC Service.");
                return null;
            }
            try {
                return sService.getNfcAdapterExtrasInterface(mContext.getPackageName());
            } catch (RemoteException ee) {
                Log.e(TAG, "Failed to recover NFC Service.");
            }
            return null;
        }
    }

    void enforceResumed(Activity activity) {
        if (!activity.isResumed()) {
            throw new IllegalStateException("API cannot be called while activity is paused");
        }
    }

    int getSdkVersion() {
        if (mContext == null) {
            return android.os.Build.VERSION_CODES.GINGERBREAD; // best guess
        } else {
            return mContext.getApplicationInfo().targetSdkVersion;
        }
    }

    /**
     * Sets NFC controller always on feature.
     * <p>This API is for the NFCC internal state management. It allows to discriminate
     * the controller function from the NFC function by keeping the NFC controller on without
     * any NFC RF enabled if necessary.
     * <p>This call is asynchronous. Register a callback {@link #ControllerAlwaysOnStateCallback}
     * by {@link #registerControllerAlwaysOnStateCallback} to find out when the operation is
     * complete.
     * <p>If this returns true, then either NFCC always on state has been set based on the value,
     * or a {@link ControllerAlwaysOnStateCallback#onStateChanged(boolean)} will be invoked to
     * indicate the state change.
     * If this returns false, then there is some problem that prevents an attempt to turn NFCC
     * always on.
     * @param value if true the NFCC will be kept on (with no RF enabled if NFC adapter is
     * disabled), if false the NFCC will follow completely the Nfc adapter state.
     * @throws UnsupportedOperationException if FEATURE_NFC is unavailable.
     * @return void
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.NFC_SET_CONTROLLER_ALWAYS_ON)
    public boolean setControllerAlwaysOn(boolean value) {
        if (!sHasNfcFeature) {
            throw new UnsupportedOperationException();
        }
        try {
            return sService.setControllerAlwaysOn(value);
        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
            // Try one more time
            if (sService == null) {
                Log.e(TAG, "Failed to recover NFC Service.");
                return false;
            }
            try {
                return sService.setControllerAlwaysOn(value);
            } catch (RemoteException ee) {
                Log.e(TAG, "Failed to recover NFC Service.");
            }
            return false;
        }
    }

    /**
     * Checks NFC controller always on feature is enabled.
     *
     * @return True if NFC controller always on is enabled, false otherwise
     * @throws UnsupportedOperationException if FEATURE_NFC is unavailable.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.NFC_SET_CONTROLLER_ALWAYS_ON)
    public boolean isControllerAlwaysOn() {
        try {
            return sService.isControllerAlwaysOn();
        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
            // Try one more time
            if (sService == null) {
                Log.e(TAG, "Failed to recover NFC Service.");
                return false;
            }
            try {
                return sService.isControllerAlwaysOn();
            } catch (RemoteException ee) {
                Log.e(TAG, "Failed to recover NFC Service.");
            }
            return false;
        }
    }

    /**
     * Checks if the device supports NFC controller always on functionality.
     *
     * @return True if device supports NFC controller always on, false otherwise
     * @throws UnsupportedOperationException if FEATURE_NFC is unavailable.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.NFC_SET_CONTROLLER_ALWAYS_ON)
    public boolean isControllerAlwaysOnSupported() {
        if (!sHasNfcFeature) {
            throw new UnsupportedOperationException();
        }
        try {
            return sService.isControllerAlwaysOnSupported();
        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
            // Try one more time
            if (sService == null) {
                Log.e(TAG, "Failed to recover NFC Service.");
                return false;
            }
            try {
                return sService.isControllerAlwaysOnSupported();
            } catch (RemoteException ee) {
                Log.e(TAG, "Failed to recover NFC Service.");
            }
            return false;
        }
    }

    /**
     * Register a {@link ControllerAlwaysOnStateCallback} to listen for NFC controller always on
     * state changes
     * <p>The provided callback will be invoked by the given {@link Executor}.
     *
     * @param executor an {@link Executor} to execute given callback
     * @param callback user implementation of the {@link ControllerAlwaysOnStateCallback}
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.NFC_SET_CONTROLLER_ALWAYS_ON)
    public void registerControllerAlwaysOnStateCallback(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull ControllerAlwaysOnStateCallback callback) {
        mControllerAlwaysOnStateListener.register(executor, callback);
    }

    /**
     * Unregister the specified {@link ControllerAlwaysOnStateCallback}
     * <p>The same {@link ControllerAlwaysOnStateCallback} object used when calling
     * {@link #registerControllerAlwaysOnStateCallback(Executor, ControllerAlwaysOnStateCallback)}
     * must be used.
     *
     * <p>Callbacks are automatically unregistered when application process goes away
     *
     * @param callback user implementation of the {@link ControllerAlwaysOnStateCallback}
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.NFC_SET_CONTROLLER_ALWAYS_ON)
    public void unregisterControllerAlwaysOnStateCallback(
            @NonNull ControllerAlwaysOnStateCallback callback) {
        mControllerAlwaysOnStateListener.unregister(callback);
    }
}
