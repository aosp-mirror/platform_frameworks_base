/*
 * Copyright (C) 2016 The Android Open Source Project
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
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.support.v14.preference.PreferenceFragment;
import android.support.v14.preference.SwitchPreference;
import android.support.v7.preference.PreferenceScreen;
import android.support.v7.preference.PreferenceViewHolder;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.view.View;

import com.android.systemui.R;
import com.android.systemui.plugins.PluginInstanceManager;
import com.android.systemui.plugins.PluginManager;
import com.android.systemui.plugins.PluginPrefs;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class PluginFragment extends PreferenceFragment {

    public static final String ACTION_PLUGIN_SETTINGS
            = "com.android.systemui.action.PLUGIN_SETTINGS";

    private PluginPrefs mPluginPrefs;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addDataScheme("package");
        getContext().registerReceiver(mReceiver, filter);
        filter = new IntentFilter(Intent.ACTION_USER_UNLOCKED);
        getContext().registerReceiver(mReceiver, filter);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        getContext().unregisterReceiver(mReceiver);
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        loadPrefs();
    }

    private void loadPrefs() {
        PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(getContext());
        screen.setOrderingAsAdded(false);
        Context prefContext = getPreferenceManager().getContext();
        mPluginPrefs = new PluginPrefs(getContext());
        PackageManager pm = getContext().getPackageManager();

        Set<String> pluginActions = mPluginPrefs.getPluginList();
        ArrayMap<String, ArraySet<String>> plugins = new ArrayMap<>();
        for (String action : pluginActions) {
            String name = toName(action);
            List<ResolveInfo> result = pm.queryIntentServices(
                    new Intent(action), PackageManager.MATCH_DISABLED_COMPONENTS);
            for (ResolveInfo info : result) {
                String packageName = info.serviceInfo.packageName;
                if (!plugins.containsKey(packageName)) {
                    plugins.put(packageName, new ArraySet<>());
                }
                plugins.get(packageName).add(name);
            }
        }

        List<PackageInfo> apps = pm.getPackagesHoldingPermissions(new String[]{
                PluginInstanceManager.PLUGIN_PERMISSION},
                PackageManager.MATCH_DISABLED_COMPONENTS | PackageManager.GET_SERVICES);
        apps.forEach(app -> {
            if (!plugins.containsKey(app.packageName)) return;
            SwitchPreference pref = new PluginPreference(prefContext, app);
            pref.setSummary("Plugins: " + toString(plugins.get(app.packageName)));
            screen.addPreference(pref);
        });
        setPreferenceScreen(screen);
    }

    private String toString(ArraySet<String> plugins) {
        StringBuilder b = new StringBuilder();
        for (String string : plugins) {
            if (b.length() != 0) {
                b.append(", ");
            }
            b.append(string);
        }
        return b.toString();
    }

    private String toName(String action) {
        String str = action.replace("com.android.systemui.action.PLUGIN_", "");
        StringBuilder b = new StringBuilder();
        for (String s : str.split("_")) {
            if (b.length() != 0) {
                b.append(' ');
            }
            b.append(s.substring(0, 1));
            b.append(s.substring(1).toLowerCase());
        }
        return b.toString();
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            loadPrefs();
        }
    };

    private static class PluginPreference extends SwitchPreference {
        private final boolean mHasSettings;
        private final PackageInfo mInfo;
        private final PackageManager mPm;

        public PluginPreference(Context prefContext, PackageInfo info) {
            super(prefContext);
            mPm = prefContext.getPackageManager();
            mHasSettings = mPm.resolveActivity(new Intent(ACTION_PLUGIN_SETTINGS)
                    .setPackage(info.packageName), 0) != null;
            mInfo = info;
            setTitle(info.applicationInfo.loadLabel(mPm));
            setChecked(isPluginEnabled());
            setWidgetLayoutResource(R.layout.tuner_widget_settings_switch);
        }

        private boolean isPluginEnabled() {
            for (int i = 0; i < mInfo.services.length; i++) {
                ComponentName componentName = new ComponentName(mInfo.packageName,
                        mInfo.services[i].name);
                if (mPm.getComponentEnabledSetting(componentName)
                        == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                    return false;
                }
            }
            return true;
        }

        @Override
        protected boolean persistBoolean(boolean value) {
            final int desiredState = value ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
            boolean shouldSendBroadcast = false;
            for (int i = 0; i < mInfo.services.length; i++) {
                ComponentName componentName = new ComponentName(mInfo.packageName,
                        mInfo.services[i].name);

                if (mPm.getComponentEnabledSetting(componentName) != desiredState) {
                    mPm.setComponentEnabledSetting(componentName, desiredState,
                            PackageManager.DONT_KILL_APP);
                    shouldSendBroadcast = true;
                }
            }
            if (shouldSendBroadcast) {
                final String pkg = mInfo.packageName;
                final Intent intent = new Intent(PluginManager.PLUGIN_CHANGED,
                        pkg != null ? Uri.fromParts("package", pkg, null) : null);
                getContext().sendBroadcast(intent);
            }
            return true;
        }

        @Override
        public void onBindViewHolder(PreferenceViewHolder holder) {
            super.onBindViewHolder(holder);
            holder.findViewById(R.id.settings).setVisibility(mHasSettings ? View.VISIBLE
                    : View.GONE);
            holder.findViewById(R.id.divider).setVisibility(mHasSettings ? View.VISIBLE
                    : View.GONE);
            holder.findViewById(R.id.settings).setOnClickListener(v -> {
                ResolveInfo result = v.getContext().getPackageManager().resolveActivity(
                        new Intent(ACTION_PLUGIN_SETTINGS).setPackage(
                                mInfo.packageName), 0);
                if (result != null) {
                    v.getContext().startActivity(new Intent().setComponent(
                            new ComponentName(result.activityInfo.packageName,
                                    result.activityInfo.name)));
                }
            });
            holder.itemView.setOnLongClickListener(v -> {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(Uri.fromParts("package", mInfo.packageName, null));
                getContext().startActivity(intent);
                return true;
            });
        }
    }
}
