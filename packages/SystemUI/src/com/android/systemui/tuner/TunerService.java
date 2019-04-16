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

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.UserHandle;
import android.provider.Settings;

import static android.provider.Settings.System.SHOW_BATTERY_PERCENT;
import com.android.systemui.DemoMode;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.statusbar.phone.SystemUIDialog;

public abstract class TunerService {

    public static final String ACTION_CLEAR = "com.android.systemui.action.CLEAR_TUNER";

    public abstract void clearAll();
    public abstract void destroy();

    public abstract String getValue(String setting);
    public abstract int getValue(String setting, int def);
    public abstract String getValue(String setting, String def);

    public abstract void setValue(String setting, String value);
    public abstract void setValue(String setting, int value);

    public abstract void addTunable(Tunable tunable, String... keys);
    public abstract void removeTunable(Tunable tunable);

    public interface Tunable {
        void onTuningChanged(String key, String newValue);
    }

    private static Context userContext(Context context) {
        try {
            return context.createPackageContextAsUser(context.getPackageName(), 0,
                    new UserHandle(ActivityManager.getCurrentUser()));
        } catch (NameNotFoundException e) {
            return context;
        }
    }

    public static final void setTunerEnabled(Context context, boolean enabled) {
        userContext(context).getPackageManager().setComponentEnabledSetting(
                new ComponentName(context, TunerActivity.class),
                enabled ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
    }

    public static final boolean isTunerEnabled(Context context) {
        return userContext(context).getPackageManager().getComponentEnabledSetting(
                new ComponentName(context, TunerActivity.class))
                == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
    }

    public static class ClearReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_CLEAR.equals(intent.getAction())) {
                Dependency.get(TunerService.class).clearAll();
            }
        }
    }

    public static final void showResetRequest(final Context context, final Runnable onDisabled) {
        SystemUIDialog dialog = new SystemUIDialog(context);
        dialog.setShowForAllUsers(true);
        dialog.setMessage(R.string.remove_from_settings_prompt);
        dialog.setButton(DialogInterface.BUTTON_NEGATIVE, context.getString(R.string.cancel),
                (OnClickListener) null);
        dialog.setButton(DialogInterface.BUTTON_POSITIVE,
                context.getString(R.string.guest_exit_guest_dialog_remove), new OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                // Tell the tuner (in main SysUI process) to clear all its settings.
                context.sendBroadcast(new Intent(TunerService.ACTION_CLEAR));
                // Disable access to tuner.
                TunerService.setTunerEnabled(context, false);
                // Make them sit through the warning dialog again.
                Settings.Secure.putInt(context.getContentResolver(),
                        TunerFragment.SETTING_SEEN_TUNER_WARNING, 0);
                if (onDisabled != null) {
                    onDisabled.run();
                }
            }
        });
        dialog.show();
    }

    public static boolean parseIntegerSwitch(String value, boolean defaultValue) {
        try {
            return value != null ? Integer.parseInt(value) != 0 : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
