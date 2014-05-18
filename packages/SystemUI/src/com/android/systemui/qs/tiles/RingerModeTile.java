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

import com.android.systemui.R;
import com.android.systemui.qs.QSTile;

/** Quick settings tile: Ringer mode **/
public class RingerModeTile extends QSTile<RingerModeTile.IntState> {

    private final AudioManager mAudioManager;

    public RingerModeTile(Host host) {
        super(host);
        mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
    }

    @Override
    protected IntState newTileState() {
        return new IntState();
    }

    @Override
    public void setListening(boolean listening) {
        if (listening) {
            final IntentFilter filter = new IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION);
            mContext.registerReceiver(mReceiver, filter);
        } else {
            mContext.unregisterReceiver(mReceiver);
        }
    }

    @Override
    protected void handleClick() {
        final int oldValue = (Integer) mState.value;
        final int newValue =
                oldValue == AudioManager.RINGER_MODE_NORMAL ? AudioManager.RINGER_MODE_VIBRATE
              : oldValue == AudioManager.RINGER_MODE_VIBRATE ? AudioManager.RINGER_MODE_SILENT
              : AudioManager.RINGER_MODE_NORMAL;

        mAudioManager.setRingerMode(newValue);
    }

    @Override
    protected void handleUpdateState(IntState state, Object arg) {
        final int ringerMode = mAudioManager.getRingerMode();
        state.visible = true;
        state.value = ringerMode;
        if (ringerMode == AudioManager.RINGER_MODE_VIBRATE) {
            state.iconId = R.drawable.ic_qs_ringer_vibrate;
            state.label = "Vibrate";
        } else if (ringerMode == AudioManager.RINGER_MODE_SILENT) {
            state.iconId = R.drawable.ic_qs_ringer_silent;
            state.label = "Silent";
        } else {
            state.iconId = R.drawable.ic_qs_ringer_audible;
            state.label = "Audible";
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

    public static class IntState extends QSTile.State {
        public int value;

        @Override
        public boolean copyTo(State other) {
            final IntState o = (IntState) other;
            final boolean changed = o.value != value;
            o.value = value;
            return super.copyTo(other) || changed;
        }

        @Override
        protected StringBuilder toStringBuilder() {
            final StringBuilder rt = super.toStringBuilder();
            rt.insert(rt.length() - 1, ",value=" + value);
            return rt;
        }
    }
}
