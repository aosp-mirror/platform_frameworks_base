/**
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.server.vr;

import android.annotation.NonNull;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.content.PackageMonitor;
import com.android.server.vr.SettingsObserver.SettingChangeListener;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Detects changes in packages, settings, and current users that may affect whether components
 * implementing a given service can be run.
 *
 * @hide
 */
public class EnabledComponentsObserver implements SettingChangeListener {

    private static final String TAG = EnabledComponentsObserver.class.getSimpleName();
    private static final String ENABLED_SERVICES_SEPARATOR = ":";

    public static final int NO_ERROR = 0;
    public static final int DISABLED = -1;
    public static final int NOT_INSTALLED = -2;

    private final Object mLock;
    private final Context mContext;
    private final String mSettingName;
    private final String mServiceName;
    private final String mServicePermission;
    private final SparseArray<ArraySet<ComponentName>> mInstalledSet = new SparseArray<>();
    private final SparseArray<ArraySet<ComponentName>> mEnabledSet = new SparseArray<>();
    private final Set<EnabledComponentChangeListener> mEnabledComponentListeners = new ArraySet<>();

    /**
     * Implement this to receive callbacks when relevant changes to the allowed components occur.
     */
    public interface EnabledComponentChangeListener {

        /**
         * Called when a change in the allowed components occurs.
         */
        void onEnabledComponentChanged();
    }

    private EnabledComponentsObserver(@NonNull Context context, @NonNull String settingName,
            @NonNull String servicePermission, @NonNull String serviceName, @NonNull Object lock,
            @NonNull Collection<EnabledComponentChangeListener> listeners) {
        mLock = lock;
        mContext = context;
        mSettingName = settingName;
        mServiceName = serviceName;
        mServicePermission = servicePermission;
        mEnabledComponentListeners.addAll(listeners);
    }

    /**
     * Create a EnabledComponentObserver instance.
     *
     * @param context the context to query for changes.
     * @param handler a handler to receive lifecycle events from system services on.
     * @param settingName the name of a setting to monitor for a list of enabled components.
     * @param looper a {@link Looper} to use for receiving package callbacks.
     * @param servicePermission the permission required by the components to be bound.
     * @param serviceName the intent action implemented by the tracked components.
     * @param lock a lock object used to guard instance state in all callbacks and method calls.
     * @return an EnableComponentObserver instance.
     */
    public static EnabledComponentsObserver build(@NonNull Context context,
            @NonNull Handler handler, @NonNull String settingName, @NonNull Looper looper,
            @NonNull String servicePermission, @NonNull String serviceName,
            @NonNull final Object lock,
            @NonNull Collection<EnabledComponentChangeListener> listeners) {

        SettingsObserver s = SettingsObserver.build(context, handler, settingName);

        final EnabledComponentsObserver o = new EnabledComponentsObserver(context, settingName,
                servicePermission, serviceName, lock, listeners);

        PackageMonitor packageMonitor = new PackageMonitor(true) {
            @Override
            public void onSomePackagesChanged() {
                o.onPackagesChanged();

            }

            @Override
            public void onPackageDisappeared(String packageName, int reason) {
                o.onPackagesChanged();

            }

            @Override
            public void onPackageModified(String packageName) {
                o.onPackagesChanged();

            }

            @Override
            public boolean onHandleForceStop(Intent intent, String[] packages, int uid,
                    boolean doit) {
                o.onPackagesChanged();

                return super.onHandleForceStop(intent, packages, uid, doit);
            }
        };

        packageMonitor.register(context, looper, UserHandle.ALL, true);

        s.addListener(o);

        return o;

    }

    public void onPackagesChanged() {
        rebuildAll();
    }

    @Override
    public void onSettingChanged() {
        rebuildAll();
    }

    @Override
    public void onSettingRestored(String prevValue, String newValue, int userId) {
        rebuildAll();
    }

    public void onUsersChanged() {
        rebuildAll();
    }

    /**
     * Rebuild the sets of allowed components for each current user profile.
     */
    public void rebuildAll() {
        synchronized (mLock) {
            mInstalledSet.clear();
            mEnabledSet.clear();
            final int[] userIds = getCurrentProfileIds();
            for (int i : userIds) {
                ArraySet<ComponentName> implementingPackages = loadComponentNamesForUser(i);
                ArraySet<ComponentName> packagesFromSettings =
                        loadComponentNamesFromSetting(mSettingName, i);
                packagesFromSettings.retainAll(implementingPackages);

                mInstalledSet.put(i, implementingPackages);
                mEnabledSet.put(i, packagesFromSettings);

            }
        }
        sendSettingChanged();
    }

