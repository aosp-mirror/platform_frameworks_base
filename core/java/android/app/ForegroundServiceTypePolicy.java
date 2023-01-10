/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.app;

import static android.app.AppOpsManager.MODE_ALLOWED;
import static android.app.AppOpsManager.MODE_FOREGROUND;
import static android.content.pm.PackageManager.PERMISSION_DENIED;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_FILE_MANAGEMENT;
import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH;
import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST;
import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK;
import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE;
import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL;
import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING;
import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE;
import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED;

import android.Manifest;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.app.compat.CompatChanges;
import android.compat.Compatibility;
import android.compat.annotation.ChangeId;
import android.compat.annotation.Disabled;
import android.compat.annotation.EnabledAfter;
import android.compat.annotation.Overridable;
import android.content.Context;
import android.content.PermissionChecker;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.pm.ServiceInfo.ForegroundServiceType;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.permission.PermissionCheckerManager;
import android.util.ArraySet;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.compat.CompatibilityChangeConfig;
import com.android.internal.compat.IPlatformCompat;
import com.android.internal.util.ArrayUtils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Optional;

/**
 * This class enforces the policies around the foreground service types.
 *
 * @hide
 */
public abstract class ForegroundServiceTypePolicy {
    static final String TAG = "ForegroundServiceTypePolicy";
    static final boolean DEBUG_FOREGROUND_SERVICE_TYPE_POLICY = false;

    /**
     * The FGS type enforcement:
     * deprecating the {@link android.content.pm.ServiceInfo#FOREGROUND_SERVICE_TYPE_NONE}.
     *
     * <p>Starting a FGS with this type (equivalent of no type) from apps with
     * targetSdkVersion {@link android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE} or later will
     * result in a warning in the log.</p>
     *
     * @hide
     */
    @ChangeId
    @EnabledAfter(targetSdkVersion = android.os.Build.VERSION_CODES.TIRAMISU)
    @Overridable
    public static final long FGS_TYPE_NONE_DEPRECATION_CHANGE_ID = 255042465L;

    /**
     * The FGS type enforcement:
     * disabling the {@link android.content.pm.ServiceInfo#FOREGROUND_SERVICE_TYPE_NONE}.
     *
     * <p>Starting a FGS with this type (equivalent of no type) from apps with
     * targetSdkVersion {@link android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE} or later will
     * result in an exception.</p>
     *
     * @hide
     */
    // TODO (b/254661666): Change to @EnabledAfter(T)
    @ChangeId
    @Disabled
    @Overridable
    public static final long FGS_TYPE_NONE_DISABLED_CHANGE_ID = 255038118L;

    /**
     * The FGS type enforcement:
     * deprecating the {@link android.content.pm.ServiceInfo#FOREGROUND_SERVICE_TYPE_DATA_SYNC}.
     *
     * @hide
     */
    @ChangeId
    @Disabled
    @Overridable
    public static final long FGS_TYPE_DATA_SYNC_DEPRECATION_CHANGE_ID = 255039210L;

    /**
     * The FGS type enforcement:
     * disabling the {@link android.content.pm.ServiceInfo#FOREGROUND_SERVICE_TYPE_DATA_SYNC}.
     *
     * @hide
     */
    @ChangeId
    @Disabled
    @Overridable
    public static final long FGS_TYPE_DATA_SYNC_DISABLED_CHANGE_ID = 255659651L;

    /**
     * The FGS type enforcement: Starting a FGS from apps with targetSdkVersion
     * {@link android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE} or later but without the required
     * permissions associated with the FGS type will result in a SecurityException.
     *
     * @hide
     */
    // TODO (b/254661666): Change to @EnabledAfter(T)
    @ChangeId
    @Disabled
    @Overridable
    public static final long FGS_TYPE_PERMISSION_CHANGE_ID = 254662522L;

    /**
     * The policy for the {@link ServiceInfo#FOREGROUND_SERVICE_TYPE_MANIFEST}.
     *
     * @hide
     */
    public static final @NonNull ForegroundServiceTypePolicyInfo FGS_TYPE_POLICY_MANIFEST =
            new ForegroundServiceTypePolicyInfo(
            FOREGROUND_SERVICE_TYPE_MANIFEST,
            FGS_TYPE_NONE_DEPRECATION_CHANGE_ID,
            FGS_TYPE_NONE_DISABLED_CHANGE_ID,
            null,
            null
    );

    /**
     * The policy for the {@link ServiceInfo#FOREGROUND_SERVICE_TYPE_NONE}.
     *
     * @hide
     */
    public static final @NonNull ForegroundServiceTypePolicyInfo FGS_TYPE_POLICY_NONE =
            new ForegroundServiceTypePolicyInfo(
            FOREGROUND_SERVICE_TYPE_NONE,
            FGS_TYPE_NONE_DEPRECATION_CHANGE_ID,
            FGS_TYPE_NONE_DISABLED_CHANGE_ID,
            null,
            null
    );

