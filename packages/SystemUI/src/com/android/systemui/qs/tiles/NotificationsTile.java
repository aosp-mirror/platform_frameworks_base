/*
 * Copyright (C) 2014 The Android Open Source Project
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
import android.media.AudioManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnAttachStateChangeListener;
import android.view.ViewGroup;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.qs.QSTile;
import com.android.systemui.statusbar.policy.ZenModeController;
import com.android.systemui.volume.VolumeComponent;
import com.android.systemui.volume.VolumePanel;
import com.android.systemui.volume.ZenModePanel;

/** Quick settings tile: Notifications **/
public class NotificationsTile extends QSTile<NotificationsTile.NotificationsState> {
    private final ZenModeController mZenController;
    private final AudioManager mAudioManager;

    public NotificationsTile(Host host) {
        super(host);
        mZenController = host.getZenModeController();
        mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
    }

    @Override
    public View createDetailView(Context context, ViewGroup root) {
        final View v = LayoutInflater.from(context).inflate(R.layout.qs_detail, root, false);
        final TextView title = (TextView) v.findViewById(android.R.id.title);
        title.setText(R.string.quick_settings_notifications_label);
        final View close = v.findViewById(android.R.id.button1);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDetail(false);
            }
        });
        final ViewGroup content = (ViewGroup) v.findViewById(android.R.id.content);
        final VolumeComponent volumeComponent = mHost.getVolumeComponent();
        final VolumePanel vp = new VolumePanel(mContext, content, mZenController);
        v.addOnAttachStateChangeListener(new OnAttachStateChangeListener() {
            @Override
            public void onViewDetachedFromWindow(View v) {
                volumeComponent.setVolumePanel(null);
            }

            @Override
            public void onViewAttachedToWindow(View v) {
                volumeComponent.setVolumePanel(vp);
            }
        });
        vp.setZenModePanelCallback(new ZenModePanel.Callback() {
            @Override
            public void onMoreSettings() {
                mHost.startSettingsActivity(ZenModePanel.ZEN_SETTINGS);
            }

            @Override
            public void onInteraction() {
                // noop
            }
        });
        vp.postVolumeChanged(AudioManager.STREAM_RING, AudioManager.FLAG_SHOW_UI);
        return v;
    }

    @Override
    protected NotificationsState newTileState() {
        return new NotificationsState();
    }

    @Override
    public void setListening(boolean listening) {
        if (listening) {
            mZenController.addCallback(mCallback);
            final IntentFilter filter = new IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION);
            mContext.registerReceiver(mReceiver, filter);
        } else {
            mZenController.removeCallback(mCallback);
            mContext.unregisterReceiver(mReceiver);
        }
    }

    @Override
    protected void handleClick() {
        showDetail(true);
    }

    @Override
    protected void handleUpdateState(NotificationsState state, Object arg) {
        state.visible = true;
        state.zen = arg instanceof Boolean ? (Boolean) arg : mZenController.isZen();
        state.ringerMode = mAudioManager.getRingerMode();
        if (state.zen) {
            state.iconId = R.drawable.ic_qs_zen_on;
        } else if (state.ringerMode == AudioManager.RINGER_MODE_VIBRATE) {
            state.iconId = R.drawable.ic_qs_ringer_vibrate;
        } else if (state.ringerMode == AudioManager.RINGER_MODE_SILENT) {
            state.iconId = R.drawable.ic_qs_ringer_silent;
        } else {
            state.iconId = R.drawable.ic_qs_ringer_audible;
        }
        state.label = mContext.getString(R.string.quick_settings_notifications_label);
    }

    private final ZenModeController.Callback mCallback = new ZenModeController.Callback() {
        @Override
        public void onZenChanged(boolean zen) {
            if (DEBUG) Log.d(TAG, "onZenChanged " + zen);
            refreshState(zen);
        }
    };

    public static final class NotificationsState extends QSTile.State {
        public boolean zen;
        public int ringerMode;

        @Override
        public boolean copyTo(State other) {
            final NotificationsState o = (NotificationsState) other;
            final boolean changed = o.zen != zen || o.ringerMode != ringerMode;
            o.zen = zen;
            o.ringerMode = ringerMode;
            return super.copyTo(other) || changed;
        }

        @Override
        protected StringBuilder toStringBuilder() {
            final StringBuilder rt = super.toStringBuilder();
            rt.insert(rt.length() - 1, ",zen=" + zen + ",ringerMode=" + ringerMode);
            return rt;
        }
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (AudioManager.RINGER_MODE_CHANGED_ACTION.equals(intent.getAction())) {
                refreshState();
            }
        }
    };
}
