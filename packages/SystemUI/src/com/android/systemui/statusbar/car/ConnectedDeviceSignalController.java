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
import android.telephony.SignalStrength;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.ImageView;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.statusbar.ScalingDrawableWrapper;
import com.android.systemui.statusbar.phone.SignalDrawable;
import com.android.systemui.statusbar.policy.BluetoothController;

import static com.android.systemui.statusbar.phone.StatusBar.DEBUG;

/**
 * Controller that monitors signal strength for a device that is connected via bluetooth.
 */
public class ConnectedDeviceSignalController extends BroadcastReceiver implements
        BluetoothController.Callback {
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
            0,
            0,
            1,
            2,
            3,
            4,
    };

    private static final int INVALID_SIGNAL = -1;

    private final BluetoothAdapter mAdapter = BluetoothAdapter.getDefaultAdapter();
    private final Context mContext;
    private final BluetoothController mController;

    private final View mSignalsView;
    private final ImageView mNetworkSignalView;

    private final float mIconScaleFactor;
    private final SignalDrawable mSignalDrawable;

    private BluetoothHeadsetClient mBluetoothHeadsetClient;

    public ConnectedDeviceSignalController(Context context, View signalsView) {
        mContext = context;
        mController = Dependency.get(BluetoothController.class);

        mSignalsView = signalsView;
        mNetworkSignalView = (ImageView)
                mSignalsView.findViewById(R.id.connected_device_network_signal);

        TypedValue typedValue = new TypedValue();
        context.getResources().getValue(R.dimen.status_bar_icon_scale_factor, typedValue, true);
        mIconScaleFactor = typedValue.getFloat();
        mSignalDrawable = new SignalDrawable(mNetworkSignalView.getContext());
        mNetworkSignalView.setImageDrawable(
                new ScalingDrawableWrapper(mSignalDrawable, mIconScaleFactor));

        if (mAdapter == null) {
          return;
        }

        mAdapter.getProfileProxy(context.getApplicationContext(), mHfpServiceListener,
                BluetoothProfile.HEADSET_CLIENT);
    }

    public void startListening() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothHeadsetClient.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothHeadsetClient.ACTION_AG_EVENT);
        mContext.registerReceiver(this, filter);

        mController.addCallback(this);
    }

    public void stopListening() {
        mContext.unregisterReceiver(this);
        mController.removeCallback(this);
    }

    @Override
    public void onBluetoothDevicesChanged() {
        // Nothing to do here because this Controller is not displaying a list of possible
        // bluetooth devices.
    }

    @Override
    public void onBluetoothStateChange(boolean enabled) {
        if (DEBUG) {
            Log.d(TAG, "onBluetoothStateChange(). enabled: " + enabled);
        }

        // Only need to handle the case if bluetooth has been disabled, in which case the
        // signal indicators are hidden. If bluetooth has been enabled, then this class should
        // receive updates to the connection state via onReceive().
        if (!enabled) {
            mNetworkSignalView.setVisibility(View.GONE);
            mSignalsView.setVisibility(View.GONE);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();

        if (DEBUG) {
            Log.d(TAG, "onReceive(). action: " + action);
        }

        if (BluetoothHeadsetClient.ACTION_AG_EVENT.equals(action)) {
            if (DEBUG) {
                Log.d(TAG, "Received ACTION_AG_EVENT");
            }

            processActionAgEvent(intent);
        } else if (BluetoothHeadsetClient.ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
            int newState = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1);

            if (DEBUG) {
                int oldState = intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, -1);
                Log.d(TAG, "ACTION_CONNECTION_STATE_CHANGED event: "
                        + oldState + " -> " + newState);
            }
            BluetoothDevice device =
                    (BluetoothDevice) intent.getExtra(BluetoothDevice.EXTRA_DEVICE);
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
            if (DEBUG) {
                Log.d(TAG, "EXTRA_NETWORK_STATUS: " + " " + networkStatus);
            }

            if (networkStatus == NETWORK_UNAVAILABLE) {
                setNetworkSignalIcon(NETWORK_UNAVAILABLE_ICON_ID);
            }
        }

        int signalStrength = intent.getIntExtra(
                BluetoothHeadsetClient.EXTRA_NETWORK_SIGNAL_STRENGTH, INVALID_SIGNAL);
        if (signalStrength != INVALID_SIGNAL) {
            if (DEBUG) {
                Log.d(TAG, "EXTRA_NETWORK_SIGNAL_STRENGTH: " + signalStrength);
            }

            setNetworkSignalIcon(SIGNAL_STRENGTH_ICONS[signalStrength]);
        }

        int roamingStatus = intent.getIntExtra(BluetoothHeadsetClient.EXTRA_NETWORK_ROAMING,
                INVALID_SIGNAL);
        if (roamingStatus != INVALID_SIGNAL) {
            if (DEBUG) {
                Log.d(TAG, "EXTRA_NETWORK_ROAMING: " + roamingStatus);
            }
        }
    }

    private void setNetworkSignalIcon(int level) {
        // Setting the icon on a child view of mSignalView, so toggle this container visible.
        mSignalsView.setVisibility(View.VISIBLE);

        mSignalDrawable.setLevel(SignalDrawable.getState(level,
                SignalStrength.NUM_SIGNAL_STRENGTH_BINS, false));
        mNetworkSignalView.setVisibility(View.VISIBLE);
    }

    private void updateViewVisibility(BluetoothDevice device, int newState) {
        if (newState == BluetoothProfile.STATE_CONNECTED) {
            if (DEBUG) {
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
                if (DEBUG) {
                    Log.d(TAG, "EXTRA_NETWORK_SIGNAL_STRENGTH: " + signalStrength);
                }

                setNetworkSignalIcon(SIGNAL_STRENGTH_ICONS[signalStrength]);
            }
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            if (DEBUG) {
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
