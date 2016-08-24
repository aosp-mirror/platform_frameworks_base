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

import android.app.ActivityManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.ArraySet;
import android.util.Log;
import android.util.SparseArray;
import android.view.View;
import android.view.ViewGroup;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.MetricsProto.MetricsEvent;
import com.android.settingslib.RestrictedLockUtils;
import com.android.systemui.qs.QSTile.State;
import com.android.systemui.qs.external.TileServices;
import com.android.systemui.statusbar.phone.ManagedProfileController;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.BluetoothController;
import com.android.systemui.statusbar.policy.CastController;
import com.android.systemui.statusbar.policy.NightModeController;
import com.android.systemui.statusbar.policy.FlashlightController;
import com.android.systemui.statusbar.policy.HotspotController;
import com.android.systemui.statusbar.policy.KeyguardMonitor;
import com.android.systemui.statusbar.policy.Listenable;
import com.android.systemui.statusbar.policy.LocationController;
import com.android.systemui.statusbar.policy.NetworkController;
import com.android.systemui.statusbar.policy.RotationLockController;
import com.android.systemui.statusbar.policy.UserInfoController;
import com.android.systemui.statusbar.policy.UserSwitcherController;
import com.android.systemui.statusbar.policy.ZenModeController;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Objects;

import static com.android.settingslib.RestrictedLockUtils.EnforcedAdmin;

/**
 * Base quick-settings tile, extend this to create a new tile.
 *
 * State management done on a looper provided by the host.  Tiles should update state in
 * handleUpdateState.  Callbacks affecting state should use refreshState to trigger another
 * state update pass on tile looper.
 */
public abstract class QSTile<TState extends State> {
    protected final String TAG = "Tile." + getClass().getSimpleName();
    protected static final boolean DEBUG = Log.isLoggable("Tile", Log.DEBUG);

    protected final Host mHost;
    protected final Context mContext;
    protected final H mHandler;
    protected final Handler mUiHandler = new Handler(Looper.getMainLooper());
    private final ArraySet<Object> mListeners = new ArraySet<>();

    private final ArrayList<Callback> mCallbacks = new ArrayList<>();
    protected TState mState = newTileState();
    private TState mTmpState = newTileState();
    private boolean mAnnounceNextStateChange;

    private String mTileSpec;

    public abstract TState newTileState();
    abstract protected void handleClick();
    abstract protected void handleUpdateState(TState state, Object arg);

    /**
     * Declare the category of this tile.
     *
     * Categories are defined in {@link com.android.internal.logging.MetricsProto.MetricsEvent}
     * by editing frameworks/base/proto/src/metrics_constants.proto.
     */
    abstract public int getMetricsCategory();

    protected QSTile(Host host) {
        mHost = host;
        mContext = host.getContext();
        mHandler = new H(host.getLooper());
    }

    /**
     * Adds or removes a listening client for the tile. If the tile has one or more
     * listening client it will go into the listening state.
     */
    public void setListening(Object listener, boolean listening) {
        if (listening) {
            if (mListeners.add(listener) && mListeners.size() == 1) {
                if (DEBUG) Log.d(TAG, "setListening " + true);
                mHandler.obtainMessage(H.SET_LISTENING, 1, 0).sendToTarget();
            }
        } else {
            if (mListeners.remove(listener) && mListeners.size() == 0) {
                if (DEBUG) Log.d(TAG, "setListening " + false);
                mHandler.obtainMessage(H.SET_LISTENING, 0, 0).sendToTarget();
            }
        }
    }

    public String getTileSpec() {
        return mTileSpec;
    }

    public void setTileSpec(String tileSpec) {
        mTileSpec = tileSpec;
    }

    public Host getHost() {
        return mHost;
    }

    public QSIconView createTileView(Context context) {
        return new QSIconView(context);
    }

    public DetailAdapter getDetailAdapter() {
        return null; // optional
    }

