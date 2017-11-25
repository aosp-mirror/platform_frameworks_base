/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.statsd.loadtest;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.util.StatsLog;
import android.util.StatsManager;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import android.os.IStatsManager;
import android.os.ServiceManager;
import android.view.View.OnFocusChangeListener;

public abstract class NumericalWatcher implements TextWatcher {

  private static final String TAG = "NumericalWatcher";

    private final TextView mTextView;
    private final int mMin;
    private final int mMax;
    private int currentValue = -1;

    public NumericalWatcher(TextView textView, int min, int max) {
        mTextView = textView;
        mMin = min;
        mMax = max;
    }

    public abstract void onNewValue(int newValue);

    @Override
    final public void afterTextChanged(Editable editable) {
        String s = mTextView.getText().toString();
        if (s.isEmpty()) {
          return;
        }
        int unsanitized = Integer.parseInt(s);
        int newValue = sanitize(unsanitized);

        Log.d(TAG, "YOYO " + currentValue + " " + newValue + " " + unsanitized);

        if (currentValue != newValue || unsanitized != newValue) {
            currentValue = newValue;
            editable.clear();
            editable.append(newValue + "");
        }
        onNewValue(newValue);
    }

    @Override
    final public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

    @Override
    final public void onTextChanged(CharSequence s, int start, int before, int count) {}

    private int sanitize(int val) {
        if (val > mMax) {
            val = mMax;
        } else if (val < mMin) {
            val = mMin;
        }
        return val;
    }
}
