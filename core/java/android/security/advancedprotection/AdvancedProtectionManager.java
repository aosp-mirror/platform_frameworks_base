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

package android.security.advancedprotection;

import static android.app.admin.DevicePolicyIdentifiers.MEMORY_TAGGING_POLICY;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.os.UserManager.DISALLOW_CELLULAR_2G;
import static android.os.UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY;

import android.Manifest;
import android.annotation.CallbackExecutor;
import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.StringDef;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.RemoteException;
import android.security.Flags;
import android.util.Log;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * <p>Advanced Protection is a mode that users can enroll their device into, that enhances security
 * by enabling features and restrictions across both the platform and user apps.
 *
 * <p>This class provides methods to query and control the advanced protection mode
 * for the device.
 */
@FlaggedApi(Flags.FLAG_AAPM_API)
@SystemService(Context.ADVANCED_PROTECTION_SERVICE)
public final class AdvancedProtectionManager {
    private static final String TAG = "AdvancedProtectionMgr";

    /**
     * Advanced Protection's identifier for setting policies or restrictions in DevicePolicyManager.
     *
     * @hide */
    public static final String ADVANCED_PROTECTION_SYSTEM_ENTITY =
            "android.security.advancedprotection";

    /**
     * Feature identifier for disallowing 2G.
     *
     * @hide */
    @SystemApi
    public static final String FEATURE_ID_DISALLOW_CELLULAR_2G =
            "android.security.advancedprotection.feature_disallow_2g";

    /**
     * Feature identifier for disallowing install of unknown sources.
     *
     * @hide */
    @SystemApi
    public static final String FEATURE_ID_DISALLOW_INSTALL_UNKNOWN_SOURCES =
            "android.security.advancedprotection.feature_disallow_install_unknown_sources";

    /**
     * Feature identifier for disallowing USB.
     *
     * @hide */
    @SystemApi
    public static final String FEATURE_ID_DISALLOW_USB =
            "android.security.advancedprotection.feature_disallow_usb";

    /**
     * Feature identifier for disallowing WEP.
     *
     * @hide */
    @SystemApi
    public static final String FEATURE_ID_DISALLOW_WEP =
            "android.security.advancedprotection.feature_disallow_wep";

    /**
     * Feature identifier for enabling MTE.
     *
     * @hide */
    @SystemApi
    public static final String FEATURE_ID_ENABLE_MTE =
            "android.security.advancedprotection.feature_enable_mte";

