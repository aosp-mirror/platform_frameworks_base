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

package com.android.systemui.qs;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import com.android.systemui.qs.QSTile.State;
import com.android.systemui.statusbar.policy.BluetoothController;
import com.android.systemui.statusbar.policy.CastController;
import com.android.systemui.statusbar.policy.FlashlightController;
import com.android.systemui.statusbar.policy.KeyguardMonitor;
import com.android.systemui.statusbar.policy.Listenable;
import com.android.systemui.statusbar.policy.LocationController;
import com.android.systemui.statusbar.policy.NetworkController;
import com.android.systemui.statusbar.policy.RotationLockController;
import com.android.systemui.statusbar.policy.HotspotController;
import com.android.systemui.statusbar.policy.ZenModeController;

import java.util.Collection;
import java.util.Objects;

/**
 * Base quick-settings tile, extend this to create a new tile.
 *
 * State management done on a looper provided by the host.  Tiles should update state in
 * handleUpdateState.  Callbacks affecting state should use refreshState to trigger another
 * state update pass on tile looper.
 */
public abstract class QSTile<TState extends State> implements Listenable {
    protected final String TAG = "QSTile." + getClass().getSimpleName();
    protected static final boolean DEBUG = Log.isLoggable("QSTile", Log.DEBUG);

    protected final Host mHost;
    protected final Context mContext;
    protected final H mHandler;
    protected final Handler mUiHandler = new Handler(Looper.getMainLooper());

    private Callback mCallback;
    protected final TState mState = newTileState();
    private final TState mTmpState = newTileState();

    abstract protected TState newTileState();
    abstract protected void handleClick();
    abstract protected void handleUpdateState(TState state, Object arg);

    protected QSTile(Host host) {
        mHost = host;
        mContext = host.getContext();
        mHandler = new H(host.getLooper());
    }

    public boolean supportsDualTargets() {
        return false;
    }

    public Host getHost() {
        return mHost;
    }

    public QSTileView createTileView(Context context) {
        return new QSTileView(context);
    }

    public DetailAdapter getDetailAdapter() {
        return null; // optional
    }

    public interface DetailAdapter {
        int getTitle();
        Boolean getToggleState();
        View createDetailView(Context context, View convertView, ViewGroup parent);
        Intent getSettingsIntent();
        void setToggleState(boolean state);
    }

    // safe to call from any thread

    public void setCallback(Callback callback) {
        mHandler.obtainMessage(H.SET_CALLBACK, callback).sendToTarget();
    }

    public void click() {
        mHandler.sendEmptyMessage(H.CLICK);
    }

    public void secondaryClick() {
        mHandler.sendEmptyMessage(H.SECONDARY_CLICK);
    }

    public void showDetail(boolean show) {
        mHandler.obtainMessage(H.SHOW_DETAIL, show ? 1 : 0, 0).sendToTarget();
    }

    protected final void refreshState() {
        refreshState(null);
    }

    protected final void refreshState(Object arg) {
        mHandler.obtainMessage(H.REFRESH_STATE, arg).sendToTarget();
    }

    public void userSwitch(int newUserId) {
        mHandler.obtainMessage(H.USER_SWITCH, newUserId).sendToTarget();
    }

    public void fireToggleStateChanged(boolean state) {
        mHandler.obtainMessage(H.TOGGLE_STATE_CHANGED, state ? 1 : 0, 0).sendToTarget();
    }

    public void fireScanStateChanged(boolean state) {
        mHandler.obtainMessage(H.SCAN_STATE_CHANGED, state ? 1 : 0, 0).sendToTarget();
    }

    public void destroy() {
        mHandler.sendEmptyMessage(H.DESTROY);
    }

    public TState getState() {
        return mState;
    }

    // call only on tile worker looper

    private void handleSetCallback(Callback callback) {
        mCallback = callback;
        handleRefreshState(null);
    }

    protected void handleSecondaryClick() {
        // optional
    }

    protected void handleRefreshState(Object arg) {
        handleUpdateState(mTmpState, arg);
        final boolean changed = mTmpState.copyTo(mState);
        if (changed) {
            handleStateChanged();
        }
    }

    private void handleStateChanged() {
        if (mCallback != null) {
            mCallback.onStateChanged(mState);
        }
    }

    private void handleShowDetail(boolean show) {
        if (mCallback != null) {
            mCallback.onShowDetail(show);
        }
    }

    private void handleToggleStateChanged(boolean state) {
        if (mCallback != null) {
            mCallback.onToggleStateChanged(state);
        }
    }

    private void handleScanStateChanged(boolean state) {
        if (mCallback != null) {
            mCallback.onScanStateChanged(state);
        }
    }

    protected void handleUserSwitch(int newUserId) {
        handleRefreshState(null);
    }

    protected void handleDestroy() {
        setListening(false);
        mCallback = null;
    }

    protected final class H extends Handler {
        private static final int SET_CALLBACK = 1;
        private static final int CLICK = 2;
        private static final int SECONDARY_CLICK = 3;
        private static final int REFRESH_STATE = 4;
        private static final int SHOW_DETAIL = 5;
        private static final int USER_SWITCH = 6;
        private static final int TOGGLE_STATE_CHANGED = 7;
        private static final int SCAN_STATE_CHANGED = 8;
        private static final int DESTROY = 9;

