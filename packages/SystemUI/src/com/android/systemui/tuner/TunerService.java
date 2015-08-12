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
 * limitations under the License
 */
package com.android.systemui.tuner;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ArrayMap;

import com.android.systemui.BatteryMeterView;
import com.android.systemui.DemoMode;
import com.android.systemui.R;
import com.android.systemui.SystemUI;
import com.android.systemui.SystemUIApplication;
import com.android.systemui.settings.CurrentUserTracker;
import com.android.systemui.statusbar.phone.SystemUIDialog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;


public class TunerService extends SystemUI {

    public static final String ACTION_CLEAR = "com.android.systemui.action.CLEAR_TUNER";

    private final Observer mObserver = new Observer();
    // Map of Uris we listen on to their settings keys.
    private final ArrayMap<Uri, String> mListeningUris = new ArrayMap<>();
    // Map of settings keys to the listener.
    private final HashMap<String, List<Tunable>> mTunableLookup = new HashMap<>();

    private ContentResolver mContentResolver;
    private int mCurrentUser;
    private CurrentUserTracker mUserTracker;

    @Override
    public void start() {
        mContentResolver = mContext.getContentResolver();
        putComponent(TunerService.class, this);

        mCurrentUser = ActivityManager.getCurrentUser();
        mUserTracker = new CurrentUserTracker(mContext) {
            @Override
            public void onUserSwitched(int newUserId) {
                mCurrentUser = newUserId;
                reloadAll();
                reregisterAll();
            }
        };
        mUserTracker.startTracking();
    }

    public void addTunable(Tunable tunable, String... keys) {
        for (String key : keys) {
            addTunable(tunable, key);
        }
    }

    private void addTunable(Tunable tunable, String key) {
        if (!mTunableLookup.containsKey(key)) {
            mTunableLookup.put(key, new ArrayList<Tunable>());
        }
        mTunableLookup.get(key).add(tunable);
        Uri uri = Settings.Secure.getUriFor(key);
        if (!mListeningUris.containsKey(uri)) {
            mListeningUris.put(uri, key);
            mContentResolver.registerContentObserver(uri, false, mObserver, mCurrentUser);
        }
        // Send the first state.
        String value = Settings.Secure.getStringForUser(mContentResolver, key, mCurrentUser);
        tunable.onTuningChanged(key, value);
    }

    public void removeTunable(Tunable tunable) {
        for (List<Tunable> list : mTunableLookup.values()) {
            list.remove(tunable);
        }
    }

    protected void reregisterAll() {
        if (mListeningUris.size() == 0) {
            return;
        }
        mContentResolver.unregisterContentObserver(mObserver);
        for (Uri uri : mListeningUris.keySet()) {
            mContentResolver.registerContentObserver(uri, false, mObserver, mCurrentUser);
        }
    }

    public void reloadSetting(Uri uri) {
        String key = mListeningUris.get(uri);
        String value = Settings.Secure.getStringForUser(mContentResolver, key, mCurrentUser);
        for (Tunable tunable : mTunableLookup.get(key)) {
            tunable.onTuningChanged(key, value);
        }
    }

    private void reloadAll() {
        for (String key : mTunableLookup.keySet()) {
            String value = Settings.Secure.getStringForUser(mContentResolver, key,
                    mCurrentUser);
            for (Tunable tunable : mTunableLookup.get(key)) {
                tunable.onTuningChanged(key, value);
            }
        }
    }

    public void clearAll() {
        // A couple special cases.
        Settings.Global.putString(mContentResolver, DemoMode.DEMO_MODE_ALLOWED, null);
        Settings.System.putString(mContentResolver, BatteryMeterView.SHOW_PERCENT_SETTING, null);
        Intent intent = new Intent(DemoMode.ACTION_DEMO);
        intent.putExtra(DemoMode.EXTRA_COMMAND, DemoMode.COMMAND_EXIT);
        mContext.sendBroadcast(intent);

        for (String key : mTunableLookup.keySet()) {
            Settings.Secure.putString(mContentResolver, key, null);
        }
    }

    // Only used in other processes, such as the tuner.
    private static TunerService sInstance;

    public static TunerService get(Context context) {
        SystemUIApplication sysUi = (SystemUIApplication) context.getApplicationContext();
        TunerService service = sysUi.getComponent(TunerService.class);
        if (service == null) {
            // Can't get it as a component, must in the tuner, lets just create one for now.
            return getStaticService(context);
        }
        return service;
    }

    private static TunerService getStaticService(Context context) {
        if (sInstance == null) {
            sInstance = new TunerService();
            sInstance.mContext = context.getApplicationContext();
            sInstance.mComponents = new HashMap<>();
            sInstance.start();
        }
        return sInstance;
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

    private static Context userContext(Context context) {
        try {
            return context.createPackageContextAsUser(context.getPackageName(), 0,
                    new UserHandle(ActivityManager.getCurrentUser()));
        } catch (NameNotFoundException e) {
            return context;
        }
    }

    private class Observer extends ContentObserver {
        public Observer() {
            super(new Handler(Looper.getMainLooper()));
        }

        @Override
        public void onChange(boolean selfChange, Uri uri, int userId) {
            if (userId == ActivityManager.getCurrentUser()) {
                reloadSetting(uri);
            }
        }
    }

    public interface Tunable {
        void onTuningChanged(String key, String newValue);
    }

    public static class ClearReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_CLEAR.equals(intent.getAction())) {
                get(context).clearAll();
            }
        }
    }
}