    /**
     * The policy for the {@link ServiceInfo#FOREGROUND_SERVICE_TYPE_DATA_SYNC}.
     *
     * @hide
     */
    public static final @NonNull ForegroundServiceTypePolicyInfo FGS_TYPE_POLICY_DATA_SYNC =
            new ForegroundServiceTypePolicyInfo(
            FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            FGS_TYPE_DATA_SYNC_DEPRECATION_CHANGE_ID,
            FGS_TYPE_DATA_SYNC_DISABLED_CHANGE_ID,
            new ForegroundServiceTypePermissions(new ForegroundServiceTypePermission[] {
                new RegularPermission(Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC)
            }, true),
            null
    );

    /**
     * The policy for the {@link ServiceInfo#FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK}.
     *
     * @hide
     */
    public static final @NonNull ForegroundServiceTypePolicyInfo FGS_TYPE_POLICY_MEDIA_PLAYBACK =
            new ForegroundServiceTypePolicyInfo(
            FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            ForegroundServiceTypePolicyInfo.INVALID_CHANGE_ID,
            ForegroundServiceTypePolicyInfo.INVALID_CHANGE_ID,
            new ForegroundServiceTypePermissions(new ForegroundServiceTypePermission[] {
                new RegularPermission(Manifest.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK)
            }, true),
            null
    );

    /**
     * The policy for the {@link ServiceInfo#FOREGROUND_SERVICE_TYPE_PHONE_CALL}.
     *
     * @hide
     */
    public static final @NonNull ForegroundServiceTypePolicyInfo FGS_TYPE_POLICY_PHONE_CALL =
            new ForegroundServiceTypePolicyInfo(
            FOREGROUND_SERVICE_TYPE_PHONE_CALL,
            ForegroundServiceTypePolicyInfo.INVALID_CHANGE_ID,
            ForegroundServiceTypePolicyInfo.INVALID_CHANGE_ID,
            new ForegroundServiceTypePermissions(new ForegroundServiceTypePermission[] {
                new RegularPermission(Manifest.permission.FOREGROUND_SERVICE_PHONE_CALL)
            }, true),
            new ForegroundServiceTypePermissions(new ForegroundServiceTypePermission[] {
                new RegularPermission(Manifest.permission.MANAGE_OWN_CALLS)
            }, false)
    );

    /**
     * The policy for the {@link ServiceInfo#FOREGROUND_SERVICE_TYPE_LOCATION}.
     *
     * @hide
     */
    public static final @NonNull ForegroundServiceTypePolicyInfo FGS_TYPE_POLICY_LOCATION =
            new ForegroundServiceTypePolicyInfo(
            FOREGROUND_SERVICE_TYPE_LOCATION,
            ForegroundServiceTypePolicyInfo.INVALID_CHANGE_ID,
            ForegroundServiceTypePolicyInfo.INVALID_CHANGE_ID,
            new ForegroundServiceTypePermissions(new ForegroundServiceTypePermission[] {
                new RegularPermission(Manifest.permission.FOREGROUND_SERVICE_LOCATION)
            }, true),
            new ForegroundServiceTypePermissions(new ForegroundServiceTypePermission[] {
                new RegularPermission(Manifest.permission.ACCESS_COARSE_LOCATION),
                new RegularPermission(Manifest.permission.ACCESS_FINE_LOCATION),
            }, false)
    );

    /**
     * The policy for the {@link ServiceInfo#FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE}.
     *
     * @hide
     */
    public static final @NonNull ForegroundServiceTypePolicyInfo FGS_TYPE_POLICY_CONNECTED_DEVICE =
            new ForegroundServiceTypePolicyInfo(
            FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            ForegroundServiceTypePolicyInfo.INVALID_CHANGE_ID,
            ForegroundServiceTypePolicyInfo.INVALID_CHANGE_ID,
            new ForegroundServiceTypePermissions(new ForegroundServiceTypePermission[] {
                new RegularPermission(Manifest.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE)
            }, true),
            new ForegroundServiceTypePermissions(new ForegroundServiceTypePermission[] {
                new RegularPermission(Manifest.permission.BLUETOOTH_CONNECT),
                new RegularPermission(Manifest.permission.CHANGE_NETWORK_STATE),
                new RegularPermission(Manifest.permission.CHANGE_WIFI_STATE),
                new RegularPermission(Manifest.permission.CHANGE_WIFI_MULTICAST_STATE),
                new RegularPermission(Manifest.permission.NFC),
                new RegularPermission(Manifest.permission.TRANSMIT_IR),
                new UsbDevicePermission(),
                new UsbAccessoryPermission(),
            }, false)
    );

    /**
     * The policy for the {@link ServiceInfo#FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION}.
     *
     * @hide
     */
    public static final @NonNull ForegroundServiceTypePolicyInfo FGS_TYPE_POLICY_MEDIA_PROJECTION =
            new ForegroundServiceTypePolicyInfo(
            FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            ForegroundServiceTypePolicyInfo.INVALID_CHANGE_ID,
            ForegroundServiceTypePolicyInfo.INVALID_CHANGE_ID,
            new ForegroundServiceTypePermissions(new ForegroundServiceTypePermission[] {
                new RegularPermission(Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION)
            }, true),
            new ForegroundServiceTypePermissions(new ForegroundServiceTypePermission[] {
                new RegularPermission(Manifest.permission.CAPTURE_VIDEO_OUTPUT),
                new AppOpPermission(AppOpsManager.OP_PROJECT_MEDIA)
            }, false)
    );