    /**
     * Is a startup check whether this device currently supports this tile.
     * Should not be used to conditionally hide tiles.  Only checked on tile
     * creation or whether should be shown in edit screen.
     */
    public boolean isAvailable() {
        return true;
    }

    public interface DetailAdapter {
        CharSequence getTitle();
        Boolean getToggleState();
        View createDetailView(Context context, View convertView, ViewGroup parent);
        Intent getSettingsIntent();
        void setToggleState(boolean state);
        int getMetricsCategory();
    }

    // safe to call from any thread

    public void addCallback(Callback callback) {
        mHandler.obtainMessage(H.ADD_CALLBACK, callback).sendToTarget();
    }

    public void removeCallback(Callback callback) {
        mHandler.obtainMessage(H.REMOVE_CALLBACK, callback).sendToTarget();
    }

    public void removeCallbacks() {
        mHandler.sendEmptyMessage(H.REMOVE_CALLBACKS);
    }

    public void click() {
        mHandler.sendEmptyMessage(H.CLICK);
    }

    public void secondaryClick() {
        mHandler.sendEmptyMessage(H.SECONDARY_CLICK);
    }

    public void longClick() {
        mHandler.sendEmptyMessage(H.LONG_CLICK);
    }

    public void showDetail(boolean show) {
        mHandler.obtainMessage(H.SHOW_DETAIL, show ? 1 : 0, 0).sendToTarget();
    }

    public final void refreshState() {
        refreshState(null);
    }

    protected final void refreshState(Object arg) {
        mHandler.obtainMessage(H.REFRESH_STATE, arg).sendToTarget();
    }

    public final void clearState() {
        mHandler.sendEmptyMessage(H.CLEAR_STATE);
    }

