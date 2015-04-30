/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.server.notification;

import android.content.ComponentName;
import android.content.Context;
import android.net.Uri;
import android.service.notification.Condition;
import android.service.notification.IConditionProvider;
import android.service.notification.ZenModeConfig;
import android.util.ArraySet;
import android.util.Log;
import android.util.Slog;

import com.android.server.notification.NotificationManagerService.DumpFilter;

import java.io.PrintWriter;

/**
 * Built-in zen condition provider for calendar event-based conditions.
 */
public class EventConditionProvider extends SystemConditionProviderService {
    private static final String TAG = "ConditionProviders";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    public static final ComponentName COMPONENT =
            new ComponentName("android", EventConditionProvider.class.getName());
    private static final String NOT_SHOWN = "...";

    private final ArraySet<Uri> mSubscriptions = new ArraySet<Uri>();

    private boolean mConnected;
    private boolean mRegistered;

    public EventConditionProvider() {
        if (DEBUG) Slog.d(TAG, "new EventConditionProvider()");
    }

    @Override
    public ComponentName getComponent() {
        return COMPONENT;
    }

    @Override
    public boolean isValidConditionId(Uri id) {
        return ZenModeConfig.isValidEventConditionId(id);
    }

    @Override
    public void dump(PrintWriter pw, DumpFilter filter) {
        pw.println("    EventConditionProvider:");
        pw.print("      mConnected="); pw.println(mConnected);
        pw.print("      mRegistered="); pw.println(mRegistered);
        pw.println("      mSubscriptions=");
        for (Uri conditionId : mSubscriptions) {
            pw.print("        ");
            pw.println(conditionId);
        }
    }

    @Override
    public void onConnected() {
        if (DEBUG) Slog.d(TAG, "onConnected");
        mConnected = true;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (DEBUG) Slog.d(TAG, "onDestroy");
        mConnected = false;
    }

    @Override
    public void onRequestConditions(int relevance) {
        if (DEBUG) Slog.d(TAG, "onRequestConditions relevance=" + relevance);
        // does not advertise conditions
    }

    @Override
    public void onSubscribe(Uri conditionId) {
        if (DEBUG) Slog.d(TAG, "onSubscribe " + conditionId);
        if (!ZenModeConfig.isValidEventConditionId(conditionId)) {
            notifyCondition(conditionId, Condition.STATE_FALSE, "badCondition");
            return;
        }
        mSubscriptions.add(conditionId);
        evaluateSubscriptions();
    }

    @Override
    public void onUnsubscribe(Uri conditionId) {
        if (DEBUG) Slog.d(TAG, "onUnsubscribe " + conditionId);
        if (mSubscriptions.remove(conditionId)) {
            evaluateSubscriptions();
        }
    }

    @Override
    public void attachBase(Context base) {
        attachBaseContext(base);
    }

    @Override
    public IConditionProvider asInterface() {
        return (IConditionProvider) onBind(null);
    }

    private void evaluateSubscriptions() {
        for (Uri conditionId : mSubscriptions) {
            notifyCondition(conditionId, Condition.STATE_FALSE, "notImplemented");
        }
    }

    private void notifyCondition(Uri conditionId, int state, String reason) {
        if (DEBUG) Slog.d(TAG, "notifyCondition " + Condition.stateToString(state)
                + " reason=" + reason);
        notifyCondition(createCondition(conditionId, state));
    }

    private Condition createCondition(Uri id, int state) {
        final String summary = NOT_SHOWN;
        final String line1 = NOT_SHOWN;
        final String line2 = NOT_SHOWN;
        return new Condition(id, summary, line1, line2, 0, state, Condition.FLAG_RELEVANT_ALWAYS);
    }

}
