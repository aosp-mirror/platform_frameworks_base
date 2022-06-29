/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.servicewatcher;

import static android.content.pm.PackageManager.GET_META_DATA;
import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_AUTO;
import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_AWARE;
import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_UNAWARE;
import static android.content.pm.PackageManager.MATCH_SYSTEM_ONLY;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.os.UserHandle.USER_SYSTEM;

import android.annotation.BoolRes;
import android.annotation.Nullable;
import android.annotation.StringRes;
import android.app.ActivityManagerInternal;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.UserHandle;
import android.permission.PermissionManager;
import android.util.Log;

import com.android.internal.util.Preconditions;
import com.android.server.FgThread;
import com.android.server.LocalServices;
import com.android.server.servicewatcher.ServiceWatcher.ServiceChangedListener;
import com.android.server.servicewatcher.ServiceWatcher.ServiceSupplier;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Supplies services based on the current active user and version as defined in the service
 * manifest. This implementation uses {@link android.content.pm.PackageManager#MATCH_SYSTEM_ONLY} to
 * ensure only system (ie, privileged) services are matched. It also handles services that are not
 * direct boot aware, and will automatically pick the best service as the user's direct boot state
 * changes.
 *
 * <p>Optionally, two permissions may be specified: (1) a caller permission - any service that does
 * not require callers to hold this permission is rejected (2) a service permission - any service
 * whose package does not hold this permission is rejected.
 */
public final class CurrentUserServiceSupplier extends BroadcastReceiver implements
        ServiceSupplier<CurrentUserServiceSupplier.BoundServiceInfo> {

    private static final String TAG = "CurrentUserServiceSupplier";

    private static final String EXTRA_SERVICE_VERSION = "serviceVersion";
    private static final String EXTRA_SERVICE_IS_MULTIUSER = "serviceIsMultiuser";

    private static final Comparator<BoundServiceInfo> sBoundServiceInfoComparator = (o1, o2) -> {
        if (o1 == o2) {
            return 0;
        } else if (o1 == null) {
            return -1;
        } else if (o2 == null) {
            return 1;
        }

        // ServiceInfos with higher version numbers always win. if version numbers are equal
        // then we prefer components that work for all users vs components that only work for a
        // single user at a time. otherwise everything's equal.
        int ret = Integer.compare(o1.getVersion(), o2.getVersion());
        if (ret == 0) {
            if (o1.getUserId() != USER_SYSTEM && o2.getUserId() == USER_SYSTEM) {
                ret = -1;
            } else if (o1.getUserId() == USER_SYSTEM && o2.getUserId() != USER_SYSTEM) {
                ret = 1;
            }
        }
        return ret;
    };

    /** Bound service information with version information. */
    public static class BoundServiceInfo extends ServiceWatcher.BoundServiceInfo {

        private static int parseUid(ResolveInfo resolveInfo) {
            int uid = resolveInfo.serviceInfo.applicationInfo.uid;
            Bundle metadata = resolveInfo.serviceInfo.metaData;
            if (metadata != null && metadata.getBoolean(EXTRA_SERVICE_IS_MULTIUSER, false)) {
                // reconstruct a uid for the same app but with the system user - hope this exists
                uid = UserHandle.getUid(USER_SYSTEM, UserHandle.getAppId(uid));
            }
            return uid;
        }

        private static int parseVersion(ResolveInfo resolveInfo) {
            int version = Integer.MIN_VALUE;
            if (resolveInfo.serviceInfo.metaData != null) {
                version = resolveInfo.serviceInfo.metaData.getInt(EXTRA_SERVICE_VERSION, version);
            }
            return version;
        }

        private final int mVersion;
        private final @Nullable Bundle mMetadata;

        protected BoundServiceInfo(String action, ResolveInfo resolveInfo) {
            this(action, parseUid(resolveInfo), resolveInfo.serviceInfo.getComponentName(),
                    parseVersion(resolveInfo), resolveInfo.serviceInfo.metaData);
        }

        protected BoundServiceInfo(String action, int uid, ComponentName componentName, int version,
                @Nullable Bundle metadata) {
            super(action, uid, componentName);

            mVersion = version;
            mMetadata = metadata;
        }

        public int getVersion() {
            return mVersion;
        }

        public @Nullable Bundle getMetadata() {
            return mMetadata;
        }

        @Override
        public String toString() {
            return super.toString() + "@" + mVersion;
        }
    }

    /**
     * Creates an instance using package details retrieved from config.
     *
     * @see #create(Context, String, String, String, String)
     */
    public static CurrentUserServiceSupplier createFromConfig(Context context, String action,
            @BoolRes int enableOverlayResId, @StringRes int nonOverlayPackageResId) {
        String explicitPackage = retrieveExplicitPackage(context, enableOverlayResId,
                nonOverlayPackageResId);
        return CurrentUserServiceSupplier.create(context, action, explicitPackage,
                /*callerPermission=*/null, /*servicePermission=*/null);
    }

    /**
     * Creates an instance with the specific service details and permission requirements.
     *
     * @param context the context the supplier is to use
     * @param action the action the service must declare in its intent-filter
     * @param explicitPackage the package of the service, or {@code null} if the package of the
     *     service is not constrained
     * @param callerPermission a permission that the service forces callers (i.e.
     *     ServiceWatcher/system server) to hold, or {@code null} if there isn't one
     * @param servicePermission a permission that the service package should hold, or {@code null}
     *     if there isn't one
     */
    public static CurrentUserServiceSupplier create(Context context, String action,
            @Nullable String explicitPackage, @Nullable String callerPermission,
            @Nullable String servicePermission) {
        boolean matchSystemAppsOnly = true;
        return new CurrentUserServiceSupplier(context, action,
                explicitPackage, callerPermission, servicePermission, matchSystemAppsOnly);
    }

    /**
     * Creates an instance like {@link #create} except it allows connection to services that are not
     * supplied by system packages. Only intended for use during tests.
     *
     * @see #create(Context, String, String, String, String)
     */
    public static CurrentUserServiceSupplier createUnsafeForTestsOnly(Context context,
            String action, @Nullable String explicitPackage, @Nullable String callerPermission,
            @Nullable String servicePermission) {
        boolean matchSystemAppsOnly = false;
        return new CurrentUserServiceSupplier(context, action,
                explicitPackage, callerPermission, servicePermission, matchSystemAppsOnly);
    }

    private static @Nullable String retrieveExplicitPackage(Context context,
            @BoolRes int enableOverlayResId, @StringRes int nonOverlayPackageResId) {
        Resources resources = context.getResources();
        boolean enableOverlay = resources.getBoolean(enableOverlayResId);
        if (!enableOverlay) {
            return resources.getString(nonOverlayPackageResId);
        } else {
            return null;
        }
    }

    private final Context mContext;
    private final ActivityManagerInternal mActivityManager;
    private final Intent mIntent;
    // a permission that the service forces callers (ie ServiceWatcher/system server) to hold
    private final @Nullable String mCallerPermission;
    // a permission that the service package should hold
    private final @Nullable String mServicePermission;
    // whether to use MATCH_SYSTEM_ONLY in queries
    private final boolean mMatchSystemAppsOnly;

    private volatile ServiceChangedListener mListener;

    private CurrentUserServiceSupplier(Context context, String action,
            @Nullable String explicitPackage, @Nullable String callerPermission,
            @Nullable String servicePermission, boolean matchSystemAppsOnly) {
        mContext = context;
        mActivityManager = Objects.requireNonNull(
                LocalServices.getService(ActivityManagerInternal.class));
        mIntent = new Intent(action);

        if (explicitPackage != null) {
            mIntent.setPackage(explicitPackage);
        }

        mCallerPermission = callerPermission;
        mServicePermission = servicePermission;
        mMatchSystemAppsOnly = matchSystemAppsOnly;
    }

    @Override
    public boolean hasMatchingService() {
        int intentQueryFlags = MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE;
        if (mMatchSystemAppsOnly) {
            intentQueryFlags |= MATCH_SYSTEM_ONLY;
        }
        List<ResolveInfo> resolveInfos = mContext.getPackageManager()
                .queryIntentServicesAsUser(mIntent,
                        intentQueryFlags,
                        UserHandle.USER_SYSTEM);
        return !resolveInfos.isEmpty();
    }

    @Override
    public void register(ServiceChangedListener listener) {
        Preconditions.checkState(mListener == null);

        mListener = listener;

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_USER_SWITCHED);
        intentFilter.addAction(Intent.ACTION_USER_UNLOCKED);
        mContext.registerReceiverAsUser(this, UserHandle.ALL, intentFilter, null,
                FgThread.getHandler());
    }

    @Override
    public void unregister() {
        Preconditions.checkArgument(mListener != null);

        mListener = null;
        mContext.unregisterReceiver(this);
    }

    @Override
    public BoundServiceInfo getServiceInfo() {
        BoundServiceInfo bestServiceInfo = null;

        // only allow services in the correct direct boot state to match
        int intentQueryFlags = MATCH_DIRECT_BOOT_AUTO | GET_META_DATA;
        if (mMatchSystemAppsOnly) {
            intentQueryFlags |= MATCH_SYSTEM_ONLY;
        }
        int currentUserId = mActivityManager.getCurrentUserId();
        List<ResolveInfo> resolveInfos = mContext.getPackageManager().queryIntentServicesAsUser(
                mIntent,
                intentQueryFlags,
                currentUserId);
        for (ResolveInfo resolveInfo : resolveInfos) {
            ServiceInfo service = Objects.requireNonNull(resolveInfo.serviceInfo);

            if (mCallerPermission != null) {
                if (!mCallerPermission.equals(service.permission)) {
                    Log.d(TAG, service.getComponentName().flattenToShortString()
                            + " disqualified due to not requiring " + mCallerPermission);
                    continue;
                }
            }

            BoundServiceInfo serviceInfo = new BoundServiceInfo(mIntent.getAction(), resolveInfo);

            if (mServicePermission != null) {
                if (PermissionManager.checkPackageNamePermission(mServicePermission,
                        service.packageName, serviceInfo.getUserId()) != PERMISSION_GRANTED) {
                    Log.d(TAG, serviceInfo.getComponentName().flattenToShortString()
                            + " disqualified due to not holding " + mCallerPermission);
                    continue;
                }
            }

            if (sBoundServiceInfoComparator.compare(serviceInfo, bestServiceInfo) > 0) {
                bestServiceInfo = serviceInfo;
            }
        }

        return bestServiceInfo;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) {
            return;
        }
        int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, UserHandle.USER_NULL);
        if (userId == UserHandle.USER_NULL) {
            return;
        }
        ServiceChangedListener listener = mListener;
        if (listener == null) {
            return;
        }

        switch (action) {
            case Intent.ACTION_USER_SWITCHED:
                listener.onServiceChanged();
                break;
            case Intent.ACTION_USER_UNLOCKED:
                // user unlocked implies direct boot mode may have changed
                if (userId == mActivityManager.getCurrentUserId()) {
                    listener.onServiceChanged();
                }
                break;
            default:
                break;
        }
    }
}
