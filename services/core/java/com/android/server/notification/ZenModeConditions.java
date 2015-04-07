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

    private CountdownConditionProvider mCountdown;
    private ScheduleConditionProvider mSchedule;

    public ZenModeConditions(ZenModeHelper helper, ConditionProviders conditionProviders) {
        mHelper = helper;
        mConditionProviders = conditionProviders;
        if (mConditionProviders.isSystemProviderEnabled(ZenModeConfig.COUNTDOWN_PATH)) {
            mCountdown = new CountdownConditionProvider();
            mConditionProviders.addSystemProvider(mCountdown);
        }
        if (mConditionProviders.isSystemProviderEnabled(ZenModeConfig.SCHEDULE_PATH)) {
            mSchedule = new ScheduleConditionProvider();
            mConditionProviders.addSystemProvider(mSchedule);
        }
        mConditionProviders.setCallback(this);
    }

    public void dump(PrintWriter pw, String prefix) {
        pw.print(prefix); pw.print("mSubscriptions="); pw.println(mSubscriptions);
    }

    public void requestConditions(IConditionListener callback, int relevance) {
        mConditionProviders.requestConditions(callback, relevance);
    }

    public void evaluateConfig(ZenModeConfig config) {
        if (config == null) return;
        if (config.manualRule != null && !config.manualRule.isTrueOrUnknown()) {
            if (DEBUG) Log.d(TAG, "evaluateConfig: clearing manual rule");
            config.manualRule = null;
        }
        final ArraySet<Uri> current = new ArraySet<>();
        evaluateRule(config.manualRule, current);
        for (ZenRule automaticRule : config.automaticRules.values()) {
            evaluateRule(automaticRule, current);
        }
        final int N = mSubscriptions.size();
        for (int i = N - 1; i >= 0; i--) {
            final Uri id = mSubscriptions.keyAt(i);
            final ComponentName component = mSubscriptions.valueAt(i);
            if (!current.contains(id)) {
                mConditionProviders.unsubscribeIfNecessary(component, id);
                mSubscriptions.removeAt(i);
            }
        }
    }

    private void evaluateRule(ZenRule rule, ArraySet<Uri> current) {
        if (rule == null || rule.conditionId == null) return;
        final Uri id = rule.conditionId;
        for (SystemConditionProviderService sp : mConditionProviders.getSystemProviders()) {
            if (sp.isValidConditionid(id)) {
                mConditionProviders.ensureRecordExists(sp.getComponent(), id, sp.asInterface());
                rule.component = sp.getComponent();
            }
        }
        current.add(id);
        if (mConditionProviders.subscribeIfNecessary(rule.component, rule.conditionId)) {
            mSubscriptions.put(rule.conditionId, rule.component);
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
            mHelper.setConfig(config, "conditionChanged");
        }
    }

    private boolean updateSnoozing(ZenRule rule) {
        if (rule != null && rule.snoozing && !rule.isTrueOrUnknown()) {
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