    /**
     * The policy for the {@link ServiceInfo#FOREGROUND_SERVICE_TYPE_CAMERA}.
     *
     * @hide
     */
    public static final @NonNull ForegroundServiceTypePolicyInfo FGS_TYPE_POLICY_CAMERA =
            new ForegroundServiceTypePolicyInfo(
            FOREGROUND_SERVICE_TYPE_CAMERA,
            ForegroundServiceTypePolicyInfo.INVALID_CHANGE_ID,
            ForegroundServiceTypePolicyInfo.INVALID_CHANGE_ID,
            new ForegroundServiceTypePermissions(new ForegroundServiceTypePermission[] {
                new RegularPermission(Manifest.permission.FOREGROUND_SERVICE_CAMERA)
            }, true),
            new ForegroundServiceTypePermissions(new ForegroundServiceTypePermission[] {
                new RegularPermission(Manifest.permission.CAMERA),
                new RegularPermission(Manifest.permission.SYSTEM_CAMERA),
            }, false)
    );

    /**
     * The policy for the {@link ServiceInfo#FOREGROUND_SERVICE_TYPE_MICROPHONE}.
     *
     * @hide
     */
    public static final @NonNull ForegroundServiceTypePolicyInfo FGS_TYPE_POLICY_MICROPHONE =
            new ForegroundServiceTypePolicyInfo(
            FOREGROUND_SERVICE_TYPE_MICROPHONE,
            ForegroundServiceTypePolicyInfo.INVALID_CHANGE_ID,
            ForegroundServiceTypePolicyInfo.INVALID_CHANGE_ID,
            new ForegroundServiceTypePermissions(new ForegroundServiceTypePermission[] {
                new RegularPermission(Manifest.permission.FOREGROUND_SERVICE_MICROPHONE)
            }, true),
            new ForegroundServiceTypePermissions(new ForegroundServiceTypePermission[] {
                new RegularPermission(Manifest.permission.CAPTURE_AUDIO_HOTWORD),
                new RegularPermission(Manifest.permission.CAPTURE_AUDIO_OUTPUT),
                new RegularPermission(Manifest.permission.CAPTURE_MEDIA_OUTPUT),
                new RegularPermission(Manifest.permission.CAPTURE_TUNER_AUDIO_INPUT),
                new RegularPermission(Manifest.permission.CAPTURE_VOICE_COMMUNICATION_OUTPUT),
                new RegularPermission(Manifest.permission.RECORD_AUDIO),
            }, false)
    );

    /**
     * The policy for the {@link ServiceInfo#FOREGROUND_SERVICE_TYPE_HEALTH}.
     *
     * @hide
     */
    public static final @NonNull ForegroundServiceTypePolicyInfo FGS_TYPE_POLICY_HEALTH =
            new ForegroundServiceTypePolicyInfo(
            FOREGROUND_SERVICE_TYPE_HEALTH,
            ForegroundServiceTypePolicyInfo.INVALID_CHANGE_ID,
            ForegroundServiceTypePolicyInfo.INVALID_CHANGE_ID,
            new ForegroundServiceTypePermissions(new ForegroundServiceTypePermission[] {
                new RegularPermission(Manifest.permission.FOREGROUND_SERVICE_HEALTH)
            }, true),
            new ForegroundServiceTypePermissions(new ForegroundServiceTypePermission[] {
                new RegularPermission(Manifest.permission.ACTIVITY_RECOGNITION),
                new RegularPermission(Manifest.permission.BODY_SENSORS),
                new RegularPermission(Manifest.permission.HIGH_SAMPLING_RATE_SENSORS),
            }, false)
    );

    /**
     * The policy for the {@link ServiceInfo#FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING}.
     *
     * @hide
     */
    public static final @NonNull ForegroundServiceTypePolicyInfo FGS_TYPE_POLICY_REMOTE_MESSAGING =
            new ForegroundServiceTypePolicyInfo(
            FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING,
            ForegroundServiceTypePolicyInfo.INVALID_CHANGE_ID,
            ForegroundServiceTypePolicyInfo.INVALID_CHANGE_ID,
            new ForegroundServiceTypePermissions(new ForegroundServiceTypePermission[] {
                new RegularPermission(Manifest.permission.FOREGROUND_SERVICE_REMOTE_MESSAGING)
            }, true),
            null
    );

    /**
     * The policy for the {@link ServiceInfo#FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED}.
     *
     * @hide
     */
    public static final @NonNull ForegroundServiceTypePolicyInfo FGS_TYPE_POLICY_SYSTEM_EXEMPTED =
            new ForegroundServiceTypePolicyInfo(
            FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED,
            ForegroundServiceTypePolicyInfo.INVALID_CHANGE_ID,
            ForegroundServiceTypePolicyInfo.INVALID_CHANGE_ID,
            new ForegroundServiceTypePermissions(new ForegroundServiceTypePermission[] {
                new RegularPermission(Manifest.permission.FOREGROUND_SERVICE_SYSTEM_EXEMPTED)
            }, true),
            new ForegroundServiceTypePermissions(new ForegroundServiceTypePermission[] {
                new RegularPermission(Manifest.permission.SCHEDULE_EXACT_ALARM),
                new RegularPermission(Manifest.permission.USE_EXACT_ALARM),
                new AppOpPermission(AppOpsManager.OP_ACTIVATE_VPN),
                new AppOpPermission(AppOpsManager.OP_ACTIVATE_PLATFORM_VPN),
            }, false)
    );

