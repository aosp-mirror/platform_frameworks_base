/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.systemui.dreams.conditions;

import android.app.DreamManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.text.TextUtils;

import com.android.systemui.shared.condition.Condition;

import javax.inject.Inject;

/**
 * {@link DreamCondition} provides a signal when a dream begins and ends.
 */
public class DreamCondition extends Condition {
    private final Context mContext;
    private final DreamManager mDreamManager;

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            processIntent(intent);
        }
    };

    @Inject
    public DreamCondition(Context context,
            DreamManager dreamManager) {
        mContext = context;
        mDreamManager = dreamManager;
    }

    private void processIntent(Intent intent) {
        // In the case of a non-existent sticky broadcast, ignore when there is no intent.
        if (intent == null) {
            return;
        }
        if (TextUtils.equals(intent.getAction(), Intent.ACTION_DREAMING_STARTED)) {
            updateCondition(true);
        } else if (TextUtils.equals(intent.getAction(), Intent.ACTION_DREAMING_STOPPED)) {
            updateCondition(false);
        } else {
            throw new IllegalStateException("unexpected intent:" + intent);
        }
    }

    @Override
    protected void start() {
        final IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_DREAMING_STARTED);
        filter.addAction(Intent.ACTION_DREAMING_STOPPED);
        mContext.registerReceiver(mReceiver, filter);
        updateCondition(mDreamManager.isDreaming());
    }

    @Override
    protected void stop() {
        mContext.unregisterReceiver(mReceiver);
    }
}
