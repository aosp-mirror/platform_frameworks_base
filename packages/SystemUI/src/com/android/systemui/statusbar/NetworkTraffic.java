/**
 * Copyright (C) 2017-2018 The LineageOS project
 * Copyright (C) 2019 The PixelExperience project
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

package com.android.systemui.statusbar;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.NetworkStats;
import android.net.TrafficStats;
import android.os.Handler;
import android.os.INetworkManagementService;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;

import android.provider.Settings;

import com.android.internal.util.custom.cutout.CutoutUtils;
import com.android.systemui.R;

import java.util.HashMap;

public class NetworkTraffic extends TextView {
    private static final String TAG = "NetworkTraffic";

    private static final boolean DEBUG = false;

    private static final int MODE_DISABLED = 0;
    private static final int MODE_UPSTREAM_ONLY = 1;
    private static final int MODE_DOWNSTREAM_ONLY = 2;
    private static final int MODE_UPSTREAM_AND_DOWNSTREAM = 3;

    private static final int MESSAGE_TYPE_PERIODIC_REFRESH = 0;
    private static final int MESSAGE_TYPE_UPDATE_VIEW = 1;

    private static final int REFRESH_INTERVAL = 2000;

    private static final int UNITS_KILOBITS = 0;
    private static final int UNITS_MEGABITS = 1;
    private static final int UNITS_KILOBYTES = 2;
    private static final int UNITS_MEGABYTES = 3;

    // Thresholds themselves are always defined in kbps
    private static final long AUTOHIDE_THRESHOLD_KILOBITS  = 10;
    private static final long AUTOHIDE_THRESHOLD_MEGABITS  = 100;
    private static final long AUTOHIDE_THRESHOLD_KILOBYTES = 8;
    private static final long AUTOHIDE_THRESHOLD_MEGABYTES = 80;

    private int mMode = MODE_DISABLED;
    private boolean mNetworkTrafficIsVisible;
    private long mTxKbps;
    private long mRxKbps;
    private long mLastTxBytes;
    private long mLastRxBytes;
    private long mLastUpdateTime;
    private int mTextSizeSingle;
    private int mTextSizeMulti;
    private boolean mAutoHide;
    private long mAutoHideThreshold;
    private int mUnits;
    private boolean mShowUnits;
    private int mDarkModeFillColor;
    private int mLightModeFillColor;
    private int mIconTint = Color.WHITE;
    private SettingsObserver mObserver;
    private Drawable mDrawable;

    // Network tracking related variables
    final private ConnectivityManager mConnectivityManager;
    final private HashMap<Network, NetworkState> mNetworkMap = new HashMap<>();
    // Used to indicate that the set of sources contributing
    // to current stats have changed.
    private boolean mNetworksChanged = true;

    public class NetworkState {
        public NetworkCapabilities mNetworkCapabilities;
        public LinkProperties mLinkProperties;

        public NetworkState(NetworkCapabilities networkCapabilities,
                LinkProperties linkProperties) {
            mNetworkCapabilities = networkCapabilities;
            mLinkProperties = linkProperties;
        }
    };

    private INetworkManagementService mNetworkManagementService;

    public NetworkTraffic(Context context) {
        this(context, null);
    }

    public NetworkTraffic(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public NetworkTraffic(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        mNetworkManagementService = INetworkManagementService.Stub.asInterface(
                    ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE));

        final Resources resources = getResources();
        mTextSizeSingle = resources.getDimensionPixelSize(R.dimen.net_traffic_single_text_size);
        mTextSizeMulti = resources.getDimensionPixelSize(R.dimen.net_traffic_multi_text_size);

        mNetworkTrafficIsVisible = false;

        mObserver = new SettingsObserver(mTrafficHandler);

        mConnectivityManager = getContext().getSystemService(ConnectivityManager.class);
        final NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build();
        mConnectivityManager.registerNetworkCallback(request, mNetworkCallback);
    }

    private CustomStatusBarItem.DarkReceiver mDarkReceiver =
            new CustomStatusBarItem.DarkReceiver() {
        public void onDarkChanged(Rect area, float darkIntensity, int tint) {
            mIconTint = tint;
            setTextColor(mIconTint);
            updateTrafficDrawableColor();
        }
        public void setFillColors(int darkColor, int lightColor) {
            mDarkModeFillColor = darkColor;
            mLightModeFillColor = lightColor;
        }
    };

    private CustomStatusBarItem.VisibilityReceiver mVisibilityReceiver =
            new CustomStatusBarItem.VisibilityReceiver() {
        public void onVisibilityChanged(boolean isVisible) {
            if (mNetworkTrafficIsVisible != isVisible) {
                mNetworkTrafficIsVisible = isVisible;
                updateViewState();
            }
        }
    };

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        CustomStatusBarItem.Manager manager =
                CustomStatusBarItem.findManager((View) this);
        manager.addDarkReceiver(mDarkReceiver);
        manager.addVisibilityReceiver(mVisibilityReceiver);

        mContext.registerReceiver(mIntentReceiver,
                new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
        mObserver.observe();
        updateSettings();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mContext.unregisterReceiver(mIntentReceiver);
        mObserver.unobserve();
    }

    private Handler mTrafficHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            final long now = SystemClock.elapsedRealtime();
            final long timeDelta = now - mLastUpdateTime; /* ms */
            if (msg.what == MESSAGE_TYPE_PERIODIC_REFRESH
                    && timeDelta >= REFRESH_INTERVAL * 0.95f) {
                // Sum tx and rx bytes from all sources of interest
                long txBytes = 0;
                long rxBytes = 0;
                // Add interface stats
                for (NetworkState state : mNetworkMap.values()) {
                    final String iface = state.mLinkProperties.getInterfaceName();
                    if (iface == null) {
                        continue;
                    }
                    final long ifaceTxBytes = TrafficStats.getTxBytes(iface);
                    final long ifaceRxBytes = TrafficStats.getRxBytes(iface);
                    if (DEBUG) {
                        Log.d(TAG, "adding stats from interface " + iface
                                + " txbytes " + ifaceTxBytes + " rxbytes " + ifaceRxBytes);
                    }
                    txBytes += ifaceTxBytes;
                    rxBytes += ifaceRxBytes;
                }

                // Add tether hw offload counters since these are
                // not included in netd interface stats.
                final TetheringStats tetheringStats = getOffloadTetheringStats();
                txBytes += tetheringStats.txBytes;
                rxBytes += tetheringStats.rxBytes;

                if (DEBUG) {
                    Log.d(TAG, "mNetworksChanged = " + mNetworksChanged);
                    Log.d(TAG, "tether hw offload txBytes: " + tetheringStats.txBytes
                            + " rxBytes: " + tetheringStats.rxBytes);
                }

                final long txBytesDelta = txBytes - mLastTxBytes;
                final long rxBytesDelta = rxBytes - mLastRxBytes;

                if (!mNetworksChanged && timeDelta > 0 && txBytesDelta >= 0 && rxBytesDelta >= 0) {
                    mTxKbps = (long) (txBytesDelta * 8f / 1000f / (timeDelta / 1000f));
                    mRxKbps = (long) (rxBytesDelta * 8f / 1000f / (timeDelta / 1000f));
                } else if (mNetworksChanged) {
                    mTxKbps = 0;
                    mRxKbps = 0;
                    mNetworksChanged = false;
                }
                mLastTxBytes = txBytes;
                mLastRxBytes = rxBytes;
                mLastUpdateTime = now;
            }

            final boolean enabled = mMode != MODE_DISABLED && isConnectionAvailable();
            final boolean showUpstream =
                    mMode == MODE_UPSTREAM_ONLY || mMode == MODE_UPSTREAM_AND_DOWNSTREAM;
            final boolean showDownstream =
                    mMode == MODE_DOWNSTREAM_ONLY || mMode == MODE_UPSTREAM_AND_DOWNSTREAM;
            final boolean shouldHide = mAutoHide && (!showUpstream || mTxKbps < mAutoHideThreshold)
                    && (!showDownstream || mRxKbps < mAutoHideThreshold);

            if (!enabled || shouldHide) {
                setText("");
                setVisibility(GONE);
            } else {
                // Get information for uplink ready so the line return can be added
                StringBuilder output = new StringBuilder();
                if (showUpstream) {
                    output.append(formatOutput(mTxKbps));
                }

                // Ensure text size is where it needs to be
                int textSize;
                if (showUpstream && showDownstream) {
                    output.append("\n");
                    textSize = mTextSizeMulti;
                } else {
                    textSize = mTextSizeSingle;
                }

                // Add information for downlink if it's called for
                if (showDownstream) {
                    output.append(formatOutput(mRxKbps));
                }

                // Update view if there's anything new to show
                if (!output.toString().contentEquals(getText())) {
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, (float) textSize);
                    setText(output.toString());
                }
                setVisibility(VISIBLE);
            }

            // Schedule periodic refresh
            mTrafficHandler.removeMessages(MESSAGE_TYPE_PERIODIC_REFRESH);
            if (enabled && mNetworkTrafficIsVisible) {
                mTrafficHandler.sendEmptyMessageDelayed(MESSAGE_TYPE_PERIODIC_REFRESH,
                        REFRESH_INTERVAL);
            }
        }

        private String formatOutput(long kbps) {
            final String value;
            final String unit;
            switch (mUnits) {
                case UNITS_KILOBITS:
                    value = String.format("%d", kbps);
                    unit = mContext.getString(R.string.kilobitspersecond_short);
                    break;
                case UNITS_MEGABITS:
                    value = String.format("%.1f", (float) kbps / 1000);
                    unit = mContext.getString(R.string.megabitspersecond_short);
                    break;
                case UNITS_KILOBYTES:
                    value = String.format("%d", kbps / 8);
                    unit = mContext.getString(R.string.kilobytespersecond_short);
                    break;
                case UNITS_MEGABYTES:
                    value = String.format("%.2f", (float) kbps / 8000);
                    unit = mContext.getString(R.string.megabytespersecond_short);
                    break;
                default:
                    value = "unknown";
                    unit = "unknown";
                    break;
            }

            if (mShowUnits) {
                return value + " " + unit;
            } else {
                return value;
            }
        }
    };

    private final BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ConnectivityManager.CONNECTIVITY_ACTION.equals(action)) {
                updateViewState();
            }
        }
    };

    class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.NETWORK_TRAFFIC_MODE),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.NETWORK_TRAFFIC_AUTOHIDE),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.NETWORK_TRAFFIC_UNITS),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.NETWORK_TRAFFIC_SHOW_UNITS),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.DISPLAY_CUTOUT_HIDDEN),
                    false, this, UserHandle.USER_ALL);
        }

        void unobserve() {
            mContext.getContentResolver().unregisterContentObserver(this);
        }

        @Override
        public void onChange(boolean selfChange) {
            updateSettings();
        }
    }

    private boolean isConnectionAvailable() {
        ConnectivityManager cm =
                (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        return cm.getActiveNetworkInfo() != null;
    }

    private class TetheringStats {
        long txBytes;
        long rxBytes;
    }

    private TetheringStats getOffloadTetheringStats() {
        TetheringStats tetheringStats = new TetheringStats();

        NetworkStats stats = null;
        try {
            // STATS_PER_UID returns hw offload and netd stats combined (as entry UID_TETHERING)
            // STATS_PER_IFACE returns only hw offload stats (as entry UID_ALL)
            stats = mNetworkManagementService.getNetworkStatsTethering(
                    NetworkStats.STATS_PER_IFACE);
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to call getNetworkStatsTethering: " + e);
        }
        if (stats == null) {
            // nothing we can do except return zero stats
            return tetheringStats;
        }

        NetworkStats.Entry entry = null;
        // Entries here are per tethered interface.
        // Counters persist even after tethering has been disabled.
        for (int i = 0; i < stats.size(); i++) {
            entry = stats.getValues(i, entry);
            if (DEBUG) {
                Log.d(TAG, "tethering stats entry: " + entry);
            }
            // hw offload tether stats are reported under UID_ALL.
            if (entry.uid == NetworkStats.UID_ALL) {
                tetheringStats.txBytes += entry.txBytes;
                tetheringStats.rxBytes += entry.rxBytes;
            }
        }
        return tetheringStats;
    }

    private boolean isNotchHidden(){
        return !CutoutUtils.hasCenteredCutout(mContext);
    }

    private void updateSettings() {
        ContentResolver resolver = mContext.getContentResolver();

        mMode = isNotchHidden() ? Settings.System.getIntForUser(resolver,
                Settings.System.NETWORK_TRAFFIC_MODE, 0, UserHandle.USER_CURRENT) : MODE_DISABLED;
        mAutoHide = Settings.System.getIntForUser(resolver,
                Settings.System.NETWORK_TRAFFIC_AUTOHIDE, 0, UserHandle.USER_CURRENT) == 1;
        mUnits = Settings.System.getIntForUser(resolver,
                Settings.System.NETWORK_TRAFFIC_UNITS, /* Mbps */ 1,
                UserHandle.USER_CURRENT);

        switch (mUnits) {
            case UNITS_KILOBITS:
                mAutoHideThreshold = AUTOHIDE_THRESHOLD_KILOBITS;
                break;
            case UNITS_MEGABITS:
                mAutoHideThreshold = AUTOHIDE_THRESHOLD_MEGABITS;
                break;
            case UNITS_KILOBYTES:
                mAutoHideThreshold = AUTOHIDE_THRESHOLD_KILOBYTES;
                break;
            case UNITS_MEGABYTES:
                mAutoHideThreshold = AUTOHIDE_THRESHOLD_MEGABYTES;
                break;
            default:
                mAutoHideThreshold = 0;
                break;
        }

        mShowUnits = Settings.System.getIntForUser(resolver,
                Settings.System.NETWORK_TRAFFIC_SHOW_UNITS, 1,
                UserHandle.USER_CURRENT) == 1;

        if (mMode != MODE_DISABLED) {
            updateTrafficDrawable();
        }
        updateViewState();
    }

    private void updateViewState() {
        mTrafficHandler.sendEmptyMessage(MESSAGE_TYPE_UPDATE_VIEW);
    }

    private void clearHandlerCallbacks() {
        mTrafficHandler.removeMessages(MESSAGE_TYPE_PERIODIC_REFRESH);
        mTrafficHandler.removeMessages(MESSAGE_TYPE_UPDATE_VIEW);
    }

    private void updateTrafficDrawable() {
        final int drawableResId;
        if (mMode == MODE_UPSTREAM_AND_DOWNSTREAM) {
            drawableResId = R.drawable.stat_sys_network_traffic_updown;
        } else if (mMode == MODE_UPSTREAM_ONLY) {
            drawableResId = R.drawable.stat_sys_network_traffic_up;
        } else if (mMode == MODE_DOWNSTREAM_ONLY) {
            drawableResId = R.drawable.stat_sys_network_traffic_down;
        } else {
            drawableResId = 0;
        }
        mDrawable = drawableResId != 0 ? getResources().getDrawable(drawableResId) : null;
        setCompoundDrawablesWithIntrinsicBounds(null, null, mDrawable, null);
        updateTrafficDrawableColor();
    }

    private void updateTrafficDrawableColor() {
        if (mDrawable != null) {
            mDrawable.setColorFilter(mIconTint, PorterDuff.Mode.MULTIPLY);
        }
    }

    private ConnectivityManager.NetworkCallback mNetworkCallback =
            new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(Network network) {
            mNetworkMap.put(network,
                    new NetworkState(mConnectivityManager.getNetworkCapabilities(network),
                    mConnectivityManager.getLinkProperties(network)));
            mNetworksChanged = true;
        }

        @Override
        public void onCapabilitiesChanged(Network network,
                NetworkCapabilities networkCapabilities) {
            if (mNetworkMap.containsKey(network)) {
                mNetworkMap.put(network, new NetworkState(networkCapabilities,
                        mConnectivityManager.getLinkProperties(network)));
                mNetworksChanged = true;
            }
        }

        @Override
        public void onLinkPropertiesChanged(Network network, LinkProperties linkProperties) {
            if (mNetworkMap.containsKey(network)) {
                mNetworkMap.put(network,
                        new NetworkState(mConnectivityManager.getNetworkCapabilities(network),
                        linkProperties));
                mNetworksChanged = true;
            }
        }

        @Override
        public void onLost(Network network) {
            mNetworkMap.remove(network);
            mNetworksChanged = true;
        }
    };
}