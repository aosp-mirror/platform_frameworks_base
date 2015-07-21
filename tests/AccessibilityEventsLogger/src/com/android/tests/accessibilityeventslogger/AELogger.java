/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.android.tests.accessibilityeventslogger;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

import java.util.Locale;

public class AELogger extends AccessibilityService {
    private static final String TAG = AELogger.class.getCanonicalName();

    private static final int TOAST_EVENT_TYPES =
            AccessibilityEvent.TYPE_VIEW_CLICKED | AccessibilityEvent.TYPE_VIEW_LONG_CLICKED;

    @Override
    public void onServiceConnected() {
      super.onServiceConnected();
      Log.v(TAG, "Service connected.");
    }


    @Override
    public void onInterrupt() {
        // Do nothing
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        final String eventClass = event.getClassName().toString();
        final String eventText = String.valueOf(event.getText()).toLowerCase(Locale.getDefault());
        final String eventType = AccessibilityEvent.eventTypeToString(event.getEventType());

        Log.d(TAG, String.format(
                    "typ=%s cls=%s pkg=%s txt=%s dsc=%s",
                    eventType,
                    eventClass,
                    event.getPackageName(),
                    eventText,
                    event.getContentDescription()
                    ));

        // Show selected event types
        if (0 != (TOAST_EVENT_TYPES & event.getEventType())) {
            final Toast toast = Toast.makeText(this,
                    eventType + ": " + eventClass, Toast.LENGTH_SHORT);
            toast.show();
        }
    }
}