    /**
     * The policy for the {@link ServiceInfo#FOREGROUND_SERVICE_TYPE_SHORT_SERVICE}.
     *
     * @hide
     */
    public static final @NonNull ForegroundServiceTypePolicyInfo FGS_TYPE_POLICY_SHORT_SERVICE =
            new ForegroundServiceTypePolicyInfo(
            FOREGROUND_SERVICE_TYPE_SHORT_SERVICE,
            ForegroundServiceTypePolicyInfo.INVALID_CHANGE_ID,
            ForegroundServiceTypePolicyInfo.INVALID_CHANGE_ID,
            null /* no type specific permissions */, null /* no type specific permissions */
    );

    /**
     * The policy for the {@link ServiceInfo#FOREGROUND_SERVICE_TYPE_FILE_MANAGEMENT}.
     *
     * @hide
     */
    public static final @NonNull ForegroundServiceTypePolicyInfo FGS_TYPE_POLICY_FILE_MANAGEMENT =
            new ForegroundServiceTypePolicyInfo(
            FOREGROUND_SERVICE_TYPE_FILE_MANAGEMENT,
            ForegroundServiceTypePolicyInfo.INVALID_CHANGE_ID,
            ForegroundServiceTypePolicyInfo.INVALID_CHANGE_ID,
            new ForegroundServiceTypePermissions(new ForegroundServiceTypePermission[] {
                new RegularPermission(Manifest.permission.FOREGROUND_SERVICE_FILE_MANAGEMENT)
            }, true),
            null
    );

    /**
     * The policy for the {@link ServiceInfo#FOREGROUND_SERVICE_TYPE_SPECIAL_USE}.
     *
     * @hide
     */
    public static final @NonNull ForegroundServiceTypePolicyInfo FGS_TYPE_POLICY_SPECIAL_USE =
            new ForegroundServiceTypePolicyInfo(
            FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            ForegroundServiceTypePolicyInfo.INVALID_CHANGE_ID,
            ForegroundServiceTypePolicyInfo.INVALID_CHANGE_ID,
            new ForegroundServiceTypePermissions(new ForegroundServiceTypePermission[] {
                new RegularPermission(Manifest.permission.FOREGROUND_SERVICE_SPECIAL_USE)
            }, true),
            null
    );

    /**
     * Foreground service policy check result code: this one is not actually being used.
     *
     * @hide
     */
    public static final int FGS_TYPE_POLICY_CHECK_UNKNOWN =
            AppProtoEnums.FGS_TYPE_POLICY_CHECK_UNKNOWN;

    /**
     * Foreground service policy check result code: okay to go.
     *
     * @hide
     */
    public static final int FGS_TYPE_POLICY_CHECK_OK =
            AppProtoEnums.FGS_TYPE_POLICY_CHECK_OK;

    /**
     * Foreground service policy check result code: this foreground service type is deprecated.
     *
     * @hide
     */
    public static final int FGS_TYPE_POLICY_CHECK_DEPRECATED =
            AppProtoEnums.FGS_TYPE_POLICY_CHECK_DEPRECATED;

    /**
     * Foreground service policy check result code: this foreground service type is disabled.
     *
     * @hide
     */
    public static final int FGS_TYPE_POLICY_CHECK_DISABLED =
            AppProtoEnums.FGS_TYPE_POLICY_CHECK_DISABLED;

    /**
     * Foreground service policy check result code: the caller doesn't have permission to start
     * foreground service with this type, but the policy is permissive.
     *
     * @hide
     */
    public static final int FGS_TYPE_POLICY_CHECK_PERMISSION_DENIED_PERMISSIVE =
            AppProtoEnums.FGS_TYPE_POLICY_CHECK_PERMISSION_DENIED_PERMISSIVE;

    /**
     * Foreground service policy check result code: the caller doesn't have permission to start
     * foreground service with this type, and the policy is enforced.
     *
     * @hide
     */
    public static final int FGS_TYPE_POLICY_CHECK_PERMISSION_DENIED_ENFORCED =
            AppProtoEnums.FGS_TYPE_POLICY_CHECK_PERMISSION_DENIED_ENFORCED;

