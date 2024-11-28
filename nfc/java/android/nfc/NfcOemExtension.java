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

import static android.nfc.cardemulation.CardEmulation.PROTOCOL_AND_TECHNOLOGY_ROUTE_DH;
import static android.nfc.cardemulation.CardEmulation.PROTOCOL_AND_TECHNOLOGY_ROUTE_ESE;
import static android.nfc.cardemulation.CardEmulation.PROTOCOL_AND_TECHNOLOGY_ROUTE_UICC;
import static android.nfc.cardemulation.CardEmulation.routeIntToString;

import android.Manifest;
import android.annotation.CallbackExecutor;
import android.annotation.DurationMillisLong;
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.nfc.cardemulation.ApduServiceInfo;
import android.nfc.cardemulation.CardEmulation;
import android.nfc.cardemulation.CardEmulation.ProtocolAndTechnologyRoute;
import android.os.Binder;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.se.omapi.Reader;
import android.util.Log;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

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
    private static final int TYPE_TECHNOLOGY = 0;
    private static final int TYPE_PROTOCOL = 1;
    private static final int TYPE_AID = 2;
    private static final int TYPE_SYSTEMCODE = 3;

    private final NfcAdapter mAdapter;
    private final NfcOemExtensionCallback mOemNfcExtensionCallback;
    private boolean mIsRegistered = false;
    private final Map<Callback, Executor> mCallbackMap = new HashMap<>();
    private final Context mContext;
    private final Object mLock = new Object();
    private boolean mCardEmulationActivated = false;
    private boolean mRfFieldActivated = false;
    private boolean mRfDiscoveryStarted = false;
    private boolean mEeListenActivated = false;

    /**
     * Broadcast Action: Sent on NFC stack initialization when NFC OEM extensions are enabled.
     * <p> OEM extension modules should use this intent to start their extension service </p>
     * @hide
     */
    public static final String ACTION_OEM_EXTENSION_INIT = "android.nfc.action.OEM_EXTENSION_INIT";

    /**
     * Mode Type for {@link #setControllerAlwaysOnMode(int)}.
     * Enables the controller in default mode when NFC is disabled (existing API behavior).
     * works same as {@link NfcAdapter#setControllerAlwaysOn(boolean)}.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_NFC_OEM_EXTENSION)
    public static final int ENABLE_DEFAULT = NfcAdapter.CONTROLLER_ALWAYS_ON_MODE_DEFAULT;

    /**
     * Mode Type for {@link #setControllerAlwaysOnMode(int)}.
     * Enables the controller in transparent mode when NFC is disabled.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_NFC_OEM_EXTENSION)
    public static final int ENABLE_TRANSPARENT = 2;

    /**
     * Mode Type for {@link #setControllerAlwaysOnMode(int)}.
     * Enables the controller and initializes and enables the EE subsystem when NFC is disabled.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_NFC_OEM_EXTENSION)
    public static final int ENABLE_EE = 3;

    /**
     * Mode Type for {@link #setControllerAlwaysOnMode(int)}.
     * Disable the Controller Always On Mode.
     * works same as {@link NfcAdapter#setControllerAlwaysOn(boolean)}.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_NFC_OEM_EXTENSION)
    public static final int DISABLE = NfcAdapter.CONTROLLER_ALWAYS_ON_DISABLE;

    /**
     * Possible controller modes for {@link #setControllerAlwaysOnMode(int)}.
     *
     * @hide
     */
    @IntDef(prefix = { "" }, value = {
        ENABLE_DEFAULT,
        ENABLE_TRANSPARENT,
        ENABLE_EE,
        DISABLE,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ControllerMode{}

    /**
     * Technology Type for {@link #getActiveNfceeList()}.
     * @hide
     */
    @FlaggedApi(Flags.FLAG_NFC_OEM_EXTENSION)
    public static final int NFCEE_TECH_NONE = 0;

    /**
     * Technology Type for {@link #getActiveNfceeList()}.
     * @hide
     */
    @FlaggedApi(Flags.FLAG_NFC_OEM_EXTENSION)
    public static final int NFCEE_TECH_A = 1;

    /**
     * Technology Type for {@link #getActiveNfceeList()}.
     * @hide
     */
    @FlaggedApi(Flags.FLAG_NFC_OEM_EXTENSION)
    public static final int NFCEE_TECH_B = 1 << 1;

    /**
     * Technology Type for {@link #getActiveNfceeList()}.
     * @hide
     */
    @FlaggedApi(Flags.FLAG_NFC_OEM_EXTENSION)
    public static final int NFCEE_TECH_F = 1 << 2;

    /**
     * Nfc technology flags for {@link #getActiveNfceeList()}.
     *
     * @hide
     */
    @IntDef(flag = true, value = {
        NFCEE_TECH_NONE,
        NFCEE_TECH_A,
        NFCEE_TECH_B,
        NFCEE_TECH_F,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface NfceeTechnology {}

    /**
     * Event that Host Card Emulation is activated.
     */
    public static final int HCE_ACTIVATE = 1;
    /**
     * Event that some data is transferred in Host Card Emulation.
     */
    public static final int HCE_DATA_TRANSFERRED = 2;
    /**
     * Event that Host Card Emulation is deactivated.
     */
    public static final int HCE_DEACTIVATE = 3;
    /**
     * Possible events from {@link Callback#onHceEventReceived}.
     *
     * @hide
     */
    @IntDef(value = {
            HCE_ACTIVATE,
            HCE_DATA_TRANSFERRED,
            HCE_DEACTIVATE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface HostCardEmulationAction {}

    /**
     * Status code returned when the polling state change request succeeded.
     * @see #pausePolling()
     * @see #resumePolling()
     */
    public static final int POLLING_STATE_CHANGE_SUCCEEDED = 1;
    /**
     * Status code returned when the polling state change request is already in
     * required state.
     * @see #pausePolling()
     * @see #resumePolling()
     */
    public static final int POLLING_STATE_CHANGE_ALREADY_IN_REQUESTED_STATE = 2;
    /**
     * Possible status codes for {@link #pausePolling()} and
     * {@link #resumePolling()}.
     * @hide
     */
    @IntDef(value = {
            POLLING_STATE_CHANGE_SUCCEEDED,
            POLLING_STATE_CHANGE_ALREADY_IN_REQUESTED_STATE,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface PollingStateChangeStatusCode {}

    /**
     * Status OK
     */
    public static final int STATUS_OK = 0;
    /**
     * Status unknown error
     */
    public static final int STATUS_UNKNOWN_ERROR = 1;

    /**
     * Status codes passed to OEM extension callbacks.
     *
     * @hide
     */
    @IntDef(value = {
            STATUS_OK,
            STATUS_UNKNOWN_ERROR
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface StatusCode {}

    /**
     * Routing commit succeeded.
     */
    public static final int COMMIT_ROUTING_STATUS_OK = 0;
    /**
     * Routing commit failed.
     */
    public static final int COMMIT_ROUTING_STATUS_FAILED = 3;
    /**
     * Routing commit failed due to the update is in progress.
     */
    public static final int COMMIT_ROUTING_STATUS_FAILED_UPDATE_IN_PROGRESS = 6;

    /**
     * Status codes returned when calling {@link #forceRoutingTableCommit()}
     * @hide
     */
    @IntDef(prefix = "COMMIT_ROUTING_STATUS_", value = {
            COMMIT_ROUTING_STATUS_OK,
            COMMIT_ROUTING_STATUS_FAILED,
            COMMIT_ROUTING_STATUS_FAILED_UPDATE_IN_PROGRESS,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CommitRoutingStatusCode {}
    /**
     * Interface for Oem extensions for NFC.
     */
    public interface Callback {
        /**
         * Notify Oem to tag is connected or not
         * ex - if tag is connected  notify cover and Nfctest app if app is in testing mode
         *
         * @param connected status of the tag true if tag is connected otherwise false
         */
        void onTagConnected(boolean connected);

        /**
         * Update the Nfc Adapter State
         * @param state new state that need to be updated
         */
        void onStateUpdated(@NfcAdapter.AdapterState int state);
        /**
         * Check if NfcService apply routing method need to be skipped for
         * some feature.
         * @param isSkipped The {@link Consumer} to be completed. If apply routing can be skipped,
         *                  the {@link Consumer#accept(Object)} should be called with
         *                  {@link Boolean#TRUE}, otherwise call with {@link Boolean#FALSE}.
         */
        void onApplyRouting(@NonNull Consumer<Boolean> isSkipped);
        /**
         * Check if NfcService ndefRead method need to be skipped To skip
         * and start checking for presence of tag
         * @param isSkipped The {@link Consumer} to be completed. If Ndef read can be skipped,
         *                  the {@link Consumer#accept(Object)} should be called with
         *                  {@link Boolean#TRUE}, otherwise call with {@link Boolean#FALSE}.
         */
        void onNdefRead(@NonNull Consumer<Boolean> isSkipped);
        /**
         * Method to check if Nfc is allowed to be enabled by OEMs.
         * @param isAllowed The {@link Consumer} to be completed. If enabling NFC is allowed,
         *                  the {@link Consumer#accept(Object)} should be called with
         *                  {@link Boolean#TRUE}, otherwise call with {@link Boolean#FALSE}.
         * false if NFC cannot be enabled at this time.
         */
        void onEnableRequested(@NonNull Consumer<Boolean> isAllowed);
        /**
         * Method to check if Nfc is allowed to be disabled by OEMs.
         * @param isAllowed The {@link Consumer} to be completed. If disabling NFC is allowed,
         *                  the {@link Consumer#accept(Object)} should be called with
         *                  {@link Boolean#TRUE}, otherwise call with {@link Boolean#FALSE}.
         * false if NFC cannot be disabled at this time.
         */
        void onDisableRequested(@NonNull Consumer<Boolean> isAllowed);

        /**
         * Callback to indicate that Nfc starts to boot.
         */
        void onBootStarted();

        /**
         * Callback to indicate that Nfc starts to enable.
         */
        void onEnableStarted();

        /**
         * Callback to indicate that Nfc starts to disable.
         */
        void onDisableStarted();

        /**
         * Callback to indicate if NFC boots successfully or not.
         * @param status the status code indicating if boot finished successfully
         */
        void onBootFinished(@StatusCode int status);

        /**
         * Callback to indicate if NFC is successfully enabled.
         * @param status the status code indicating if enable finished successfully
         */
        void onEnableFinished(@StatusCode int status);

        /**
         * Callback to indicate if NFC is successfully disabled.
         * @param status the status code indicating if disable finished successfully
         */
        void onDisableFinished(@StatusCode int status);

        /**
         * Check if NfcService tag dispatch need to be skipped.
         * @param isSkipped The {@link Consumer} to be completed. If tag dispatch can be skipped,
         *                  the {@link Consumer#accept(Object)} should be called with
         *                  {@link Boolean#TRUE}, otherwise call with {@link Boolean#FALSE}.
         */
        void onTagDispatch(@NonNull Consumer<Boolean> isSkipped);

        /**
         * Notifies routing configuration is changed.
         * @param isCommitRoutingSkipped The {@link Consumer} to be
         * completed. If routing commit should be skipped,
         * the {@link Consumer#accept(Object)} should be called with
         * {@link Boolean#TRUE}, otherwise call with {@link Boolean#FALSE}.
         */
        void onRoutingChanged(@NonNull Consumer<Boolean> isCommitRoutingSkipped);

        /**
         * API to activate start stop cpu boost on hce event.
         *
         * <p>When HCE is activated, transferring data, and deactivated,
         * must call this method to activate, start and stop cpu boost respectively.
         * @param action Flag indicating actions to activate, start and stop cpu boost.
         */
        void onHceEventReceived(@HostCardEmulationAction int action);

        /**
         * API to notify when reader option has been changed using
         * {@link NfcAdapter#enableReaderOption(boolean)} by some app.
         * @param enabled Flag indicating ReaderMode enabled/disabled
         */
        void onReaderOptionChanged(boolean enabled);

        /**
        * Notifies NFC is activated in listen mode.
        * NFC Forum NCI-2.3 ch.5.2.6 specification
        *
        * <p>NFCC is ready to communicate with a Card reader
        *
        * @param isActivated true, if card emulation activated, else de-activated.
        */
        void onCardEmulationActivated(boolean isActivated);

        /**
        * Notifies the Remote NFC Endpoint RF Field is activated.
        * NFC Forum NCI-2.3 ch.5.3 specification
        *
        * @param isActivated true, if RF Field is ON, else RF Field is OFF.
        */
        void onRfFieldActivated(boolean isActivated);

        /**
        * Notifies the NFC RF discovery is started or in the IDLE state.
        * NFC Forum NCI-2.3 ch.5.2 specification
        *
        * @param isDiscoveryStarted true, if RF discovery started, else RF state is Idle.
        */
        void onRfDiscoveryStarted(boolean isDiscoveryStarted);

        /**
        * Notifies the NFCEE (NFC Execution Environment) Listen has been activated.
        *
        * @param isActivated true, if EE Listen is ON, else EE Listen is OFF.
        */
        void onEeListenActivated(boolean isActivated);

        /**
        * Notifies that some NFCEE (NFC Execution Environment) has been updated.
        *
        * <p> This indicates that some applet has been installed/updated/removed in
        * one of the NFCEE's.
        * </p>
        */
        void onEeUpdated();

        /**
         * Gets the intent to find the OEM package in the OEM App market. If the consumer returns
         * {@code null} or a timeout occurs, the intent from the first available package will be
         * used instead.
         *
         * @param packages the OEM packages name stored in the tag
         * @param intentConsumer The {@link Consumer} to be completed.
         *                       The {@link Consumer#accept(Object)} should be called with
         *                       the Intent required.
         *
         */
        void onGetOemAppSearchIntent(@NonNull List<String> packages,
                                     @NonNull Consumer<Intent> intentConsumer);

        /**
         * Checks if the NDEF message contains any specific OEM package executable content
         *
         * @param tag        the {@link android.nfc.Tag Tag}
         * @param message NDEF Message to read from tag
         * @param hasOemExecutableContent The {@link Consumer} to be completed. If there is
         *                                OEM package executable content, the
         *                                {@link Consumer#accept(Object)} should be called with
         *                                {@link Boolean#TRUE}, otherwise call with
         *                                {@link Boolean#FALSE}.
         */
        void onNdefMessage(@NonNull Tag tag, @NonNull NdefMessage message,
                           @NonNull Consumer<Boolean> hasOemExecutableContent);

        /**
         * Callback to indicate the app chooser activity should be launched for handling CE
         * transaction. This is invoked for example when there are more than 1 app installed that
         * can handle the HCE transaction. OEMs can launch the Activity based
         * on their requirement.
         *
         * @param selectedAid the selected AID from APDU
         * @param services {@link ApduServiceInfo} of the service triggering the activity
         * @param failedComponent the component failed to be resolved
         * @param category the category of the service
         */
        void onLaunchHceAppChooserActivity(@NonNull String selectedAid,
                                           @NonNull List<ApduServiceInfo> services,
                                           @NonNull ComponentName failedComponent,
                                           @NonNull String category);

        /**
         * Callback to indicate tap again dialog should be launched for handling HCE transaction.
         * This is invoked for example when a CE service needs the device to unlocked before
         * handling the transaction. OEMs can launch the Activity based on their requirement.
         *
         * @param service {@link ApduServiceInfo} of the service triggering the dialog
         * @param category the category of the service
         */
        void onLaunchHceTapAgainDialog(@NonNull ApduServiceInfo service, @NonNull String category);

        /**
         * Callback to indicate that routing table is full and the OEM can optionally launch a
         * dialog to request the user to remove some Card Emulation apps from the device to free
         * routing table space.
         */
        void onRoutingTableFull();

        /**
         * Callback when OEM specified log event are notified.
         * @param item the log items that contains log information of NFC event.
         */
        void onLogEventNotified(@NonNull OemLogItems item);

        /**
         * Callback to to extract OEM defined packages from given NDEF message when
         * a NFC tag is detected. These are used to handle NFC tags encoded with a
         * proprietary format for storing app name (Android native app format).
         *
         * @param message NDEF message containing OEM package names
         * @param packageConsumer The {@link Consumer} to be completed.
         *                        The {@link Consumer#accept(Object)} should be called with
         *                        the list of package names.
         */
        void onExtractOemPackages(@NonNull NdefMessage message,
                @NonNull Consumer<List<String>> packageConsumer);
    }


    /**
     * Constructor to be used only by {@link NfcAdapter}.
     */
    NfcOemExtension(@NonNull Context context, @NonNull NfcAdapter adapter) {
        mContext = context;
        mAdapter = adapter;
        mOemNfcExtensionCallback = new NfcOemExtensionCallback();
    }

    /**
     * Get an instance of {@link T4tNdefNfcee} object for performing T4T (Type-4 Tag)
     * NDEF (NFC Data Exchange Format) NFCEE (NFC Execution Environment) operations.
     * This can be used to write NDEF data to emulate a T4T tag in an NFCEE
     * (NFC Execution Environment - eSE, SIM, etc). Refer to the NFC forum specification
     * "NFCForum-TS-NCI-2.3 section 10.4" and "NFCForum-TS-T4T-1.1 section 4.2" for more details.
     *
     * This is a singleton object which shall be used by OEM extension module to do NDEF-NFCEE
     * read/write operations.
     *
     * <p>Returns {@link T4tNdefNfcee}
     * <p>Does not cause any RF activity and does not block.
     * @return NFC Data Exchange Format (NDEF) NFC Execution Environment (NFCEE) object
     * @hide
     */
    @SystemApi
    @NonNull
    @FlaggedApi(Flags.FLAG_NFC_OEM_EXTENSION)
    public T4tNdefNfcee getT4tNdefNfcee() {
        return T4tNdefNfcee.getInstance();
    }

    /**
     * Register an {@link Callback} to listen for NFC oem extension callbacks
     * Multiple clients can register and callbacks will be invoked asynchronously.
     *
     * <p>The provided callback will be invoked by the given {@link Executor}.
     * As part of {@link #registerCallback(Executor, Callback)} the
     * {@link Callback} will be invoked with current NFC state
     * before the {@link #registerCallback(Executor, Callback)} function completes.
     *
     * @param executor an {@link Executor} to execute given callback
     * @param callback oem implementation of {@link Callback}
     */
    @FlaggedApi(Flags.FLAG_NFC_OEM_EXTENSION)
    @RequiresPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
    public void registerCallback(@NonNull @CallbackExecutor Executor executor,
            @NonNull Callback callback) {
        synchronized (mLock) {
            if (executor == null || callback == null) {
                Log.e(TAG, "Executor and Callback must not be null!");
                throw new IllegalArgumentException();
            }

            if (mCallbackMap.containsKey(callback)) {
                Log.e(TAG, "Callback already registered. Unregister existing callback before"
                        + "registering");
                throw new IllegalArgumentException();
            }
            mCallbackMap.put(callback, executor);
            if (!mIsRegistered) {
                NfcAdapter.callService(() -> {
                    NfcAdapter.sService.registerOemExtensionCallback(mOemNfcExtensionCallback);
                    mIsRegistered = true;
                });
            } else {
                updateNfCState(callback, executor);
            }
        }
    }

    private void updateNfCState(Callback callback, Executor executor) {
        if (callback != null) {
            Log.i(TAG, "updateNfCState");
            executor.execute(() -> {
                callback.onCardEmulationActivated(mCardEmulationActivated);
                callback.onRfFieldActivated(mRfFieldActivated);
                callback.onRfDiscoveryStarted(mRfDiscoveryStarted);
                callback.onEeListenActivated(mEeListenActivated);
            });
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
            if (!mCallbackMap.containsKey(callback) || !mIsRegistered) {
                Log.e(TAG, "Callback not registered");
                throw new IllegalArgumentException();
            }
            if (mCallbackMap.size() == 1) {
                NfcAdapter.callService(() -> {
                    NfcAdapter.sService.unregisterOemExtensionCallback(mOemNfcExtensionCallback);
                    mIsRegistered = false;
                    mCallbackMap.remove(callback);
                });
            } else {
                mCallbackMap.remove(callback);
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
        NfcAdapter.callService(() -> NfcAdapter.sService.clearPreference());
    }

    /**
     * Get the screen state from system and set it to current screen state.
     */
    @FlaggedApi(Flags.FLAG_NFC_OEM_EXTENSION)
    @RequiresPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
    public void synchronizeScreenState() {
        NfcAdapter.callService(() -> NfcAdapter.sService.setScreenState());
    }

    /**
     * Check if the firmware needs updating.
     *
     * <p>If an update is needed, a firmware will be triggered when NFC is disabled.
     */
    @FlaggedApi(Flags.FLAG_NFC_OEM_EXTENSION)
    @RequiresPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
    public void maybeTriggerFirmwareUpdate() {
        NfcAdapter.callService(() -> NfcAdapter.sService.checkFirmware());
    }

    /**
     * Get the Active NFCEE (NFC Execution Environment) List
     *
     * @see Reader#getName() for the list of possible NFCEE names.
     *
     * @return Map< String, @NfceeTechnology Integer >
     *         A HashMap where keys are activated secure elements and
     *         the values are bitmap of technologies supported by each secure element
     *         on success keys can contain "eSE" and "UICC", otherwise empty map.
     */
    @NonNull
    @FlaggedApi(Flags.FLAG_NFC_OEM_EXTENSION)
    public Map<String, Integer> getActiveNfceeList() {
        return NfcAdapter.callServiceReturn(() ->
            NfcAdapter.sService.fetchActiveNfceeList(), new HashMap<String, Integer>());
    }

    /**
     * Sets NFC controller always on feature.
     * <p>This API is for the NFCC internal state management. It allows to discriminate
     * the controller function from the NFC function by keeping the NFC controller on without
     * any NFC RF enabled if necessary.
     * <p>This call is asynchronous, register listener {@link NfcAdapter.ControllerAlwaysOnListener}
     * by {@link NfcAdapter#registerControllerAlwaysOnListener} to find out when the operation is
     * complete.
     * <p> Note: This adds more always on modes on top of existing
     * {@link NfcAdapter#setControllerAlwaysOn(boolean)} API which can be used to set the NFCC in
     * only {@link #ENABLE_DEFAULT} and {@link #DISABLE} modes.
     * @param mode one of {@link ControllerMode} modes
     * @throws UnsupportedOperationException if
     *   <li> if FEATURE_NFC, FEATURE_NFC_HOST_CARD_EMULATION, FEATURE_NFC_HOST_CARD_EMULATION_NFCF,
     *   FEATURE_NFC_OFF_HOST_CARD_EMULATION_UICC and FEATURE_NFC_OFF_HOST_CARD_EMULATION_ESE
     *   are unavailable </li>
     *   <li> if the feature is unavailable @see NfcAdapter#isNfcControllerAlwaysOnSupported() </li>
     * @hide
     * @see NfcAdapter#setControllerAlwaysOn(boolean)
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_NFC_OEM_EXTENSION)
    @RequiresPermission(android.Manifest.permission.NFC_SET_CONTROLLER_ALWAYS_ON)
    public void setControllerAlwaysOnMode(@ControllerMode int mode) {
        if (!NfcAdapter.sHasNfcFeature && !NfcAdapter.sHasCeFeature) {
            throw new UnsupportedOperationException();
        }
        NfcAdapter.callService(() -> NfcAdapter.sService.setControllerAlwaysOn(mode));
    }

    /**
     * Triggers NFC initialization. If OEM extension is registered
     * (indicated via `enable_oem_extension` NFC overlay), the NFC stack initialization at bootup
     * is delayed until the OEM extension app triggers the initialization via this call.
     */
    @FlaggedApi(Flags.FLAG_NFC_OEM_EXTENSION)
    @RequiresPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
    public void triggerInitialization() {
        NfcAdapter.callService(() -> NfcAdapter.sService.triggerInitialization());
    }

    /**
     * Gets the last user toggle status.
     * @return true if NFC is set to ON, false otherwise
     */
    @FlaggedApi(Flags.FLAG_NFC_OEM_EXTENSION)
    @RequiresPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
    public boolean hasUserEnabledNfc() {
        return NfcAdapter.callServiceReturn(() -> NfcAdapter.sService.getSettingStatus(), false);
    }

    /**
     * Checks if the tag is present or not.
     * @return true if the tag is present, false otherwise
     */
    @FlaggedApi(Flags.FLAG_NFC_OEM_EXTENSION)
    @RequiresPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
    public boolean isTagPresent() {
        return NfcAdapter.callServiceReturn(() -> NfcAdapter.sService.isTagPresent(), false);
    }

    /**
     * Pauses NFC tag reader mode polling for a {@code timeoutInMs} millisecond.
     * In case of {@code timeoutInMs} is zero or invalid polling will be stopped indefinitely.
     * Use {@link #resumePolling()} to resume the polling.
     * Use {@link #getMaxPausePollingTimeoutMs()} to check the max timeout value.
     * @param timeoutInMs the pause polling duration in millisecond.
     * @return status of the operation
     * @throws IllegalArgumentException if timeoutInMs value is invalid
     *         (0 < timeoutInMs < max).
     */
    @FlaggedApi(Flags.FLAG_NFC_OEM_EXTENSION)
    @RequiresPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
    public @PollingStateChangeStatusCode int pausePolling(@DurationMillisLong long timeoutInMs) {
        return NfcAdapter.callServiceReturn(() ->
                NfcAdapter.sService.pausePolling(timeoutInMs),
                POLLING_STATE_CHANGE_ALREADY_IN_REQUESTED_STATE);
    }

    /**
     * Resumes default NFC tag reader mode polling for the current device state if polling is
     * paused. Calling this while already in polling is a no-op.
     * @return status of the operation
     */
    @FlaggedApi(Flags.FLAG_NFC_OEM_EXTENSION)
    @RequiresPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
    public @PollingStateChangeStatusCode int resumePolling() {
        return NfcAdapter.callServiceReturn(() ->
                NfcAdapter.sService.resumePolling(),
                POLLING_STATE_CHANGE_ALREADY_IN_REQUESTED_STATE);
    }

    /**
     * Gets the max pause polling timeout value in millisecond.
     * @return long integer representing the max timeout
     */
    @FlaggedApi(Flags.FLAG_NFC_OEM_EXTENSION)
    @RequiresPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
    @DurationMillisLong
    public long getMaxPausePollingTimeoutMills() {
        return NfcAdapter.callServiceReturn(() ->
                NfcAdapter.sService.getMaxPausePollingTimeoutMs(), 0L);
    }

    /**
     * Set whether to enable auto routing change or not (enabled by default).
     * If disabled, routing targets are limited to a single off-host destination.
     *
     * @param state status of auto routing change, true if enable, otherwise false
     */
    @FlaggedApi(Flags.FLAG_NFC_OEM_EXTENSION)
    @RequiresPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
    public void setAutoChangeEnabled(boolean state) {
        NfcAdapter.callService(() ->
                NfcAdapter.sCardEmulationService.setAutoChangeStatus(state));
    }

    /**
     * Check if auto routing change is enabled or not.
     *
     * @return true if enabled, otherwise false
     */
    @FlaggedApi(Flags.FLAG_NFC_OEM_EXTENSION)
    @RequiresPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
    public boolean isAutoChangeEnabled() {
        return NfcAdapter.callServiceReturn(() ->
                NfcAdapter.sCardEmulationService.isAutoChangeEnabled(), false);
    }

    /**
     * Get current routing status
     *
     * @return {@link RoutingStatus} indicating the default route, default ISO-DEP
     * route and default off-host route.
     */
    @NonNull
    @FlaggedApi(Flags.FLAG_NFC_OEM_EXTENSION)
    @RequiresPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
    public RoutingStatus getRoutingStatus() {
        List<String> status = NfcAdapter.callServiceReturn(() ->
                NfcAdapter.sCardEmulationService.getRoutingStatus(), new ArrayList<>());
        return new RoutingStatus(routeStringToInt(status.get(0)),
                routeStringToInt(status.get(1)),
                routeStringToInt(status.get(2)));
    }

    /**
     * Overwrites NFC controller routing table, which includes Protocol Route, Technology Route,
     * and Empty AID Route.
     *
     * The parameter set to
     * {@link ProtocolAndTechnologyRoute#PROTOCOL_AND_TECHNOLOGY_ROUTE_UNSET}
     * can be used to keep current values for that entry. At least one route should be overridden
     * when calling this API, otherwise throw {@link IllegalArgumentException}.
     *
     * @param protocol ISO-DEP route destination, where the possible inputs are defined in
     *                 {@link ProtocolAndTechnologyRoute}.
     * @param technology Tech-A, Tech-B and Tech-F route destination, where the possible inputs
     *                   are defined in
     *                   {@link ProtocolAndTechnologyRoute}
     * @param emptyAid Zero-length AID route destination, where the possible inputs are defined in
     *                 {@link ProtocolAndTechnologyRoute}
     * @param systemCode System Code route destination, where the possible inputs are defined in
     *                   {@link ProtocolAndTechnologyRoute}
     */
    @RequiresPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
    @FlaggedApi(Flags.FLAG_NFC_OEM_EXTENSION)
    public void overwriteRoutingTable(
            @CardEmulation.ProtocolAndTechnologyRoute int protocol,
            @CardEmulation.ProtocolAndTechnologyRoute int technology,
            @CardEmulation.ProtocolAndTechnologyRoute int emptyAid,
            @CardEmulation.ProtocolAndTechnologyRoute int systemCode) {

        String protocolRoute = routeIntToString(protocol);
        String technologyRoute = routeIntToString(technology);
        String emptyAidRoute = routeIntToString(emptyAid);
        String systemCodeRoute = routeIntToString(systemCode);

        NfcAdapter.callService(() ->
                NfcAdapter.sCardEmulationService.overwriteRoutingTable(
                        mContext.getUser().getIdentifier(),
                        emptyAidRoute,
                        protocolRoute,
                        technologyRoute,
                        systemCodeRoute
                ));
    }

    /**
     * Gets current routing table entries.
     * @return List of {@link NfcRoutingTableEntry} representing current routing table
     */
    @NonNull
    @RequiresPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
    @FlaggedApi(Flags.FLAG_NFC_OEM_EXTENSION)
    public List<NfcRoutingTableEntry> getRoutingTable() {
        List<Entry> entryList = NfcAdapter.callServiceReturn(() ->
                NfcAdapter.sService.getRoutingTableEntryList(), null);
        List<NfcRoutingTableEntry> result = new ArrayList<>();
        for (Entry entry : entryList) {
            switch (entry.getType()) {
                case TYPE_TECHNOLOGY -> result.add(
                        new RoutingTableTechnologyEntry(entry.getNfceeId(),
                                RoutingTableTechnologyEntry.techStringToInt(entry.getEntry()))
                );
                case TYPE_PROTOCOL -> result.add(
                        new RoutingTableProtocolEntry(entry.getNfceeId(),
                                RoutingTableProtocolEntry.protocolStringToInt(entry.getEntry()))
                );
                case TYPE_AID -> result.add(
                        new RoutingTableAidEntry(entry.getNfceeId(), entry.getEntry())
                );
                case TYPE_SYSTEMCODE -> result.add(
                        new RoutingTableSystemCodeEntry(entry.getNfceeId(),
                                entry.getEntry().getBytes(StandardCharsets.UTF_8))
                );
            }
        }
        return result;
    }

    /**
     * API to force a routing table commit.
     * @return a {@link StatusCode} to indicate if commit routing succeeded or not
     */
    @RequiresPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
    @FlaggedApi(Flags.FLAG_NFC_OEM_EXTENSION)
    @CommitRoutingStatusCode
    public int forceRoutingTableCommit() {
        return NfcAdapter.callServiceReturn(
                () -> NfcAdapter.sService.commitRouting(), COMMIT_ROUTING_STATUS_FAILED);
    }

    private final class NfcOemExtensionCallback extends INfcOemExtensionCallback.Stub {

        @Override
        public void onTagConnected(boolean connected) throws RemoteException {
            mCallbackMap.forEach((cb, ex) ->
                    handleVoidCallback(connected, cb::onTagConnected, ex));
        }

        @Override
        public void onCardEmulationActivated(boolean isActivated) throws RemoteException {
            mCardEmulationActivated = isActivated;
            mCallbackMap.forEach((cb, ex) ->
                    handleVoidCallback(isActivated, cb::onCardEmulationActivated, ex));
        }

        @Override
        public void onRfFieldActivated(boolean isActivated) throws RemoteException {
            mRfFieldActivated = isActivated;
            mCallbackMap.forEach((cb, ex) ->
                    handleVoidCallback(isActivated, cb::onRfFieldActivated, ex));
        }

        @Override
        public void onRfDiscoveryStarted(boolean isDiscoveryStarted) throws RemoteException {
            mRfDiscoveryStarted = isDiscoveryStarted;
            mCallbackMap.forEach((cb, ex) ->
                    handleVoidCallback(isDiscoveryStarted, cb::onRfDiscoveryStarted, ex));
        }

        @Override
        public void onEeListenActivated(boolean isActivated) throws RemoteException {
            mEeListenActivated = isActivated;
            mCallbackMap.forEach((cb, ex) ->
                    handleVoidCallback(isActivated, cb::onEeListenActivated, ex));
        }

        @Override
        public void onEeUpdated() throws RemoteException {
            mCallbackMap.forEach((cb, ex) ->
                    handleVoidCallback(null, (Object input) -> cb.onEeUpdated(), ex));
        }

        @Override
        public void onStateUpdated(int state) throws RemoteException {
            mCallbackMap.forEach((cb, ex) ->
                    handleVoidCallback(state, cb::onStateUpdated, ex));
        }

        @Override
        public void onApplyRouting(ResultReceiver isSkipped) throws RemoteException {
            mCallbackMap.forEach((cb, ex) ->
                    handleVoidCallback(
                        new ReceiverWrapper<>(isSkipped), cb::onApplyRouting, ex));
        }
        @Override
        public void onNdefRead(ResultReceiver isSkipped) throws RemoteException {
            mCallbackMap.forEach((cb, ex) ->
                    handleVoidCallback(
                        new ReceiverWrapper<>(isSkipped), cb::onNdefRead, ex));
        }
        @Override
        public void onEnable(ResultReceiver isAllowed) throws RemoteException {
            mCallbackMap.forEach((cb, ex) ->
                    handleVoidCallback(
                        new ReceiverWrapper<>(isAllowed), cb::onEnableRequested, ex));
        }
        @Override
        public void onDisable(ResultReceiver isAllowed) throws RemoteException {
            mCallbackMap.forEach((cb, ex) ->
                    handleVoidCallback(
                        new ReceiverWrapper<>(isAllowed), cb::onDisableRequested, ex));
        }
        @Override
        public void onBootStarted() throws RemoteException {
            mCallbackMap.forEach((cb, ex) ->
                    handleVoidCallback(null, (Object input) -> cb.onBootStarted(), ex));
        }
        @Override
        public void onEnableStarted() throws RemoteException {
            mCallbackMap.forEach((cb, ex) ->
                    handleVoidCallback(null, (Object input) -> cb.onEnableStarted(), ex));
        }
        @Override
        public void onDisableStarted() throws RemoteException {
            mCallbackMap.forEach((cb, ex) ->
                    handleVoidCallback(null, (Object input) -> cb.onDisableStarted(), ex));
        }
        @Override
        public void onBootFinished(int status) throws RemoteException {
            mCallbackMap.forEach((cb, ex) ->
                    handleVoidCallback(status, cb::onBootFinished, ex));
        }
        @Override
        public void onEnableFinished(int status) throws RemoteException {
            mCallbackMap.forEach((cb, ex) ->
                    handleVoidCallback(status, cb::onEnableFinished, ex));
        }
        @Override
        public void onDisableFinished(int status) throws RemoteException {
            mCallbackMap.forEach((cb, ex) ->
                    handleVoidCallback(status, cb::onDisableFinished, ex));
        }
        @Override
        public void onTagDispatch(ResultReceiver isSkipped) throws RemoteException {
            mCallbackMap.forEach((cb, ex) ->
                    handleVoidCallback(
                        new ReceiverWrapper<>(isSkipped), cb::onTagDispatch, ex));
        }
        @Override
        public void onRoutingChanged(ResultReceiver isSkipped) throws RemoteException {
            mCallbackMap.forEach((cb, ex) ->
                    handleVoidCallback(
                            new ReceiverWrapper<>(isSkipped), cb::onRoutingChanged, ex));
        }
        @Override
        public void onHceEventReceived(int action) throws RemoteException {
            mCallbackMap.forEach((cb, ex) ->
                    handleVoidCallback(action, cb::onHceEventReceived, ex));
        }

        @Override
        public void onReaderOptionChanged(boolean enabled) throws RemoteException {
            mCallbackMap.forEach((cb, ex) ->
                    handleVoidCallback(enabled, cb::onReaderOptionChanged, ex));
        }

        public void onRoutingTableFull() throws RemoteException {
            mCallbackMap.forEach((cb, ex) ->
                    handleVoidCallback(null,
                            (Object input) -> cb.onRoutingTableFull(), ex));
        }

        @Override
        public void onGetOemAppSearchIntent(List<String> packages, ResultReceiver intentConsumer)
                throws RemoteException {
            mCallbackMap.forEach((cb, ex) ->
                    handleVoid2ArgCallback(packages, new ReceiverWrapper<>(intentConsumer),
                            cb::onGetOemAppSearchIntent, ex));
        }

        @Override
        public void onNdefMessage(Tag tag, NdefMessage message,
                                  ResultReceiver hasOemExecutableContent) throws RemoteException {
            mCallbackMap.forEach((cb, ex) -> {
                synchronized (mLock) {
                    final long identity = Binder.clearCallingIdentity();
                    try {
                        ex.execute(() -> cb.onNdefMessage(
                                tag, message, new ReceiverWrapper<>(hasOemExecutableContent)));
                    } catch (RuntimeException exception) {
                        throw exception;
                    } finally {
                        Binder.restoreCallingIdentity(identity);
                    }
                }
            });
        }

        @Override
        public void onLaunchHceAppChooserActivity(String selectedAid,
                                                  List<ApduServiceInfo> services,
                                                  ComponentName failedComponent, String category)
                throws RemoteException {
            mCallbackMap.forEach((cb, ex) -> {
                synchronized (mLock) {
                    final long identity = Binder.clearCallingIdentity();
                    try {
                        ex.execute(() -> cb.onLaunchHceAppChooserActivity(
                                selectedAid, services, failedComponent, category));
                    } catch (RuntimeException exception) {
                        throw exception;
                    } finally {
                        Binder.restoreCallingIdentity(identity);
                    }
                }
            });
        }

        @Override
        public void onLaunchHceTapAgainActivity(ApduServiceInfo service, String category)
                throws RemoteException {
            mCallbackMap.forEach((cb, ex) ->
                    handleVoid2ArgCallback(service, category, cb::onLaunchHceTapAgainDialog, ex));
        }

        @Override
        public void onLogEventNotified(OemLogItems item) throws RemoteException  {
            mCallbackMap.forEach((cb, ex) ->
                    handleVoidCallback(item, cb::onLogEventNotified, ex));
        }

        @Override
        public void onExtractOemPackages(NdefMessage message, ResultReceiver packageConsumer)
                throws RemoteException {
            mCallbackMap.forEach((cb, ex) ->
                    handleVoid2ArgCallback(message,
                            new ReceiverWrapper<>(packageConsumer),
                            cb::onExtractOemPackages, ex));
        }

        private <T> void handleVoidCallback(
                T input, Consumer<T> callbackMethod, Executor executor) {
            synchronized (mLock) {
                final long identity = Binder.clearCallingIdentity();
                try {
                    executor.execute(() -> callbackMethod.accept(input));
                } catch (RuntimeException ex) {
                    throw ex;
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
        }

        private <T1, T2> void handleVoid2ArgCallback(
                T1 input1, T2 input2, BiConsumer<T1, T2> callbackMethod, Executor executor) {
            synchronized (mLock) {
                final long identity = Binder.clearCallingIdentity();
                try {
                    executor.execute(() -> callbackMethod.accept(input1, input2));
                } catch (RuntimeException ex) {
                    throw ex;
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
        }

        private <S, T> S handleNonVoidCallbackWithInput(
                S defaultValue, T input, Function<T, S> callbackMethod) throws RemoteException {
            synchronized (mLock) {
                final long identity = Binder.clearCallingIdentity();
                S result = defaultValue;
                try {
                    ExecutorService executor = Executors.newSingleThreadExecutor();
                    FutureTask<S> futureTask = new FutureTask<>(() -> callbackMethod.apply(input));
                    var unused = executor.submit(futureTask);
                    try {
                        result = futureTask.get(
                                OEM_EXTENSION_RESPONSE_THRESHOLD_MS, TimeUnit.MILLISECONDS);
                    } catch (ExecutionException | InterruptedException e) {
                        e.printStackTrace();
                    } catch (TimeoutException e) {
                        Log.w(TAG, "Callback timed out: " + callbackMethod);
                        e.printStackTrace();
                    } finally {
                        executor.shutdown();
                    }
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
                return result;
            }
        }

        private <T> T handleNonVoidCallbackWithoutInput(T defaultValue, Supplier<T> callbackMethod)
                throws RemoteException {
            synchronized (mLock) {
                final long identity = Binder.clearCallingIdentity();
                T result = defaultValue;
                try {
                    ExecutorService executor = Executors.newSingleThreadExecutor();
                    FutureTask<T> futureTask = new FutureTask<>(callbackMethod::get);
                    var unused = executor.submit(futureTask);
                    try {
                        result = futureTask.get(
                                OEM_EXTENSION_RESPONSE_THRESHOLD_MS, TimeUnit.MILLISECONDS);
                    } catch (ExecutionException | InterruptedException e) {
                        e.printStackTrace();
                    } catch (TimeoutException e) {
                        Log.w(TAG, "Callback timed out: " + callbackMethod);
                        e.printStackTrace();
                    } finally {
                        executor.shutdown();
                    }
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
                return result;
            }
        }
    }

    private @CardEmulation.ProtocolAndTechnologyRoute int routeStringToInt(String route) {
        if (route.equals("DH")) {
            return PROTOCOL_AND_TECHNOLOGY_ROUTE_DH;
        } else if (route.startsWith("eSE")) {
            return PROTOCOL_AND_TECHNOLOGY_ROUTE_ESE;
        } else if (route.startsWith("SIM")) {
            return PROTOCOL_AND_TECHNOLOGY_ROUTE_UICC;
        } else {
            throw new IllegalStateException("Unexpected value: " + route);
        }
    }

    private class ReceiverWrapper<T> implements Consumer<T> {
        private final ResultReceiver mResultReceiver;

        ReceiverWrapper(ResultReceiver resultReceiver) {
            mResultReceiver = resultReceiver;
        }

        @Override
        public void accept(T result) {
            if (result instanceof Boolean) {
                mResultReceiver.send((Boolean) result ? 1 : 0, null);
            } else if (result instanceof Intent) {
                Bundle bundle = new Bundle();
                bundle.putParcelable("intent", (Intent) result);
                mResultReceiver.send(0, bundle);
            } else if (result instanceof List<?> list) {
                if (list.stream().allMatch(String.class::isInstance)) {
                    Bundle bundle = new Bundle();
                    bundle.putStringArray("packageNames",
                            list.stream().map(pkg -> (String) pkg).toArray(String[]::new));
                    mResultReceiver.send(0, bundle);
                }
            }
        }

        @Override
        public Consumer<T> andThen(Consumer<? super T> after) {
            return Consumer.super.andThen(after);
        }
    }
}
