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

import android.app.AlertDialog;
import android.app.AlertDialog.Builder;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.LauncherApps.ShortcutQuery;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ShortcutInfo;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ScaleDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Process;
import androidx.preference.PreferenceFragment;
import androidx.preference.SwitchPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceGroup;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.RecyclerView.ViewHolder;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.plugins.IntentButtonProvider.IntentButton;
import com.android.systemui.statusbar.ScalingDrawableWrapper;
import com.android.systemui.statusbar.phone.ExpandableIndicator;
import com.android.systemui.statusbar.policy.ExtensionController.TunerFactory;
import com.android.systemui.tuner.ShortcutParser.Shortcut;
import com.android.systemui.tuner.TunerService.Tunable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class LockscreenFragment extends PreferenceFragment {

    private static final String KEY_LEFT = "left";
    private static final String KEY_RIGHT = "right";
    private static final String KEY_CUSTOMIZE = "customize";
    private static final String KEY_SHORTCUT = "shortcut";

    public static final String LOCKSCREEN_LEFT_BUTTON = "sysui_keyguard_left";
    public static final String LOCKSCREEN_LEFT_UNLOCK = "sysui_keyguard_left_unlock";
    public static final String LOCKSCREEN_RIGHT_BUTTON = "sysui_keyguard_right";
    public static final String LOCKSCREEN_RIGHT_UNLOCK = "sysui_keyguard_right_unlock";

    private final ArrayList<Tunable> mTunables = new ArrayList<>();
    private TunerService mTunerService;
    private Handler mHandler;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        mTunerService = Dependency.get(TunerService.class);
        mHandler = new Handler();
        addPreferencesFromResource(R.xml.lockscreen_settings);
        setupGroup(LOCKSCREEN_LEFT_BUTTON, LOCKSCREEN_LEFT_UNLOCK);
        setupGroup(LOCKSCREEN_RIGHT_BUTTON, LOCKSCREEN_RIGHT_UNLOCK);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mTunables.forEach(t -> mTunerService.removeTunable(t));
    }

    private void setupGroup(String buttonSetting, String unlockKey) {
        Preference shortcut = findPreference(buttonSetting);
        SwitchPreference unlock = (SwitchPreference) findPreference(unlockKey);
        addTunable((k, v) -> {
            boolean visible = !TextUtils.isEmpty(v);
            unlock.setVisible(visible);
            setSummary(shortcut, v);
        }, buttonSetting);
    }

    private void showSelectDialog(String buttonSetting) {
        RecyclerView v = (RecyclerView) LayoutInflater.from(getContext())
                .inflate(R.layout.tuner_shortcut_list, null);
        v.setLayoutManager(new LinearLayoutManager(getContext()));
        AlertDialog dialog = new Builder(getContext())
                .setView(v)
                .show();
        Adapter adapter = new Adapter(getContext(), item -> {
            mTunerService.setValue(buttonSetting, item.getSettingValue());
            dialog.dismiss();
        });

        v.setAdapter(adapter);
    }

    private void setSummary(Preference shortcut, String value) {
        if (value == null) {
            shortcut.setSummary(R.string.lockscreen_none);
            return;
        }
        if (value.contains("::")) {
            Shortcut info = getShortcutInfo(getContext(), value);
            shortcut.setSummary(info != null ? info.label : null);
        } else if (value.contains("/")) {
            ActivityInfo info = getActivityinfo(getContext(), value);
            shortcut.setSummary(info != null ? info.loadLabel(getContext().getPackageManager())
                    : null);
        } else {
            shortcut.setSummary(R.string.lockscreen_none);
        }
    }

    private void addTunable(Tunable t, String... keys) {
        mTunables.add(t);
        mTunerService.addTunable(t, keys);
    }

    public static ActivityInfo getActivityinfo(Context context, String value) {
        ComponentName component = ComponentName.unflattenFromString(value);
        try {
            return context.getPackageManager().getActivityInfo(component, 0);
        } catch (NameNotFoundException e) {
            return null;
        }
    }

    public static Shortcut getShortcutInfo(Context context, String value) {
        return Shortcut.create(context, value);
    }

    public static class Holder extends ViewHolder {
        public final ImageView icon;
        public final TextView title;
        public final ExpandableIndicator expand;

        public Holder(View itemView) {
            super(itemView);
            icon = (ImageView) itemView.findViewById(android.R.id.icon);
            title = (TextView) itemView.findViewById(android.R.id.title);
            expand = (ExpandableIndicator) itemView.findViewById(R.id.expand);
        }
    }

    private static class StaticShortcut extends Item {

        private final Context mContext;
        private final Shortcut mShortcut;


        public StaticShortcut(Context context, Shortcut shortcut) {
            mContext = context;
            mShortcut = shortcut;
        }

        @Override
        public Drawable getDrawable() {
            return mShortcut.icon.loadDrawable(mContext);
        }

        @Override
        public String getLabel() {
            return mShortcut.label;
        }

        @Override
        public String getSettingValue() {
            return mShortcut.toString();
        }

        @Override
        public Boolean getExpando() {
            return null;
        }
    }

    private static class App extends Item {

        private final Context mContext;
        private final LauncherActivityInfo mInfo;
        private final ArrayList<Item> mChildren = new ArrayList<>();
        private boolean mExpanded;

        public App(Context context, LauncherActivityInfo info) {
            mContext = context;
            mInfo = info;
            mExpanded = false;
        }

        public void addChild(Item child) {
            mChildren.add(child);
        }

        @Override
        public Drawable getDrawable() {
            return mInfo.getBadgedIcon(mContext.getResources().getConfiguration().densityDpi);
        }

        @Override
        public String getLabel() {
            return mInfo.getLabel().toString();
        }

        @Override
        public String getSettingValue() {
            return mInfo.getComponentName().flattenToString();
        }

        @Override
        public Boolean getExpando() {
            return mChildren.size() != 0 ? mExpanded : null;
        }

        @Override
        public void toggleExpando(Adapter adapter) {
            mExpanded = !mExpanded;
            if (mExpanded) {
                mChildren.forEach(child -> adapter.addItem(this, child));
            } else {
                mChildren.forEach(child -> adapter.remItem(child));
            }
        }
    }

    private abstract static class Item {
        public abstract Drawable getDrawable();

        public abstract String getLabel();

        public abstract String getSettingValue();

        public abstract Boolean getExpando();

        public void toggleExpando(Adapter adapter) {
        }
    }

    public static class Adapter extends RecyclerView.Adapter<Holder> {
        private ArrayList<Item> mItems = new ArrayList<>();
        private final Context mContext;
        private final Consumer<Item> mCallback;

        public Adapter(Context context, Consumer<Item> callback) {
            mContext = context;
            mCallback = callback;
        }

        @Override
        public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.tuner_shortcut_item, parent, false));
        }

        @Override
        public void onBindViewHolder(Holder holder, int position) {
            Item item = mItems.get(position);
            holder.icon.setImageDrawable(item.getDrawable());
            holder.title.setText(item.getLabel());
            holder.itemView.setOnClickListener(
                    v -> mCallback.accept(mItems.get(holder.getAdapterPosition())));
            Boolean expando = item.getExpando();
            if (expando != null) {
                holder.expand.setVisibility(View.VISIBLE);
                holder.expand.setExpanded(expando);
                holder.expand.setOnClickListener(
                        v -> mItems.get(holder.getAdapterPosition()).toggleExpando(Adapter.this));
            } else {
                holder.expand.setVisibility(View.GONE);
            }
        }

        @Override
        public int getItemCount() {
            return mItems.size();
        }

        public void addItem(Item item) {
            mItems.add(item);
            notifyDataSetChanged();
        }

        public void remItem(Item item) {
            int index = mItems.indexOf(item);
            mItems.remove(item);
            notifyItemRemoved(index);
        }

        public void addItem(Item parent, Item child) {
            int index = mItems.indexOf(parent);
            mItems.add(index + 1, child);
            notifyItemInserted(index + 1);
        }
    }

    public static class LockButtonFactory implements TunerFactory<IntentButton> {

        private final String mKey;
        private final Context mContext;

        public LockButtonFactory(Context context, String key) {
            mContext = context;
            mKey = key;
        }

        @Override
        public String[] keys() {
            return new String[]{mKey};
        }

        @Override
        public IntentButton create(Map<String, String> settings) {
            String buttonStr = settings.get(mKey);
            if (!TextUtils.isEmpty(buttonStr)) {
                if (buttonStr.contains("::")) {
                    Shortcut shortcut = getShortcutInfo(mContext, buttonStr);
                    if (shortcut != null) {
                        return new ShortcutButton(mContext, shortcut);
                    }
                } else if (buttonStr.contains("/")) {
                    ActivityInfo info = getActivityinfo(mContext, buttonStr);
                    if (info != null) {
                        return new ActivityButton(mContext, info);
                    }
                }
            }
            return null;
        }
    }

    private static class ShortcutButton implements IntentButton {
        private final Shortcut mShortcut;
        private final IconState mIconState;

        public ShortcutButton(Context context, Shortcut shortcut) {
            mShortcut = shortcut;
            mIconState = new IconState();
            mIconState.isVisible = true;
            mIconState.drawable = shortcut.icon.loadDrawable(context).mutate();
            mIconState.contentDescription = mShortcut.label;
            int size = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 32,
                    context.getResources().getDisplayMetrics());
            mIconState.drawable = new ScalingDrawableWrapper(mIconState.drawable,
                    size / (float) mIconState.drawable.getIntrinsicWidth());
            mIconState.tint = false;
        }

        @Override
        public IconState getIcon() {
            return mIconState;
        }

        @Override
        public Intent getIntent() {
            return mShortcut.intent;
        }
    }

    private static class ActivityButton implements IntentButton {
        private final Intent mIntent;
        private final IconState mIconState;

        public ActivityButton(Context context, ActivityInfo info) {
            mIntent = new Intent().setComponent(new ComponentName(info.packageName, info.name));
            mIconState = new IconState();
            mIconState.isVisible = true;
            mIconState.drawable = info.loadIcon(context.getPackageManager()).mutate();
            mIconState.contentDescription = info.loadLabel(context.getPackageManager());
            int size = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 32,
                    context.getResources().getDisplayMetrics());
            mIconState.drawable = new ScalingDrawableWrapper(mIconState.drawable,
                    size / (float) mIconState.drawable.getIntrinsicWidth());
            mIconState.tint = false;
        }

        @Override
        public IconState getIcon() {
            return mIconState;
        }

        @Override
        public Intent getIntent() {
            return mIntent;
        }
    }
}
