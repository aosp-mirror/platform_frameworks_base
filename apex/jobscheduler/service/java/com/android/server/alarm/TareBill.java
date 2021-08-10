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

package com.android.server.alarm;

import static android.app.AlarmManager.FLAG_ALLOW_WHILE_IDLE;
import static android.app.AlarmManager.FLAG_ALLOW_WHILE_IDLE_UNRESTRICTED;

import static com.android.server.tare.AlarmManagerEconomicPolicy.ACTION_ALARM_CLOCK;
import static com.android.server.tare.AlarmManagerEconomicPolicy.ACTION_ALARM_NONWAKEUP_EXACT;
import static com.android.server.tare.AlarmManagerEconomicPolicy.ACTION_ALARM_NONWAKEUP_EXACT_ALLOW_WHILE_IDLE;
import static com.android.server.tare.AlarmManagerEconomicPolicy.ACTION_ALARM_NONWAKEUP_INEXACT;
import static com.android.server.tare.AlarmManagerEconomicPolicy.ACTION_ALARM_NONWAKEUP_INEXACT_ALLOW_WHILE_IDLE;
import static com.android.server.tare.AlarmManagerEconomicPolicy.ACTION_ALARM_WAKEUP_EXACT;
import static com.android.server.tare.AlarmManagerEconomicPolicy.ACTION_ALARM_WAKEUP_EXACT_ALLOW_WHILE_IDLE;
import static com.android.server.tare.AlarmManagerEconomicPolicy.ACTION_ALARM_WAKEUP_INEXACT;
import static com.android.server.tare.AlarmManagerEconomicPolicy.ACTION_ALARM_WAKEUP_INEXACT_ALLOW_WHILE_IDLE;

import android.annotation.NonNull;

import com.android.server.tare.EconomyManagerInternal;
import com.android.server.tare.EconomyManagerInternal.ActionBill;

import java.util.List;

/**
 * Container to maintain alarm TARE {@link ActionBill}s and their related methods.
 */
final class TareBill {
    /**
     * Bill to use for AlarmClocks.
     */
    static final ActionBill ALARM_CLOCK = new ActionBill(List.of(
            new EconomyManagerInternal.AnticipatedAction(ACTION_ALARM_CLOCK, 1, 0)));
    /**
     * Bills to use for various alarm types.
     */
    static final ActionBill NONWAKEUP_INEXACT_ALARM = new ActionBill(List.of(
            new EconomyManagerInternal.AnticipatedAction(ACTION_ALARM_NONWAKEUP_INEXACT, 1, 0)));
    static final ActionBill NONWAKEUP_INEXACT_ALLOW_WHILE_IDLE_ALARM = new ActionBill(List.of(
            new EconomyManagerInternal.AnticipatedAction(
                    ACTION_ALARM_NONWAKEUP_INEXACT_ALLOW_WHILE_IDLE, 1, 0)));
    static final ActionBill NONWAKEUP_EXACT_ALARM = new ActionBill(List.of(
            new EconomyManagerInternal.AnticipatedAction(ACTION_ALARM_NONWAKEUP_EXACT, 1, 0)));
    static final ActionBill NONWAKEUP_EXACT_ALLOW_WHILE_IDLE_ALARM = new ActionBill(List.of(
            new EconomyManagerInternal.AnticipatedAction(
                    ACTION_ALARM_NONWAKEUP_EXACT_ALLOW_WHILE_IDLE, 1, 0)));
    static final ActionBill WAKEUP_INEXACT_ALARM = new ActionBill(List.of(
            new EconomyManagerInternal.AnticipatedAction(ACTION_ALARM_WAKEUP_INEXACT, 1, 0)));
    static final ActionBill WAKEUP_INEXACT_ALLOW_WHILE_IDLE_ALARM = new ActionBill(List.of(
            new EconomyManagerInternal.AnticipatedAction(
                    ACTION_ALARM_WAKEUP_INEXACT_ALLOW_WHILE_IDLE, 1, 0)));
    static final ActionBill WAKEUP_EXACT_ALARM = new ActionBill(List.of(
            new EconomyManagerInternal.AnticipatedAction(ACTION_ALARM_WAKEUP_EXACT, 1, 0)));
    static final ActionBill WAKEUP_EXACT_ALLOW_WHILE_IDLE_ALARM = new ActionBill(List.of(
            new EconomyManagerInternal.AnticipatedAction(
                    ACTION_ALARM_WAKEUP_EXACT_ALLOW_WHILE_IDLE, 1, 0)));

    @NonNull
    static ActionBill getAppropriateBill(@NonNull Alarm alarm) {
        if (alarm.alarmClock != null) {
            return ALARM_CLOCK;
        }

        final boolean allowWhileIdle =
                (alarm.flags & (FLAG_ALLOW_WHILE_IDLE_UNRESTRICTED | FLAG_ALLOW_WHILE_IDLE)) != 0;
        final boolean isExact = alarm.windowLength == 0;

        if (alarm.wakeup) {
            if (isExact) {
                if (allowWhileIdle) {
                    return WAKEUP_EXACT_ALLOW_WHILE_IDLE_ALARM;
                }
                return WAKEUP_EXACT_ALARM;
            }
            // Inexact
            if (allowWhileIdle) {
                return WAKEUP_INEXACT_ALLOW_WHILE_IDLE_ALARM;
            }
            return WAKEUP_INEXACT_ALARM;
        }

        // Nonwakeup
        if (isExact) {
            if (allowWhileIdle) {
                return NONWAKEUP_EXACT_ALLOW_WHILE_IDLE_ALARM;
            }
            return NONWAKEUP_EXACT_ALARM;

        }
        if (allowWhileIdle) {
            return NONWAKEUP_INEXACT_ALLOW_WHILE_IDLE_ALARM;
        }
        return NONWAKEUP_INEXACT_ALARM;
    }

    @NonNull
    static String getName(@NonNull ActionBill bill) {
        if (bill.equals(ALARM_CLOCK)) {
            return "ALARM_CLOCK_BILL";
        }
        if (bill.equals(NONWAKEUP_INEXACT_ALARM)) {
            return "NONWAKEUP_INEXACT_ALARM_BILL";
        }
        if (bill.equals(NONWAKEUP_INEXACT_ALLOW_WHILE_IDLE_ALARM)) {
            return "NONWAKEUP_INEXACT_ALLOW_WHILE_IDLE_ALARM_BILL";
        }
        if (bill.equals(NONWAKEUP_EXACT_ALARM)) {
            return "NONWAKEUP_EXACT_ALARM_BILL";
        }
        if (bill.equals(NONWAKEUP_EXACT_ALLOW_WHILE_IDLE_ALARM)) {
            return "NONWAKEUP_EXACT_ALLOW_WHILE_IDLE_ALARM_BILL";
        }
        if (bill.equals(WAKEUP_INEXACT_ALARM)) {
            return "WAKEUP_INEXACT_ALARM_BILL";
        }
        if (bill.equals(WAKEUP_INEXACT_ALLOW_WHILE_IDLE_ALARM)) {
            return "WAKEUP_INEXACT_ALLOW_WHILE_IDLE_ALARM_BILL";
        }
        if (bill.equals(WAKEUP_EXACT_ALARM)) {
            return "WAKEUP_EXACT_ALARM_BILL";
        }
        if (bill.equals(WAKEUP_EXACT_ALLOW_WHILE_IDLE_ALARM)) {
            return "WAKEUP_EXACT_ALLOW_WHILE_IDLE_ALARM_BILL";
        }
        return "UNKNOWN_BILL (" + bill.toString() + ")";
    }

    private TareBill() {
    }
}
