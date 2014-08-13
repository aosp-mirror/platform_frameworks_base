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
import android.provider.Settings.Global;
import android.service.notification.Condition;
import android.service.notification.ConditionProviderService;
import android.service.notification.IConditionListener;
import android.service.notification.IConditionProvider;
import android.service.notification.ZenModeConfig;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;

import com.android.internal.R;
import com.android.server.notification.NotificationManagerService.DumpFilter;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;

public class ConditionProviders extends ManagedServices {
    private static final Condition[] NO_CONDITIONS = new Condition[0];

    private final ZenModeHelper mZenModeHelper;
    private final ArrayMap<IBinder, IConditionListener> mListeners
            = new ArrayMap<IBinder, IConditionListener>();
    private final ArrayList<ConditionRecord> mRecords = new ArrayList<ConditionRecord>();
    private final CountdownConditionProvider mCountdown = new CountdownConditionProvider();
    private final DowntimeConditionProvider mDowntime = new DowntimeConditionProvider();

    private Condition mExitCondition;
    private ComponentName mExitConditionComponent;

    public ConditionProviders(Context context, Handler handler,
            UserProfiles userProfiles, ZenModeHelper zenModeHelper) {
        super(context, handler, new Object(), userProfiles);
        mZenModeHelper = zenModeHelper;
        mZenModeHelper.addCallback(new ZenModeHelperCallback());
        loadZenConfig();
    }

    @Override
    protected Config getConfig() {
        Config c = new Config();
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
            if (filter == null) {
                pw.print("    mListeners("); pw.print(mListeners.size()); pw.println("):");
                for (int i = 0; i < mListeners.size(); i++) {
                    pw.print("      "); pw.println(mListeners.keyAt(i));
                }
            }
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
        mCountdown.dump(pw, filter);
        mDowntime.dump(pw, filter);
    }

    @Override
    protected IInterface asInterface(IBinder binder) {
        return IConditionProvider.Stub.asInterface(binder);
    }

    @Override
    public void onBootPhaseAppsCanStart() {
        super.onBootPhaseAppsCanStart();
        mCountdown.attachBase(mContext);
        registerService(mCountdown.asInterface(), CountdownConditionProvider.COMPONENT,
                UserHandle.USER_OWNER);
        mDowntime.attachBase(mContext);
        registerService(mDowntime.asInterface(), DowntimeConditionProvider.COMPONENT,
                UserHandle.USER_OWNER);
        mDowntime.setCallback(new DowntimeCallback());
    }

    @Override
    protected void onServiceAdded(ManagedServiceInfo info) {
        Slog.d(TAG, "onServiceAdded " + info);
        final IConditionProvider provider = provider(info);
        try {
            provider.onConnected();
        } catch (RemoteException e) {
            // we tried
        }
        synchronized (mMutex) {
            if (info.component.equals(mExitConditionComponent)) {
                // ensure record exists, we'll wire it up and subscribe below
                final ConditionRecord manualRecord =
                        getRecordLocked(mExitCondition.id, mExitConditionComponent);
                manualRecord.isManual = true;
            }
            final int N = mRecords.size();
            for(int i = 0; i < N; i++) {
                final ConditionRecord r = mRecords.get(i);
                if (!r.component.equals(info.component)) continue;
                r.info = info;
                // if automatic or manual, auto-subscribe
                if (r.isAutomatic || r.isManual) {
                    subscribeLocked(r);
                }
            }
        }
    }

    @Override
    protected void onServiceRemovedLocked(ManagedServiceInfo removed) {
        if (removed == null) return;
        for (int i = mRecords.size() - 1; i >= 0; i--) {
            final ConditionRecord r = mRecords.get(i);
            if (!r.component.equals(removed.component)) continue;
            if (r.isManual) {
                // removing the current manual condition, exit zen
                mZenModeHelper.setZenMode(Global.ZEN_MODE_OFF, "manualServiceRemoved");
            }
            if (r.isAutomatic) {
                // removing an automatic condition, exit zen
                mZenModeHelper.setZenMode(Global.ZEN_MODE_OFF, "automaticServiceRemoved");
            }
            mRecords.remove(i);
        }
    }

