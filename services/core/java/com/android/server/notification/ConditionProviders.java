/**
 * Copyright (c) 2014, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.notification;

import android.annotation.NonNull;
import android.app.INotificationManager;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.notification.Condition;
import android.service.notification.ConditionProviderService;
import android.service.notification.IConditionProvider;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;

import com.android.internal.R;
import com.android.server.notification.NotificationManagerService.DumpFilter;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;

public class ConditionProviders extends ManagedServices {
    private final ArrayList<ConditionRecord> mRecords = new ArrayList<>();
    private final ArraySet<String> mSystemConditionProviderNames;
    private final ArraySet<SystemConditionProviderService> mSystemConditionProviders
            = new ArraySet<>();

    private Callback mCallback;

    public ConditionProviders(Context context, Handler handler, UserProfiles userProfiles) {
        super(context, handler, new Object(), userProfiles);
        mSystemConditionProviderNames = safeSet(PropConfig.getStringArray(mContext,
                "system.condition.providers",
                R.array.config_system_condition_providers));
    }

    public void setCallback(Callback callback) {
        mCallback = callback;
    }

    public boolean isSystemProviderEnabled(String path) {
        return mSystemConditionProviderNames.contains(path);
    }

    public void addSystemProvider(SystemConditionProviderService service) {
        mSystemConditionProviders.add(service);
        service.attachBase(mContext);
        registerService(service.asInterface(), service.getComponent(), UserHandle.USER_SYSTEM);
    }

    public Iterable<SystemConditionProviderService> getSystemProviders() {
        return mSystemConditionProviders;
    }

    @Override
    protected Config getConfig() {
        final Config c = new Config();
        c.caption = "condition provider";
        c.serviceInterface = ConditionProviderService.SERVICE_INTERFACE;
        c.secureSettingName = Settings.Secure.ENABLED_NOTIFICATION_POLICY_ACCESS_PACKAGES;
        c.secondarySettingName = Settings.Secure.ENABLED_NOTIFICATION_LISTENERS;
        c.bindPermission = android.Manifest.permission.BIND_CONDITION_PROVIDER_SERVICE;
        c.settingsAction = Settings.ACTION_CONDITION_PROVIDER_SETTINGS;
        c.clientLabel = R.string.condition_provider_service_binding_label;
        return c;
    }

    @Override
    public void dump(PrintWriter pw, DumpFilter filter) {
        super.dump(pw, filter);
        synchronized(mMutex) {
            pw.print("    mRecords("); pw.print(mRecords.size()); pw.println("):");
            for (int i = 0; i < mRecords.size(); i++) {
                final ConditionRecord r = mRecords.get(i);
                if (filter != null && !filter.matches(r.component)) continue;
                pw.print("      "); pw.println(r);
                final String countdownDesc =  CountdownConditionProvider.tryParseDescription(r.id);
                if (countdownDesc != null) {
                    pw.print("        ("); pw.print(countdownDesc); pw.println(")");
                }
            }
        }
        pw.print("    mSystemConditionProviders: "); pw.println(mSystemConditionProviderNames);
        for (int i = 0; i < mSystemConditionProviders.size(); i++) {
            mSystemConditionProviders.valueAt(i).dump(pw, filter);
        }
    }

    @Override
    protected IInterface asInterface(IBinder binder) {
        return IConditionProvider.Stub.asInterface(binder);
    }

    @Override
    protected boolean checkType(IInterface service) {
        return service instanceof IConditionProvider;
    }

    @Override
    public void onBootPhaseAppsCanStart() {
        super.onBootPhaseAppsCanStart();
        for (int i = 0; i < mSystemConditionProviders.size(); i++) {
            mSystemConditionProviders.valueAt(i).onBootComplete();
        }
        if (mCallback != null) {
            mCallback.onBootComplete();
        }
    }

    @Override
    public void onUserSwitched(int user) {
        super.onUserSwitched(user);
        if (mCallback != null) {
            mCallback.onUserSwitched();
        }
    }

    @Override
    protected void onServiceAdded(ManagedServiceInfo info) {
        final IConditionProvider provider = provider(info);
        try {
            provider.onConnected();
        } catch (RemoteException e) {
            // we tried
        }
        if (mCallback != null) {
            mCallback.onServiceAdded(info.component);
        }
    }

    @Override
    protected void onServiceRemovedLocked(ManagedServiceInfo removed) {
        if (removed == null) return;
        for (int i = mRecords.size() - 1; i >= 0; i--) {
            final ConditionRecord r = mRecords.get(i);
            if (!r.component.equals(removed.component)) continue;
            mRecords.remove(i);
        }
    }

    @Override
    public void onPackagesChanged(boolean removingPackage, String[] pkgList) {
        if (removingPackage) {
            INotificationManager inm = NotificationManager.getService();

            if (pkgList != null && (pkgList.length > 0)) {
                for (String pkgName : pkgList) {
                    try {
                        inm.removeAutomaticZenRules(pkgName);
                        inm.setNotificationPolicyAccessGranted(pkgName, false);
                    } catch (Exception e) {
                        Slog.e(TAG, "Failed to clean up rules for " + pkgName, e);
                    }
                }
            }
        }
        super.onPackagesChanged(removingPackage, pkgList);
    }

    public ManagedServiceInfo checkServiceToken(IConditionProvider provider) {
        synchronized(mMutex) {
            return checkServiceTokenLocked(provider);
        }
    }

    private Condition[] removeDuplicateConditions(String pkg, Condition[] conditions) {
        if (conditions == null || conditions.length == 0) return null;
        final int N = conditions.length;
        final ArrayMap<Uri, Condition> valid = new ArrayMap<Uri, Condition>(N);
        for (int i = 0; i < N; i++) {
            final Uri id = conditions[i].id;
            if (valid.containsKey(id)) {
                Slog.w(TAG, "Ignoring condition from " + pkg + " for duplicate id: " + id);
                continue;
            }
            valid.put(id, conditions[i]);
        }
        if (valid.size() == 0) return null;
        if (valid.size() == N) return conditions;
        final Condition[] rt = new Condition[valid.size()];
        for (int i = 0; i < rt.length; i++) {
            rt[i] = valid.valueAt(i);
        }
        return rt;
    }

    private ConditionRecord getRecordLocked(Uri id, ComponentName component, boolean create) {
        if (id == null || component == null) return null;
        final int N = mRecords.size();
        for (int i = 0; i < N; i++) {
            final ConditionRecord r = mRecords.get(i);
            if (r.id.equals(id) && r.component.equals(component)) {
                return r;
            }
        }
        if (create) {
            final ConditionRecord r = new ConditionRecord(id, component);
            mRecords.add(r);
            return r;
        }
        return null;
    }

    public void notifyConditions(String pkg, ManagedServiceInfo info, Condition[] conditions) {
        synchronized(mMutex) {
            if (DEBUG) Slog.d(TAG, "notifyConditions pkg=" + pkg + " info=" + info + " conditions="
                    + (conditions == null ? null : Arrays.asList(conditions)));
            conditions = removeDuplicateConditions(pkg, conditions);
            if (conditions == null || conditions.length == 0) return;
            final int N = conditions.length;
            for (int i = 0; i < N; i++) {
                final Condition c = conditions[i];
                final ConditionRecord r = getRecordLocked(c.id, info.component, true /*create*/);
                r.info = info;
                r.condition = c;
            }
        }
        final int N = conditions.length;
        for (int i = 0; i < N; i++) {
            final Condition c = conditions[i];
            if (mCallback != null) {
                mCallback.onConditionChanged(c.id, c);
            }
        }
    }

    public IConditionProvider findConditionProvider(ComponentName component) {
        if (component == null) return null;
        for (ManagedServiceInfo service : mServices) {
            if (component.equals(service.component)) {
                return provider(service);
            }
        }
        return null;
    }

    public Condition findCondition(ComponentName component, Uri conditionId) {
        if (component == null || conditionId == null) return null;
        synchronized (mMutex) {
            final ConditionRecord r = getRecordLocked(conditionId, component, false /*create*/);
            return r != null ? r.condition : null;
        }
    }

    public void ensureRecordExists(ComponentName component, Uri conditionId,
            IConditionProvider provider) {
        // constructed by convention, make sure the record exists...
        final ConditionRecord r = getRecordLocked(conditionId, component, true /*create*/);
        if (r.info == null) {
            // ... and is associated with the in-process service
            r.info = checkServiceTokenLocked(provider);
        }
    }

    @Override
    protected @NonNull ArraySet<ComponentName> loadComponentNamesFromSetting(String settingName,
            int userId) {
        final ContentResolver cr = mContext.getContentResolver();
        String settingValue = Settings.Secure.getStringForUser(
                cr,
                settingName,
                userId);
        if (TextUtils.isEmpty(settingValue))
            return new ArraySet<>();
        String[] packages = settingValue.split(ENABLED_SERVICES_SEPARATOR);
        ArraySet<ComponentName> result = new ArraySet<>(packages.length);
        for (int i = 0; i < packages.length; i++) {
            if (!TextUtils.isEmpty(packages[i])) {
                final ComponentName component = ComponentName.unflattenFromString(packages[i]);
                if (component != null) {
                    result.addAll(queryPackageForServices(component.getPackageName(), userId));
                } else {
                    result.addAll(queryPackageForServices(packages[i], userId));
                }
            }
        }
        return result;
    }

    public boolean subscribeIfNecessary(ComponentName component, Uri conditionId) {
        synchronized (mMutex) {
            final ConditionRecord r = getRecordLocked(conditionId, component, false /*create*/);
            if (r == null) {
                Slog.w(TAG, "Unable to subscribe to " + component + " " + conditionId);
                return false;
            }
            if (r.subscribed) return true;
            subscribeLocked(r);
            return r.subscribed;
        }
    }

    public void unsubscribeIfNecessary(ComponentName component, Uri conditionId) {
        synchronized (mMutex) {
            final ConditionRecord r = getRecordLocked(conditionId, component, false /*create*/);
            if (r == null) {
                Slog.w(TAG, "Unable to unsubscribe to " + component + " " + conditionId);
                return;
            }
            if (!r.subscribed) return;
            unsubscribeLocked(r);;
        }
    }

    private void subscribeLocked(ConditionRecord r) {
        if (DEBUG) Slog.d(TAG, "subscribeLocked " + r);
        final IConditionProvider provider = provider(r);
        RemoteException re = null;
        if (provider != null) {
            try {
                Slog.d(TAG, "Subscribing to " + r.id + " with " + r.component);
                provider.onSubscribe(r.id);
                r.subscribed = true;
            } catch (RemoteException e) {
                Slog.w(TAG, "Error subscribing to " + r, e);
                re = e;
            }
        }
        ZenLog.traceSubscribe(r != null ? r.id : null, provider, re);
    }

    @SafeVarargs
    private static <T> ArraySet<T> safeSet(T... items) {
        final ArraySet<T> rt = new ArraySet<T>();
        if (items == null || items.length == 0) return rt;
        final int N = items.length;
        for (int i = 0; i < N; i++) {
            final T item = items[i];
            if (item != null) {
                rt.add(item);
            }
        }
        return rt;
    }

    private void unsubscribeLocked(ConditionRecord r) {
        if (DEBUG) Slog.d(TAG, "unsubscribeLocked " + r);
        final IConditionProvider provider = provider(r);
        RemoteException re = null;
        if (provider != null) {
            try {
                provider.onUnsubscribe(r.id);
            } catch (RemoteException e) {
                Slog.w(TAG, "Error unsubscribing to " + r, e);
                re = e;
            }
            r.subscribed = false;
        }
        ZenLog.traceUnsubscribe(r != null ? r.id : null, provider, re);
    }

    private static IConditionProvider provider(ConditionRecord r) {
        return r == null ? null : provider(r.info);
    }

    private static IConditionProvider provider(ManagedServiceInfo info) {
        return info == null ? null : (IConditionProvider) info.service;
    }

    private static class ConditionRecord {
        public final Uri id;
        public final ComponentName component;
        public Condition condition;
        public ManagedServiceInfo info;
        public boolean subscribed;

        private ConditionRecord(Uri id, ComponentName component) {
            this.id = id;
            this.component = component;
        }

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder("ConditionRecord[id=")
                    .append(id).append(",component=").append(component)
                    .append(",subscribed=").append(subscribed);
            return sb.append(']').toString();
        }
    }

    public interface Callback {
        void onBootComplete();
        void onServiceAdded(ComponentName component);
        void onConditionChanged(Uri id, Condition condition);
        void onUserSwitched();
    }

}
