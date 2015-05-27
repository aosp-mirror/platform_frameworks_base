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

import android.content.ComponentName;
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
import android.service.notification.IConditionListener;
import android.service.notification.IConditionProvider;
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
    private final ArrayMap<IBinder, IConditionListener> mListeners = new ArrayMap<>();
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
        registerService(service.asInterface(), service.getComponent(), UserHandle.USER_OWNER);
    }

    public Iterable<SystemConditionProviderService> getSystemProviders() {
        return mSystemConditionProviders;
    }

    @Override
    protected Config getConfig() {
        final Config c = new Config();
        c.caption = "condition provider";
        c.serviceInterface = ConditionProviderService.SERVICE_INTERFACE;
        c.secureSettingName = Settings.Secure.ENABLED_CONDITION_PROVIDERS;
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
        if (filter == null) {
            pw.print("    mListeners("); pw.print(mListeners.size()); pw.println("):");
            for (int i = 0; i < mListeners.size(); i++) {
                pw.print("      "); pw.println(mListeners.keyAt(i));
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

    public ManagedServiceInfo checkServiceToken(IConditionProvider provider) {
        synchronized(mMutex) {
            return checkServiceTokenLocked(provider);
        }
    }

    public void requestConditions(IConditionListener callback, int relevance) {
        synchronized(mMutex) {
            if (DEBUG) Slog.d(TAG, "requestConditions callback=" + callback
                    + " relevance=" + Condition.relevanceToString(relevance));
            if (callback == null) return;
            relevance = relevance & (Condition.FLAG_RELEVANT_NOW | Condition.FLAG_RELEVANT_ALWAYS);
            if (relevance != 0) {
                mListeners.put(callback.asBinder(), callback);
                requestConditionsLocked(relevance);
            } else {
                mListeners.remove(callback.asBinder());
                if (mListeners.isEmpty()) {
                    requestConditionsLocked(0);
                }
            }
        }
    }

    private Condition[] validateConditions(String pkg, Condition[] conditions) {
        if (conditions == null || conditions.length == 0) return null;
        final int N = conditions.length;
        final ArrayMap<Uri, Condition> valid = new ArrayMap<Uri, Condition>(N);
        for (int i = 0; i < N; i++) {
            final Uri id = conditions[i].id;
            if (!Condition.isValidId(id, pkg)) {
                Slog.w(TAG, "Ignoring condition from " + pkg + " for invalid id: " + id);
                continue;
            }
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
            conditions = validateConditions(pkg, conditions);
            if (conditions == null || conditions.length == 0) return;
            final int N = conditions.length;
            for (IConditionListener listener : mListeners.values()) {
                try {
                    listener.onConditionsReceived(conditions);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Error sending conditions to listener " + listener, e);
                }
            }
            for (int i = 0; i < N; i++) {
                final Condition c = conditions[i];
                final ConditionRecord r = getRecordLocked(c.id, info.component, true /*create*/);
                r.info = info;
                r.condition = c;
                if (mCallback != null) {
                    mCallback.onConditionChanged(c.id, c);
                }
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

    private void requestConditionsLocked(int flags) {
        for (ManagedServiceInfo info : mServices) {
            final IConditionProvider provider = provider(info);
            if (provider == null) continue;
            // clear all stored conditions from this provider that we no longer care about
            for (int i = mRecords.size() - 1; i >= 0; i--) {
                final ConditionRecord r = mRecords.get(i);
                if (r.info != info) continue;
                if (r.subscribed) continue;
                mRecords.remove(i);
            }
            try {
                provider.onRequestConditions(flags);
            } catch (RemoteException e) {
                Slog.w(TAG, "Error requesting conditions from " + info.component, e);
            }
        }
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