    /**
     * @hide
     */
    @IntDef(flag = true, prefix = { "FGS_TYPE_POLICY_CHECK_" }, value = {
         FGS_TYPE_POLICY_CHECK_UNKNOWN,
         FGS_TYPE_POLICY_CHECK_OK,
         FGS_TYPE_POLICY_CHECK_DEPRECATED,
         FGS_TYPE_POLICY_CHECK_DISABLED,
         FGS_TYPE_POLICY_CHECK_PERMISSION_DENIED_PERMISSIVE,
         FGS_TYPE_POLICY_CHECK_PERMISSION_DENIED_ENFORCED,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ForegroundServicePolicyCheckCode{}

    /**
     * @return The policy info for the given type.
     */
    @NonNull
    public abstract ForegroundServiceTypePolicyInfo getForegroundServiceTypePolicyInfo(
            @ForegroundServiceType int type, @ForegroundServiceType int defaultToType);

    /**
     * Run check on the foreground service type policy for the given uid/pid
     *
     * @hide
     */
    @ForegroundServicePolicyCheckCode
    public abstract int checkForegroundServiceTypePolicy(@NonNull Context context,
            @NonNull String packageName, int callerUid, int callerPid, boolean allowWhileInUse,
            @NonNull ForegroundServiceTypePolicyInfo policy);

    @GuardedBy("sLock")
    private static ForegroundServiceTypePolicy sDefaultForegroundServiceTypePolicy = null;

    private static final Object sLock = new Object();

    /**
     * Return the default policy for FGS type.
     */
    public static @NonNull ForegroundServiceTypePolicy getDefaultPolicy() {
        synchronized (sLock) {
            if (sDefaultForegroundServiceTypePolicy == null) {
                sDefaultForegroundServiceTypePolicy = new DefaultForegroundServiceTypePolicy();
            }
            return sDefaultForegroundServiceTypePolicy;
        }
    }

    /**
     * Constructor.
     *
     * @hide
     */
    public ForegroundServiceTypePolicy() {
    }

    /**
     * This class represents the policy for a specific FGS service type.
     *
     * @hide
     */
    public static final class ForegroundServiceTypePolicyInfo {
        /**
         * The foreground service type.
         */
        final @ForegroundServiceType int mType;

        /**
         * The change id to tell if this FGS type is deprecated.
         *
         * <p>A 0 indicates it's not deprecated.</p>
         */
        final long mDeprecationChangeId;

        /**
         * The change id to tell if this FGS type is disabled.
         *
         * <p>A 0 indicates it's not disabled.</p>
         */
        final long mDisabledChangeId;

        /**
         * The required permissions to start a foreground with this type, all of them
         * MUST have been granted.
         */
        final @Nullable ForegroundServiceTypePermissions mAllOfPermissions;

        /**
         * The required permissions to start a foreground with this type, any one of them
         * being granted is sufficient.
         */
        final @Nullable ForegroundServiceTypePermissions mAnyOfPermissions;

        /**
         * A customized check for the permissions.
         */
        @Nullable ForegroundServiceTypePermission mCustomPermission;

        /**
         * Not a real change id, but a place holder.
         */
        private static final long INVALID_CHANGE_ID = 0L;

        /**
         * @return {@code true} if the given change id is valid.
         */
        private static boolean isValidChangeId(long changeId) {
            return changeId != INVALID_CHANGE_ID;
        }

        /**
         * Construct a new instance.
         *
         * @hide
         */
        public ForegroundServiceTypePolicyInfo(@ForegroundServiceType int type,
                long deprecationChangeId, long disabledChangeId,
                @Nullable ForegroundServiceTypePermissions allOfPermissions,
                @Nullable ForegroundServiceTypePermissions anyOfPermissions) {
            mType = type;
            mDeprecationChangeId = deprecationChangeId;
            mDisabledChangeId = disabledChangeId;
            mAllOfPermissions = allOfPermissions;
            mAnyOfPermissions = anyOfPermissions;
        }

        /**
         * @return The foreground service type.
         */
        @ForegroundServiceType
        public int getForegroundServiceType() {
            return mType;
        }

        @Override
        public String toString() {
            final StringBuilder sb = toPermissionString(new StringBuilder());
            sb.append("type=0x");
            sb.append(Integer.toHexString(mType));
            sb.append(" deprecationChangeId=");
            sb.append(mDeprecationChangeId);
            sb.append(" disabledChangeId=");
            sb.append(mDisabledChangeId);
            sb.append(" customPermission=");
            sb.append(mCustomPermission);
            return sb.toString();
        }

        /**
         * @return The required permissions.
         */
        public String toPermissionString() {
            return toPermissionString(new StringBuilder()).toString();
        }

        private StringBuilder toPermissionString(StringBuilder sb) {
            if (mAllOfPermissions != null) {
                sb.append("all of the permissions ");
                sb.append(mAllOfPermissions.toString());
                sb.append(' ');
            }
            if (mAnyOfPermissions != null) {
                sb.append("any of the permissions ");
                sb.append(mAnyOfPermissions.toString());
                sb.append(' ');
            }
            return sb;
        }

        /**
         * @hide
         */
        public void setCustomPermission(
                @Nullable ForegroundServiceTypePermission customPermission) {
            mCustomPermission = customPermission;
        }

        /**
         * @return The name of the permissions which are all required.
         *         It may contain app op names.
         *
         * For test only.
         */
        public @NonNull Optional<String[]> getRequiredAllOfPermissionsForTest(
                @NonNull Context context) {
            if (mAllOfPermissions == null) {
                return Optional.empty();
            }
            return Optional.of(mAllOfPermissions.toStringArray(context));
        }

        /**
         * @return The name of the permissions where any of the is granted is sufficient.
         *         It may contain app op names.
         *
         * For test only.
         */
        public @NonNull Optional<String[]> getRequiredAnyOfPermissionsForTest(
                @NonNull Context context) {
            if (mAnyOfPermissions == null) {
                return Optional.empty();
            }
            return Optional.of(mAnyOfPermissions.toStringArray(context));
        }

        /**
         * Whether or not this type is disabled.
         */
        @SuppressLint("AndroidFrameworkRequiresPermission")
        public boolean isTypeDisabled(int callerUid) {
            return isValidChangeId(mDisabledChangeId)
                    && CompatChanges.isChangeEnabled(mDisabledChangeId, callerUid);
        }

        /**
         * Override the type disabling change Id.
         *
         * For test only.
         */
        public void setTypeDisabledForTest(boolean disabled, @NonNull String packageName)
                throws RemoteException {
            overrideChangeIdForTest(mDisabledChangeId, disabled, packageName);
        }

        /**
         * clear the type disabling change Id.
         *
         * For test only.
         */
        public void clearTypeDisabledForTest(@NonNull String packageName) throws RemoteException {
            clearOverrideForTest(mDisabledChangeId, packageName);
        }

        @SuppressLint("AndroidFrameworkRequiresPermission")
        boolean isTypeDeprecated(int callerUid) {
            return isValidChangeId(mDeprecationChangeId)
                    && CompatChanges.isChangeEnabled(mDeprecationChangeId, callerUid);
        }

        private void overrideChangeIdForTest(long changeId, boolean enable, String packageName)
                throws RemoteException {
            if (!isValidChangeId(changeId)) {
                return;
            }
            final ArraySet<Long> enabled = new ArraySet<>();
            final ArraySet<Long> disabled = new ArraySet<>();
            if (enable) {
                enabled.add(changeId);
            } else {
                disabled.add(changeId);
            }
            final CompatibilityChangeConfig overrides = new CompatibilityChangeConfig(
                    new Compatibility.ChangeConfig(enabled, disabled));
            IPlatformCompat platformCompat = IPlatformCompat.Stub.asInterface(
                        ServiceManager.getService(Context.PLATFORM_COMPAT_SERVICE));
            platformCompat.setOverridesForTest(overrides, packageName);
        }

        private void clearOverrideForTest(long changeId, @NonNull String packageName)
                throws RemoteException {
            IPlatformCompat platformCompat = IPlatformCompat.Stub.asInterface(
                        ServiceManager.getService(Context.PLATFORM_COMPAT_SERVICE));
            platformCompat.clearOverrideForTest(changeId, packageName);
        }
    }

    /**
     * This represents the set of permissions that's going to be required
     * for a specific service type.
     *
     * @hide
     */
    public static class ForegroundServiceTypePermissions {
        /**
         * The set of the permissions to be required.
         */
        final @NonNull ForegroundServiceTypePermission[] mPermissions;

        /**
         * Are we requiring all of the permissions to be granted or any of them.
         */
        final boolean mAllOf;

        /**
         * Constructor.
         */
        public ForegroundServiceTypePermissions(
                @NonNull ForegroundServiceTypePermission[] permissions, boolean allOf) {
            mPermissions = permissions;
            mAllOf = allOf;
        }

        /**
         * Check the permissions.
         */
        @PackageManager.PermissionResult
        public int checkPermissions(@NonNull Context context, int callerUid, int callerPid,
                @NonNull String packageName, boolean allowWhileInUse) {
            if (mAllOf) {
                for (ForegroundServiceTypePermission perm : mPermissions) {
                    final int result = perm.checkPermission(context, callerUid, callerPid,
                            packageName, allowWhileInUse);
                    if (result != PERMISSION_GRANTED) {
                        return PERMISSION_DENIED;
                    }
                }
                return PERMISSION_GRANTED;
            } else {
                boolean anyOfGranted = false;
                for (ForegroundServiceTypePermission perm : mPermissions) {
                    final int result = perm.checkPermission(context, callerUid, callerPid,
                            packageName, allowWhileInUse);
                    if (result == PERMISSION_GRANTED) {
                        anyOfGranted = true;
                        break;
                    }
                }
                return anyOfGranted ? PERMISSION_GRANTED : PERMISSION_DENIED;
            }
        }

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder();
            sb.append("allOf=");
            sb.append(mAllOf);
            sb.append(' ');
            sb.append('[');
            for (int i = 0; i < mPermissions.length; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(mPermissions[i].toString());
            }
            sb.append(']');
            return sb.toString();
        }

        @NonNull String[] toStringArray(Context context) {
            final ArrayList<String> list = new ArrayList<>();
            for (int i = 0; i < mPermissions.length; i++) {
                mPermissions[i].addToList(context, list);
            }
            return list.toArray(new String[list.size()]);
        }
    }

    /**
     * This represents a permission that's going to be required for a specific service type.
     *
     * @hide
     */
    public abstract static class ForegroundServiceTypePermission {
        /**
         * The name of this permission.
         */
        protected final @NonNull String mName;

        /**
         * Constructor.
         */
        public ForegroundServiceTypePermission(@NonNull String name) {
            mName = name;
        }

        /**
         * Check if the given uid/pid/package has the access to the permission.
         */
        @PackageManager.PermissionResult
        public abstract int checkPermission(@NonNull Context context, int callerUid, int callerPid,
                @NonNull String packageName, boolean allowWhileInUse);

        @Override
        public String toString() {
            return mName;
        }

        void addToList(@NonNull Context context, @NonNull ArrayList<String> list) {
            list.add(mName);
        }
    }

    /**
     * This represents a regular Android permission to be required for a specific service type.
     */
    static class RegularPermission extends ForegroundServiceTypePermission {
        RegularPermission(@NonNull String name) {
            super(name);
        }

        @Override
        @SuppressLint("AndroidFrameworkRequiresPermission")
        @PackageManager.PermissionResult
        public int checkPermission(@NonNull Context context, int callerUid, int callerPid,
                String packageName, boolean allowWhileInUse) {
            return checkPermission(context, mName, callerUid, callerPid, packageName,
                    allowWhileInUse);
        }

        @SuppressLint("AndroidFrameworkRequiresPermission")
        @PackageManager.PermissionResult
        int checkPermission(@NonNull Context context, @NonNull String name, int callerUid,
                int callerPid, String packageName, boolean allowWhileInUse) {
            // Simple case, check if it's already granted.
            @PackageManager.PermissionResult int result;
            if ((result = PermissionChecker.checkPermissionForPreflight(context, name,
                    callerPid, callerUid, packageName)) == PERMISSION_GRANTED) {
                return PERMISSION_GRANTED;
            }
            if (allowWhileInUse && result == PermissionCheckerManager.PERMISSION_SOFT_DENIED) {
                // Check its appops
                final int opCode = AppOpsManager.permissionToOpCode(name);
                final AppOpsManager appOpsManager = context.getSystemService(AppOpsManager.class);
                if (opCode != AppOpsManager.OP_NONE) {
                    final int currentMode = appOpsManager.unsafeCheckOpRawNoThrow(opCode, callerUid,
                            packageName);
                    if (currentMode == MODE_FOREGROUND) {
                        // It's in foreground only mode and we're allowing while-in-use.
                        return PERMISSION_GRANTED;
                    }
                }
            }
            return PERMISSION_DENIED;
        }
    }

    /**
     * This represents an app op permission to be required for a specific service type.
     */
    static class AppOpPermission extends ForegroundServiceTypePermission {
        final int mOpCode;

        AppOpPermission(int opCode) {
            super(AppOpsManager.opToPublicName(opCode));
            mOpCode = opCode;
        }

        @Override
        @PackageManager.PermissionResult
        public int checkPermission(@NonNull Context context, int callerUid, int callerPid,
                String packageName, boolean allowWhileInUse) {
            final AppOpsManager appOpsManager = context.getSystemService(AppOpsManager.class);
            final int mode = appOpsManager.unsafeCheckOpRawNoThrow(mOpCode, callerUid, packageName);
            return (mode == MODE_ALLOWED || (allowWhileInUse && mode == MODE_FOREGROUND))
                    ? PERMISSION_GRANTED : PERMISSION_DENIED;
        }
    }

    /**
     * This represents a special Android permission to be required for accessing usb devices.
     */
    static class UsbDevicePermission extends ForegroundServiceTypePermission {
        UsbDevicePermission() {
            super("USB Device");
        }

        @Override
        @SuppressLint("AndroidFrameworkRequiresPermission")
        @PackageManager.PermissionResult
        public int checkPermission(@NonNull Context context, int callerUid, int callerPid,
                String packageName, boolean allowWhileInUse) {
            final UsbManager usbManager = context.getSystemService(UsbManager.class);
            final HashMap<String, UsbDevice> devices = usbManager.getDeviceList();
            if (!ArrayUtils.isEmpty(devices)) {
                for (UsbDevice device : devices.values()) {
                    if (usbManager.hasPermission(device, packageName, callerPid, callerUid)) {
                        return PERMISSION_GRANTED;
                    }
                }
            }
            return PERMISSION_DENIED;
        }
    }

    /**
     * This represents a special Android permission to be required for accessing usb accessories.
     */
    static class UsbAccessoryPermission extends ForegroundServiceTypePermission {
        UsbAccessoryPermission() {
            super("USB Accessory");
        }

        @Override
        @SuppressLint("AndroidFrameworkRequiresPermission")
        @PackageManager.PermissionResult
        public int checkPermission(@NonNull Context context, int callerUid, int callerPid,
                String packageName, boolean allowWhileInUse) {
            final UsbManager usbManager = context.getSystemService(UsbManager.class);
            final UsbAccessory[] accessories = usbManager.getAccessoryList();
            if (!ArrayUtils.isEmpty(accessories)) {
                for (UsbAccessory accessory: accessories) {
                    if (usbManager.hasPermission(accessory, callerPid, callerUid)) {
                        return PERMISSION_GRANTED;
                    }
                }
            }
            return PERMISSION_DENIED;
        }
    }

    /**
     * The default policy for the foreground service types.
     *
     * @hide
     */
    public static class DefaultForegroundServiceTypePolicy extends ForegroundServiceTypePolicy {
        private final SparseArray<ForegroundServiceTypePolicyInfo> mForegroundServiceTypePolicies =
                new SparseArray<>();

        /**
         * Constructor
         */
        public DefaultForegroundServiceTypePolicy() {
            mForegroundServiceTypePolicies.put(FOREGROUND_SERVICE_TYPE_MANIFEST,
                    FGS_TYPE_POLICY_MANIFEST);
            mForegroundServiceTypePolicies.put(FOREGROUND_SERVICE_TYPE_NONE,
                    FGS_TYPE_POLICY_NONE);
            mForegroundServiceTypePolicies.put(FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                    FGS_TYPE_POLICY_DATA_SYNC);
            mForegroundServiceTypePolicies.put(FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                    FGS_TYPE_POLICY_MEDIA_PLAYBACK);
            mForegroundServiceTypePolicies.put(FOREGROUND_SERVICE_TYPE_PHONE_CALL,
                    FGS_TYPE_POLICY_PHONE_CALL);
            mForegroundServiceTypePolicies.put(FOREGROUND_SERVICE_TYPE_LOCATION,
                    FGS_TYPE_POLICY_LOCATION);
            mForegroundServiceTypePolicies.put(FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                    FGS_TYPE_POLICY_CONNECTED_DEVICE);
            mForegroundServiceTypePolicies.put(FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
                    FGS_TYPE_POLICY_MEDIA_PROJECTION);
            mForegroundServiceTypePolicies.put(FOREGROUND_SERVICE_TYPE_CAMERA,
                    FGS_TYPE_POLICY_CAMERA);
            mForegroundServiceTypePolicies.put(FOREGROUND_SERVICE_TYPE_MICROPHONE,
                    FGS_TYPE_POLICY_MICROPHONE);
            mForegroundServiceTypePolicies.put(FOREGROUND_SERVICE_TYPE_HEALTH,
                    FGS_TYPE_POLICY_HEALTH);
            mForegroundServiceTypePolicies.put(FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING,
                    FGS_TYPE_POLICY_REMOTE_MESSAGING);
            mForegroundServiceTypePolicies.put(FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED,
                    FGS_TYPE_POLICY_SYSTEM_EXEMPTED);
            mForegroundServiceTypePolicies.put(FOREGROUND_SERVICE_TYPE_SHORT_SERVICE,
                    FGS_TYPE_POLICY_SHORT_SERVICE);
            mForegroundServiceTypePolicies.put(FOREGROUND_SERVICE_TYPE_FILE_MANAGEMENT,
                    FGS_TYPE_POLICY_FILE_MANAGEMENT);
            mForegroundServiceTypePolicies.put(FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                    FGS_TYPE_POLICY_SPECIAL_USE);
        }

        @Override
        public ForegroundServiceTypePolicyInfo getForegroundServiceTypePolicyInfo(
                @ForegroundServiceType int type, @ForegroundServiceType int defaultToType) {
            ForegroundServiceTypePolicyInfo info = mForegroundServiceTypePolicies.get(type);
            if (info == null) {
                // Unknown type, fallback to the defaultToType
                info = mForegroundServiceTypePolicies.get(defaultToType);
                if (info == null) {
                    // It shouldn't happen.
                    throw new IllegalArgumentException("Invalid default fgs type " + defaultToType);
                }
            }
            return info;
        }

        @Override
        @SuppressLint("AndroidFrameworkRequiresPermission")
        @ForegroundServicePolicyCheckCode
        public int checkForegroundServiceTypePolicy(Context context, String packageName,
                int callerUid, int callerPid, boolean allowWhileInUse,
                @NonNull ForegroundServiceTypePolicyInfo policy) {
            // Has this FGS type been disabled and not allowed to use anymore?
            if (policy.isTypeDisabled(callerUid)) {
                return FGS_TYPE_POLICY_CHECK_DISABLED;
            }
            int permissionResult = PERMISSION_DENIED;
            // Do we have the permission to start FGS with this type.
            if (policy.mAllOfPermissions != null) {
                permissionResult = policy.mAllOfPermissions.checkPermissions(context,
                        callerUid, callerPid, packageName, allowWhileInUse);
            }
            // If it has the "all of" permissions granted, check the "any of" ones.
            if (permissionResult == PERMISSION_GRANTED) {
                boolean checkCustomPermission = true;
                // Check the "any of" permissions.
                if (policy.mAnyOfPermissions != null) {
                    permissionResult = policy.mAnyOfPermissions.checkPermissions(context,
                            callerUid, callerPid, packageName, allowWhileInUse);
                    if (permissionResult == PERMISSION_GRANTED) {
                        // We have one of them granted, no need to check custom permissions.
                        checkCustomPermission = false;
                    }
                }
                // If we have a customized permission checker, also call it now.
                if (checkCustomPermission && policy.mCustomPermission != null) {
                    permissionResult = policy.mCustomPermission.checkPermission(context,
                            callerUid, callerPid, packageName, allowWhileInUse);
                }
            }
            if (permissionResult != PERMISSION_GRANTED) {
                return (CompatChanges.isChangeEnabled(
                        FGS_TYPE_PERMISSION_CHANGE_ID, callerUid))
                        ? FGS_TYPE_POLICY_CHECK_PERMISSION_DENIED_ENFORCED
                        : FGS_TYPE_POLICY_CHECK_PERMISSION_DENIED_PERMISSIVE;
            }
            // Has this FGS type been deprecated?
            if (policy.isTypeDeprecated(callerUid)) {
                return FGS_TYPE_POLICY_CHECK_DEPRECATED;
            }
            return FGS_TYPE_POLICY_CHECK_OK;
        }
    }
}
