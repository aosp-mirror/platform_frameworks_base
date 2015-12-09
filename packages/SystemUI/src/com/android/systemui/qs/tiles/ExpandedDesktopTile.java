/*
 * Copyright 2015 The Euphoria-OS Project
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
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.provider.Settings;
import android.view.WindowManagerPolicyControl;

import com.android.internal.logging.MetricsProto.MetricsEvent;

import com.android.systemui.qs.QSTile;
import com.android.systemui.R;

/** Quick settings tile: Expanded desktop **/
public class ExpandedDesktopTile extends QSTile<QSTile.BooleanState> {

    private static final int STATE_ENABLE_FOR_ALL = 1;
    private static final int STATE_USER_CONFIGURABLE = 2;

    private int mExpandedDesktopState;
    private ExpandedDesktopObserver mObserver;
    private boolean mListening;

    public ExpandedDesktopTile(Host host) {
        super(host);
        mExpandedDesktopState = getExpandedDesktopState(mContext.getContentResolver());
        mObserver = new ExpandedDesktopObserver(mHandler);
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.DISPLAY;
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public void handleClick() {
        /* wait for ripple animation to end */
        try {
             Thread.sleep(750);
        } catch (InterruptedException ie) {
             // Do nothing
        }
        toggleState();
        refreshState();
    }

    @Override
    public Intent getLongClickIntent() {
        return new Intent().setComponent(new ComponentName(
            "com.android.settings", "com.android.settings.Settings$ExpandedDesktopSettingsActivity"));
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_expanded_desktop);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        if (mExpandedDesktopState == 1) {
            state.icon = ResourceIcon.get(R.drawable.ic_qs_expanded_desktop);
            state.label = mContext.getString(R.string.quick_settings_expanded_desktop);
        } else {
            state.icon = ResourceIcon.get(R.drawable.ic_qs_expanded_desktop_off);
            state.label = mContext.getString(R.string.quick_settings_expanded_desktop_off);
        }
    }

    protected void toggleState() {
        int state = mExpandedDesktopState;
        switch (state) {
            case STATE_ENABLE_FOR_ALL:
                userConfigurableSettings();
                break;
            case STATE_USER_CONFIGURABLE:
                enableForAll();
                break;
        }
    }

    private void writeValue(String value) {
        Settings.Global.putString(mContext.getContentResolver(),
             Settings.Global.POLICY_CONTROL, value);
    }

    private void enableForAll() {
        mExpandedDesktopState = STATE_ENABLE_FOR_ALL;
        writeValue("immersive.full=*");
    }

    private void userConfigurableSettings() {
        mExpandedDesktopState = STATE_USER_CONFIGURABLE;
        writeValue("");
        WindowManagerPolicyControl.reloadFromSetting(mContext);
    }

    private int getExpandedDesktopState(ContentResolver cr) {
        String value = Settings.Global.getString(cr, Settings.Global.POLICY_CONTROL);
        if ("immersive.full=*".equals(value)) {
            return STATE_ENABLE_FOR_ALL;
        }
        return STATE_USER_CONFIGURABLE;
    }

    @Override
    public void setListening(boolean listening) {
        if (mListening == listening) return;
        if (listening) {
            mObserver.startObserving();
        } else {
            mObserver.endObserving();
        }
    }

    private class ExpandedDesktopObserver extends ContentObserver {
        public ExpandedDesktopObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            refreshState();
        }

        public void startObserving() {
            mExpandedDesktopState = getExpandedDesktopState(mContext.getContentResolver());
            mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.POLICY_CONTROL),
                    false, this);
        }

        public void endObserving() {
            mContext.getContentResolver().unregisterContentObserver(this);
        }
    }
}
