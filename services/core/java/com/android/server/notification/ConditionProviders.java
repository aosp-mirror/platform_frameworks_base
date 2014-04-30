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

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;
import android.provider.Settings;
import android.provider.Settings.Global;
import android.service.notification.Condition;
import android.service.notification.ConditionProviderService;
import android.service.notification.IConditionListener;
import android.service.notification.IConditionProvider;
import android.util.ArrayMap;
import android.util.Slog;

import com.android.internal.R;

import libcore.util.Objects;

import java.io.PrintWriter;
import java.util.Arrays;

public class ConditionProviders extends ManagedServices {

    private final ZenModeHelper mZenModeHelper;
    private final ArrayMap<IBinder, IConditionListener> mListeners
            = new ArrayMap<IBinder, IConditionListener>();
    private final ArrayMap<Uri, ManagedServiceInfo> mConditions
            = new ArrayMap<Uri, ManagedServiceInfo>();

    private Uri mCurrentConditionId;

    public ConditionProviders(Context context, Handler handler,
            UserProfiles userProfiles, ZenModeHelper zenModeHelper) {
        super(context, handler, new Object(), userProfiles);
        mZenModeHelper = zenModeHelper;
        mZenModeHelper.addCallback(new ZenModeHelperCallback());
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
    public void dump(PrintWriter pw) {
        super.dump(pw);
        synchronized(mMutex) {
            pw.print("    mCurrentConditionId="); pw.println(mCurrentConditionId);
            pw.print("    mListeners("); pw.print(mListeners.size()); pw.println("):");
            for (int i = 0; i < mListeners.size(); i++) {
                pw.print("      "); pw.println(mListeners.keyAt(i));
            }
            pw.print("    mConditions("); pw.print(mConditions.size()); pw.println("):");
            for (int i = 0; i < mConditions.size(); i++) {
                pw.print("      "); pw.print(mConditions.keyAt(i));
                final ManagedServiceInfo info = mConditions.valueAt(i);
                pw.print(" -> "); pw.print(info.component);
                if (!mServices.contains(info)) {
                    pw.print(" (orphan)");
                }
                pw.println();
            }
        }
    }

    @Override
    protected IInterface asInterface(IBinder binder) {
        return IConditionProvider.Stub.asInterface(binder);
    }

    @Override
    protected void onServiceAdded(IInterface service) {
        Slog.d(TAG, "onServiceAdded " + service);
        final IConditionProvider provider = (IConditionProvider) service;
        try {
            provider.onConnected();
        } catch (RemoteException e) {
            // we tried
        }
    }

    @Override
    protected void onServiceRemovedLocked(ManagedServiceInfo removed) {
        if (removed == null) return;
        if (mCurrentConditionId != null) {
            if (removed.equals(mConditions.get(mCurrentConditionId))) {
                mCurrentConditionId = null;
                mZenModeHelper.setZenMode(Global.ZEN_MODE_OFF);
            }
        }
        for (int i = mConditions.size() - 1; i >= 0; i--) {
            if (removed.equals(mConditions.valueAt(i))) {
                mConditions.removeAt(i);
            }
        }
    }

    public ManagedServiceInfo checkServiceToken(IConditionProvider provider) {
        synchronized(mMutex) {
            return checkServiceTokenLocked(provider);
        }
    }

    public void requestZenModeConditions(IConditionListener callback, boolean requested) {
        synchronized(mMutex) {
            if (DEBUG) Slog.d(TAG, "requestZenModeConditions callback=" + callback
                    + " requested=" + requested);
            if (callback == null) return;
            if (requested) {
                mListeners.put(callback.asBinder(), callback);
                requestConditionsLocked(Condition.FLAG_RELEVANT_NOW);
            } else {
                mListeners.remove(callback.asBinder());
                if (mListeners.isEmpty()) {
                    requestConditionsLocked(0);
                }
            }
        }
    }

    public void notifyConditions(String pkg, ManagedServiceInfo info, Condition[] conditions) {
        synchronized(mMutex) {
            if (DEBUG) Slog.d(TAG, "notifyConditions pkg=" + pkg + " info=" + info + " conditions="
                    + (conditions == null ? null : Arrays.asList(conditions)));
            if (conditions == null || conditions.length == 0) return;
            final int N = conditions.length;
            boolean valid = true;
            for (int i = 0; i < N; i++) {
                final Uri id = conditions[i].id;
                if (!Condition.isValidId(id, pkg)) {
                    Slog.w(TAG, "Ignoring conditions from " + pkg + " for invalid id: " + id);
                    valid = false;
                }
            }
            if (!valid) return;

            for (int i = 0; i < N; i++) {
                mConditions.put(conditions[i].id, info);
            }
            for (IConditionListener listener : mListeners.values()) {
                try {
                    listener.onConditionsReceived(conditions);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Error sending conditions to listener " + listener, e);
                }
            }
            if (mCurrentConditionId != null) {
                for (int i = 0; i < N; i++) {
                    final Condition c = conditions[i];
                    if (!c.id.equals(mCurrentConditionId)) continue;
                    if (c.state == Condition.STATE_TRUE || c.state == Condition.STATE_ERROR) {
                        triggerExitLocked(c.state == Condition.STATE_ERROR);
                        return;
                    }
                }
            }
        }
    }

    private void triggerExitLocked(boolean error) {
        if (error) {
            Slog.w(TAG, "Zen mode exit condition failed");
        } else if (DEBUG) {
            Slog.d(TAG, "Zen mode exit condition triggered");
        }
        mZenModeHelper.setZenMode(Settings.Global.ZEN_MODE_OFF);
        unsubscribeLocked(mCurrentConditionId);
        mCurrentConditionId = null;
    }

    public void setZenModeCondition(Uri conditionId) {
        synchronized(mMutex) {
            if (DEBUG) Slog.d(TAG, "setZenModeCondition " + conditionId);
            if (Objects.equal(mCurrentConditionId, conditionId)) return;

            if (mCurrentConditionId != null) {
                unsubscribeLocked(mCurrentConditionId);
            }
            if (conditionId != null) {
                final ManagedServiceInfo info = mConditions.get(conditionId);
                final IConditionProvider provider = provider(info);
                if (provider == null) return;
                try {
                    provider.onSubscribe(conditionId);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Error subscribing to " + conditionId
                            + " from " + info.component, e);
                }
            }
            mCurrentConditionId = conditionId;
        }
    }

