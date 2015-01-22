/*
 * Copyright (C) 2015 The CyanogenMod Project
 * Copyright (C) 2017-2018 The LineageOS Project
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

package com.android.systemui.qs.tiles;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.os.Handler;
import android.service.quicksettings.Tile;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckedTextView;
import android.widget.ListView;

import com.android.systemui.Dependency;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.qs.DetailAdapter;
import com.android.systemui.plugins.qs.QSTile.State;
import com.android.systemui.qs.QSDetailItemsList;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.R;
import com.android.systemui.statusbar.policy.KeyguardMonitor;

import lineageos.app.Profile;
import lineageos.app.ProfileManager;
import lineageos.providers.LineageSettings;
import org.lineageos.internal.logging.LineageMetricsLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.inject.Inject;

public class ProfilesTile extends QSTileImpl<State> {

    private static final Intent PROFILES_SETTINGS =
            new Intent("org.lineageos.lineageparts.PROFILES_SETTINGS");

    private final Icon mIcon = ResourceIcon.get(R.drawable.ic_qs_profiles);

    private boolean mListening;
    private QSDetailItemsList mDetails;
    private ProfileAdapter mAdapter;

    private final ActivityStarter mActivityStarter;
    private final KeyguardMonitor mKeyguardMonitor;
    private final ProfileManager mProfileManager;
    private final ProfilesObserver mObserver;
    private final ProfileDetailAdapter mDetailAdapter;
    private final KeyguardMonitorCallback mCallback = new KeyguardMonitorCallback();

    @Inject
    public ProfilesTile(QSHost host) {
        super(host);
        mActivityStarter = Dependency.get(ActivityStarter.class);
        mKeyguardMonitor = Dependency.get(KeyguardMonitor.class);
        mProfileManager = ProfileManager.getInstance(mContext);
        mObserver = new ProfilesObserver(mHandler);
        mDetailAdapter = (ProfileDetailAdapter) createDetailAdapter();
    }

    @Override
    public State newTileState() {
        return new State();
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_profiles_label);
    }

    @Override
    public Intent getLongClickIntent() {
        return PROFILES_SETTINGS;
    }

    @Override
    protected void handleClick() {
        if (mKeyguardMonitor.isSecure() && mKeyguardMonitor.isShowing()) {
            mActivityStarter.postQSRunnableDismissingKeyguard(() -> {
                setProfilesEnabled(!profilesEnabled());
            });
            return;
        }
        setProfilesEnabled(!profilesEnabled());
    }

    @Override
    protected void handleSecondaryClick() {
        if (mKeyguardMonitor.isSecure() && mKeyguardMonitor.isShowing()) {
            mActivityStarter.postQSRunnableDismissingKeyguard(() -> {
                setProfilesEnabled(true);
                showDetail(true);
            });
            return;
        }
        setProfilesEnabled(true);
        showDetail(true);
    }

    @Override
    protected void handleLongClick() {
        mActivityStarter.postStartActivityDismissingKeyguard(PROFILES_SETTINGS, 0);
    }

    @Override
    protected void handleUpdateState(State state, Object arg) {
        state.icon = mIcon;
        state.label = mContext.getString(R.string.quick_settings_profiles_label);
        if (profilesEnabled()) {
            state.secondaryLabel = mProfileManager.getActiveProfile().getName();
            state.contentDescription = mContext.getString(
                    R.string.accessibility_quick_settings_profiles, state.label);
            state.state = Tile.STATE_ACTIVE;
        } else {
            state.secondaryLabel = null;
            state.contentDescription = mContext.getString(
                    R.string.accessibility_quick_settings_profiles_off);
            state.state = Tile.STATE_INACTIVE;
        }
        state.dualTarget = true;
    }

    @Override
    protected String composeChangeAnnouncement() {
        if (profilesEnabled()) {
            return mContext.getString(R.string.accessibility_quick_settings_profiles_changed,
                    mState.label);
        } else {
            return mContext.getString(R.string.accessibility_quick_settings_profiles_changed_off);
        }
    }

    private void setProfilesEnabled(Boolean enabled) {
        LineageSettings.System.putInt(mContext.getContentResolver(),
                LineageSettings.System.SYSTEM_PROFILES_ENABLED, enabled ? 1 : 0);
    }

    private boolean profilesEnabled() {
        return LineageSettings.System.getInt(mContext.getContentResolver(),
                LineageSettings.System.SYSTEM_PROFILES_ENABLED, 1) == 1;
    }

    @Override
    public int getMetricsCategory() {
        return LineageMetricsLogger.TILE_PROFILES;
    }

    @Override
    public void handleSetListening(boolean listening) {
        if (mListening == listening) return;
        mListening = listening;
        if (listening) {
            mObserver.startObserving();
            final IntentFilter filter = new IntentFilter();
            filter.addAction(ProfileManager.INTENT_ACTION_PROFILE_SELECTED);
            filter.addAction(ProfileManager.INTENT_ACTION_PROFILE_UPDATED);
            mContext.registerReceiver(mReceiver, filter);
            mKeyguardMonitor.addCallback(mCallback);
            refreshState();
        } else {
            mObserver.endObserving();
            mContext.unregisterReceiver(mReceiver);
            mKeyguardMonitor.removeCallback(mCallback);
        }
    }

    @Override
    public DetailAdapter getDetailAdapter() {
        return mDetailAdapter;
    }

    @Override
    protected DetailAdapter createDetailAdapter() {
        return new ProfileDetailAdapter();
    }

    private class KeyguardMonitorCallback implements KeyguardMonitor.Callback {
        @Override
        public void onKeyguardShowingChanged() {
            refreshState();
        }
    }

    private class ProfileAdapter extends ArrayAdapter<Profile> {
        public ProfileAdapter(Context context, List<Profile> profiles) {
            super(context, android.R.layout.simple_list_item_single_choice, profiles);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            CheckedTextView label = convertView != null
                    ? (CheckedTextView) convertView
                    : (CheckedTextView) LayoutInflater.from(mContext).inflate(
                            android.R.layout.simple_list_item_single_choice, parent, false);

            Profile p = getItem(position);
            label.setText(p.getName());

            return label;
        }
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ProfileManager.INTENT_ACTION_PROFILE_SELECTED.equals(intent.getAction())
                    || ProfileManager.INTENT_ACTION_PROFILE_UPDATED.equals(intent.getAction())) {
                refreshState();
            }
        }
    };

    public class ProfileDetailAdapter implements DetailAdapter, AdapterView.OnItemClickListener {

        private List<Profile> mProfilesList;

        @Override
        public CharSequence getTitle() {
            return mContext.getString(R.string.quick_settings_profiles_label);
        }

        @Override
        public Boolean getToggleState() {
            return profilesEnabled();
        }

        @Override
        public int getMetricsCategory() {
            return LineageMetricsLogger.TILE_PROFILES_DETAIL;
        }

        @Override
        public View createDetailView(Context context, View convertView, ViewGroup parent) {
            mDetails = QSDetailItemsList.convertOrInflate(context, convertView, parent);
            mProfilesList = new ArrayList<>();
            mDetails.setAdapter(mAdapter = new ProfileAdapter(context, mProfilesList));

            final ListView list = mDetails.getListView();
            list.setChoiceMode(AbsListView.CHOICE_MODE_SINGLE);
            list.setOnItemClickListener(this);

            buildProfilesList();

            return mDetails;
        }

        private void buildProfilesList() {
            mProfilesList.clear();
            int selected = -1;

            final Profile[] profiles = mProfileManager.getProfiles();
            final Profile activeProfile = mProfileManager.getActiveProfile();
            final UUID activeUuid = activeProfile != null ? activeProfile.getUuid() : null;

            for (int i = 0; i < profiles.length; i++) {
                mProfilesList.add(profiles[i]);
                if (activeUuid != null && activeUuid.equals(profiles[i].getUuid())) {
                    selected = i;
                }
            }
            mDetails.getListView().setItemChecked(selected, true);
            mAdapter.notifyDataSetChanged();
        }

        @Override
        public Intent getSettingsIntent() {
            return PROFILES_SETTINGS;
        }

        @Override
        public void setToggleState(boolean state) {
            setProfilesEnabled(state);
            showDetail(false);
        }

        @Override
        public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
            Profile selected = (Profile) parent.getItemAtPosition(position);
            mProfileManager.setActiveProfile(selected.getUuid());
        }
    }

    private class ProfilesObserver extends ContentObserver {
        public ProfilesObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            refreshState();
        }

        public void startObserving() {
            mContext.getContentResolver().registerContentObserver(
                    LineageSettings.System.getUriFor(LineageSettings.System.SYSTEM_PROFILES_ENABLED),
                    false, this);
        }

        public void endObserving() {
            mContext.getContentResolver().unregisterContentObserver(this);
        }
    }
}
