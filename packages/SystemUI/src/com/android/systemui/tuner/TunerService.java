/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.tuner;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.android.systemui.Dependency;

/**
 * @deprecated Don't use this class to listen to Secure Settings. Use {@code SecureSettings} instead
 * or {@code SettingsObserver} to be able to specify the handler.
 */
@Deprecated
public abstract class TunerService {

    public static final String ACTION_CLEAR = "com.android.systemui.action.CLEAR_TUNER";
    private final Context mContext;

    public abstract void clearAll();
    public abstract void destroy();

    public abstract String getValue(String setting);
    public abstract int getValue(String setting, int def);
    public abstract String getValue(String setting, String def);

    public abstract void setValue(String setting, String value);
    public abstract void setValue(String setting, int value);

    public abstract void addTunable(Tunable tunable, String... keys);
    public abstract void removeTunable(Tunable tunable);

    /**
     * Sets the state of the {@link TunerActivity} component for the current user
     */
    public abstract void setTunerEnabled(boolean enabled);

    /**
     * Returns true if the tuner is enabled for the current user.
     */
    public abstract boolean isTunerEnabled();

    public interface Tunable {
        void onTuningChanged(String key, String newValue);
    }

    public TunerService(Context context) {
        mContext = context;
    }

    public static class ClearReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_CLEAR.equals(intent.getAction())) {
                Dependency.get(TunerService.class).clearAll();
            }
        }
    }

    /** */
    public abstract void showResetRequest(Runnable onDisabled);

    public static boolean parseIntegerSwitch(String value, boolean defaultValue) {
        try {
            return value != null ? Integer.parseInt(value) != 0 : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
