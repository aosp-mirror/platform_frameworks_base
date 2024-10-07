/**
 * Copyright (c) 2015, The Android Open Source Project
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
import android.net.Uri;
import android.os.Binder;
import android.os.Process;
import android.service.notification.Condition;
import android.service.notification.IConditionProvider;
import android.service.notification.ZenModeConfig;
import android.service.notification.ZenModeConfig.ZenRule;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;

/**
 * Helper class for managing active rules from
 * {@link android.service.notification.ConditionProviderService CPSes}.
 */
public class ZenModeConditions implements ConditionProviders.Callback {
    private static final String TAG = ZenModeHelper.TAG;
    private static final boolean DEBUG = ZenModeHelper.DEBUG;

    private final ZenModeHelper mHelper;
    private final ConditionProviders mConditionProviders;

    @VisibleForTesting
    protected final ArrayMap<Uri, ComponentName> mSubscriptions = new ArrayMap<>();

    public ZenModeConditions(ZenModeHelper helper, ConditionProviders conditionProviders) {
        mHelper = helper;
        mConditionProviders = conditionProviders;
        if (mConditionProviders.isSystemProviderEnabled(ZenModeConfig.COUNTDOWN_PATH)) {
            mConditionProviders.addSystemProvider(new CountdownConditionProvider());
        }
        if (mConditionProviders.isSystemProviderEnabled(ZenModeConfig.SCHEDULE_PATH)) {
            mConditionProviders.addSystemProvider(new ScheduleConditionProvider());
        }
        if (mConditionProviders.isSystemProviderEnabled(ZenModeConfig.EVENT_PATH)) {
            mConditionProviders.addSystemProvider(new EventConditionProvider());
        }
        if (mConditionProviders.isSystemProviderEnabled(ZenModeConfig.CUSTOM_MANUAL_PATH)) {
            mConditionProviders.addSystemProvider(new CustomManualConditionProvider());
        }
        mConditionProviders.setCallback(this);
    }

    public void dump(PrintWriter pw, String prefix) {
        pw.print(prefix); pw.print("mSubscriptions="); pw.println(mSubscriptions);
    }

    public void evaluateConfig(ZenModeConfig config, ComponentName trigger,
            boolean processSubscriptions) {
        if (config == null) return;
        if (!android.app.Flags.modesUi() && config.manualRule != null
                && config.manualRule.condition != null
                && !config.manualRule.isTrueOrUnknown()) {
            if (DEBUG) Log.d(TAG, "evaluateConfig: clearing manual rule");
            config.manualRule = null;
        }
        final ArraySet<Uri> current = new ArraySet<>();
        evaluateRule(config.manualRule, current, null, processSubscriptions, true);
        for (ZenRule automaticRule : config.automaticRules.values()) {
            if (automaticRule.component != null) {
                evaluateRule(automaticRule, current, trigger, processSubscriptions, false);
                automaticRule.reconsiderConditionOverride();
            }
        }

        synchronized (mSubscriptions) {
            final int N = mSubscriptions.size();
            for (int i = N - 1; i >= 0; i--) {
                final Uri id = mSubscriptions.keyAt(i);
                final ComponentName component = mSubscriptions.valueAt(i);
                if (processSubscriptions) {
                    if (!current.contains(id)) {
                        mConditionProviders.unsubscribeIfNecessary(component, id);
                        mSubscriptions.removeAt(i);
                    }
                }
            }
        }
    }

    @Override
    public void onBootComplete() {
        // noop
    }

    @Override
    public void onUserSwitched() {
        // noop
    }

    @Override
    public void onServiceAdded(ComponentName component) {
        if (DEBUG) Log.d(TAG, "onServiceAdded " + component);
        final int callingUid = Binder.getCallingUid();
        mHelper.setConfig(mHelper.getConfig(), component,
                callingUid == Process.SYSTEM_UID ? ZenModeConfig.ORIGIN_SYSTEM
                        : ZenModeConfig.ORIGIN_APP,
                "zmc.onServiceAdded:" + component, callingUid);
    }

    @Override
    public void onConditionChanged(Uri id, Condition condition) {
        if (DEBUG) Log.d(TAG, "onConditionChanged " + id + " " + condition);
        ZenModeConfig config = mHelper.getConfig();
        if (config == null) return;
        final int callingUid = Binder.getCallingUid();
        mHelper.setAutomaticZenRuleState(id, condition,
                callingUid == Process.SYSTEM_UID ? ZenModeConfig.ORIGIN_SYSTEM
                        : ZenModeConfig.ORIGIN_APP,
                callingUid);
    }

    // Only valid for CPS backed rules
    private void evaluateRule(ZenRule rule, ArraySet<Uri> current, ComponentName trigger,
            boolean processSubscriptions, boolean isManual) {
        if (rule == null || rule.conditionId == null) return;
        if (rule.configurationActivity != null) return;
        final Uri id = rule.conditionId;
        boolean isSystemCondition = false;
        for (SystemConditionProviderService sp : mConditionProviders.getSystemProviders()) {
            if (sp.isValidConditionId(id)) {
                mConditionProviders.ensureRecordExists(sp.getComponent(), id, sp.asInterface());
                rule.component = sp.getComponent();
                isSystemCondition = true;
            }
        }
        // ensure that we have a record of the rule if it's backed by an currently alive CPS
        if (!isSystemCondition) {
            final IConditionProvider cp = mConditionProviders.findConditionProvider(rule.component);
            if (DEBUG) Log.d(TAG, "Ensure external rule exists: " + (cp != null) + " for " + id);
            if (cp != null) {
                mConditionProviders.ensureRecordExists(rule.component, id, cp);
            }
        }
        // empty rule? disable and bail early
        if (rule.component == null && rule.enabler == null) {
            if (!isManual) {
                Log.w(TAG, "No component found for automatic rule: " + rule.conditionId);
                rule.enabled = false;
            }
            return;
        }
        if (current != null) {
            current.add(id);
        }

        // If the rule is bound by a CPS and the CPS is alive, tell them about the rule
        if (processSubscriptions && ((trigger != null && trigger.equals(rule.component))
                || isSystemCondition)) {
            if (DEBUG) Log.d(TAG, "Subscribing to " + rule.component);
            if (mConditionProviders.subscribeIfNecessary(rule.component, rule.conditionId)) {
                synchronized (mSubscriptions) {
                    mSubscriptions.put(rule.conditionId, rule.component);
                }
            } else {
                rule.condition = null;
                if (DEBUG) Log.d(TAG, "zmc failed to subscribe");
            }
        }
        // backfill the rule state from CPS backed components if it's missing
        if (rule.component != null && rule.condition == null) {
            rule.condition = mConditionProviders.findCondition(rule.component, rule.conditionId);
            if (rule.condition != null && DEBUG) Log.d(TAG, "Found existing condition for: "
                    + rule.conditionId);
        }
    }
}