    public void userSwitch(int newUserId) {
        mHandler.obtainMessage(H.USER_SWITCH, newUserId, 0).sendToTarget();
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

    public void setDetailListening(boolean listening) {
        // optional
    }

    // call only on tile worker looper

    private void handleAddCallback(Callback callback) {
        mCallbacks.add(callback);
        callback.onStateChanged(mState);
    }

    private void handleRemoveCallback(Callback callback) {
        mCallbacks.remove(callback);
    }

    private void handleRemoveCallbacks() {
        mCallbacks.clear();
    }

    protected void handleSecondaryClick() {
        // Default to normal click.
        handleClick();
    }

    protected void handleLongClick() {
        MetricsLogger.action(mContext, MetricsEvent.ACTION_QS_LONG_PRESS, getTileSpec());
        mHost.startActivityDismissingKeyguard(getLongClickIntent());
    }

    public abstract Intent getLongClickIntent();

    protected void handleClearState() {
        mTmpState = newTileState();
        mState = newTileState();
    }

    protected void handleRefreshState(Object arg) {
        handleUpdateState(mTmpState, arg);
        final boolean changed = mTmpState.copyTo(mState);
        if (changed) {
            handleStateChanged();
        }
    }

    private void handleStateChanged() {
        boolean delayAnnouncement = shouldAnnouncementBeDelayed();
        if (mCallbacks.size() != 0) {
            for (int i = 0; i < mCallbacks.size(); i++) {
                mCallbacks.get(i).onStateChanged(mState);
            }
            if (mAnnounceNextStateChange && !delayAnnouncement) {
                String announcement = composeChangeAnnouncement();
                if (announcement != null) {
                    mCallbacks.get(0).onAnnouncementRequested(announcement);
                }
            }
        }
        mAnnounceNextStateChange = mAnnounceNextStateChange && delayAnnouncement;
    }

    protected boolean shouldAnnouncementBeDelayed() {
        return false;
    }

    protected String composeChangeAnnouncement() {
        return null;
    }

    private void handleShowDetail(boolean show) {
        for (int i = 0; i < mCallbacks.size(); i++) {
            mCallbacks.get(i).onShowDetail(show);
        }
    }

    private void handleToggleStateChanged(boolean state) {
        for (int i = 0; i < mCallbacks.size(); i++) {
            mCallbacks.get(i).onToggleStateChanged(state);
        }
    }

    private void handleScanStateChanged(boolean state) {
        for (int i = 0; i < mCallbacks.size(); i++) {
            mCallbacks.get(i).onScanStateChanged(state);
        }
    }

    protected void handleUserSwitch(int newUserId) {
        handleRefreshState(null);
    }

    protected abstract void setListening(boolean listening);

    protected void handleDestroy() {
        setListening(false);
        mCallbacks.clear();
    }

    protected void checkIfRestrictionEnforcedByAdminOnly(State state, String userRestriction) {
        EnforcedAdmin admin = RestrictedLockUtils.checkIfRestrictionEnforced(mContext,
                userRestriction, ActivityManager.getCurrentUser());
        if (admin != null && !RestrictedLockUtils.hasBaseUserRestriction(mContext,
                userRestriction, ActivityManager.getCurrentUser())) {
            state.disabledByPolicy = true;
            state.enforcedAdmin = admin;
        } else {
            state.disabledByPolicy = false;
            state.enforcedAdmin = null;
        }
    }

    public abstract CharSequence getTileLabel();

    protected final class H extends Handler {
        private static final int ADD_CALLBACK = 1;
        private static final int CLICK = 2;
        private static final int SECONDARY_CLICK = 3;
        private static final int LONG_CLICK = 4;
        private static final int REFRESH_STATE = 5;
        private static final int SHOW_DETAIL = 6;
        private static final int USER_SWITCH = 7;
        private static final int TOGGLE_STATE_CHANGED = 8;
        private static final int SCAN_STATE_CHANGED = 9;
        private static final int DESTROY = 10;
        private static final int CLEAR_STATE = 11;
        private static final int REMOVE_CALLBACKS = 12;
        private static final int REMOVE_CALLBACK = 13;
        private static final int SET_LISTENING = 14;

        private H(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            String name = null;
            try {
                if (msg.what == ADD_CALLBACK) {
                    name = "handleAddCallback";
                    handleAddCallback((QSTile.Callback) msg.obj);
                } else if (msg.what == REMOVE_CALLBACKS) {
                    name = "handleRemoveCallbacks";
                    handleRemoveCallbacks();
                } else if (msg.what == REMOVE_CALLBACK) {
                    name = "handleRemoveCallback";
                    handleRemoveCallback((QSTile.Callback) msg.obj);
                } else if (msg.what == CLICK) {
                    name = "handleClick";
                    if (mState.disabledByPolicy) {
                        Intent intent = RestrictedLockUtils.getShowAdminSupportDetailsIntent(
                                mContext, mState.enforcedAdmin);
                        mHost.startActivityDismissingKeyguard(intent);
                    } else {
                        mAnnounceNextStateChange = true;
                        handleClick();
                    }
                } else if (msg.what == SECONDARY_CLICK) {
                    name = "handleSecondaryClick";
                    handleSecondaryClick();
                } else if (msg.what == LONG_CLICK) {
                    name = "handleLongClick";
                    handleLongClick();
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
                } else if (msg.what == CLEAR_STATE) {
                    name = "handleClearState";
                    handleClearState();
                } else if (msg.what == SET_LISTENING) {
                    name = "setListening";
                    setListening(msg.arg1 != 0);
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
        void onAnnouncementRequested(CharSequence announcement);
    }

    public interface Host {
        void startActivityDismissingKeyguard(Intent intent);
        void startActivityDismissingKeyguard(PendingIntent intent);
        void startRunnableDismissingKeyguard(Runnable runnable);
        void warn(String message, Throwable t);
        void collapsePanels();
        void animateToggleQSExpansion();
        void openPanels();
        Looper getLooper();
        Context getContext();
        Collection<QSTile<?>> getTiles();
        void addCallback(Callback callback);
        void removeCallback(Callback callback);
        BluetoothController getBluetoothController();
        LocationController getLocationController();
        RotationLockController getRotationLockController();
        NetworkController getNetworkController();
        ZenModeController getZenModeController();
        HotspotController getHotspotController();
        CastController getCastController();
        FlashlightController getFlashlightController();
        KeyguardMonitor getKeyguardMonitor();
        UserSwitcherController getUserSwitcherController();
        UserInfoController getUserInfoController();
        BatteryController getBatteryController();
        TileServices getTileServices();
        NightModeController getNightModeController();
        void removeTile(String tileSpec);
        ManagedProfileController getManagedProfileController();


        public interface Callback {
            void onTilesChanged();
        }
    }

    public static abstract class Icon {
        abstract public Drawable getDrawable(Context context);

        public Drawable getInvisibleDrawable(Context context) {
            return getDrawable(context);
        }

        @Override
        public int hashCode() {
            return Icon.class.hashCode();
        }

        public int getPadding() {
            return 0;
        }
    }

    public static class DrawableIcon extends Icon {
        protected final Drawable mDrawable;

        public DrawableIcon(Drawable drawable) {
            mDrawable = drawable;
        }

        @Override
        public Drawable getDrawable(Context context) {
            return mDrawable;
        }

        @Override
        public Drawable getInvisibleDrawable(Context context) {
            return mDrawable;
        }
    }

    public static class ResourceIcon extends Icon {
        private static final SparseArray<Icon> ICONS = new SparseArray<Icon>();

        protected final int mResId;

        private ResourceIcon(int resId) {
            mResId = resId;
        }

        public static Icon get(int resId) {
            Icon icon = ICONS.get(resId);
            if (icon == null) {
                icon = new ResourceIcon(resId);
                ICONS.put(resId, icon);
            }
            return icon;
        }

        @Override
        public Drawable getDrawable(Context context) {
            return context.getDrawable(mResId);
        }

        @Override
        public Drawable getInvisibleDrawable(Context context) {
            return context.getDrawable(mResId);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof ResourceIcon && ((ResourceIcon) o).mResId == mResId;
        }

        @Override
        public String toString() {
            return String.format("ResourceIcon[resId=0x%08x]", mResId);
        }
    }

    protected class AnimationIcon extends ResourceIcon {
        private final int mAnimatedResId;

        public AnimationIcon(int resId, int staticResId) {
            super(staticResId);
            mAnimatedResId = resId;
        }

        @Override
        public Drawable getDrawable(Context context) {
            // workaround: get a clean state for every new AVD
            return context.getDrawable(mAnimatedResId).getConstantState().newDrawable();
        }
    }

    public static class State {
        public Icon icon;
        public CharSequence label;
        public CharSequence contentDescription;
        public CharSequence dualLabelContentDescription;
        public CharSequence minimalContentDescription;
        public boolean autoMirrorDrawable = true;
        public boolean disabledByPolicy;
        public EnforcedAdmin enforcedAdmin;
        public String minimalAccessibilityClassName;
        public String expandedAccessibilityClassName;

        public boolean copyTo(State other) {
            if (other == null) throw new IllegalArgumentException();
            if (!other.getClass().equals(getClass())) throw new IllegalArgumentException();
            final boolean changed = !Objects.equals(other.icon, icon)
                    || !Objects.equals(other.label, label)
                    || !Objects.equals(other.contentDescription, contentDescription)
                    || !Objects.equals(other.autoMirrorDrawable, autoMirrorDrawable)
                    || !Objects.equals(other.dualLabelContentDescription,
                    dualLabelContentDescription)
                    || !Objects.equals(other.minimalContentDescription,
                    minimalContentDescription)
                    || !Objects.equals(other.minimalAccessibilityClassName,
                    minimalAccessibilityClassName)
                    || !Objects.equals(other.expandedAccessibilityClassName,
                    expandedAccessibilityClassName)
                    || !Objects.equals(other.disabledByPolicy, disabledByPolicy)
                    || !Objects.equals(other.enforcedAdmin, enforcedAdmin);
            other.icon = icon;
            other.label = label;
            other.contentDescription = contentDescription;
            other.dualLabelContentDescription = dualLabelContentDescription;
            other.minimalContentDescription = minimalContentDescription;
            other.minimalAccessibilityClassName = minimalAccessibilityClassName;
            other.expandedAccessibilityClassName = expandedAccessibilityClassName;
            other.autoMirrorDrawable = autoMirrorDrawable;
            other.disabledByPolicy = disabledByPolicy;
            if (enforcedAdmin == null) {
                other.enforcedAdmin = null;
            } else if (other.enforcedAdmin == null) {
                other.enforcedAdmin = new EnforcedAdmin(enforcedAdmin);
            } else {
                enforcedAdmin.copyTo(other.enforcedAdmin);
            }
            return changed;
        }

        @Override
        public String toString() {
            return toStringBuilder().toString();
        }

        protected StringBuilder toStringBuilder() {
            final StringBuilder sb = new StringBuilder(getClass().getSimpleName()).append('[');
            sb.append(",icon=").append(icon);
            sb.append(",label=").append(label);
            sb.append(",contentDescription=").append(contentDescription);
            sb.append(",dualLabelContentDescription=").append(dualLabelContentDescription);
            sb.append(",minimalContentDescription=").append(minimalContentDescription);
            sb.append(",minimalAccessibilityClassName=").append(minimalAccessibilityClassName);
            sb.append(",expandedAccessibilityClassName=").append(expandedAccessibilityClassName);
            sb.append(",autoMirrorDrawable=").append(autoMirrorDrawable);
            sb.append(",disabledByPolicy=").append(disabledByPolicy);
            sb.append(",enforcedAdmin=").append(enforcedAdmin);
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

    public static class AirplaneBooleanState extends BooleanState {
        public boolean isAirplaneMode;

        @Override
        public boolean copyTo(State other) {
            final AirplaneBooleanState o = (AirplaneBooleanState) other;
            final boolean changed = super.copyTo(other) || o.isAirplaneMode != isAirplaneMode;
            o.isAirplaneMode = isAirplaneMode;
            return changed;
        }
    }

    public static final class SignalState extends BooleanState {
        public boolean connected;
        public boolean activityIn;
        public boolean activityOut;
        public int overlayIconId;
        public boolean filter;
        public boolean isOverlayIconWide;

        @Override
        public boolean copyTo(State other) {
            final SignalState o = (SignalState) other;
            final boolean changed = o.connected != connected || o.activityIn != activityIn
                    || o.activityOut != activityOut
                    || o.overlayIconId != overlayIconId
                    || o.isOverlayIconWide != isOverlayIconWide;
            o.connected = connected;
            o.activityIn = activityIn;
            o.activityOut = activityOut;
            o.overlayIconId = overlayIconId;
            o.filter = filter;
            o.isOverlayIconWide = isOverlayIconWide;
            return super.copyTo(other) || changed;
        }

        @Override
        protected StringBuilder toStringBuilder() {
            final StringBuilder rt = super.toStringBuilder();
            rt.insert(rt.length() - 1, ",connected=" + connected);
            rt.insert(rt.length() - 1, ",activityIn=" + activityIn);
            rt.insert(rt.length() - 1, ",activityOut=" + activityOut);
            rt.insert(rt.length() - 1, ",overlayIconId=" + overlayIconId);
            rt.insert(rt.length() - 1, ",filter=" + filter);
            rt.insert(rt.length() - 1, ",wideOverlayIcon=" + isOverlayIconWide);
            return rt;
        }
    }
}