    public ManagedServiceInfo checkServiceToken(IConditionProvider provider) {
        synchronized(mMutex) {
            return checkServiceTokenLocked(provider);
        }
    }

    public void requestZenModeConditions(IConditionListener callback, int relevance) {
        synchronized(mMutex) {
            if (DEBUG) Slog.d(TAG, "requestZenModeConditions callback=" + callback
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

    private ConditionRecord getRecordLocked(Uri id, ComponentName component) {
        final int N = mRecords.size();
        for (int i = 0; i < N; i++) {
            final ConditionRecord r = mRecords.get(i);
            if (r.id.equals(id) && r.component.equals(component)) {
                return r;
            }
        }
        final ConditionRecord r = new ConditionRecord(id, component);
        mRecords.add(r);
        return r;
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
                final ConditionRecord r = getRecordLocked(c.id, info.component);
                r.info = info;
                r.condition = c;
                // if manual, exit zen if false (or failed)
                if (r.isManual) {
                    if (c.state == Condition.STATE_FALSE || c.state == Condition.STATE_ERROR) {
                        final boolean failed = c.state == Condition.STATE_ERROR;
                        if (failed) {
                            Slog.w(TAG, "Exit zen: manual condition failed: " + c);
                        } else if (DEBUG) {
                            Slog.d(TAG, "Exit zen: manual condition false: " + c);
                        }
                        mZenModeHelper.setZenMode(Settings.Global.ZEN_MODE_OFF,
                                "manualConditionExit");
                        unsubscribeLocked(r);
                        r.isManual = false;
                    }
                }
                // if automatic, exit zen if false (or failed), enter zen if true
                if (r.isAutomatic) {
                    if (c.state == Condition.STATE_FALSE || c.state == Condition.STATE_ERROR) {
                        final boolean failed = c.state == Condition.STATE_ERROR;
                        if (failed) {
                            Slog.w(TAG, "Exit zen: automatic condition failed: " + c);
                        } else if (DEBUG) {
                            Slog.d(TAG, "Exit zen: automatic condition false: " + c);
                        }
                        mZenModeHelper.setZenMode(Settings.Global.ZEN_MODE_OFF,
                                "automaticConditionExit");
                    } else if (c.state == Condition.STATE_TRUE) {
                        Slog.d(TAG, "Enter zen: automatic condition true: " + c);
                        mZenModeHelper.setZenMode(Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS,
                                "automaticConditionEnter");
                    }
                }
            }
        }
    }

    public void setZenModeCondition(Condition condition, String reason) {
        if (DEBUG) Slog.d(TAG, "setZenModeCondition " + condition);
        synchronized(mMutex) {
            ComponentName conditionComponent = null;
            if (condition != null) {
                if (ZenModeConfig.isValidCountdownConditionId(condition.id)) {
                    // constructed by the client, make sure the record exists...
                    final ConditionRecord r = getRecordLocked(condition.id,
                            CountdownConditionProvider.COMPONENT);
                    if (r.info == null) {
                        // ... and is associated with the in-process service
                        r.info = checkServiceTokenLocked(mCountdown.asInterface());
                    }
                }
                if (ZenModeConfig.isValidDowntimeConditionId(condition.id)) {
                    // constructed by the client, make sure the record exists...
                    final ConditionRecord r = getRecordLocked(condition.id,
                            DowntimeConditionProvider.COMPONENT);
                    if (r.info == null) {
                        // ... and is associated with the in-process service
                        r.info = checkServiceTokenLocked(mDowntime.asInterface());
                    }
                }
            }
            final int N = mRecords.size();
            for (int i = 0; i < N; i++) {
                final ConditionRecord r = mRecords.get(i);
                final boolean idEqual = condition != null && r.id.equals(condition.id);
                if (r.isManual && !idEqual) {
                    // was previous manual condition, unsubscribe
                    unsubscribeLocked(r);
                    r.isManual = false;
                } else if (idEqual && !r.isManual) {
                    // is new manual condition, subscribe
                    subscribeLocked(r);
                    r.isManual = true;
                }
                if (idEqual) {
                    conditionComponent = r.component;
                }
            }
            if (!Objects.equals(mExitCondition, condition)) {
                mExitCondition = condition;
                mExitConditionComponent = conditionComponent;
                ZenLog.traceExitCondition(mExitCondition, mExitConditionComponent, reason);
                saveZenConfigLocked();
            }
        }
    }

    private void subscribeLocked(ConditionRecord r) {
        if (DEBUG) Slog.d(TAG, "subscribeLocked " + r);
        final IConditionProvider provider = provider(r);
        RemoteException re = null;
        if (provider != null) {
            try {
                Slog.d(TAG, "Subscribing to " + r.id + " with " + provider);
                provider.onSubscribe(r.id);
            } catch (RemoteException e) {
                Slog.w(TAG, "Error subscribing to " + r, e);
                re = e;
            }
        }
        ZenLog.traceSubscribe(r != null ? r.id : null, provider, re);
    }

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

    public void setAutomaticZenModeConditions(Uri[] conditionIds) {
        setAutomaticZenModeConditions(conditionIds, true /*save*/);
    }

    private void setAutomaticZenModeConditions(Uri[] conditionIds, boolean save) {
        if (DEBUG) Slog.d(TAG, "setAutomaticZenModeConditions "
                + (conditionIds == null ? null : Arrays.asList(conditionIds)));
        synchronized(mMutex) {
            final ArraySet<Uri> newIds = safeSet(conditionIds);
            final int N = mRecords.size();
            boolean changed = false;
            for (int i = 0; i < N; i++) {
                final ConditionRecord r = mRecords.get(i);
                final boolean automatic = newIds.contains(r.id);
                if (!r.isAutomatic && automatic) {
                    // subscribe to new automatic
                    subscribeLocked(r);
                    r.isAutomatic = true;
                    changed = true;
                } else if (r.isAutomatic && !automatic) {
                    // unsubscribe from old automatic
                    unsubscribeLocked(r);
                    r.isAutomatic = false;
                    changed = true;
                }
            }
            if (save && changed) {
                saveZenConfigLocked();
            }
        }
    }

    public Condition[] getAutomaticZenModeConditions() {
        synchronized(mMutex) {
            final int N = mRecords.size();
            ArrayList<Condition> rt = null;
            for (int i = 0; i < N; i++) {
                final ConditionRecord r = mRecords.get(i);
                if (r.isAutomatic && r.condition != null) {
                    if (rt == null) rt = new ArrayList<Condition>();
                    rt.add(r.condition);
                }
            }
            return rt == null ? NO_CONDITIONS : rt.toArray(new Condition[rt.size()]);
        }
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
                if (r.isManual || r.isAutomatic) continue;
                mRecords.remove(i);
            }
            try {
                provider.onRequestConditions(flags);
            } catch (RemoteException e) {
                Slog.w(TAG, "Error requesting conditions from " + info.component, e);
            }
        }
    }

    private void loadZenConfig() {
        final ZenModeConfig config = mZenModeHelper.getConfig();
        if (config == null) {
            if (DEBUG) Slog.d(TAG, "loadZenConfig: no config");
            return;
        }
        synchronized (mMutex) {
            final boolean changingExit = !Objects.equals(mExitCondition, config.exitCondition);
            mExitCondition = config.exitCondition;
            mExitConditionComponent = config.exitConditionComponent;
            if (changingExit) {
                ZenLog.traceExitCondition(mExitCondition, mExitConditionComponent, "config");
            }
            mDowntime.setConfig(config);
            if (config.conditionComponents == null || config.conditionIds == null
                    || config.conditionComponents.length != config.conditionIds.length) {
                if (DEBUG) Slog.d(TAG, "loadZenConfig: no conditions");
                setAutomaticZenModeConditions(null, false /*save*/);
                return;
            }
            final ArraySet<Uri> newIds = new ArraySet<Uri>();
            final int N = config.conditionComponents.length;
            for (int i = 0; i < N; i++) {
                final ComponentName component = config.conditionComponents[i];
                final Uri id = config.conditionIds[i];
                if (component != null && id != null) {
                    getRecordLocked(id, component);  // ensure record exists
                    newIds.add(id);
                }
            }
            if (DEBUG) Slog.d(TAG, "loadZenConfig: N=" + N);
            setAutomaticZenModeConditions(newIds.toArray(new Uri[newIds.size()]), false /*save*/);
        }
    }

    private void saveZenConfigLocked() {
        ZenModeConfig config = mZenModeHelper.getConfig();
        if (config == null) return;
        config = config.copy();
        final ArrayList<ConditionRecord> automatic = new ArrayList<ConditionRecord>();
        final int automaticN = mRecords.size();
        for (int i = 0; i < automaticN; i++) {
            final ConditionRecord r = mRecords.get(i);
            if (r.isAutomatic) {
                automatic.add(r);
            }
        }
        if (automatic.isEmpty()) {
            config.conditionComponents = null;
            config.conditionIds = null;
        } else {
            final int N = automatic.size();
            config.conditionComponents = new ComponentName[N];
            config.conditionIds = new Uri[N];
            for (int i = 0; i < N; i++) {
                final ConditionRecord r = automatic.get(i);
                config.conditionComponents[i] = r.component;
                config.conditionIds[i] = r.id;
            }
        }
        config.exitCondition = mExitCondition;
        config.exitConditionComponent = mExitConditionComponent;
        if (DEBUG) Slog.d(TAG, "Setting zen config to: " + config);
        mZenModeHelper.setConfig(config);
    }

    private class ZenModeHelperCallback extends ZenModeHelper.Callback {
        @Override
        void onConfigChanged() {
            loadZenConfig();
        }

        @Override
        void onZenModeChanged() {
            final int mode = mZenModeHelper.getZenMode();
            if (mode == Global.ZEN_MODE_OFF) {
                // ensure any manual condition is cleared
                setZenModeCondition(null, "zenOff");
            }
        }
    }

    private class DowntimeCallback implements DowntimeConditionProvider.Callback {
        @Override
        public void onDowntimeChanged(boolean inDowntime) {
            final int mode = mZenModeHelper.getZenMode();
            final ZenModeConfig config = mZenModeHelper.getConfig();
            // enter downtime
            if (inDowntime && mode == Global.ZEN_MODE_OFF && config != null) {
                final Condition condition = mDowntime.createCondition(config.toDowntimeInfo(),
                        Condition.STATE_TRUE);
                mZenModeHelper.setZenMode(Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS, "downtimeEnter");
                setZenModeCondition(condition, "downtime");
            }
            // exit downtime
            if (!inDowntime && mode == Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS
                    && mDowntime.isDowntimeCondition(mExitCondition)) {
                mZenModeHelper.setZenMode(Global.ZEN_MODE_OFF, "downtimeExit");
            }
        }
    }

    private static class ConditionRecord {
        public final Uri id;
        public final ComponentName component;
        public Condition condition;
        public ManagedServiceInfo info;
        public boolean isAutomatic;
        public boolean isManual;

        private ConditionRecord(Uri id, ComponentName component) {
            this.id = id;
            this.component = component;
        }

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder("ConditionRecord[id=")
                    .append(id).append(",component=").append(component);
            if (isAutomatic) sb.append(",automatic");
            if (isManual) sb.append(",manual");
            return sb.append(']').toString();
        }
    }
}