    /** @hide */
    @StringDef(prefix = { "FEATURE_ID_" }, value = {
            FEATURE_ID_DISALLOW_CELLULAR_2G,
            FEATURE_ID_DISALLOW_INSTALL_UNKNOWN_SOURCES,
            FEATURE_ID_DISALLOW_USB,
            FEATURE_ID_DISALLOW_WEP,
            FEATURE_ID_ENABLE_MTE,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface FeatureId {}

    private static final Set<String> ALL_FEATURE_IDS = Set.of(
            FEATURE_ID_DISALLOW_CELLULAR_2G,
            FEATURE_ID_DISALLOW_INSTALL_UNKNOWN_SOURCES,
            FEATURE_ID_DISALLOW_USB,
            FEATURE_ID_DISALLOW_WEP,
            FEATURE_ID_ENABLE_MTE);

    /**
     * Activity Action: Show a dialog with disabled by advanced protection message.
     * <p> If a user action or a setting toggle is disabled by advanced protection, this dialog can
     * be triggered to let the user know about this.
     * <p>
     * Input:
     * <p>{@link #EXTRA_SUPPORT_DIALOG_FEATURE}: The feature identifier.
     * <p>{@link #EXTRA_SUPPORT_DIALOG_TYPE}: The type of the action.
     * <p>
     * Output: Nothing.
     *
     * @hide */
    @SystemApi
    @SdkConstant(SdkConstant.SdkConstantType.ACTIVITY_INTENT_ACTION)
    @FlaggedApi(android.security.Flags.FLAG_AAPM_API)
    public static final String ACTION_SHOW_ADVANCED_PROTECTION_SUPPORT_DIALOG =
            "android.security.advancedprotection.action.SHOW_ADVANCED_PROTECTION_SUPPORT_DIALOG";

    /**
     * A string extra used with {@link #createSupportIntent} to identify the feature that needs to
     * show a support dialog explaining it was disabled by advanced protection.
     *
     * @hide */
    @FeatureId
    @SystemApi
    public static final String EXTRA_SUPPORT_DIALOG_FEATURE =
            "android.security.advancedprotection.extra.SUPPORT_DIALOG_FEATURE";

    /**
     * A string extra used with {@link #createSupportIntent} to identify the type of the action that
     * needs to be explained in the support dialog.
     *
     * @hide */
    @SupportDialogType
    @SystemApi
    public static final String EXTRA_SUPPORT_DIALOG_TYPE =
            "android.security.advancedprotection.extra.SUPPORT_DIALOG_TYPE";

    /**
     * Type for {@link #EXTRA_SUPPORT_DIALOG_TYPE} indicating a user performed an action that was
     * blocked by advanced protection.
     *
     * @hide */
    @SystemApi
    public static final String SUPPORT_DIALOG_TYPE_BLOCKED_INTERACTION =
            "android.security.advancedprotection.type_blocked_interaction";

    /**
     * Type for {@link #EXTRA_SUPPORT_DIALOG_TYPE} indicating a user pressed on a setting toggle
     * that was disabled by advanced protection.
     *
     * @hide */
    @SystemApi
    public static final String SUPPORT_DIALOG_TYPE_DISABLED_SETTING =
            "android.security.advancedprotection.type_disabled_setting";

    /** @hide */
    @StringDef(prefix = { "SUPPORT_DIALOG_TYPE_" }, value = {
            SUPPORT_DIALOG_TYPE_BLOCKED_INTERACTION,
            SUPPORT_DIALOG_TYPE_DISABLED_SETTING,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SupportDialogType {}

    private static final Set<String> ALL_SUPPORT_DIALOG_TYPES = Set.of(
            SUPPORT_DIALOG_TYPE_BLOCKED_INTERACTION,
            SUPPORT_DIALOG_TYPE_DISABLED_SETTING);

    private final ConcurrentHashMap<Callback, IAdvancedProtectionCallback>
            mCallbackMap = new ConcurrentHashMap<>();

    @NonNull
    private final IAdvancedProtectionService mService;

    /** @hide */
    public AdvancedProtectionManager(@NonNull IAdvancedProtectionService service) {
        mService = service;
    }

    /**
     * Checks if advanced protection is enabled on the device.
     *
     * @return {@code true} if advanced protection is enabled, {@code false} otherwise.
     */
    @RequiresPermission(Manifest.permission.QUERY_ADVANCED_PROTECTION_MODE)
    public boolean isAdvancedProtectionEnabled() {
        try {
            return mService.isAdvancedProtectionEnabled();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Registers a {@link Callback} to be notified of changes to the Advanced Protection state.
     *
     * <p>The provided callback will be called on the specified executor with the updated
     * state. Methods are called when the state changes, as well as once
     * on initial registration.
     *
     * @param executor The executor of where the callback will execute.
     * @param callback The {@link Callback} object to register..
     */
    @RequiresPermission(Manifest.permission.QUERY_ADVANCED_PROTECTION_MODE)
    public void registerAdvancedProtectionCallback(@NonNull @CallbackExecutor Executor executor,
            @NonNull Callback callback) {
        if (mCallbackMap.get(callback) != null) {
            Log.d(TAG, "registerAdvancedProtectionCallback callback already present");
            return;
        }

        IAdvancedProtectionCallback delegate = new IAdvancedProtectionCallback.Stub() {
            @Override
            public void onAdvancedProtectionChanged(boolean enabled) {
                final long identity = Binder.clearCallingIdentity();
                try {
                    executor.execute(() -> callback.onAdvancedProtectionChanged(enabled));
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
        };

        try {
            mService.registerAdvancedProtectionCallback(delegate);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        mCallbackMap.put(callback, delegate);
    }

    /**
     * Unregister an existing {@link Callback}.
     *
     * @param callback The {@link Callback} object to unregister.
     */
    @RequiresPermission(Manifest.permission.QUERY_ADVANCED_PROTECTION_MODE)
    public void unregisterAdvancedProtectionCallback(@NonNull Callback callback) {
        IAdvancedProtectionCallback delegate = mCallbackMap.get(callback);
        if (delegate == null) {
            Log.d(TAG, "unregisterAdvancedProtectionCallback callback not present");
            return;
        }

        try {
            mService.unregisterAdvancedProtectionCallback(delegate);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        mCallbackMap.remove(callback);
    }

    /**
     * Enables or disables advanced protection on the device.
     *
     * @param enabled {@code true} to enable advanced protection, {@code false} to disable it.
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MANAGE_ADVANCED_PROTECTION_MODE)
    public void setAdvancedProtectionEnabled(boolean enabled) {
        try {
            mService.setAdvancedProtectionEnabled(enabled);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the list of advanced protection features which are available on this device.
     *
     * @hide
     */
    @SystemApi
    @NonNull
    @RequiresPermission(Manifest.permission.MANAGE_ADVANCED_PROTECTION_MODE)
    public List<AdvancedProtectionFeature> getAdvancedProtectionFeatures() {
        try {
            return mService.getAdvancedProtectionFeatures();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Called by a feature to display a support dialog when a feature was disabled by advanced
     * protection. This returns an intent that can be used with
     * {@link Context#startActivity(Intent)} to display the dialog.
     *
     * <p>Note that this method doesn't check if the feature is actually disabled, i.e. this method
     * will always return an intent.
     *
     * @param featureId The feature identifier.
     * @param type The type of the feature describing the action that needs to be explained
     *                 in the dialog or null for default explanation.
     * @return Intent An intent to be used to start the dialog-activity that explains a feature was
     *                disabled by advanced protection.
     * @hide
     */
    @SystemApi
    public @NonNull Intent createSupportIntent(@NonNull @FeatureId String featureId,
            @Nullable @SupportDialogType String type) {
        Objects.requireNonNull(featureId);
        if (!ALL_FEATURE_IDS.contains(featureId)) {
            throw new IllegalArgumentException(featureId + " is not a valid feature ID. See"
                    + " FEATURE_ID_* APIs.");
        }
        if (type != null && !ALL_SUPPORT_DIALOG_TYPES.contains(type)) {
            throw new IllegalArgumentException(type + " is not a valid type. See"
                    + " SUPPORT_DIALOG_TYPE_* APIs.");
        }

        Intent intent = new Intent(ACTION_SHOW_ADVANCED_PROTECTION_SUPPORT_DIALOG);
        intent.setFlags(FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(EXTRA_SUPPORT_DIALOG_FEATURE, featureId);
        if (type != null) {
            intent.putExtra(EXTRA_SUPPORT_DIALOG_TYPE, type);
        }
        return intent;
    }

    /** @hide */
    public @NonNull Intent createSupportIntentForPolicyIdentifierOrRestriction(
            @NonNull String identifier, @Nullable @SupportDialogType String type) {
        Objects.requireNonNull(identifier);
        if (type != null && !ALL_SUPPORT_DIALOG_TYPES.contains(type)) {
            throw new IllegalArgumentException(type + " is not a valid type. See"
                    + " SUPPORT_DIALOG_TYPE_* APIs.");
        }
        final String featureId;
        if (DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY.equals(identifier)) {
            featureId = FEATURE_ID_DISALLOW_INSTALL_UNKNOWN_SOURCES;
        } else if (DISALLOW_CELLULAR_2G.equals(identifier)) {
            featureId = FEATURE_ID_DISALLOW_CELLULAR_2G;
        } else if (android.app.admin.flags.Flags.setMtePolicyCoexistence() && MEMORY_TAGGING_POLICY
                .equals(identifier)) {
            featureId = FEATURE_ID_ENABLE_MTE;
        } else {
            throw new UnsupportedOperationException("Unsupported identifier: " + identifier);
        }
        return createSupportIntent(featureId, type);
    }

    /**
     * A callback class for monitoring changes to Advanced Protection state
     *
     * <p>To register a callback, implement this interface, and register it with
     * {@link AdvancedProtectionManager#registerAdvancedProtectionCallback(Executor, Callback)}.
     * Methods are called when the state changes, as well as once on initial registration.
     */
    @FlaggedApi(Flags.FLAG_AAPM_API)
    public interface Callback {
        /**
         * Called when advanced protection state changes
         * @param enabled the new state
         */
        void onAdvancedProtectionChanged(boolean enabled);
    }
}
