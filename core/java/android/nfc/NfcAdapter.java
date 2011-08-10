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
import android.app.Activity;
import android.app.ActivityThread;
import android.app.OnActivityPausedListener;
import android.app.PendingIntent;
import android.content.Context;
import android.content.IntentFilter;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.nfc.tech.MifareClassic;
import android.nfc.tech.Ndef;
import android.nfc.tech.NfcA;
import android.nfc.tech.NfcF;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

/**
 * Represents the local NFC adapter.
 * <p>
 * Use the helper {@link #getDefaultAdapter(Context)} to get the default NFC
 * adapter for this Android device.
 */
public final class NfcAdapter {
    private static final String TAG = "NFC";

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
     *   &lt;activity android:name=".nfc.TechFilter" android:label="NFC/TechFilter"&gt;
     *       &lt;!-- Add a technology filter --&gt;
     *       &lt;intent-filter&gt;
     *           &lt;action android:name="android.nfc.action.TECH_DISCOVERED" /&gt;
     *       &lt;/intent-filter&gt;
     *
     *       &lt;meta-data android:name="android.nfc.action.TECH_DISCOVERED"
     *           android:resource="@xml/filter_nfc"
     *       /&gt;
     *   &lt;/activity&gt;
     * </pre>
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
     * &lt;/resources&gt;
     * </pre>
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
     * Optional extra containing an array of {@link NdefMessage} present on the discovered tag for
     * the {@link #ACTION_NDEF_DISCOVERED}, {@link #ACTION_TECH_DISCOVERED}, and
     * {@link #ACTION_TAG_DISCOVERED} intents.
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
     * <p>Always contains the extra field {@link #EXTRA_STATE}
     * @hide
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_ADAPTER_STATE_CHANGED =
            "android.nfc.action.ADAPTER_STATE_CHANGED";

    /**
     * Used as an int extra field in {@link #ACTION_STATE_CHANGED}
     * intents to request the current power state. Possible values are:
     * {@link #STATE_OFF},
     * {@link #STATE_TURNING_ON},
     * {@link #STATE_ON},
     * {@link #STATE_TURNING_OFF},
     * @hide
     */
    public static final String EXTRA_ADAPTER_STATE = "android.nfc.extra.ADAPTER_STATE";

    /** @hide */
    public static final int STATE_OFF = 1;
    /** @hide */
    public static final int STATE_TURNING_ON = 2;
    /** @hide */
    public static final int STATE_ON = 3;
    /** @hide */
    public static final int STATE_TURNING_OFF = 4;

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

    /**
     * Callback passed into {@link #enableForegroundNdefPush(Activity,NdefPushCallback)}. This
     */
    public interface NdefPushCallback {
        /**
         * Called when a P2P connection is created.
         */
        NdefMessage createMessage();
        /**
         * Called when the message is pushed.
         */
        void onMessagePushed();
    }

    private static class NdefPushCallbackWrapper extends INdefPushCallback.Stub {
        private NdefPushCallback mCallback;

        public NdefPushCallbackWrapper(NdefPushCallback callback) {
            mCallback = callback;
        }

        @Override
        public NdefMessage onConnect() {
            return mCallback.createMessage();
        }

        @Override
        public void onMessagePushed() {
            mCallback.onMessagePushed();
        }
    }

    // Guarded by NfcAdapter.class
    private static boolean sIsInitialized = false;

    // Final after first constructor, except for
    // attemptDeadServiceRecovery() when NFC crashes - we accept a best effort
    // recovery
    private static INfcAdapter sService;
    private static INfcTag sTagService;

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
            try {
                sTagService = sService.getNfcTagInterface();
            } catch (RemoteException e) {
                Log.e(TAG, "could not retrieve NFC Tag service");
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
    }

    /**
     * Returns the binder interface to the service.
     * @hide
     */
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
     * NFC service dead - attempt best effort recovery
     * @hide
     */
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
        }

        return;
    }

    /**
     * Return true if this NFC Adapter has any features enabled.
     *
     * <p>Application may use this as a helper to suggest that the user
     * should turn on NFC in Settings.
     * <p>If this method returns false, the NFC hardware is guaranteed not to
     * generate or respond to any NFC transactions.
     *
     * @return true if this NFC Adapter has any features enabled
     */
    public boolean isEnabled() {
        try {
            return sService.getState() == STATE_ON;
        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
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
    public int getAdapterState() {
        try {
            return sService.getState();
        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
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
    public boolean disable() {
        try {
            return sService.disable();
        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
            return false;
        }
    }

    /**
     * Enable foreground dispatch to the given Activity.
     *
     * <p>This will give give priority to the foreground activity when
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
     */
    public void enableForegroundDispatch(Activity activity, PendingIntent intent,
            IntentFilter[] filters, String[][] techLists) {
        if (activity == null || intent == null) {
            throw new NullPointerException();
        }
        if (!activity.isResumed()) {
            throw new IllegalStateException("Foregorund dispatching can only be enabled " +
                    "when your activity is resumed");
        }
        try {
            TechListParcel parcel = null;
            if (techLists != null && techLists.length > 0) {
                parcel = new TechListParcel(techLists);
            }
            ActivityThread.currentActivityThread().registerOnActivityPausedListener(activity,
                    mForegroundDispatchListener);
            sService.enableForegroundDispatch(activity.getComponentName(), intent, filters,
                    parcel);
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
     */
    public void disableForegroundDispatch(Activity activity) {
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
            sService.disableForegroundDispatch(activity.getComponentName());
            if (!force && !activity.isResumed()) {
                throw new IllegalStateException("You must disable forgeground dispatching " +
                        "while your activity is still resumed");
            }
        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
        }
    }

    /**
     * Enable NDEF message push over P2P while this Activity is in the foreground.
     *
     * <p>For this to function properly the other NFC device being scanned must
     * support the "com.android.npp" NDEF push protocol. Support for this
     * protocol is currently optional for Android NFC devices.
     *
     * <p>This method must be called from the main thread.
     *
     * <p class="note"><em>NOTE:</em> While foreground NDEF push is active standard tag dispatch is disabled.
     * Only the foreground activity may receive tag discovered dispatches via
     * {@link #enableForegroundDispatch}.
     *
     * <p class="note">Requires the {@link android.Manifest.permission#NFC} permission.
     *
     * @param activity the foreground Activity
     * @param msg a NDEF Message to push over P2P
     * @throws IllegalStateException if the Activity is not currently in the foreground
     * @throws OperationNotSupportedException if this Android device does not support NDEF push
     */
    public void enableForegroundNdefPush(Activity activity, NdefMessage msg) {
        if (activity == null || msg == null) {
            throw new NullPointerException();
        }
        if (!activity.isResumed()) {
            throw new IllegalStateException("Foregorund NDEF push can only be enabled " +
                    "when your activity is resumed");
        }
        try {
            ActivityThread.currentActivityThread().registerOnActivityPausedListener(activity,
                    mForegroundNdefPushListener);
            sService.enableForegroundNdefPush(activity.getComponentName(), msg);
        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
        }
    }

    /**
     * Enable NDEF message push over P2P while this Activity is in the foreground.
     *
     * <p>For this to function properly the other NFC device being scanned must
     * support the "com.android.npp" NDEF push protocol. Support for this
     * protocol is currently optional for Android NFC devices.
     *
     * <p>This method must be called from the main thread.
     *
     * <p class="note"><em>NOTE:</em> While foreground NDEF push is active standard tag dispatch is disabled.
     * Only the foreground activity may receive tag discovered dispatches via
     * {@link #enableForegroundDispatch}.
     *
     * <p class="note">Requires the {@link android.Manifest.permission#NFC} permission.
     *
     * @param activity the foreground Activity
     * @param callback is called on when the P2P connection is established
     * @throws IllegalStateException if the Activity is not currently in the foreground
     * @throws OperationNotSupportedException if this Android device does not support NDEF push
     */
    public void enableForegroundNdefPush(Activity activity, NdefPushCallback callback) {
        if (activity == null || callback == null) {
            throw new NullPointerException();
        }
        if (!activity.isResumed()) {
            throw new IllegalStateException("Foregorund NDEF push can only be enabled " +
                    "when your activity is resumed");
        }
        try {
            ActivityThread.currentActivityThread().registerOnActivityPausedListener(activity,
                    mForegroundNdefPushListener);
            sService.enableForegroundNdefPushWithCallback(activity.getComponentName(),
                    new NdefPushCallbackWrapper(callback));
        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
        }
    }

    /**
     * Disable NDEF message push over P2P.
     *
     * <p>After calling {@link #enableForegroundNdefPush}, an activity
     * must call this method before its {@link Activity#onPause} callback
     * completes.
     *
     * <p>This method must be called from the main thread.
     *
     * <p class="note">Requires the {@link android.Manifest.permission#NFC} permission.
     *
     * @param activity the Foreground activity
     * @throws IllegalStateException if the Activity has already been paused
     * @throws OperationNotSupportedException if this Android device does not support NDEF push
     */
    public void disableForegroundNdefPush(Activity activity) {
        ActivityThread.currentActivityThread().unregisterOnActivityPausedListener(activity,
                mForegroundNdefPushListener);
        disableForegroundNdefPushInternal(activity, false);
    }

    OnActivityPausedListener mForegroundNdefPushListener = new OnActivityPausedListener() {
        @Override
        public void onPaused(Activity activity) {
            disableForegroundNdefPushInternal(activity, true);
        }
    };

    void disableForegroundNdefPushInternal(Activity activity, boolean force) {
        try {
            sService.disableForegroundNdefPush(activity.getComponentName());
            if (!force && !activity.isResumed()) {
                throw new IllegalStateException("You must disable forgeground NDEF push " +
                        "while your activity is still resumed");
            }
        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
        }
    }

    /**
     * Enable zero-click sharing.
     *
     * @hide
     */
    public boolean enableZeroClick() {
        try {
            return sService.enableZeroClick();
        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
            return false;
        }
    }

    /**
     * Disable zero-click sharing.
     *
     * @hide
     */
    public boolean disableZeroClick() {
        try {
            return sService.disableZeroClick();
        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
            return false;
        }
    }

    /**
     * Return true if zero-click sharing feature is enabled.
     * <p>This function can return true even if NFC is currently turned-off.
     * This indicates that zero-click is not currently active, but it has
     * been requested by the user and will be active as soon as NFC is turned
     * on.
     * <p>If you want to check if zero-click sharing is currently active, use
     * <code>{@link #isEnabled()} && {@link #isZeroClickEnabled()}</code>
     *
     * @return true if zero-click sharing is enabled
     * @hide
     */
    public boolean isZeroClickEnabled() {
        try {
            return sService.isZeroClickEnabled();
        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
            return false;
        }
    }

    /**
     * @hide
     */
    public INfcAdapterExtras getNfcAdapterExtrasInterface() {
        try {
            return sService.getNfcAdapterExtrasInterface();
        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
            return null;
        }
    }
}