        private H(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            String name = null;
            try {
                if (msg.what == SET_CALLBACK) {
                    name = "handleSetCallback";
                    handleSetCallback((QSTile.Callback)msg.obj);
                } else if (msg.what == CLICK) {
                    name = "handleClick";
                    handleClick();
                } else if (msg.what == SECONDARY_CLICK) {
                    name = "handleSecondaryClick";
                    handleSecondaryClick();
                } else if (msg.what == REFRESH_STATE) {
                    name = "handleRefreshState";
                    handleRefreshState(msg.obj);
                } else if (msg.what == SHOW_DETAIL) {
                    name = "handleShowDetail";
                    handleShowDetail(msg.arg1 != 0);
                } else if (msg.what == USER_SWITCH) {
                    name = "handleUserSwitch";
                    handleUserSwitch(msg.arg1);
                } else if (msg.what == TOGGLE_STATE_CHANGED) {
                    name = "handleToggleStateChanged";
                    handleToggleStateChanged(msg.arg1 != 0);
                } else if (msg.what == SCAN_STATE_CHANGED) {
                    name = "handleScanStateChanged";
                    handleScanStateChanged(msg.arg1 != 0);
                } else if (msg.what == DESTROY) {
                    name = "handleDestroy";
                    handleDestroy();
                } else {
                    throw new IllegalArgumentException("Unknown msg: " + msg.what);
                }
            } catch (Throwable t) {
                final String error = "Error in " + name;
                Log.w(TAG, error, t);
                mHost.warn(error, t);
            }
        }
    }

    public interface Callback {
        void onStateChanged(State state);
        void onShowDetail(boolean show);
        void onToggleStateChanged(boolean state);
        void onScanStateChanged(boolean state);
    }

    public interface Host {
        void startSettingsActivity(Intent intent);
        void warn(String message, Throwable t);
        void collapsePanels();
        Looper getLooper();
        Context getContext();
        Collection<QSTile<?>> getTiles();
        void setCallback(Callback callback);
        BluetoothController getBluetoothController();
        LocationController getLocationController();
        RotationLockController getRotationLockController();
        NetworkController getNetworkController();
        ZenModeController getZenModeController();
        HotspotController getHotspotController();
        CastController getCastController();
        FlashlightController getFlashlightController();
        KeyguardMonitor getKeyguardMonitor();

        public interface Callback {
            void onTilesChanged();
        }
    }

    public static class State {
        public boolean visible;
        public int iconId;
        public Drawable icon;
        public String label;
        public String contentDescription;

        public boolean copyTo(State other) {
            if (other == null) throw new IllegalArgumentException();
            if (!other.getClass().equals(getClass())) throw new IllegalArgumentException();
            final boolean changed = other.visible != visible
                    || other.iconId != iconId
                    || !Objects.equals(other.icon, icon)
                    || !Objects.equals(other.label, label)
                    || !Objects.equals(other.contentDescription, contentDescription);
            other.visible = visible;
            other.iconId = iconId;
            other.icon = icon;
            other.label = label;
            other.contentDescription = contentDescription;
            return changed;
        }

        @Override
        public String toString() {
            return toStringBuilder().toString();
        }

        protected StringBuilder toStringBuilder() {
            final StringBuilder sb = new StringBuilder(  getClass().getSimpleName()).append('[');
            sb.append("visible=").append(visible);
            sb.append(",iconId=").append(iconId);
            sb.append(",icon=").append(icon);
            sb.append(",label=").append(label);
            sb.append(",contentDescription=").append(contentDescription);
            return sb.append(']');
        }
    }

    public static class BooleanState extends State {
        public boolean value;

        @Override
        public boolean copyTo(State other) {
            final BooleanState o = (BooleanState) other;
            final boolean changed = super.copyTo(other) || o.value != value;
            o.value = value;
            return changed;
        }

        @Override
        protected StringBuilder toStringBuilder() {
            final StringBuilder rt = super.toStringBuilder();
            rt.insert(rt.length() - 1, ",value=" + value);
            return rt;
        }
    }

    public static final class SignalState extends State {
        public boolean enabled;
        public boolean connected;
        public boolean activityIn;
        public boolean activityOut;
        public int overlayIconId;
        public boolean filter;

        @Override
        public boolean copyTo(State other) {
            final SignalState o = (SignalState) other;
            final boolean changed = o.enabled != enabled
                    || o.connected != connected || o.activityIn != activityIn
                    || o.activityOut != activityOut
                    || o.overlayIconId != overlayIconId;
            o.enabled = enabled;
            o.connected = connected;
            o.activityIn = activityIn;
            o.activityOut = activityOut;
            o.overlayIconId = overlayIconId;
            o.filter = filter;
            return super.copyTo(other) || changed;
        }

        @Override
        protected StringBuilder toStringBuilder() {
            final StringBuilder rt = super.toStringBuilder();
            rt.insert(rt.length() - 1, ",enabled=" + enabled);
            rt.insert(rt.length() - 1, ",connected=" + connected);
            rt.insert(rt.length() - 1, ",activityIn=" + activityIn);
            rt.insert(rt.length() - 1, ",activityOut=" + activityOut);
            rt.insert(rt.length() - 1, ",overlayIconId=" + overlayIconId);
            rt.insert(rt.length() - 1, ",filter=" + filter);
            return rt;
        }
    }
}
