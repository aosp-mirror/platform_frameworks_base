/*
 * Copyright (C) 2015 The CyanogenMod Project
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

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SyncStatusObserver;

import com.android.systemui.R;
import com.android.systemui.qs.QSTile;

import com.android.internal.logging.MetricsProto.MetricsEvent;

/** Quick settings tile: Sync **/
public class SyncTile extends QSTile<QSTile.BooleanState> {

    private Object mSyncObserverHandle = null;
    private boolean mListening;

    public SyncTile(Host host) {
        super(host);
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public void handleClick() {
        ContentResolver.setMasterSyncAutomatically(!mState.value);
        refreshState();
    }

    @Override
    public Intent getLongClickIntent() {
        return new Intent("android.settings.SYNC_SETTINGS");
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_sync_label);
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.ACCOUNTS_ACCOUNT_SYNC;
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        state.value = ContentResolver.getMasterSyncAutomatically();
        state.label = mContext.getString(R.string.quick_settings_sync_label);
        if (state.value) {
            state.icon = ResourceIcon.get(R.drawable.ic_qs_sync_on);
            state.contentDescription =  mContext.getString(
                    R.string.accessibility_quick_settings_sync_on);
        } else {
            state.icon = ResourceIcon.get(R.drawable.ic_qs_sync_off);
            state.contentDescription =  mContext.getString(
                    R.string.accessibility_quick_settings_sync_off);
        }
    }

    @Override
    protected String composeChangeAnnouncement() {
        if (mState.value) {
            return mContext.getString(R.string.accessibility_quick_settings_sync_changed_on);
        } else {
            return mContext.getString(R.string.accessibility_quick_settings_sync_changed_off);
        }
    }

    @Override
    public void setListening(boolean listening) {
        if (mListening == listening) return;
        mListening = listening;

        if (listening) {
            mSyncObserverHandle = ContentResolver.addStatusChangeListener(
                    ContentResolver.SYNC_OBSERVER_TYPE_SETTINGS, mSyncObserver);
        } else {
            ContentResolver.removeStatusChangeListener(mSyncObserverHandle);
            mSyncObserverHandle = null;
        }
    }

    private SyncStatusObserver mSyncObserver = new SyncStatusObserver() {
        public void onStatusChanged(int which) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    refreshState();
                }
            });
        }
    };
}
