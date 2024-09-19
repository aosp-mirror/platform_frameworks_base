/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.nfc.cardemulation;

import android.Manifest;
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.SystemApi;
import android.annotation.UserHandleAware;
import android.annotation.UserIdInt;
import android.app.Activity;
import android.app.role.RoleManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.nfc.Constants;
import android.nfc.Flags;
import android.nfc.INfcCardEmulation;
import android.nfc.NfcAdapter;
import android.os.Build;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.util.Log;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * This class can be used to query the state of
 * NFC card emulation services.
 *
 * For a general introduction into NFC card emulation,
 * please read the <a href="{@docRoot}guide/topics/connectivity/nfc/hce.html">
 * NFC card emulation developer guide</a>.</p>
 *
 * <p class="note">Use of this class requires the
 * {@link PackageManager#FEATURE_NFC_HOST_CARD_EMULATION} to be present
 * on the device.
 */
public final class CardEmulation {
    private static final Pattern AID_PATTERN = Pattern.compile("[0-9A-Fa-f]{10,32}\\*?\\#?");
    private static final Pattern PLPF_PATTERN = Pattern.compile("[0-9A-Fa-f,\\?,\\*\\.]*");

    static final String TAG = "CardEmulation";

    /**
     * Activity action: ask the user to change the default
     * card emulation service for a certain category. This will
     * show a dialog that asks the user whether they want to
     * replace the current default service with the service
     * identified with the ComponentName specified in
     * {@link #EXTRA_SERVICE_COMPONENT}, for the category
     * specified in {@link #EXTRA_CATEGORY}. There is an optional
     * extra field using {@link Intent#EXTRA_USER} to specify
     * the {@link UserHandle} of the user that owns the app.
     *
     * @deprecated Please use {@link android.app.role.RoleManager#createRequestRoleIntent(String)}
     * with {@link android.app.role.RoleManager#ROLE_WALLET} parameter
     * and {@link Activity#startActivityForResult(Intent, int)} instead.
     */
    @Deprecated
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_CHANGE_DEFAULT =
            "android.nfc.cardemulation.action.ACTION_CHANGE_DEFAULT";

    /**
     * The category extra for {@link #ACTION_CHANGE_DEFAULT}.
     *
     * @see #ACTION_CHANGE_DEFAULT
     */
    public static final String EXTRA_CATEGORY = "category";

    /**
     * The service {@link ComponentName} object passed in as an
     * extra for {@link #ACTION_CHANGE_DEFAULT}.
     *
     * @see #ACTION_CHANGE_DEFAULT
     */
    public static final String EXTRA_SERVICE_COMPONENT = "component";

    /**
     * Category used for NFC payment services.
     */
    public static final String CATEGORY_PAYMENT = "payment";

    /**
     * Category that can be used for all other card emulation
     * services.
     */
    public static final String CATEGORY_OTHER = "other";

    /**
     * Return value for {@link #getSelectionModeForCategory(String)}.
     *
     * <p>In this mode, the user has set a default service for this
     *    category.
     *
     * <p>When using ISO-DEP card emulation with {@link HostApduService}
     *    or {@link OffHostApduService}, if a remote NFC device selects
     *    any of the Application IDs (AIDs)
     *    that the default service has registered in this category,
     *    that service will automatically be bound to to handle
     *    the transaction.
     */
    public static final int SELECTION_MODE_PREFER_DEFAULT = 0;

    /**
     * Return value for {@link #getSelectionModeForCategory(String)}.
     *
     * <p>In this mode, when using ISO-DEP card emulation with {@link HostApduService}
     *    or {@link OffHostApduService}, whenever an Application ID (AID) of this category
     *    is selected, the user is asked which service they want to use to handle
     *    the transaction, even if there is only one matching service.
     */
    public static final int SELECTION_MODE_ALWAYS_ASK = 1;

    /**
     * Return value for {@link #getSelectionModeForCategory(String)}.
     *
     * <p>In this mode, when using ISO-DEP card emulation with {@link HostApduService}
     *    or {@link OffHostApduService}, the user will only be asked to select a service
     *    if the Application ID (AID) selected by the reader has been registered by multiple
     *    services. If there is only one service that has registered for the AID,
     *    that service will be invoked directly.
     */
    public static final int SELECTION_MODE_ASK_IF_CONFLICT = 2;
    /**
     * Route to Device Host (DH).
     */
    @FlaggedApi(Flags.FLAG_NFC_OVERRIDE_RECOVER_ROUTING_TABLE)
    public static final int PROTOCOL_AND_TECHNOLOGY_ROUTE_DH = 0;
    /**
     * Route to eSE.
     */
    @FlaggedApi(Flags.FLAG_NFC_OVERRIDE_RECOVER_ROUTING_TABLE)
    public static final int PROTOCOL_AND_TECHNOLOGY_ROUTE_ESE = 1;
    /**
     * Route to UICC.
     */
    @FlaggedApi(Flags.FLAG_NFC_OVERRIDE_RECOVER_ROUTING_TABLE)
    public static final int PROTOCOL_AND_TECHNOLOGY_ROUTE_UICC = 2;

    /**
     * Route unset.
     */
    @FlaggedApi(Flags.FLAG_NFC_OVERRIDE_RECOVER_ROUTING_TABLE)
    public static final int PROTOCOL_AND_TECHNOLOGY_ROUTE_UNSET = -1;

    static boolean sIsInitialized = false;
    static HashMap<Context, CardEmulation> sCardEmus = new HashMap<Context, CardEmulation>();
    static INfcCardEmulation sService;

    final Context mContext;

    private CardEmulation(Context context, INfcCardEmulation service) {
        mContext = context.getApplicationContext();
        sService = service;
    }

    /**
     * Helper to get an instance of this class.
     *
     * @param adapter A reference to an NfcAdapter object.
     * @return
     */
    public static synchronized CardEmulation getInstance(NfcAdapter adapter) {
        if (adapter == null) throw new NullPointerException("NfcAdapter is null");
        Context context = adapter.getContext();
        if (context == null) {
            Log.e(TAG, "NfcAdapter context is null.");
            throw new UnsupportedOperationException();
        }
        if (!sIsInitialized) {
            PackageManager pm = context.getPackageManager();
            if (pm == null) {
                Log.e(TAG, "Cannot get PackageManager");
                throw new UnsupportedOperationException();
            }
            if (!pm.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)) {
                Log.e(TAG, "This device does not support card emulation");
                throw new UnsupportedOperationException();
            }
            sIsInitialized = true;
        }
        CardEmulation manager = sCardEmus.get(context);
        if (manager == null) {
            // Get card emu service
            INfcCardEmulation service = adapter.getCardEmulationService();
            if (service == null) {
                Log.e(TAG, "This device does not implement the INfcCardEmulation interface.");
                throw new UnsupportedOperationException();
            }
            manager = new CardEmulation(context, service);
            sCardEmus.put(context, manager);
        }
        return manager;
    }

    /**
     * Allows an application to query whether a service is currently
     * the default service to handle a card emulation category.
     *
     * <p>Note that if {@link #getSelectionModeForCategory(String)}
     * returns {@link #SELECTION_MODE_ALWAYS_ASK} or {@link #SELECTION_MODE_ASK_IF_CONFLICT},
     * this method will always return false. That is because in these
     * selection modes a default can't be set at the category level. For categories where
     * the selection mode is {@link #SELECTION_MODE_ALWAYS_ASK} or
     * {@link #SELECTION_MODE_ASK_IF_CONFLICT}, use
     * {@link #isDefaultServiceForAid(ComponentName, String)} to determine whether a service
     * is the default for a specific AID.
     *
     * @param service The ComponentName of the service
     * @param category The category
     * @return whether service is currently the default service for the category.
     *
     * <p class="note">Requires the {@link android.Manifest.permission#NFC} permission.
     */
    public boolean isDefaultServiceForCategory(ComponentName service, String category) {
        return callServiceReturn(() ->
            sService.isDefaultServiceForCategory(
                mContext.getUser().getIdentifier(), service, category), false);
    }

    /**
     *
     * Allows an application to query whether a service is currently
     * the default handler for a specified ISO7816-4 Application ID.
     *
     * @param service The ComponentName of the service
     * @param aid The ISO7816-4 Application ID
     * @return whether the service is the default handler for the specified AID
     *
     * <p class="note">Requires the {@link android.Manifest.permission#NFC} permission.
     */
    public boolean isDefaultServiceForAid(ComponentName service, String aid) {
        return callServiceReturn(() ->
            sService.isDefaultServiceForAid(
                mContext.getUser().getIdentifier(), service, aid), false);
    }

    /**
     * <p>
     * Returns whether the user has allowed AIDs registered in the
     * specified category to be handled by a service that is preferred
     * by the foreground application, instead of by a pre-configured default.
     *
     * Foreground applications can set such preferences using the
     * {@link #setPreferredService(Activity, ComponentName)} method.
     * <p class="note">
     * Starting with {@link Build.VERSION_CODES#VANILLA_ICE_CREAM}, this method will always
     * return true.
     *
     * @param category The category, e.g. {@link #CATEGORY_PAYMENT}
     * @return whether AIDs in the category can be handled by a service
     *         specified by the foreground app.
     */
    @SuppressWarnings("NonUserGetterCalled")
    public boolean categoryAllowsForegroundPreference(String category) {
        Context contextAsUser = mContext.createContextAsUser(
                UserHandle.of(UserHandle.myUserId()), 0);

        RoleManager roleManager = contextAsUser.getSystemService(RoleManager.class);
        if (roleManager.isRoleAvailable(RoleManager.ROLE_WALLET)) {
            return true;
        }

        if (CATEGORY_PAYMENT.equals(category)) {
            boolean preferForeground = false;
            try {
                preferForeground = Settings.Secure.getInt(
                        contextAsUser.getContentResolver(),
                        Constants.SETTINGS_SECURE_NFC_PAYMENT_FOREGROUND) != 0;
            } catch (SettingNotFoundException e) {
            }
            return preferForeground;
        } else {
            // Allowed for all other categories
            return true;
        }
    }

    /**
     * Returns the service selection mode for the passed in category.
     * Valid return values are:
     * <p>{@link #SELECTION_MODE_PREFER_DEFAULT} the user has requested a default
     *    service for this category, which will be preferred.
     * <p>{@link #SELECTION_MODE_ALWAYS_ASK} the user has requested to be asked
     *    every time what service they would like to use in this category.
     * <p>{@link #SELECTION_MODE_ASK_IF_CONFLICT} the user will only be asked
     *    to pick a service if there is a conflict.
     *
     * <p class="note">
     * Starting with {@link Build.VERSION_CODES#VANILLA_ICE_CREAM}, the default service defined
     * by the holder of {@link android.app.role.RoleManager#ROLE_WALLET} and is category agnostic.
     *
     * @param category The category, for example {@link #CATEGORY_PAYMENT}
     * @return the selection mode for the passed in category
     */
    public int getSelectionModeForCategory(String category) {
        if (CATEGORY_PAYMENT.equals(category)) {
            boolean paymentRegistered = callServiceReturn(() ->
                    sService.isDefaultPaymentRegistered(), false);
            if (paymentRegistered) {
                return SELECTION_MODE_PREFER_DEFAULT;
            } else {
                return SELECTION_MODE_ALWAYS_ASK;
            }
        } else {
            return SELECTION_MODE_ASK_IF_CONFLICT;
        }
    }
    /**
     * Sets whether when this service becomes the preferred service, if the NFC stack
     * should enable observe mode or disable observe mode. The default is to not enable observe
     * mode when a service either the foreground default service or the default payment service so
     * not calling this method will preserve that behavior.
     *
     * @param service The component name of the service
     * @param enable Whether the service should default to observe mode or not
     * @return whether the change was successful.
     */
    @FlaggedApi(Flags.FLAG_NFC_OBSERVE_MODE)
    public boolean setShouldDefaultToObserveModeForService(@NonNull ComponentName service,
            boolean enable) {
        return callServiceReturn(() ->
            sService.setShouldDefaultToObserveModeForService(
                mContext.getUser().getIdentifier(), service, enable), false);
    }

    /**
     * Register a polling loop filter (PLF) for a HostApduService and indicate whether it should
     * auto-transact or not.  The PLF can be sequence of an
     * even number of at least 2 hexadecimal numbers (0-9, A-F or a-f), representing a series of
     * bytes. When non-standard polling loop frame matches this sequence exactly, it may be
     * delivered to {@link HostApduService#processPollingFrames(List)}.  If auto-transact
     * is set to true and this service is currently preferred or there are no other services
     * registered for this filter then observe mode will also be disabled.
     * @param service The HostApduService to register the filter for
     * @param pollingLoopFilter The filter to register
     * @param autoTransact true to have the NFC stack automatically disable observe mode and allow
     *         transactions to proceed when this filter matches, false otherwise
     * @return true if the filter was registered, false otherwise
     * @throws IllegalArgumentException if the passed in string doesn't parse to at least one byte
     */
    @FlaggedApi(Flags.FLAG_NFC_READ_POLLING_LOOP)
    public boolean registerPollingLoopFilterForService(@NonNull ComponentName service,
            @NonNull String pollingLoopFilter, boolean autoTransact) {
        final String pollingLoopFilterV = validatePollingLoopFilter(pollingLoopFilter);
        return callServiceReturn(() ->
            sService.registerPollingLoopFilterForService(
                mContext.getUser().getIdentifier(), service, pollingLoopFilterV, autoTransact),
            false);
    }

    /**
     * Unregister a polling loop filter (PLF) for a HostApduService. If the PLF had previously been
     * registered via {@link #registerPollingLoopFilterForService(ComponentName, String, boolean)}
     * for this service it will be removed.
     * @param service The HostApduService to unregister the filter for
     * @param pollingLoopFilter The filter to unregister
     * @return true if the filter was removed, false otherwise
     * @throws IllegalArgumentException if the passed in string doesn't parse to at least one byte
     */
    @FlaggedApi(Flags.FLAG_NFC_READ_POLLING_LOOP)
    public boolean removePollingLoopFilterForService(@NonNull ComponentName service,
            @NonNull String pollingLoopFilter) {
        final String pollingLoopFilterV = validatePollingLoopFilter(pollingLoopFilter);
        return callServiceReturn(() ->
            sService.removePollingLoopFilterForService(
                mContext.getUser().getIdentifier(), service, pollingLoopFilterV), false);
    }


    /**
     * Register a polling loop pattern filter (PLPF) for a HostApduService and indicate whether it
     * should auto-transact or not. The pattern may include the characters 0-9 and A-F as well as
     * the regular expression operators `.`, `?` and `*`. When the beginning of anon-standard
     * polling loop frame matches this sequence exactly, it may be delivered to
     * {@link HostApduService#processPollingFrames(List)}. If auto-transact is set to true and this
     * service is currently preferred or there are no other services registered for this filter
     * then observe mode will also be disabled.
     * @param service The HostApduService to register the filter for
     * @param pollingLoopPatternFilter The pattern filter to register, must to be compatible with
     *         {@link java.util.regex.Pattern#compile(String)} and only contain hexadecimal numbers
     *         and `.`, `?` and `*` operators
     * @param autoTransact true to have the NFC stack automatically disable observe mode and allow
     *         transactions to proceed when this filter matches, false otherwise
     * @return true if the filter was registered, false otherwise
     * @throws IllegalArgumentException if the filter containst elements other than hexadecimal
     *         numbers and `.`, `?` and `*` operators
     * @throws java.util.regex.PatternSyntaxException if the regex syntax is invalid
     */
    @FlaggedApi(Flags.FLAG_NFC_READ_POLLING_LOOP)
    public boolean registerPollingLoopPatternFilterForService(@NonNull ComponentName service,
            @NonNull String pollingLoopPatternFilter, boolean autoTransact) {
        final String pollingLoopPatternFilterV =
            validatePollingLoopPatternFilter(pollingLoopPatternFilter);
        return callServiceReturn(() ->
            sService.registerPollingLoopPatternFilterForService(
                mContext.getUser().getIdentifier(), service, pollingLoopPatternFilterV,
                autoTransact),
            false);
    }

    /**
     * Unregister a polling loop pattern filter (PLPF) for a HostApduService. If the PLF had
     * previously been registered via
     * {@link #registerPollingLoopFilterForService(ComponentName, String, boolean)} for this
     * service it will be removed.
     * @param service The HostApduService to unregister the filter for
     * @param pollingLoopPatternFilter The filter to unregister, must to be compatible with
     *         {@link java.util.regex.Pattern#compile(String)} and only contain hexadecimal numbers
     *         and`.`, `?` and `*` operators
     * @return true if the filter was removed, false otherwise
     * @throws IllegalArgumentException if the filter containst elements other than hexadecimal
     *         numbers and `.`, `?` and `*` operators
     * @throws java.util.regex.PatternSyntaxException if the regex syntax is invalid
     */
    @FlaggedApi(Flags.FLAG_NFC_READ_POLLING_LOOP)
    public boolean removePollingLoopPatternFilterForService(@NonNull ComponentName service,
            @NonNull String pollingLoopPatternFilter) {
        final String pollingLoopPatternFilterV =
            validatePollingLoopPatternFilter(pollingLoopPatternFilter);
        return callServiceReturn(() ->
            sService.removePollingLoopPatternFilterForService(
                mContext.getUser().getIdentifier(), service, pollingLoopPatternFilterV), false);
    }

    /**
     * Registers a list of AIDs for a specific category for the
     * specified service.
     *
     * <p>If a list of AIDs for that category was previously
     * registered for this service (either statically
     * through the manifest, or dynamically by using this API),
     * that list of AIDs will be replaced with this one.
     *
     * <p>Note that you can only register AIDs for a service that
     * is running under the same UID as the caller of this API. Typically
     * this means you need to call this from the same
     * package as the service itself, though UIDs can also
     * be shared between packages using shared UIDs.
     *
     * @param service The component name of the service
     * @param category The category of AIDs to be registered
     * @param aids A list containing the AIDs to be registered
     * @return whether the registration was successful.
     */
    public boolean registerAidsForService(ComponentName service, String category,
            List<String> aids) {
        final AidGroup aidGroup = new AidGroup(aids, category);
        return callServiceReturn(() ->
            sService.registerAidGroupForService(
                mContext.getUser().getIdentifier(), service, aidGroup), false);
    }

    /**
     * Unsets the off-host Secure Element for the given service.
     *
     * <p>Note that this will only remove Secure Element that was dynamically
     * set using the {@link #setOffHostForService(ComponentName, String)}
     * and resets it to a value that was statically assigned using manifest.
     *
     * <p>Note that you can only unset off-host SE for a service that
     * is running under the same UID as the caller of this API. Typically
     * this means you need to call this from the same
     * package as the service itself, though UIDs can also
     * be shared between packages using shared UIDs.
     *
     * @param service The component name of the service
     * @return whether the registration was successful.
     */
    @RequiresPermission(android.Manifest.permission.NFC)
    @NonNull
    public boolean unsetOffHostForService(@NonNull ComponentName service) {
        return callServiceReturn(() ->
            sService.unsetOffHostForService(
                mContext.getUser().getIdentifier(), service), false);
    }

    /**
     * Sets the off-host Secure Element for the given service.
     *
     * <p>If off-host SE was initially set (either statically
     * through the manifest, or dynamically by using this API),
     * it will be replaced with this one. All AIDs registered by
     * this service will be re-routed to this Secure Element if
     * successful. AIDs that was statically assigned using manifest
     * will re-route to off-host SE that stated in manifest after NFC
     * toggle.
     *
     * <p>Note that you can only set off-host SE for a service that
     * is running under the same UID as the caller of this API. Typically
     * this means you need to call this from the same
     * package as the service itself, though UIDs can also
     * be shared between packages using shared UIDs.
     *
     * <p>Registeration will be successful only if the Secure Element
     * exists on the device.
     *
     * @param service The component name of the service
     * @param offHostSecureElement Secure Element to register the AID to. Only accept strings with
     *                             prefix SIM or prefix eSE.
     *                             Ref: GSMA TS.26 - NFC Handset Requirements
     *                             TS26_NFC_REQ_069: For UICC, Secure Element Name SHALL be
     *                                               SIM[smartcard slot]
     *                                               (e.g. SIM/SIM1, SIM2… SIMn).
     *                             TS26_NFC_REQ_070: For embedded SE, Secure Element Name SHALL be
     *                                               eSE[number]
     *                                               (e.g. eSE/eSE1, eSE2, etc.).
     * @return whether the registration was successful.
     */
    @RequiresPermission(android.Manifest.permission.NFC)
    @NonNull
    public boolean setOffHostForService(@NonNull ComponentName service,
            @NonNull String offHostSecureElement) {
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(mContext);
        if (adapter == null || offHostSecureElement == null) {
            return false;
        }

        List<String> validSE = adapter.getSupportedOffHostSecureElements();
        if ((offHostSecureElement.startsWith("eSE") && !validSE.contains("eSE"))
                || (offHostSecureElement.startsWith("SIM") && !validSE.contains("SIM"))) {
            return false;
        }

        if (!offHostSecureElement.startsWith("eSE") && !offHostSecureElement.startsWith("SIM")) {
            return false;
        }

        if (offHostSecureElement.equals("eSE")) {
            offHostSecureElement = "eSE1";
        } else if (offHostSecureElement.equals("SIM")) {
            offHostSecureElement = "SIM1";
        }
        final String offHostSecureElementV = new String(offHostSecureElement);
        return callServiceReturn(() ->
            sService.setOffHostForService(
                mContext.getUser().getIdentifier(), service, offHostSecureElementV), false);
    }

    /**
     * Retrieves the currently registered AIDs for the specified
     * category for a service.
     *
     * <p>Note that this will only return AIDs that were dynamically
     * registered using {@link #registerAidsForService(ComponentName, String, List)}
     * method. It will *not* return AIDs that were statically registered
     * in the manifest.
     *
     * @param service The component name of the service
     * @param category The category for which the AIDs were registered,
     *                 e.g. {@link #CATEGORY_PAYMENT}
     * @return The list of AIDs registered for this category, or null if it couldn't be found.
     */
    public List<String> getAidsForService(ComponentName service, String category) {
        AidGroup group = callServiceReturn(() ->
               sService.getAidGroupForService(
                   mContext.getUser().getIdentifier(), service, category), null);
        return (group != null ? group.getAids() : null);
    }

    /**
     * Removes a previously registered list of AIDs for the specified category for the
     * service provided.
     *
     * <p>Note that this will only remove AIDs that were dynamically
     * registered using the {@link #registerAidsForService(ComponentName, String, List)}
     * method. It will *not* remove AIDs that were statically registered in
     * the manifest. If dynamically registered AIDs are removed using
     * this method, and a statically registered AID group for the same category
     * exists in the manifest, the static AID group will become active again.
     *
     * @param service The component name of the service
     * @param category The category of the AIDs to be removed, e.g. {@link #CATEGORY_PAYMENT}
     * @return whether the group was successfully removed.
     */
    public boolean removeAidsForService(ComponentName service, String category) {
        return callServiceReturn(() ->
            sService.removeAidGroupForService(
                mContext.getUser().getIdentifier(), service, category), false);
    }

    /**
     * Allows a foreground application to specify which card emulation service
     * should be preferred while a specific Activity is in the foreground.
     *
     * <p>The specified Activity must currently be in resumed state. A good
     * paradigm is to call this method in your {@link Activity#onResume}, and to call
     * {@link #unsetPreferredService(Activity)} in your {@link Activity#onPause}.
     *
     * <p>This method call will fail in two specific scenarios:
     * <ul>
     * <li> If the service registers one or more AIDs in the {@link #CATEGORY_PAYMENT}
     * category, but the user has indicated that foreground apps are not allowed
     * to override the default payment service.
     * <li> If the service registers one or more AIDs in the {@link #CATEGORY_OTHER}
     * category that are also handled by the default payment service, and the
     * user has indicated that foreground apps are not allowed to override the
     * default payment service.
     * </ul>
     *
     * <p> Use {@link #categoryAllowsForegroundPreference(String)} to determine
     * whether foreground apps can override the default payment service.
     *
     * <p>Note that this preference is not persisted by the OS, and hence must be
     * called every time the Activity is resumed.
     *
     * @param activity The activity which prefers this service to be invoked
     * @param service The service to be preferred while this activity is in the foreground
     * @return whether the registration was successful
     */
    public boolean setPreferredService(Activity activity, ComponentName service) {
        // Verify the activity is in the foreground before calling into NfcService
        if (activity == null || service == null) {
            throw new NullPointerException("activity or service or category is null");
        }
        return callServiceReturn(() -> sService.setPreferredService(service), false);
    }

    /**
     * Unsets the preferred service for the specified Activity.
     *
     * <p>Note that the specified Activity must still be in resumed
     * state at the time of this call. A good place to call this method
     * is in your {@link Activity#onPause} implementation.
     *
     * @param activity The activity which the service was registered for
     * @return true when successful
     */
    public boolean unsetPreferredService(Activity activity) {
        if (activity == null) {
            throw new NullPointerException("activity is null");
        }
        return callServiceReturn(() -> sService.unsetPreferredService(), false);
    }

    /**
     * Some devices may allow an application to register all
     * AIDs that starts with a certain prefix, e.g.
     * "A000000004*" to register all MasterCard AIDs.
     *
     * Use this method to determine whether this device
     * supports registering AID prefixes.
     *
     * @return whether AID prefix registering is supported on this device.
     */
    public boolean supportsAidPrefixRegistration() {
        return callServiceReturn(() -> sService.supportsAidPrefixRegistration(), false);
    }

    /**
     * Retrieves the registered AIDs for the preferred payment service.
     *
     * @return The list of AIDs registered for this category, or null if it couldn't be found.
     */
    @RequiresPermission(android.Manifest.permission.NFC_PREFERRED_PAYMENT_INFO)
    @Nullable
    public List<String> getAidsForPreferredPaymentService() {
        ApduServiceInfo serviceInfo = callServiceReturn(() ->
                sService.getPreferredPaymentService(mContext.getUser().getIdentifier()), null);
        return (serviceInfo != null ? serviceInfo.getAids() : null);
    }

    /**
     * Retrieves the route destination for the preferred payment service.
     *
     * <p class="note">
     * Starting with {@link Build.VERSION_CODES#VANILLA_ICE_CREAM}, the preferred payment service
     * no longer exists and is replaced by {@link android.app.role.RoleManager#ROLE_WALLET}. This
     * will return the route for one of the services registered by the role holder (if any). If
     * there are multiple services registered, it is unspecified which of those will be used to
     * determine the route.
     *
     * @return The route destination secure element name of the preferred payment service.
     *         HCE payment: "Host"
     *         OffHost payment: 1. String with prefix SIM or prefix eSE string.
     *                             Ref: GSMA TS.26 - NFC Handset Requirements
     *                             TS26_NFC_REQ_069: For UICC, Secure Element Name SHALL be
     *                                               SIM[smartcard slot]
     *                                               (e.g. SIM/SIM1, SIM2… SIMn).
     *                             TS26_NFC_REQ_070: For embedded SE, Secure Element Name SHALL be
     *                                               eSE[number]
     *                                               (e.g. eSE/eSE1, eSE2, etc.).
     *                          2. "OffHost" if the payment service does not specify secure element
     *                             name.
     */
    @RequiresPermission(android.Manifest.permission.NFC_PREFERRED_PAYMENT_INFO)
    @Nullable
    public String getRouteDestinationForPreferredPaymentService() {
        ApduServiceInfo serviceInfo = callServiceReturn(() ->
                sService.getPreferredPaymentService(mContext.getUser().getIdentifier()), null);
        if (serviceInfo != null) {
            if (!serviceInfo.isOnHost()) {
                return serviceInfo.getOffHostSecureElement() == null ?
                        "OffHost" : serviceInfo.getOffHostSecureElement();
            }
            return "Host";
        }
        return null;
    }

    /**
     * Returns a user-visible description of the preferred payment service.
     *
     * <p class="note">
     * Starting with {@link Build.VERSION_CODES#VANILLA_ICE_CREAM}, the preferred payment service
     * no longer exists and is replaced by {@link android.app.role.RoleManager#ROLE_WALLET}. This
     * will return the description for one of the services registered by the role holder (if any).
     * If there are multiple services registered, it is unspecified which of those will be used
     * to obtain the service description here.
     *
     * @return the preferred payment service description
     */
    @RequiresPermission(Manifest.permission.NFC_PREFERRED_PAYMENT_INFO)
    @Nullable
    public CharSequence getDescriptionForPreferredPaymentService() {
        ApduServiceInfo serviceInfo = callServiceReturn(() ->
                sService.getPreferredPaymentService(mContext.getUser().getIdentifier()), null);
        return (serviceInfo != null ? serviceInfo.getDescription() : null);
    }

    /**
     * @hide
     */
    public boolean setDefaultServiceForCategory(ComponentName service, String category) {
        return callServiceReturn(() ->
                sService.setDefaultServiceForCategory(
                    mContext.getUser().getIdentifier(), service, category), false);
    }

    /**
     * @hide
     */
    public boolean setDefaultForNextTap(ComponentName service) {
        return callServiceReturn(() ->
                sService.setDefaultForNextTap(
                    mContext.getUser().getIdentifier(), service), false);
    }

    /**
     * @hide
     */
    public boolean setDefaultForNextTap(int userId, ComponentName service) {
        return callServiceReturn(() ->
                sService.setDefaultForNextTap(userId, service), false);
    }

    /**
     * @hide
     */
    public List<ApduServiceInfo> getServices(String category) {
        return callServiceReturn(() ->
                sService.getServices(
                    mContext.getUser().getIdentifier(), category), null);
    }

    /**
     * Retrieves list of services registered of the provided category for the provided user.
     *
     * @param category Category string, one of {@link #CATEGORY_PAYMENT} or {@link #CATEGORY_OTHER}
     * @param userId the user handle of the user whose information is being requested.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_ENABLE_NFC_MAINLINE)
    @NonNull
    public List<ApduServiceInfo> getServices(@NonNull String category, @UserIdInt int userId) {
        return callServiceReturn(() ->
                sService.getServices(userId, category), null);
    }

    /**
     * Tests the validity of the polling loop filter.
     * @param pollingLoopFilter The polling loop filter to test.
     *
     * @hide
     */
    @FlaggedApi(Flags.FLAG_NFC_READ_POLLING_LOOP)
    public static @NonNull String validatePollingLoopFilter(@NonNull String pollingLoopFilter) {
        // Verify hex characters
        byte[] plfBytes = HexFormat.of().parseHex(pollingLoopFilter);
        if (plfBytes.length == 0) {
            throw new IllegalArgumentException(
                "Polling loop filter must contain at least one byte.");
        }
        return HexFormat.of().withUpperCase().formatHex(plfBytes);
    }

    /**
     * Tests the validity of the polling loop pattern filter.
     * @param pollingLoopPatternFilter The polling loop filter to test.
     *
     * @hide
     */
    @FlaggedApi(Flags.FLAG_NFC_READ_POLLING_LOOP)
    public static @NonNull String validatePollingLoopPatternFilter(
        @NonNull String pollingLoopPatternFilter) {
        // Verify hex characters
        if (!PLPF_PATTERN.matcher(pollingLoopPatternFilter).matches()) {
            throw new IllegalArgumentException(
                "Polling loop pattern filters may only contain hexadecimal numbers, ?s and *s");
        }
        return Pattern.compile(pollingLoopPatternFilter.toUpperCase(Locale.ROOT)).toString();
    }

    /**
     * A valid AID according to ISO/IEC 7816-4:
     * <ul>
     * <li>Has >= 5 bytes and <=16 bytes (>=10 hex chars and <= 32 hex chars)
     * <li>Consist of only hex characters
     * <li>Additionally, we allow an asterisk at the end, to indicate
     *     a prefix
     * <li>Additinally we allow an (#) at symbol at the end, to indicate
     *     a subset
     * </ul>
     *
     * @hide
     */
    public static boolean isValidAid(String aid) {
        if (aid == null)
            return false;

        // If a prefix/subset AID, the total length must be odd (even # of AID chars + '*')
        if ((aid.endsWith("*") || aid.endsWith("#")) && ((aid.length() % 2) == 0)) {
            Log.e(TAG, "AID " + aid + " is not a valid AID.");
            return false;
        }

        // If not a prefix/subset AID, the total length must be even (even # of AID chars)
        if ((!(aid.endsWith("*") || aid.endsWith("#"))) && ((aid.length() % 2) != 0)) {
            Log.e(TAG, "AID " + aid + " is not a valid AID.");
            return false;
        }

        // Verify hex characters
        if (!AID_PATTERN.matcher(aid).matches()) {
            Log.e(TAG, "AID " + aid + " is not a valid AID.");
            return false;
        }

        return true;
    }

    /**
     * Allows to set or unset preferred service (category other) to avoid  AID Collision.
     *
     * @param service The ComponentName of the service
     * @param status  true to enable, false to disable
     * @param userId the user handle of the user whose information is being requested.
     * @return set service for the category and true if service is already set return false.
     *
     * @hide
     */
    public boolean setServiceEnabledForCategoryOther(ComponentName service, boolean status,
                                                     int userId) {
        if (service == null) {
            throw new NullPointerException("activity or service or category is null");
        }
        return callServiceReturn(() ->
                sService.setServiceEnabledForCategoryOther(userId, service, status), false);
    }

    /** @hide */
    @IntDef(prefix = "PROTOCOL_AND_TECHNOLOGY_ROUTE_",
            value = {
                    PROTOCOL_AND_TECHNOLOGY_ROUTE_DH,
                    PROTOCOL_AND_TECHNOLOGY_ROUTE_ESE,
                    PROTOCOL_AND_TECHNOLOGY_ROUTE_UICC,
                    PROTOCOL_AND_TECHNOLOGY_ROUTE_UNSET
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ProtocolAndTechnologyRoute {}

     /**
      * Setting NFC controller routing table, which includes Protocol Route and Technology Route,
      * while this Activity is in the foreground.
      *
      * The parameter set to {@link #PROTOCOL_AND_TECHNOLOGY_ROUTE_UNSET}
      * can be used to keep current values for that entry. Either
      * Protocol Route or Technology Route should be override when calling this API, otherwise
      * throw {@link IllegalArgumentException}.
      * <p>
      * Example usage in an Activity that requires to set proto route to "ESE" and keep tech route:
      * <pre>
      * protected void onResume() {
      *     mNfcAdapter.overrideRoutingTable(
      *         this, {@link #PROTOCOL_AND_TECHNOLOGY_ROUTE_ESE}, null);
      * }</pre>
      * </p>
      * Also activities must call {@link #recoverRoutingTable(Activity)}
      * when it goes to the background. Only the package of the
      * currently preferred service (the service set as preferred by the current foreground
      * application via {@link CardEmulation#setPreferredService(Activity, ComponentName)} or the
      * current Default Wallet Role Holder {@link RoleManager#ROLE_WALLET}),
      * otherwise a call to this method will fail and throw {@link SecurityException}.
      * @param activity The Activity that requests NFC controller routing table to be changed.
      * @param protocol ISO-DEP route destination, where the possible inputs are defined
      *                 in {@link ProtocolAndTechnologyRoute}.
      * @param technology Tech-A, Tech-B and Tech-F route destination, where the possible inputs
      *                   are defined in {@link ProtocolAndTechnologyRoute}
      * @throws SecurityException if the caller is not the preferred NFC service
      * @throws IllegalArgumentException if the activity is not resumed or the caller is not in the
      * foreground.
      * <p>
      * This is a high risk API and only included to support mainline effort
      * @hide
      */
    @SystemApi
    @FlaggedApi(Flags.FLAG_NFC_OVERRIDE_RECOVER_ROUTING_TABLE)
    public void overrideRoutingTable(
            @NonNull Activity activity, @ProtocolAndTechnologyRoute int protocol,
            @ProtocolAndTechnologyRoute int technology) {
        if (!activity.isResumed()) {
            throw new IllegalArgumentException("Activity must be resumed.");
        }
        String protocolRoute = switch (protocol) {
            case PROTOCOL_AND_TECHNOLOGY_ROUTE_DH -> "DH";
            case PROTOCOL_AND_TECHNOLOGY_ROUTE_ESE -> "ESE";
            case PROTOCOL_AND_TECHNOLOGY_ROUTE_UICC -> "UICC";
            case PROTOCOL_AND_TECHNOLOGY_ROUTE_UNSET -> null;
            default -> throw new IllegalStateException("Unexpected value: " + protocol);
        };
        String technologyRoute = switch (technology) {
            case PROTOCOL_AND_TECHNOLOGY_ROUTE_DH -> "DH";
            case PROTOCOL_AND_TECHNOLOGY_ROUTE_ESE -> "ESE";
            case PROTOCOL_AND_TECHNOLOGY_ROUTE_UICC -> "UICC";
            case PROTOCOL_AND_TECHNOLOGY_ROUTE_UNSET -> null;
            default -> throw new IllegalStateException("Unexpected value: " + protocol);
        };
        callService(() ->
                sService.overrideRoutingTable(
                    mContext.getUser().getIdentifier(),
                    protocolRoute,
                    technologyRoute,
                    mContext.getPackageName()));
    }

    /**
     * Restore the NFC controller routing table,
     * which was changed by {@link #overrideRoutingTable(Activity, int, int)}
     *
     * @param activity The Activity that requested NFC controller routing table to be changed.
     * @throws IllegalArgumentException if the caller is not in the foreground.
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_NFC_OVERRIDE_RECOVER_ROUTING_TABLE)
    public void recoverRoutingTable(@NonNull Activity activity) {
        if (!activity.isResumed()) {
            throw new IllegalArgumentException("Activity must be resumed.");
        }
        callService(() ->
                sService.recoverRoutingTable(
                    mContext.getUser().getIdentifier()));
    }

    /**
     * Is EUICC supported as a Secure Element EE which supports off host card emulation.
     *
     * @return true if the device supports EUICC for off host card emulation, false otherwise.
     */
    @FlaggedApi(android.nfc.Flags.FLAG_ENABLE_CARD_EMULATION_EUICC)
    public boolean isEuiccSupported() {
        return callServiceReturn(() -> sService.isEuiccSupported(), false);
    }

    /**
     * Returns the value of {@link Settings.Secure#NFC_PAYMENT_DEFAULT_COMPONENT}.
     *
     * @param context A context
     * @return A ComponentName for the setting value, or null.
     *
     * @hide
     */
    @SystemApi
    @UserHandleAware
    @RequiresPermission(android.Manifest.permission.NFC_PREFERRED_PAYMENT_INFO)
    @SuppressWarnings("AndroidFrameworkClientSidePermissionCheck")
    @FlaggedApi(android.permission.flags.Flags.FLAG_WALLET_ROLE_ENABLED)
    @Nullable
    public static ComponentName getPreferredPaymentService(@NonNull Context context) {
        context.checkCallingOrSelfPermission(Manifest.permission.NFC_PREFERRED_PAYMENT_INFO);
        String defaultPaymentComponent = Settings.Secure.getString(context.getContentResolver(),
                Constants.SETTINGS_SECURE_NFC_PAYMENT_DEFAULT_COMPONENT);

        if (defaultPaymentComponent == null) {
            return null;
        }

        return ComponentName.unflattenFromString(defaultPaymentComponent);
    }

    /** @hide */
    interface ServiceCall {
        void call() throws RemoteException;
    }
    /** @hide */
    public static void callService(ServiceCall call) {
        try {
            if (sService == null) {
                NfcAdapter.attemptDeadServiceRecovery(
                    new RemoteException("NFC CardEmulation Service is null"));
                sService = NfcAdapter.getCardEmulationService();
            }
            call.call();
        } catch (RemoteException e) {
            NfcAdapter.attemptDeadServiceRecovery(e);
            sService = NfcAdapter.getCardEmulationService();
            try {
                call.call();
            } catch (RemoteException ee) {
                ee.rethrowAsRuntimeException();
            }
        }
    }
    /** @hide */
    interface ServiceCallReturn<T> {
        T call() throws RemoteException;
    }
    /** @hide */
    public static <T> T callServiceReturn(ServiceCallReturn<T> call, T defaultReturn) {
        try {
            if (sService == null) {
                NfcAdapter.attemptDeadServiceRecovery(
                    new RemoteException("NFC CardEmulation Service is null"));
                sService = NfcAdapter.getCardEmulationService();
            }
            return call.call();
        } catch (RemoteException e) {
            NfcAdapter.attemptDeadServiceRecovery(e);
            sService = NfcAdapter.getCardEmulationService();
            // Try one more time
            try {
                return call.call();
            } catch (RemoteException ee) {
                ee.rethrowAsRuntimeException();
            }
        }
        return defaultReturn;
    }
}
