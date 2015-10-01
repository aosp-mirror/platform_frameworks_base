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
import android.service.notification.Condition;
import android.service.notification.IConditionListener;
import android.service.notification.IConditionProvider;
import android.service.notification.ZenModeConfig;
import android.service.notification.ZenModeConfig.ZenRule;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;

import java.io.PrintWriter;
import java.util.Objects;

public class ZenModeConditions implements ConditionProviders.Callback {
    private static final String TAG = ZenModeHelper.TAG;
    private static final boolean DEBUG = ZenModeHelper.DEBUG;

    private final ZenModeHelper mHelper;
    private final ConditionProviders mConditionProviders;
    private final ArrayMap<Uri, ComponentName> mSubscriptions = new ArrayMap<>();

    private boolean mFirstEvaluation = true;

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
            mConditionProviders.addSystemProvider(new EventConditionProvider(helper.getLooper()));
        }
        mConditionProviders.setCallback(this);
    }

    public void dump(PrintWriter pw, String prefix) {
        pw.print(prefix); pw.print("mSubscriptions="); pw.println(mSubscriptions);
    }

    public void requestConditions(IConditionListener callback, int relevance) {
        mConditionProviders.requestConditions(callback, relevance);
    }

    public void evaluateConfig(ZenModeConfig config, boolean processSubscriptions) {
        if (config == null) return;
        if (config.manualRule != null && config.manualRule.condition != null
                && !config.manualRule.isTrueOrUnknown()) {
            if (DEBUG) Log.d(TAG, "evaluateConfig: clearing manual rule");
            config.manualRule = null;
        }
        final ArraySet<Uri> current = new ArraySet<>();
        evaluateRule(config.manualRule, current, processSubscriptions);
        for (ZenRule automaticRule : config.automaticRules.values()) {
            evaluateRule(automaticRule, current, processSubscriptions);
            updateSnoozing(automaticRule);
        }
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
        mFirstEvaluation = false;
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
        if (isAutomaticActive(component)) {
            mHelper.setConfigAsync(mHelper.getConfig(), "zmc.onServiceAdded");
        }
    }

    @Override
    public void onConditionChanged(Uri id, Condition condition) {
        if (DEBUG) Log.d(TAG, "onConditionChanged " + id + " " + condition);
        ZenModeConfig config = mHelper.getConfig();
        if (config == null) return;
        config = config.copy();
        boolean updated = updateCondition(id, condition, config.manualRule);
        for (ZenRule automaticRule : config.automaticRules.values()) {
            updated |= updateCondition(id, condition, automaticRule);
            updated |= updateSnoozing(automaticRule);
        }
        if (updated) {
            mHelper.setConfigAsync(config, "conditionChanged");
        }
    }

    private void evaluateRule(ZenRule rule, ArraySet<Uri> current, boolean processSubscriptions) {
        if (rule == null || rule.conditionId == null) return;
        final Uri id = rule.conditionId;
        boolean isSystemCondition = false;
        for (SystemConditionProviderService sp : mConditionProviders.getSystemProviders()) {
            if (sp.isValidConditionId(id)) {
                mConditionProviders.ensureRecordExists(sp.getComponent(), id, sp.asInterface());
                rule.component = sp.getComponent();
                isSystemCondition = true;
            }
        }
        if (!isSystemCondition) {
            final IConditionProvider cp = mConditionProviders.findConditionProvider(rule.component);
            if (DEBUG) Log.d(TAG, "Ensure external rule exists: " + (cp != null) + " for " + id);
            if (cp != null) {
                mConditionProviders.ensureRecordExists(rule.component, id, cp);
            }
        }
        if (rule.component == null) {
            Log.w(TAG, "No component found for automatic rule: " + rule.conditionId);
            rule.enabled = false;
            return;
        }
        if (current != null) {
            current.add(id);
        }
        if (processSubscriptions) {
            if (mConditionProviders.subscribeIfNecessary(rule.component, rule.conditionId)) {
                mSubscriptions.put(rule.conditionId, rule.component);
            } else {
                if (DEBUG) Log.d(TAG, "zmc failed to subscribe");
            }
        }
        if (rule.condition == null) {
            rule.condition = mConditionProviders.findCondition(rule.component, rule.conditionId);
            if (rule.condition != null && DEBUG) Log.d(TAG, "Found existing condition for: "
                    + rule.conditionId);
        }
    }

    private boolean isAutomaticActive(ComponentName component) {
        if (component == null) return false;
        final ZenModeConfig config = mHelper.getConfig();
        if (config == null) return false;
        for (ZenRule rule : config.automaticRules.values()) {
            if (component.equals(rule.component) && rule.isAutomaticActive()) {
                return true;
            }
        }
        return false;
    }

    private boolean updateSnoozing(ZenRule rule) {
        if (rule != null && rule.snoozing && (mFirstEvaluation || !rule.isTrueOrUnknown())) {
            rule.snoozing = false;
            if (DEBUG) Log.d(TAG, "Snoozing reset for " + rule.conditionId);
            return true;
        }
        return false;
    }

    private boolean updateCondition(Uri id, Condition condition, ZenRule rule) {
        if (id == null || rule == null || rule.conditionId == null) return false;
        if (!rule.conditionId.equals(id)) return false;
        if (Objects.equals(condition, rule.condition)) return false;
        rule.condition = condition;
        return true;
    }

}
