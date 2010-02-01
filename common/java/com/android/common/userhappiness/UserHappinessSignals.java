/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.android.common.userhappiness;

import android.content.Intent;
import android.content.Context;
import com.android.common.speech.LoggingEvents;

/**
 * Metrics for User Happiness are recorded here. Each app can define when to
 * call these User Happiness metrics.
 */
public class UserHappinessSignals {

    /**
     *  Log when a user "accepted" IME text. Each application can define what
     *  it means to "accept" text. In the case of Gmail, pressing the "Send"
     *  button indicates text acceptance. We broadcast this information to
     *  VoiceSearch LoggingEvents and use it to aggregate VoiceIME Happiness Metrics
     */
    public static void userAcceptedImeText(Context context) {
        // Create a Voice IME Logging intent.
        Intent i = new Intent(LoggingEvents.ACTION_LOG_EVENT);
        i.putExtra(LoggingEvents.EXTRA_APP_NAME, LoggingEvents.VoiceIme.APP_NAME);
        i.putExtra(LoggingEvents.EXTRA_EVENT, LoggingEvents.VoiceIme.IME_TEXT_ACCEPTED);
        i.putExtra(LoggingEvents.EXTRA_CALLING_APP_NAME, context.getPackageName());
        i.putExtra(LoggingEvents.EXTRA_TIMESTAMP, System.currentTimeMillis());
        context.sendBroadcast(i);
    }

}
