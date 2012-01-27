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

import java.util.HashMap;

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
 *
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>For more information about using NFC, read the
 * <a href="{@docRoot}guide/topics/nfc/index.html">Near Field Communication</a> developer guide.</p>
 * </div>
 */
public final class NfcAdapter {
    static final String TAG = "NFC";

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

    // Guarded by NfcAdapter.class
    static boolean sIsInitialized = false;

    // Final after first constructor, except for
    // attemptDeadServiceRecovery() when NFC crashes - we accept a best effort
    // recovery
    static INfcAdapter sService;
    static INfcTag sTagService;

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

    /**
     * A callback to be invoked when the system successfully delivers your {@link NdefMessage}
     * to another device.
     * @see #setOnNdefPushCompleteCallback
     */
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
     */
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
     * Returns the NfcAdapter for application context,
     * or throws if NFC is not available.
     * @hide
     */
    public static synchronized NfcAdapter getNfcAdapter(Context context) {
        if (!sIsInitialized) {
            /* is this device meant to have NFC */
            if (!hasNfcFeature()) {
                Log.v(TAG, "this device does not have NFC support");
                throw new UnsupportedOperationException();
            }

            sService = getServiceInterface();
            if (sService == null) {
                Log.e(TAG, "could not retrieve NFC service");
                throw new UnsupportedOperationException();
            }
            try {
                sTagService = sService.getNfcTagInterface();
            } catch (RemoteException e) {
                Log.e(TAG, "could not retrieve NFC Tag service");
                throw new UnsupportedOperationException();
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
     * <pre>{@code
     * NfcManager manager = (NfcManager) context.getSystemService(Context.NFC_SERVICE);
     * NfcAdapter adapter = manager.getDefaultAdapter();
     * }</pre>
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
    }

    /**
     * @hide
     */
    public Context getContext() {
        return mContext;
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
     * Set the {@link NdefMessage} to push over NFC during the specified activities.
     *
     * <p>This method may be called at any time, but the NDEF message is
     * only made available for NDEF push when one of the specified activities
     * is in resumed (foreground) state.
     *
     * <p>Only one NDEF message can be pushed by the currently resumed activity.
     * If both {@link #setNdefPushMessage} and
     * {@link #setNdefPushMessageCallback} are set then
     * the callback will take priority.
     *
     * <p>Pass a null NDEF message to disable foreground NDEF push in the
     * specified activities.
     *
     * <p>At least one activity must be specified, and usually only one is necessary.
     *
     * <p class="note">Requires the {@link android.Manifest.permission#NFC} permission.
     *
     * @param message NDEF message to push over NFC, or null to disable
     * @param activity an activity in which NDEF push should be enabled to share the provided
     *                 NDEF message
     * @param activities optional additional activities that should also enable NDEF push with
     *                   the provided NDEF message
     */
    public void setNdefPushMessage(NdefMessage message, Activity activity,
            Activity ... activities) {
        if (activity == null) {
            throw new NullPointerException("activity cannot be null");
        }
        mNfcActivityManager.setNdefPushMessage(activity, message);
        for (Activity a : activities) {
            if (a == null) {
                throw new NullPointerException("activities cannot contain null");
            }
            mNfcActivityManager.setNdefPushMessage(a, message);
        }
    }

    /**
     * Set the callback to create a {@link NdefMessage} to push over NFC.
     *
     * <p>This method may be called at any time, but this callback is
     * only made if one of the specified activities
     * is in resumed (foreground) state.
     *
     * <p>Only one NDEF message can be pushed by the currently resumed activity.
     * If both {@link #setNdefPushMessage} and
     * {@link #setNdefPushMessageCallback} are set then
     * the callback will take priority.
     *
     * <p>Pass a null callback to disable the callback in the
     * specified activities.
     *
     * <p>At least one activity must be specified, and usually only one is necessary.
     *
     * <p class="note">Requires the {@link android.Manifest.permission#NFC} permission.
     *
     * @param callback callback, or null to disable
     * @param activity an activity in which NDEF push should be enabled to share an NDEF message
     *                 that's retrieved from the provided callback
     * @param activities optional additional activities that should also enable NDEF push using
     *                   the provided callback
     */
    public void setNdefPushMessageCallback(CreateNdefMessageCallback callback, Activity activity,
            Activity ... activities) {
        if (activity == null) {
            throw new NullPointerException("activity cannot be null");
        }
        mNfcActivityManager.setNdefPushMessageCallback(activity, callback);
        for (Activity a : activities) {
            if (a == null) {
                throw new NullPointerException("activities cannot contain null");
            }
            mNfcActivityManager.setNdefPushMessageCallback(a, callback);
        }
    }

    /**
     * Set the callback on a successful NDEF push over NFC.
     *
     * <p>This method may be called at any time, but NDEF push and this callback
     * can only occur when one of the specified activities is in resumed
     * (foreground) state.
     *
     * <p>One or more activities must be specified.
     *
     * <p class="note">Requires the {@link android.Manifest.permission#NFC} permission.
     *
     * @param callback callback, or null to disable
     * @param activity an activity to enable the callback (at least one is required)
     * @param activities zero or more additional activities to enable to callback
     */
    public void setOnNdefPushCompleteCallback(OnNdefPushCompleteCallback callback,
            Activity activity, Activity ... activities) {
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
     * @deprecated use {@link #setNdefPushMessage} instead
     */
    @Deprecated
    public void enableForegroundNdefPush(Activity activity, NdefMessage message) {
        if (activity == null || message == null) {
            throw new NullPointerException();
        }
        enforceResumed(activity);
        mNfcActivityManager.setNdefPushMessage(activity, message);
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
     * @deprecated use {@link #setNdefPushMessage} instead
     */
    @Deprecated
    public void disableForegroundNdefPush(Activity activity) {
        if (activity == null) {
            throw new NullPointerException();
        }
        enforceResumed(activity);
        mNfcActivityManager.setNdefPushMessage(activity, null);
        mNfcActivityManager.setNdefPushMessageCallback(activity, null);
        mNfcActivityManager.setOnNdefPushCompleteCallback(activity, null);
    }

    /**
     * Enable NDEF Push feature.
     * <p>This API is for the Settings application.
     * @hide
     */
    public boolean enableNdefPush() {
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
    public boolean disableNdefPush() {
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
     * }
     * </pre>
     *
     * @see android.provider.Settings#ACTION_NFCSHARING_SETTINGS
     * @return true if NDEF Push feature is enabled
     */
    public boolean isNdefPushEnabled() {
        try {
            return sService.isNdefPushEnabled();
        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
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
    public INfcAdapterExtras getNfcAdapterExtrasInterface() {
        if (mContext == null) {
            throw new UnsupportedOperationException("You need a context on NfcAdapter to use the "
                    + " NFC extras APIs");
        }
        try {
            return sService.getNfcAdapterExtrasInterface(mContext.getPackageName());
        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
            return null;
        }
    }

    void enforceResumed(Activity activity) {
        if (!activity.isResumed()) {
            throw new IllegalStateException("API cannot be called while activity is paused");
        }
    }
}