    private void unsubscribeLocked(Uri conditionId) {
        final ManagedServiceInfo info = mConditions.get(mCurrentConditionId);
        final IConditionProvider provider = provider(info);
        if (provider == null) return;
        try {
            provider.onUnsubscribe(conditionId);
        } catch (RemoteException e) {
            Slog.w(TAG, "Error unsubscribing to " + conditionId + " from " + info.component, e);
        }
    }

    private static IConditionProvider provider(ManagedServiceInfo info) {
        return info == null ? null : (IConditionProvider) info.service;
    }

    private void requestConditionsLocked(int flags) {
        for (ManagedServiceInfo info : mServices) {
            final IConditionProvider provider = provider(info);
            if (provider == null) continue;
            try {
                provider.onRequestConditions(flags);
            } catch (RemoteException e) {
                Slog.w(TAG, "Error requesting conditions from " + info.component, e);
            }
        }
    }

    private class ZenModeHelperCallback extends ZenModeHelper.Callback {
        @Override
        void onZenModeChanged() {
            final int mode = mZenModeHelper.getZenMode();
            if (mode == Global.ZEN_MODE_OFF) {
                synchronized (mMutex) {
                    if (mCurrentConditionId != null) {
                        if (DEBUG) Slog.d(TAG, "Zen mode off, forcing unsubscribe from "
                                + mCurrentConditionId);
                        unsubscribeLocked(mCurrentConditionId);
                        mCurrentConditionId = null;
                    }
                }
            }
        }
    }
}
