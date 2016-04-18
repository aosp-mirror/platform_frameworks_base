package com.android.systemui.statusbar.car;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadsetClient;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothProfile.ServiceListener;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.ImageView;
import com.android.systemui.R;
import com.android.systemui.statusbar.ScalingDrawableWrapper;

/**
 * Controller that monitors signal strength for a device that is connected via bluetooth.
 */
public class ConnectedDeviceSignalController extends BroadcastReceiver {
    private final static String TAG = "DeviceSignalCtlr";

    /**
     * The value that indicates if a network is unavailable. This value is according ot the
     * Bluetooth HFP 1.5 spec, which indicates this value is one of two: 0 or 1. These stand
     * for network unavailable and available respectively.
     */
    private static final int NETWORK_UNAVAILABLE = 0;
    private static final int NETWORK_UNAVAILABLE_ICON_ID = R.drawable.stat_sys_signal_null;

    /**
     * All possible signal strength icons. According to the Bluetooth HFP 1.5 specification,
     * signal strength is indicated by a value from 1-5, where these values represent the following:
     *
     * <p>0%% - 0, 1-25%% - 1, 26-50%% - 2, 51-75%% - 3, 76-99%% - 4, 100%% - 5
     *
     * <p>As a result, these are treated as an index into this array for the corresponding icon.
     * Note that the icon is the same for 0 and 1.
     */
    private static final int[] SIGNAL_STRENGTH_ICONS = {
            R.drawable.stat_sys_signal_0_fully,
            R.drawable.stat_sys_signal_0_fully,
            R.drawable.stat_sys_signal_1_fully,
            R.drawable.stat_sys_signal_2_fully,
            R.drawable.stat_sys_signal_3_fully,
            R.drawable.stat_sys_signal_4_fully,
    };

    private static final int INVALID_SIGNAL = -1;

    private final BluetoothAdapter mAdapter = BluetoothAdapter.getDefaultAdapter();
    private final Context mContext;
    private final View mSignalsView;

    private final ImageView mNetworkSignalView;

    private final float mIconScaleFactor;

    private BluetoothHeadsetClient mBluetoothHeadsetClient;

    public ConnectedDeviceSignalController(Context context, View signalsView) {
        mContext = context;
        mSignalsView = signalsView;

        mNetworkSignalView = (ImageView)
                mSignalsView.findViewById(R.id.connected_device_network_signal);

        mAdapter.getProfileProxy(context.getApplicationContext(), mHfpServiceListener,
                BluetoothProfile.HEADSET_CLIENT);

        TypedValue typedValue = new TypedValue();
        context.getResources().getValue(R.dimen.status_bar_icon_scale_factor, typedValue, true);
        mIconScaleFactor = typedValue.getFloat();
    }

    public void startListening() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothHeadsetClient.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothHeadsetClient.ACTION_AG_EVENT);
        mContext.registerReceiver(this, filter);
    }

    public void stopListening() {
        mContext.unregisterReceiver(this);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();

        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "onReceive(). action: " + action);
        }

        if (BluetoothHeadsetClient.ACTION_AG_EVENT.equals(action)) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Received ACTION_AG_EVENT");
            }

            processActionAgEvent(intent);
        } else if (BluetoothHeadsetClient.ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
            int newState = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1);

            if (Log.isLoggable(TAG, Log.DEBUG)) {
                int oldState = intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, -1);
                Log.d(TAG, "ACTION_CONNECTION_STATE_CHANGED event: "
                        + oldState + " -> " + newState);
            }
            BluetoothDevice device =
                    (BluetoothDevice)intent.getExtra(BluetoothDevice.EXTRA_DEVICE);
            updateViewVisibility(device, newState);
        }
    }

    /**
     * Processes an {@link Intent} that had an action of
     * {@link BluetoothHeadsetClient#ACTION_AG_EVENT}.
     */
    private void processActionAgEvent(Intent intent) {
        int networkStatus = intent.getIntExtra(BluetoothHeadsetClient.EXTRA_NETWORK_STATUS,
                INVALID_SIGNAL);
        if (networkStatus != INVALID_SIGNAL) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "EXTRA_NETWORK_STATUS: " + " " + networkStatus);
            }

            if (networkStatus == NETWORK_UNAVAILABLE) {
                setNetworkSignalIcon(NETWORK_UNAVAILABLE_ICON_ID);
            }
        }

        int signalStrength = intent.getIntExtra(
                BluetoothHeadsetClient.EXTRA_NETWORK_SIGNAL_STRENGTH, INVALID_SIGNAL);
        if (signalStrength != INVALID_SIGNAL) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "EXTRA_NETWORK_SIGNAL_STRENGTH: " + signalStrength);
            }

            setNetworkSignalIcon(SIGNAL_STRENGTH_ICONS[signalStrength]);
        }

        int roamingStatus = intent.getIntExtra(BluetoothHeadsetClient.EXTRA_NETWORK_ROAMING,
                INVALID_SIGNAL);
        if (roamingStatus != INVALID_SIGNAL) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "EXTRA_NETWORK_ROAMING: " + roamingStatus);
            }
        }
    }

    private void setNetworkSignalIcon(int iconId) {
        // Setting the icon on a child view of mSignalView, so toggle this container visible.
        mSignalsView.setVisibility(View.VISIBLE);

        // Using mNetworkSignalView's context to get the Drawable in order to preserve the theme.
        Drawable icon = mNetworkSignalView.getContext().getDrawable(iconId);

        mNetworkSignalView.setImageDrawable(new ScalingDrawableWrapper(icon, mIconScaleFactor));
        mNetworkSignalView.setVisibility(View.VISIBLE);
    }

    private void updateViewVisibility(BluetoothDevice device, int newState) {
        if (newState == BluetoothProfile.STATE_CONNECTED) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Device connected");
            }

            if (mBluetoothHeadsetClient == null || device == null) {
                return;
            }

            // Check if battery information is available and immediately update.
            Bundle featuresBundle = mBluetoothHeadsetClient.getCurrentAgEvents(device);
            if (featuresBundle == null) {
                return;
            }

            int signalStrength = featuresBundle.getInt(
                    BluetoothHeadsetClient.EXTRA_NETWORK_SIGNAL_STRENGTH, INVALID_SIGNAL);
            if (signalStrength != INVALID_SIGNAL) {
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "EXTRA_NETWORK_SIGNAL_STRENGTH: " + signalStrength);
                }

                setNetworkSignalIcon(SIGNAL_STRENGTH_ICONS[signalStrength]);
            }
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Device disconnected");
            }

            mNetworkSignalView.setVisibility(View.GONE);
            mSignalsView.setVisibility(View.GONE);
        }
    }

    private final ServiceListener mHfpServiceListener = new ServiceListener() {
        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (profile == BluetoothProfile.HEADSET_CLIENT) {
                mBluetoothHeadsetClient = (BluetoothHeadsetClient) proxy;
            }
        }

        @Override
        public void onServiceDisconnected(int profile) {
            if (profile == BluetoothProfile.HEADSET_CLIENT) {
                mBluetoothHeadsetClient = null;
            }
        }
    };
}