    /**
     * Check whether a given component is present and enabled for the given user.
     *
     * @param component the component to check.
     * @param userId the user ID for the component to check.
     * @return {@code true} if present and enabled.
     */
    public int isValid(ComponentName component, int userId) {
        synchronized (mLock) {
            ArraySet<ComponentName> installedComponents = mInstalledSet.get(userId);
            if (installedComponents == null || !installedComponents.contains(component)) {
                return NOT_INSTALLED;
            }
            ArraySet<ComponentName> validComponents = mEnabledSet.get(userId);
            if (validComponents == null || !validComponents.contains(component)) {
                return DISABLED;
            }
            return NO_ERROR;
        }
    }

    /**
     * Return all VrListenerService components installed for this user.
     *
     * @param userId ID of the user to check.
     * @return a set of {@link ComponentName}s.
     */
    public ArraySet<ComponentName> getInstalled(int userId) {
        synchronized (mLock) {
            ArraySet<ComponentName> ret = mInstalledSet.get(userId);
            if (ret == null) {
                return new ArraySet<ComponentName>();
            }
            return ret;
        }
    }

    /**
     * Return all VrListenerService components enabled for this user.
     *
     * @param userId ID of the user to check.
     * @return a set of {@link ComponentName}s.
     */
    public ArraySet<ComponentName> getEnabled(int userId) {
        synchronized (mLock) {
            ArraySet<ComponentName> ret = mEnabledSet.get(userId);
            if (ret == null) {
                return new ArraySet<ComponentName>();
            }
            return ret;

        }
    }

    private int[] getCurrentProfileIds() {
        UserManager userManager = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
        if (userManager == null) {
            return null;
        }
        return userManager.getEnabledProfileIds(ActivityManager.getCurrentUser());
    }

    public static ArraySet<ComponentName> loadComponentNames(PackageManager pm, int userId,
            String serviceName, String permissionName) {

        ArraySet<ComponentName> installed = new ArraySet<>();
        Intent queryIntent = new Intent(serviceName);
        List<ResolveInfo> installedServices = pm.queryIntentServicesAsUser(
                queryIntent,
                PackageManager.GET_SERVICES | PackageManager.GET_META_DATA |
                                    PackageManager.MATCH_DIRECT_BOOT_AWARE |
                                    PackageManager.MATCH_DIRECT_BOOT_UNAWARE,
                userId);
        if (installedServices != null) {
            for (int i = 0, count = installedServices.size(); i < count; i++) {
                ResolveInfo resolveInfo = installedServices.get(i);
                ServiceInfo info = resolveInfo.serviceInfo;

                ComponentName component = new ComponentName(info.packageName, info.name);
                if (!permissionName.equals(info.permission)) {
                    Slog.w(TAG, "Skipping service " + info.packageName + "/" + info.name
                            + ": it does not require the permission "
                            + permissionName);
                    continue;
                }
                installed.add(component);
            }
        }
        return installed;
    }

    private ArraySet<ComponentName> loadComponentNamesForUser(int userId) {
        return loadComponentNames(mContext.getPackageManager(), userId, mServiceName,
                mServicePermission);
    }

    private ArraySet<ComponentName> loadComponentNamesFromSetting(String settingName,
            int userId) {
        final ContentResolver cr = mContext.getContentResolver();
        String settingValue = Settings.Secure.getStringForUser(
                cr,
                settingName,
                userId);
        if (TextUtils.isEmpty(settingValue))
            return new ArraySet<>();
        String[] restored = settingValue.split(ENABLED_SERVICES_SEPARATOR);
        ArraySet<ComponentName> result = new ArraySet<>(restored.length);
        for (int i = 0; i < restored.length; i++) {
            ComponentName value = ComponentName.unflattenFromString(restored[i]);
            if (null != value) {
                result.add(value);
            }
        }
        return result;
    }

    private void sendSettingChanged() {
        for (EnabledComponentChangeListener l : mEnabledComponentListeners) {
            l.onEnabledComponentChanged();
        }
    }

}
