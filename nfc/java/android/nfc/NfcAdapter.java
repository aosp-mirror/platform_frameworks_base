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
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.annotation.UserIdInt;
import android.app.Activity;
import android.app.PendingIntent;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.nfc.tech.MifareClassic;
import android.nfc.tech.Ndef;
import android.nfc.tech.NfcA;
import android.nfc.tech.NfcF;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    private final NfcControllerAlwaysOnListener mControllerAlwaysOnListener;
    private final NfcWlcStateListener mNfcWlcStateListener;
    private final NfcVendorNciCallbackListener mNfcVendorNciCallbackListener;

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
     * Possible states from {@link #getAdapterState}.
     *
     * @hide
     */
    @IntDef(prefix = { "STATE_" }, value = {
            STATE_OFF,
            STATE_TURNING_ON,
            STATE_ON,
            STATE_TURNING_OFF
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface AdapterState{}

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

    /** @hide */
    @IntDef(flag = true, prefix = {"FLAG_READER_"}, value = {
        FLAG_READER_KEEP,
        FLAG_READER_DISABLE,
        FLAG_READER_NFC_A,
        FLAG_READER_NFC_B,
        FLAG_READER_NFC_F,
        FLAG_READER_NFC_V,
        FLAG_READER_NFC_BARCODE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface PollTechnology {}

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

    /**
     * Flag for use with {@link #setDiscoveryTechnology(Activity, int, int)}.
     * <p>
     * Setting this flag enables listening for Nfc-A technology.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_NFC_SET_DISCOVERY_TECH)
    public static final int FLAG_LISTEN_NFC_PASSIVE_A = 0x1;

    /**
     * Flag for use with {@link #setDiscoveryTechnology(Activity, int, int)}.
     * <p>
     * Setting this flag enables listening for Nfc-B technology.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_NFC_SET_DISCOVERY_TECH)
    public static final int FLAG_LISTEN_NFC_PASSIVE_B = 1 << 1;

    /**
     * Flag for use with {@link #setDiscoveryTechnology(Activity, int, int)}.
     * <p>
     * Setting this flag enables listening for Nfc-F technology.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_NFC_SET_DISCOVERY_TECH)
    public static final int FLAG_LISTEN_NFC_PASSIVE_F = 1 << 2;

    /**
     * Flags for use with {@link #setDiscoveryTechnology(Activity, int, int)}.
     * <p>
     * Setting this flag disables listening.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_NFC_SET_DISCOVERY_TECH)
    public static final int FLAG_LISTEN_DISABLE = 0x0;

    /**
     * Flags for use with {@link #setDiscoveryTechnology(Activity, int, int)}.
     * <p>
     * Setting this flag disables polling.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_NFC_SET_DISCOVERY_TECH)
    public static final int FLAG_READER_DISABLE = 0x0;

    /**
     * Flags for use with {@link #setDiscoveryTechnology(Activity, int, int)}.
     * <p>
     * Setting this flag makes listening to keep the current technology configuration.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_NFC_SET_DISCOVERY_TECH)
    public static final int FLAG_LISTEN_KEEP = 0x80000000;

    /**
     * Flags for use with {@link #setDiscoveryTechnology(Activity, int, int)}.
     * <p>
     * Setting this flag makes polling to keep the current technology configuration.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_NFC_SET_DISCOVERY_TECH)
    public static final int FLAG_READER_KEEP = 0x80000000;

    /** @hide */
    public static final int FLAG_USE_ALL_TECH = 0xff;

    /** @hide */
    @IntDef(flag = true, prefix = {"FLAG_LISTEN_"}, value = {
        FLAG_LISTEN_KEEP,
        FLAG_LISTEN_DISABLE,
        FLAG_LISTEN_NFC_PASSIVE_A,
        FLAG_LISTEN_NFC_PASSIVE_B,
        FLAG_LISTEN_NFC_PASSIVE_F
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ListenTechnology {}

    /**
     * @hide
     * @removed
     */
    @SystemApi
    @UnsupportedAppUsage
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
    @SystemApi
    @FlaggedApi(Flags.FLAG_ENABLE_NFC_MAINLINE)
    public static final String ACTION_REQUIRE_UNLOCK_FOR_NFC =
            "android.nfc.action.REQUIRE_UNLOCK_FOR_NFC";

    /**
     * The requested app is correctly added to the Tag intent app preference.
     *
     * @see #setTagIntentAppPreferenceForUser(int userId, String pkg, boolean allow)
     * @hide
     */
    @SystemApi
    public static final int TAG_INTENT_APP_PREF_RESULT_SUCCESS = 0;

    /**
     * The requested app is not installed on the device.
     *
     * @see #setTagIntentAppPreferenceForUser(int userId, String pkg, boolean allow)
     * @hide
     */
    @SystemApi
    public static final int TAG_INTENT_APP_PREF_RESULT_PACKAGE_NOT_FOUND = -1;

    /**
     * The NfcService is not available.
     *
     * @see #setTagIntentAppPreferenceForUser(int userId, String pkg, boolean allow)
     * @hide
     */
    @SystemApi
    public static final int TAG_INTENT_APP_PREF_RESULT_UNAVAILABLE = -2;

    /**
     * Possible response codes from {@link #setTagIntentAppPreferenceForUser}.
     *
     * @hide
     */
    @IntDef(prefix = { "TAG_INTENT_APP_PREF_RESULT" }, value = {
            TAG_INTENT_APP_PREF_RESULT_SUCCESS,
            TAG_INTENT_APP_PREF_RESULT_PACKAGE_NOT_FOUND,
            TAG_INTENT_APP_PREF_RESULT_UNAVAILABLE})
    @Retention(RetentionPolicy.SOURCE)
    public @interface TagIntentAppPreferenceResult {}

    // Guarded by sLock
    static boolean sIsInitialized = false;
    static boolean sHasNfcFeature;
    static boolean sHasCeFeature;
    static boolean sHasNfcWlcFeature;

    static Object sLock = new Object();

    // Final after first constructor, except for
    // attemptDeadServiceRecovery() when NFC crashes - we accept a best effort
    // recovery
    @UnsupportedAppUsage
    static INfcAdapter sService;
    static NfcServiceManager.ServiceRegisterer sServiceRegisterer;
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
     * A listener to be invoked when NFC controller always on state changes.
     * <p>Register your {@code ControllerAlwaysOnListener} implementation with {@link
     * NfcAdapter#registerControllerAlwaysOnListener} and disable it with {@link
     * NfcAdapter#unregisterControllerAlwaysOnListener}.
     * @see #registerControllerAlwaysOnListener
     * @hide
     */
    @SystemApi
    public interface ControllerAlwaysOnListener {
        /**
         * Called on NFC controller always on state changes
         */
        void onControllerAlwaysOnChanged(boolean isEnabled);
    }

    /**
     * A callback to be invoked when the system successfully delivers your {@link NdefMessage}
     * to another device.
     * @deprecated this feature is removed. File sharing can work using other technology like
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
         */
        public void onNdefPushComplete(NfcEvent event);
    }

    /**
     * A callback to be invoked when another NFC device capable of NDEF push (Android Beam)
     * is within range.
     * <p>Implement this interface and pass it to {@code
     * NfcAdapter#setNdefPushMessageCallback setNdefPushMessageCallback()} in order to create an
     * {@link NdefMessage} at the moment that another device is within range for NFC. Using this
     * callback allows you to create a message with data that might vary based on the
     * content currently visible to the user. Alternatively, you can call {@code
     * #setNdefPushMessage setNdefPushMessage()} if the {@link NdefMessage} always contains the
     * same data.
     * @deprecated this feature is removed. File sharing can work using other technology like
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
     * @deprecated this feature is removed. File sharing can work using other technology like
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
     * Return list of Secure Elements which support off host card emulation.
     *
     * @return List<String> containing secure elements on the device which supports
     *                      off host card emulation. eSE for Embedded secure element,
     *                      SIM for UICC and so on.
     * @hide
     */
    public @NonNull List<String> getSupportedOffHostSecureElements() {
        if (mContext == null) {
            throw new UnsupportedOperationException("You need a context on NfcAdapter to use the "
                    + " getSupportedOffHostSecureElements APIs");
        }
        List<String> offHostSE = new ArrayList<String>();
        PackageManager pm = mContext.getPackageManager();
        if (pm == null) {
            Log.e(TAG, "Cannot get package manager, assuming no off-host CE feature");
            return offHostSE;
        }
        if (pm.hasSystemFeature(PackageManager.FEATURE_NFC_OFF_HOST_CARD_EMULATION_UICC)) {
            offHostSE.add("SIM");
        }
        if (pm.hasSystemFeature(PackageManager.FEATURE_NFC_OFF_HOST_CARD_EMULATION_ESE)) {
            offHostSE.add("eSE");
        }
        return offHostSE;
    }

    private static void retrieveServiceRegisterer() {
        if (sServiceRegisterer == null) {
            NfcServiceManager manager = NfcFrameworkInitializer.getNfcServiceManager();
            if (manager == null) {
                Log.e(TAG, "NfcServiceManager is null");
                throw new UnsupportedOperationException();
            }
            sServiceRegisterer = manager.getNfcManagerServiceRegisterer();
        }
    }

    /**
     * Returns the NfcAdapter for application context,
     * or throws if NFC is not available.
     * @hide
     */
    @UnsupportedAppUsage
    public static synchronized NfcAdapter getNfcAdapter(Context context) {
        if (context == null) {
            if (sNullContextNfcAdapter == null) {
                sNullContextNfcAdapter = new NfcAdapter(null);
            }
            return sNullContextNfcAdapter;
        }
        if (!sIsInitialized) {
            PackageManager pm;
            pm = context.getPackageManager();
            sHasNfcFeature = pm.hasSystemFeature(PackageManager.FEATURE_NFC);
            sHasCeFeature =
                    pm.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)
                    || pm.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION_NFCF)
                    || pm.hasSystemFeature(PackageManager.FEATURE_NFC_OFF_HOST_CARD_EMULATION_UICC)
                    || pm.hasSystemFeature(PackageManager.FEATURE_NFC_OFF_HOST_CARD_EMULATION_ESE);
            sHasNfcWlcFeature = pm.hasSystemFeature(PackageManager.FEATURE_NFC_CHARGING);
            /* is this device meant to have NFC */
            if (!sHasNfcFeature && !sHasCeFeature && !sHasNfcWlcFeature) {
                Log.v(TAG, "this device does not have NFC support");
                throw new UnsupportedOperationException();
            }
            retrieveServiceRegisterer();
            sService = getServiceInterface();
            if (sService == null) {
                Log.e(TAG, "could not retrieve NFC service");
                throw new UnsupportedOperationException();
            }
            if (sHasNfcFeature) {
                try {
                    sTagService = sService.getNfcTagInterface();
                } catch (RemoteException e) {
                    sTagService = null;
                    Log.e(TAG, "could not retrieve NFC Tag service");
                    throw new UnsupportedOperationException();
                }
            }
            if (sHasCeFeature) {
                try {
                    sNfcFCardEmulationService = sService.getNfcFCardEmulationInterface();
                } catch (RemoteException e) {
                    sNfcFCardEmulationService = null;
                    Log.e(TAG, "could not retrieve NFC-F card emulation service");
                    throw new UnsupportedOperationException();
                }
                try {
                    sCardEmulationService = sService.getNfcCardEmulationInterface();
                } catch (RemoteException e) {
                    sCardEmulationService = null;
                    Log.e(TAG, "could not retrieve card emulation service");
                    throw new UnsupportedOperationException();
                }
            }

            sIsInitialized = true;
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
        IBinder b = sServiceRegisterer.get();
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
        retrieveServiceRegisterer();
        if (sServiceRegisterer.tryGet() == null) {
            if (sIsInitialized) {
                synchronized (NfcAdapter.class) {
                    /* Stale sService pointer */
                    if (sIsInitialized) sIsInitialized = false;
                }
            }
            return null;
        }
        /* Try to initialize the service */
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
        mControllerAlwaysOnListener = new NfcControllerAlwaysOnListener(getService());
        mNfcWlcStateListener = new NfcWlcStateListener(getService());
        mNfcVendorNciCallbackListener = new NfcVendorNciCallbackListener(getService());
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
        if (sHasNfcFeature) {
            try {
                sTagService = service.getNfcTagInterface();
            } catch (RemoteException ee) {
                sTagService = null;
                Log.e(TAG, "could not retrieve NFC tag service during service recovery");
                // nothing more can be done now, sService is still stale, we'll hit
                // this recovery path again later
                return;
            }
        }

        if (sHasCeFeature) {
            try {
                sCardEmulationService = service.getNfcCardEmulationInterface();
            } catch (RemoteException ee) {
                sCardEmulationService = null;
                Log.e(TAG,
                        "could not retrieve NFC card emulation service during service recovery");
            }

            try {
                sNfcFCardEmulationService = service.getNfcFCardEmulationInterface();
            } catch (RemoteException ee) {
                sNfcFCardEmulationService = null;
                Log.e(TAG,
                        "could not retrieve NFC-F card emulation service during service recovery");
            }
        }

        return;
    }

    private boolean isCardEmulationEnabled() {
        if (sHasCeFeature) {
            return (sCardEmulationService != null || sNfcFCardEmulationService != null);
        }
        return false;
    }

    private boolean isTagReadingEnabled() {
        if (sHasNfcFeature) {
            return sTagService != null;
        }
        return false;
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
        boolean serviceState = false;
        try {
            serviceState = sService.getState() == STATE_ON;
        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
            // Try one more time
            if (sService == null) {
                Log.e(TAG, "Failed to recover NFC Service.");
                return false;
            }
            try {
                serviceState = sService.getState() == STATE_ON;
            } catch (RemoteException ee) {
                Log.e(TAG, "Failed to recover NFC Service.");
            }
        }
        return serviceState
                && (isTagReadingEnabled() || isCardEmulationEnabled() || sHasNfcWlcFeature);
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
    @SystemApi
    @FlaggedApi(Flags.FLAG_ENABLE_NFC_MAINLINE)
    public @AdapterState int getAdapterState() {
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
     * Returns whether the device supports observer mode or not. When observe
     * mode is enabled, the NFC hardware will listen for NFC readers, but not
     * respond to them. When observe mode is disabled, the NFC hardware will
     * resoond to the reader and proceed with the transaction.
     * @return true if the mode is supported, false otherwise.
     */
    @FlaggedApi(Flags.FLAG_NFC_OBSERVE_MODE)
    public boolean isObserveModeSupported() {
        try {
            return sService.isObserveModeSupported();
        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
            return false;
        }
    }

    /**
     * Controls whether the NFC adapter will allow transactions to proceed or be in observe mode
     * and simply observe and notify the APDU service of polling loop frames. See
     * {@link #isObserveModeSupported()} for a description of observe mode.
     *
     * @param allowed true disables observe mode to allow the transaction to proceed while false
     *                enables observe mode and does not allow transactions to proceed.
     *
     * @return boolean indicating success or failure.
     */

    @FlaggedApi(Flags.FLAG_NFC_OBSERVE_MODE)
    public boolean setTransactionAllowed(boolean allowed) {
        try {
            return sService.setObserveMode(!allowed);
        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
            return false;
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
     * @removed this feature is removed. File sharing can work using other technology like
     * Bluetooth.
     */
    @java.lang.Deprecated
    @UnsupportedAppUsage
    public void setBeamPushUris(Uri[] uris, Activity activity) {
        synchronized (sLock) {
            if (!sHasNfcFeature) {
                throw new UnsupportedOperationException();
            }
        }
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
     * @removed this feature is removed. File sharing can work using other technology like
     * Bluetooth.
     */
    @java.lang.Deprecated
    @UnsupportedAppUsage
    public void setBeamPushUrisCallback(CreateBeamUrisCallback callback, Activity activity) {
        synchronized (sLock) {
            if (!sHasNfcFeature) {
                throw new UnsupportedOperationException();
            }
        }
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
     * @removed this feature is removed. File sharing can work using other technology like
     * Bluetooth.
     */
    @java.lang.Deprecated
    @UnsupportedAppUsage
    public void setNdefPushMessage(NdefMessage message, Activity activity,
            Activity ... activities) {
        synchronized (sLock) {
            if (!sHasNfcFeature) {
                throw new UnsupportedOperationException();
            }
        }
    }

    /**
     * @hide
     * @removed
     */
    @SystemApi
    @UnsupportedAppUsage
    public void setNdefPushMessage(NdefMessage message, Activity activity, int flags) {
        synchronized (sLock) {
            if (!sHasNfcFeature) {
                throw new UnsupportedOperationException();
            }
        }
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
     * @removed this feature is removed. File sharing can work using other technology like
     * Bluetooth.
     */
    @java.lang.Deprecated
    @UnsupportedAppUsage
    public void setNdefPushMessageCallback(CreateNdefMessageCallback callback, Activity activity,
            Activity ... activities) {
        synchronized (sLock) {
            if (!sHasNfcFeature) {
                throw new UnsupportedOperationException();
            }
        }
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
     * @removed this feature is removed. File sharing can work using other technology like
     * Bluetooth.
     */
    @java.lang.Deprecated
    @UnsupportedAppUsage
    public void setOnNdefPushCompleteCallback(OnNdefPushCompleteCallback callback,
            Activity activity, Activity ... activities) {
        synchronized (sLock) {
            if (!sHasNfcFeature) {
                throw new UnsupportedOperationException();
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
        synchronized (sLock) {
            if (!sHasNfcFeature) {
                throw new UnsupportedOperationException();
            }
        }
        if (activity == null || intent == null) {
            throw new NullPointerException();
        }
        try {
            TechListParcel parcel = null;
            if (techLists != null && techLists.length > 0) {
                parcel = new TechListParcel(techLists);
            }
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
        synchronized (sLock) {
            if (!sHasNfcFeature) {
                throw new UnsupportedOperationException();
            }
        }
        try {
            sService.setForegroundDispatch(null, null, null);
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
        synchronized (sLock) {
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
        synchronized (sLock) {
            if (!sHasNfcFeature) {
                throw new UnsupportedOperationException();
            }
        }
        mNfcActivityManager.disableReaderMode(activity);
    }

    // Flags arguments to NFC adapter to enable/disable NFC
    private static final int DISABLE_POLLING_FLAGS = 0x1000;
    private static final int ENABLE_POLLING_FLAGS = 0x0000;

    /**
     * Privileged API to enable or disable reader polling.
     * Unlike {@link #enableReaderMode(Activity, ReaderCallback, int, Bundle)}, this API does not
     * need a foreground activity to control reader mode parameters
     * Note: Use with caution! The app is responsible for ensuring that the polling state is
     * returned to normal.
     *
     * @see #enableReaderMode(Activity, ReaderCallback, int, Bundle)  for more detailed
     * documentation.
     *
     * @param enablePolling whether to enable or disable polling.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
    @FlaggedApi(Flags.FLAG_ENABLE_NFC_MAINLINE)
    @SuppressLint("VisiblySynchronized")
    public void setReaderModePollingEnabled(boolean enable) {
        synchronized (sLock) {
            if (!sHasNfcFeature) {
                throw new UnsupportedOperationException();
            }
        }
        Binder token = new Binder();
        int flags = enable ? ENABLE_POLLING_FLAGS : DISABLE_POLLING_FLAGS;
        try {
            NfcAdapter.sService.setReaderMode(token, null, flags, null);
        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
        }
    }

    /**
     * Set the NFC controller to enable specific poll/listen technologies,
     * as specified in parameters, while this Activity is in the foreground.
     *
     * Use {@link #FLAG_READER_KEEP} to keep current polling technology.
     * Use {@link #FLAG_LISTEN_KEEP} to keep current listenig technology.
     * (if the _KEEP flag is specified the other technology flags shouldn't be set
     * and are quietly ignored otherwise).
     * Use {@link #FLAG_READER_DISABLE} to disable polling.
     * Use {@link #FLAG_LISTEN_DISABLE} to disable listening.
     * Also refer to {@link #resetDiscoveryTechnology(Activity)} to restore these changes.
     * </p>
     * The pollTechnology, listenTechnology parameters can be one or several of below list.
     * <pre>
     *                    Poll                    Listen
     *  Passive A         0x01   (NFC_A)           0x01  (NFC_PASSIVE_A)
     *  Passive B         0x02   (NFC_B)           0x02  (NFC_PASSIVE_B)
     *  Passive F         0x04   (NFC_F)           0x04  (NFC_PASSIVE_F)
     *  ISO 15693         0x08   (NFC_V)             -
     *  Kovio             0x10   (NFC_BARCODE)       -
     * </pre>
     * <p>Example usage in an Activity that requires to disable poll,
     * keep current listen technologies:
     * <pre>
     * protected void onResume() {
     *     mNfcAdapter = NfcAdapter.getDefaultAdapter(getApplicationContext());
     *     mNfcAdapter.setDiscoveryTechnology(this,
     *         NfcAdapter.FLAG_READER_DISABLE, NfcAdapter.FLAG_LISTEN_KEEP);
     * }</pre></p>
     * @param activity The Activity that requests NFC controller to enable specific technologies.
     * @param pollTechnology Flags indicating poll technologies.
     * @param listenTechnology Flags indicating listen technologies.
     * @throws UnsupportedOperationException if FEATURE_NFC,
     * FEATURE_NFC_HOST_CARD_EMULATION, FEATURE_NFC_HOST_CARD_EMULATION_NFCF are unavailable.
     */

    @FlaggedApi(Flags.FLAG_ENABLE_NFC_SET_DISCOVERY_TECH)
    public void setDiscoveryTechnology(@NonNull Activity activity,
            @PollTechnology int pollTechnology, @ListenTechnology int listenTechnology) {

        // A special treatment of the _KEEP flags
        if ((listenTechnology & FLAG_LISTEN_KEEP) != 0) {
            listenTechnology = -1;
        }
        if ((pollTechnology & FLAG_READER_KEEP) != 0) {
            pollTechnology = -1;
        }

        if (listenTechnology == FLAG_LISTEN_DISABLE) {
            synchronized (sLock) {
                if (!sHasNfcFeature) {
                    throw new UnsupportedOperationException();
                }
            }
            mNfcActivityManager.enableReaderMode(activity, null, pollTechnology, null);
            return;
        }
        if (pollTechnology == FLAG_READER_DISABLE) {
            synchronized (sLock) {
                if (!sHasCeFeature) {
                    throw new UnsupportedOperationException();
                }
            }
        } else {
            synchronized (sLock) {
                if (!sHasNfcFeature || !sHasCeFeature) {
                    throw new UnsupportedOperationException();
                }
            }
        }
        mNfcActivityManager.setDiscoveryTech(activity, pollTechnology, listenTechnology);
    }

    /**
     * Restore the poll/listen technologies of NFC controller to its default state,
     * which were changed by {@link #setDiscoveryTechnology(Activity , int , int)}
     *
     * @param activity The Activity that requested to change technologies.
     */

    @FlaggedApi(Flags.FLAG_ENABLE_NFC_SET_DISCOVERY_TECH)
    public void resetDiscoveryTechnology(@NonNull Activity activity) {
        mNfcActivityManager.resetDiscoveryTech(activity);
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
     * @removed this feature is removed. File sharing can work using other technology like
     * Bluetooth.
     */
    @java.lang.Deprecated
    @UnsupportedAppUsage
    public boolean invokeBeam(Activity activity) {
        synchronized (sLock) {
            if (!sHasNfcFeature) {
                throw new UnsupportedOperationException();
            }
        }
        return false;
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
     * @throws UnsupportedOperationException if FEATURE_NFC is unavailable
     * @removed this feature is removed. File sharing can work using other technology like
     * Bluetooth.
     */
    @Deprecated
    @UnsupportedAppUsage
    public void enableForegroundNdefPush(Activity activity, NdefMessage message) {
        synchronized (sLock) {
            if (!sHasNfcFeature) {
                throw new UnsupportedOperationException();
            }
        }
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
     * @throws UnsupportedOperationException if FEATURE_NFC is unavailable
     * @removed this feature is removed. File sharing can work using other technology like
     * Bluetooth.
     */
    @Deprecated
    @UnsupportedAppUsage
    public void disableForegroundNdefPush(Activity activity) {
        synchronized (sLock) {
            if (!sHasNfcFeature) {
                throw new UnsupportedOperationException();
            }
        }
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
        if (!sHasNfcFeature && !sHasCeFeature) {
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
     * @throws UnsupportedOperationException if FEATURE_NFC,
     * FEATURE_NFC_HOST_CARD_EMULATION, FEATURE_NFC_HOST_CARD_EMULATION_NFCF,
     * FEATURE_NFC_OFF_HOST_CARD_EMULATION_UICC and FEATURE_NFC_OFF_HOST_CARD_EMULATION_ESE
     * are unavailable
     */
    public boolean isSecureNfcSupported() {
        if (!sHasNfcFeature && !sHasCeFeature) {
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
     * Returns information regarding Nfc antennas on the device
     * such as their relative positioning on the device.
     *
     * @return Information on the nfc antenna(s) on the device.
     * @throws UnsupportedOperationException if FEATURE_NFC,
     * FEATURE_NFC_HOST_CARD_EMULATION, FEATURE_NFC_HOST_CARD_EMULATION_NFCF,
     * FEATURE_NFC_OFF_HOST_CARD_EMULATION_UICC and FEATURE_NFC_OFF_HOST_CARD_EMULATION_ESE
     * are unavailable
     */
    @Nullable
    public NfcAntennaInfo getNfcAntennaInfo() {
        if (!sHasNfcFeature && !sHasCeFeature) {
            throw new UnsupportedOperationException();
        }
        try {
            return sService.getNfcAntennaInfo();
        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
            // Try one more time
            if (sService == null) {
                Log.e(TAG, "Failed to recover NFC Service.");
                return null;
            }
            try {
                return sService.getNfcAntennaInfo();
            } catch (RemoteException ee) {
                Log.e(TAG, "Failed to recover NFC Service.");
            }
            return null;
        }
    }

    /**
     * Checks Secure NFC feature is enabled.
     *
     * @return True if Secure NFC is enabled, false otherwise
     * @throws UnsupportedOperationException if FEATURE_NFC,
     * FEATURE_NFC_HOST_CARD_EMULATION, FEATURE_NFC_HOST_CARD_EMULATION_NFCF,
     * FEATURE_NFC_OFF_HOST_CARD_EMULATION_UICC and FEATURE_NFC_OFF_HOST_CARD_EMULATION_ESE
     * are unavailable
     * @throws UnsupportedOperationException if device doesn't support
     *         Secure NFC functionality. {@link #isSecureNfcSupported}
     */
    public boolean isSecureNfcEnabled() {
        if (!sHasNfcFeature && !sHasCeFeature) {
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
     * Sets NFC Reader option feature.
     * <p>This API is for the Settings application.
     * @return True if successful
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_ENABLE_NFC_READER_OPTION)
    @RequiresPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
    public boolean enableReaderOption(boolean enable) {
        if (!sHasNfcFeature) {
            throw new UnsupportedOperationException();
        }
        try {
            return sService.enableReaderOption(enable);
        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
            // Try one more time
            if (sService == null) {
                Log.e(TAG, "Failed to recover NFC Service.");
                return false;
            }
            try {
                return sService.enableReaderOption(enable);
            } catch (RemoteException ee) {
                Log.e(TAG, "Failed to recover NFC Service.");
            }
            return false;
        }
    }

    /**
     * Checks if the device supports NFC Reader option functionality.
     *
     * @return True if device supports NFC Reader option, false otherwise
     * @throws UnsupportedOperationException if FEATURE_NFC is unavailable.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_NFC_READER_OPTION)
    public boolean isReaderOptionSupported() {
        if (!sHasNfcFeature) {
            throw new UnsupportedOperationException();
        }
        try {
            return sService.isReaderOptionSupported();
        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
            // Try one more time
            if (sService == null) {
                Log.e(TAG, "Failed to recover NFC Service.");
                return false;
            }
            try {
                return sService.isReaderOptionSupported();
            } catch (RemoteException ee) {
                Log.e(TAG, "Failed to recover NFC Service.");
            }
            return false;
        }
    }

    /**
     * Checks NFC Reader option feature is enabled.
     *
     * @return True if NFC Reader option  is enabled, false otherwise
     * @throws UnsupportedOperationException if FEATURE_NFC is unavailable.
     * @throws UnsupportedOperationException if device doesn't support
     *         NFC Reader option functionality. {@link #isReaderOptionSupported}
     */
    @FlaggedApi(Flags.FLAG_ENABLE_NFC_READER_OPTION)
    public boolean isReaderOptionEnabled() {
        if (!sHasNfcFeature) {
            throw new UnsupportedOperationException();
        }
        try {
            return sService.isReaderOptionEnabled();
        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
            // Try one more time
            if (sService == null) {
                Log.e(TAG, "Failed to recover NFC Service.");
                return false;
            }
            try {
                return sService.isReaderOptionEnabled();
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
     * @removed
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
    @UnsupportedAppUsage
    public boolean enableNdefPush() {
        return false;
    }

    /**
     * Disable NDEF Push feature.
     * <p>This API is for the Settings application.
     * @hide
     * @removed
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
    @UnsupportedAppUsage
    public boolean disableNdefPush() {
        return false;
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
     * @removed this feature is removed. File sharing can work using other technology like
     * Bluetooth.
     */
    @java.lang.Deprecated
    @UnsupportedAppUsage
    public boolean isNdefPushEnabled() {
        synchronized (sLock) {
            if (!sHasNfcFeature) {
                throw new UnsupportedOperationException();
            }
        }
        return false;
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
        synchronized (sLock) {
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
        synchronized (sLock) {
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
     * <p>This call is asynchronous. Register a listener {@link #ControllerAlwaysOnListener}
     * by {@link #registerControllerAlwaysOnListener} to find out when the operation is
     * complete.
     * <p>If this returns true, then either NFCC always on state has been set based on the value,
     * or a {@link ControllerAlwaysOnListener#onControllerAlwaysOnChanged(boolean)} will be invoked
     * to indicate the state change.
     * If this returns false, then there is some problem that prevents an attempt to turn NFCC
     * always on.
     * @param value if true the NFCC will be kept on (with no RF enabled if NFC adapter is
     * disabled), if false the NFCC will follow completely the Nfc adapter state.
     * @throws UnsupportedOperationException if FEATURE_NFC,
     * FEATURE_NFC_HOST_CARD_EMULATION, FEATURE_NFC_HOST_CARD_EMULATION_NFCF,
     * FEATURE_NFC_OFF_HOST_CARD_EMULATION_UICC and FEATURE_NFC_OFF_HOST_CARD_EMULATION_ESE
     * are unavailable
     * @return void
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.NFC_SET_CONTROLLER_ALWAYS_ON)
    public boolean setControllerAlwaysOn(boolean value) {
        if (!sHasNfcFeature && !sHasCeFeature) {
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
     * @throws UnsupportedOperationException if FEATURE_NFC,
     * FEATURE_NFC_HOST_CARD_EMULATION, FEATURE_NFC_HOST_CARD_EMULATION_NFCF,
     * FEATURE_NFC_OFF_HOST_CARD_EMULATION_UICC and FEATURE_NFC_OFF_HOST_CARD_EMULATION_ESE
     * are unavailable
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
     * @throws UnsupportedOperationException if FEATURE_NFC,
     * FEATURE_NFC_HOST_CARD_EMULATION, FEATURE_NFC_HOST_CARD_EMULATION_NFCF,
     * FEATURE_NFC_OFF_HOST_CARD_EMULATION_UICC and FEATURE_NFC_OFF_HOST_CARD_EMULATION_ESE
     * are unavailable
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.NFC_SET_CONTROLLER_ALWAYS_ON)
    public boolean isControllerAlwaysOnSupported() {
        if (!sHasNfcFeature && !sHasCeFeature) {
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
     * Register a {@link ControllerAlwaysOnListener} to listen for NFC controller always on
     * state changes
     * <p>The provided listener will be invoked by the given {@link Executor}.
     *
     * @param executor an {@link Executor} to execute given listener
     * @param listener user implementation of the {@link ControllerAlwaysOnListener}
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.NFC_SET_CONTROLLER_ALWAYS_ON)
    public void registerControllerAlwaysOnListener(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull ControllerAlwaysOnListener listener) {
        mControllerAlwaysOnListener.register(executor, listener);
    }

    /**
     * Unregister the specified {@link ControllerAlwaysOnListener}
     * <p>The same {@link ControllerAlwaysOnListener} object used when calling
     * {@link #registerControllerAlwaysOnListener(Executor, ControllerAlwaysOnListener)}
     * must be used.
     *
     * <p>Listeners are automatically unregistered when application process goes away
     *
     * @param listener user implementation of the {@link ControllerAlwaysOnListener}
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.NFC_SET_CONTROLLER_ALWAYS_ON)
    public void unregisterControllerAlwaysOnListener(
            @NonNull ControllerAlwaysOnListener listener) {
        mControllerAlwaysOnListener.unregister(listener);
    }


    /**
     * Sets whether we dispatch NFC Tag intents to the package.
     *
     * <p>{@link #ACTION_NDEF_DISCOVERED}, {@link #ACTION_TECH_DISCOVERED} or
     * {@link #ACTION_TAG_DISCOVERED} will not be dispatched to an Activity if its package is
     * disallowed.
     * <p>An app is added to the preference list with the allowed flag set to {@code true}
     * when a Tag intent is dispatched to the package for the first time. This API is called
     * by settings to note that the user wants to change this default preference.
     *
     * @param userId the user to whom this package name will belong to
     * @param pkg the full name (i.e. com.google.android.tag) of the package that will be added to
     * the preference list
     * @param allow {@code true} to allow dispatching Tag intents to the package's activity,
     * {@code false} otherwise
     * @return the {@link #TagIntentAppPreferenceResult} value
     * @throws UnsupportedOperationException if {@link isTagIntentAppPreferenceSupported} returns
     * {@code false}
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
    @TagIntentAppPreferenceResult
    public int setTagIntentAppPreferenceForUser(@UserIdInt int userId,
                @NonNull String pkg, boolean allow) {
        Objects.requireNonNull(pkg, "pkg cannot be null");
        if (!isTagIntentAppPreferenceSupported()) {
            Log.e(TAG, "TagIntentAppPreference is not supported");
            throw new UnsupportedOperationException();
        }
        try {
            return sService.setTagIntentAppPreferenceForUser(userId, pkg, allow);
        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
            // Try one more time
            if (sService == null) {
                Log.e(TAG, "Failed to recover NFC Service.");
            }
            try {
                return sService.setTagIntentAppPreferenceForUser(userId, pkg, allow);
            } catch (RemoteException ee) {
                Log.e(TAG, "Failed to recover NFC Service.");
            }
            return TAG_INTENT_APP_PREF_RESULT_UNAVAILABLE;
        }
    }


    /**
     * Get the Tag dispatch preference list of the UserId.
     *
     * <p>This returns a mapping of package names for this user id to whether we dispatch Tag
     * intents to the package. {@link #ACTION_NDEF_DISCOVERED}, {@link #ACTION_TECH_DISCOVERED} or
     * {@link #ACTION_TAG_DISCOVERED} will not be dispatched to an Activity if its package is
     * mapped to {@code false}.
     * <p>There are three different possible cases:
     * <p>A package not being in the preference list.
     * It does not contain any Tag intent filters or the user never triggers a Tag detection that
     * matches the intent filter of the package.
     * <p>A package being mapped to {@code true}.
     * When a package has been launched by a tag detection for the first time, the package name is
     * put to the map and by default mapped to {@code true}. The package will receive Tag intents as
     * usual.
     * <p>A package being mapped to {@code false}.
     * The user chooses to disable this package and it will not receive any Tag intents anymore.
     *
     * @param userId the user to whom this preference list will belong to
     * @return a map of the UserId which indicates the mapping from package name to
     * boolean(allow status), otherwise return an empty map
     * @throws UnsupportedOperationException if {@link isTagIntentAppPreferenceSupported} returns
     * {@code false}
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
    @NonNull
    public Map<String, Boolean> getTagIntentAppPreferenceForUser(@UserIdInt int userId) {
        if (!isTagIntentAppPreferenceSupported()) {
            Log.e(TAG, "TagIntentAppPreference is not supported");
            throw new UnsupportedOperationException();
        }
        try {
            Map<String, Boolean> result = (Map<String, Boolean>) sService
                     .getTagIntentAppPreferenceForUser(userId);
            return result;
        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
            // Try one more time
            if (sService == null) {
                Log.e(TAG, "Failed to recover NFC Service.");
                return Collections.emptyMap();
            }
            try {
                Map<String, Boolean> result = (Map<String, Boolean>) sService
                        .getTagIntentAppPreferenceForUser(userId);
                return result;
            } catch (RemoteException ee) {
                Log.e(TAG, "Failed to recover NFC Service.");
            }
            return Collections.emptyMap();
        }
    }

    /**
     * Checks if the device supports Tag application preference.
     *
     * @return {@code true} if the device supports Tag application preference, {@code false}
     * otherwise
     * @throws UnsupportedOperationException if FEATURE_NFC is unavailable
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
    public boolean isTagIntentAppPreferenceSupported() {
        if (!sHasNfcFeature) {
            throw new UnsupportedOperationException();
        }
        try {
            return sService.isTagIntentAppPreferenceSupported();
        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
            // Try one more time
            if (sService == null) {
                Log.e(TAG, "Failed to recover NFC Service.");
                return false;
            }
            try {
                return sService.isTagIntentAppPreferenceSupported();
            } catch (RemoteException ee) {
                Log.e(TAG, "Failed to recover NFC Service.");
            }
            return false;
        }
    }

   /**
     * Notifies the system of a new polling loop.
     *
     * @param frame is the new frame.
     *
     * @hide
     */
    @TestApi
    @FlaggedApi(Flags.FLAG_NFC_READ_POLLING_LOOP)
    public void notifyPollingLoop(@NonNull Bundle frame) {
        try {
            if (sService == null) {
                attemptDeadServiceRecovery(null);
            }
            sService.notifyPollingLoop(frame);
        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
            // Try one more time
            if (sService == null) {
                Log.e(TAG, "Failed to recover NFC Service.");
                return;
            }
            try {
                sService.notifyPollingLoop(frame);
            } catch (RemoteException ee) {
                Log.e(TAG, "Failed to recover NFC Service.");
            }
        }
    }

   /**
     * Notifies the system of a an HCE session being deactivated.
     *     *
     * @hide
     */
    @TestApi
    @FlaggedApi(Flags.FLAG_NFC_READ_POLLING_LOOP)
    public void notifyHceDeactivated() {
        try {
            if (sService == null) {
                attemptDeadServiceRecovery(null);
            }
            sService.notifyHceDeactivated();
        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
            // Try one more time
            if (sService == null) {
                Log.e(TAG, "Failed to recover NFC Service.");
                return;
            }
            try {
                sService.notifyHceDeactivated();
            } catch (RemoteException ee) {
                Log.e(TAG, "Failed to recover NFC Service.");
            }
        }
    }

    /**
     * Sets NFC charging feature.
     * <p>This API is for the Settings application.
     * @return True if successful
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_ENABLE_NFC_CHARGING)
    @RequiresPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
    public boolean setWlcEnabled(boolean enable) {
        if (!sHasNfcWlcFeature) {
            throw new UnsupportedOperationException();
        }
        try {
            return sService.setWlcEnabled(enable);
        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
            // Try one more time
            if (sService == null) {
                Log.e(TAG, "Failed to recover NFC Service.");
                return false;
            }
            try {
                return sService.setWlcEnabled(enable);
            } catch (RemoteException ee) {
                Log.e(TAG, "Failed to recover NFC Service.");
            }
            return false;
        }
    }

    /**
     * Checks NFC charging feature is enabled.
     *
     * @return True if NFC charging is enabled, false otherwise
     * @throws UnsupportedOperationException if FEATURE_NFC_CHARGING
     * is unavailable
     */
    @FlaggedApi(Flags.FLAG_ENABLE_NFC_CHARGING)
    public boolean isWlcEnabled() {
        if (!sHasNfcWlcFeature) {
            throw new UnsupportedOperationException();
        }
        try {
            return sService.isWlcEnabled();
        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
            // Try one more time
            if (sService == null) {
                Log.e(TAG, "Failed to recover NFC Service.");
                return false;
            }
            try {
                return sService.isWlcEnabled();
            } catch (RemoteException ee) {
                Log.e(TAG, "Failed to recover NFC Service.");
            }
            return false;
        }
    }

    /**
     * A listener to be invoked when NFC controller always on state changes.
     * <p>Register your {@code ControllerAlwaysOnListener} implementation with {@link
     * NfcAdapter#registerWlcStateListener} and disable it with {@link
     * NfcAdapter#unregisterWlcStateListenerListener}.
     * @see #registerWlcStateListener
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_ENABLE_NFC_CHARGING)
    public interface WlcStateListener {
        /**
         * Called on NFC WLC state changes
         */
        void onWlcStateChanged(@NonNull WlcListenerDeviceInfo wlcListenerDeviceInfo);
    }

    /**
     * Register a {@link WlcStateListener} to listen for NFC WLC state changes
     * <p>The provided listener will be invoked by the given {@link Executor}.
     *
     * @param executor an {@link Executor} to execute given listener
     * @param listener user implementation of the {@link WlcStateListener}
     * @throws UnsupportedOperationException if FEATURE_NFC_CHARGING
     * is unavailable
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_ENABLE_NFC_CHARGING)
    public void registerWlcStateListener(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull WlcStateListener listener) {
        if (!sHasNfcWlcFeature) {
            throw new UnsupportedOperationException();
        }
        mNfcWlcStateListener.register(executor, listener);
    }

    /**
     * Unregister the specified {@link WlcStateListener}
     * <p>The same {@link WlcStateListener} object used when calling
     * {@link #registerWlcStateListener(Executor, WlcStateListener)}
     * must be used.
     *
     * <p>Listeners are automatically unregistered when application process goes away
     *
     * @param listener user implementation of the {@link WlcStateListener}a
     * @throws UnsupportedOperationException if FEATURE_NFC_CHARGING
     * is unavailable
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_ENABLE_NFC_CHARGING)
    public void unregisterWlcStateListener(
            @NonNull WlcStateListener listener) {
        if (!sHasNfcWlcFeature) {
            throw new UnsupportedOperationException();
        }
        mNfcWlcStateListener.unregister(listener);
    }

    /**
     * Returns information on the NFC charging listener device
     *
     * @return Information on the NFC charging listener device
     * @throws UnsupportedOperationException if FEATURE_NFC_CHARGING
     * is unavailable
     */
    @FlaggedApi(Flags.FLAG_ENABLE_NFC_CHARGING)
    @Nullable
    public WlcListenerDeviceInfo getWlcListenerDeviceInfo() {
        if (!sHasNfcWlcFeature) {
            throw new UnsupportedOperationException();
        }
        try {
            return sService.getWlcListenerDeviceInfo();
        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
            // Try one more time
            if (sService == null) {
                Log.e(TAG, "Failed to recover NFC Service.");
                return null;
            }
            try {
                return sService.getWlcListenerDeviceInfo();
            } catch (RemoteException ee) {
                Log.e(TAG, "Failed to recover NFC Service.");
            }
            return null;
        }
    }

    /**
     * Vendor NCI command success.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_NFC_VENDOR_CMD)
    public static final int SEND_VENDOR_NCI_STATUS_SUCCESS = 0;
    /**
     * Vendor NCI command rejected.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_NFC_VENDOR_CMD)
    public static final int SEND_VENDOR_NCI_STATUS_REJECTED = 1;
    /**
     * Vendor NCI command corrupted.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_NFC_VENDOR_CMD)
    public static final int SEND_VENDOR_NCI_STATUS_MESSAGE_CORRUPTED  = 2;
    /**
     * Vendor NCI command failed with unknown reason.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_NFC_VENDOR_CMD)
    public static final int SEND_VENDOR_NCI_STATUS_FAILED = 3;

    /**
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {
            SEND_VENDOR_NCI_STATUS_SUCCESS,
            SEND_VENDOR_NCI_STATUS_REJECTED,
            SEND_VENDOR_NCI_STATUS_MESSAGE_CORRUPTED,
            SEND_VENDOR_NCI_STATUS_FAILED,
    })
    @interface SendVendorNciStatus {}

    /**
     * Message Type for NCI Command.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_NFC_VENDOR_CMD)
    public static final int MESSAGE_TYPE_COMMAND = 1;

    /**
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {
            MESSAGE_TYPE_COMMAND,
    })
    @interface MessageType {}

    /**
     * Send Vendor specific Nci Messages with custom message type.
     *
     * The format of the NCI messages are defined in the NCI specification. The platform is
     * responsible for fragmenting the payload if necessary.
     *
     * Note that mt (message type) is added at the beginning of method parameters as it is more
     * distinctive than other parameters and was requested from vendor.
     *
     * @param mt message Type of the command
     * @param gid group ID of the command. This needs to be one of the vendor reserved GIDs from
     *            the NCI specification
     * @param oid opcode ID of the command. This is left to the OEM / vendor to decide
     * @param payload containing vendor Nci message payload
     * @return message send status
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_NFC_VENDOR_CMD)
    @RequiresPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
    public @SendVendorNciStatus int sendVendorNciMessage(@MessageType int mt,
            @IntRange(from = 0, to = 15) int gid, @IntRange(from = 0) int oid,
            @NonNull byte[] payload) {
        Objects.requireNonNull(payload, "Payload must not be null");
        try {
            return sService.sendVendorNciMessage(mt, gid, oid, payload);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Register an {@link NfcVendorNciCallback} to listen for Nfc vendor responses and notifications
     * <p>The provided callback will be invoked by the given {@link Executor}.
     *
     * <p>When first registering a callback, the callbacks's
     * {@link NfcVendorNciCallback#onVendorNciCallBack(byte[])} is immediately invoked to
     * notify the vendor notification.
     *
     * @param executor an {@link Executor} to execute given callback
     * @param callback user implementation of the {@link NfcVendorNciCallback}
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_NFC_VENDOR_CMD)
    @RequiresPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
    public void registerNfcVendorNciCallback(@NonNull @CallbackExecutor Executor executor,
            @NonNull NfcVendorNciCallback callback) {
        mNfcVendorNciCallbackListener.register(executor, callback);
    }

    /**
     * Unregister the specified {@link NfcVendorNciCallback}
     *
     * <p>The same {@link NfcVendorNciCallback} object used when calling
     * {@link #registerNfcVendorNciCallback(Executor, NfcVendorNciCallback)} must be used.
     *
     * <p>Callbacks are automatically unregistered when application process goes away
     *
     * @param callback user implementation of the {@link NfcVendorNciCallback}
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_ENABLE_NFC_MAINLINE)
    @RequiresPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
    public void unregisterNfcVendorNciCallback(@NonNull NfcVendorNciCallback callback) {
        mNfcVendorNciCallbackListener.unregister(callback);
    }

    /**
     * Interface for receiving vendor NCI responses and notifications.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_NFC_VENDOR_CMD)
    public interface NfcVendorNciCallback {
        /**
         * Invoked when a vendor specific NCI response is received.
         *
         * @param gid group ID of the command. This needs to be one of the vendor reserved GIDs from
         *            the NCI specification.
         * @param oid opcode ID of the command. This is left to the OEM / vendor to decide.
         * @param payload containing vendor Nci message payload.
         */
        @FlaggedApi(Flags.FLAG_NFC_VENDOR_CMD)
        void onVendorNciResponse(
                @IntRange(from = 0, to = 15) int gid, int oid, @NonNull byte[] payload);

        /**
         * Invoked when a vendor specific NCI notification is received.
         *
         * @param gid group ID of the command. This needs to be one of the vendor reserved GIDs from
         *            the NCI specification.
         * @param oid opcode ID of the command. This is left to the OEM / vendor to decide.
         * @param payload containing vendor Nci message payload.
         */
        @FlaggedApi(Flags.FLAG_NFC_VENDOR_CMD)
        void onVendorNciNotification(
                @IntRange(from = 9, to = 15) int gid, int oid, @NonNull byte[] payload);
    }
}
