/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.net.lowpan;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Handler;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * LoWPAN Scanner
 *
 * <p>This class allows performing network (active) scans and energy (passive) scans.
 *
 * @see LowpanInterface
 * @hide
 */
// @SystemApi
public class LowpanScanner {
    private static final String TAG = LowpanScanner.class.getSimpleName();

    // Public Classes

    /**
     * Callback base class for LowpanScanner
     *
     * @hide
     */
    // @SystemApi
    public abstract static class Callback {
        public void onNetScanBeacon(LowpanBeaconInfo beacon) {}

        public void onEnergyScanResult(LowpanEnergyScanResult result) {}

        public void onScanFinished() {}
    }

    // Instance Variables

    private ILowpanInterface mBinder;
    private Callback mCallback = null;
    private Handler mHandler = null;
    private ArrayList<Integer> mChannelMask = null;
    private int mTxPower = Integer.MAX_VALUE;

    // Constructors/Accessors and Exception Glue

    LowpanScanner(@NonNull ILowpanInterface binder) {
        mBinder = binder;
    }

    /** Sets an instance of {@link LowpanScanner.Callback} to receive events. */
    public synchronized void setCallback(@Nullable Callback cb, @Nullable Handler handler) {
        mCallback = cb;
        mHandler = handler;
    }

    /** Sets an instance of {@link LowpanScanner.Callback} to receive events. */
    public void setCallback(@Nullable Callback cb) {
        setCallback(cb, null);
    }

    /**
     * Sets the channel mask to use when scanning.
     *
     * @param mask The channel mask to use when scanning. If <code>null</code>, any previously set
     *     channel mask will be cleared and all channels not masked by the current regulatory zone
     *     will be scanned.
     */
    public void setChannelMask(@Nullable Collection<Integer> mask) {
        if (mask == null) {
            mChannelMask = null;
        } else {
            if (mChannelMask == null) {
                mChannelMask = new ArrayList<>();
            } else {
                mChannelMask.clear();
            }
            mChannelMask.addAll(mask);
        }
    }

    /**
     * Gets the current channel mask.
     *
     * @return the current channel mask, or <code>null</code> if no channel mask is currently set.
     */
    public @Nullable Collection<Integer> getChannelMask() {
        return (Collection<Integer>) mChannelMask.clone();
    }

    /**
     * Adds a channel to the channel mask used for scanning.
     *
     * <p>If a channel mask was previously <code>null</code>, a new one is created containing only
     * this channel. May be called multiple times to add additional channels ot the channel mask.
     *
     * @see #setChannelMask
     * @see #getChannelMask
     * @see #getTxPower
     */
    public void addChannel(int channel) {
        if (mChannelMask == null) {
            mChannelMask = new ArrayList<>();
        }
        mChannelMask.add(Integer.valueOf(channel));
    }

    /**
     * Sets the maximum transmit power to be used for active scanning.
     *
     * <p>The actual transmit power used is the lesser of this value and the currently configured
     * maximum transmit power for the interface.
     *
     * @see #getTxPower
     */
    public void setTxPower(int txPower) {
        mTxPower = txPower;
    }

    /**
     * Gets the maximum transmit power used for active scanning.
     *
     * @see #setTxPower
     */
    public int getTxPower() {
        return mTxPower;
    }

    private Map<String, Object> createScanOptionMap() {
        Map<String, Object> map = new HashMap();

        if (mChannelMask != null) {
            LowpanProperties.KEY_CHANNEL_MASK.putInMap(
                    map, mChannelMask.stream().mapToInt(i -> i).toArray());
        }

        if (mTxPower != Integer.MAX_VALUE) {
            LowpanProperties.KEY_MAX_TX_POWER.putInMap(map, Integer.valueOf(mTxPower));
        }

        return map;
    }

    /**
     * Start a network scan.
     *
     * <p>This method will return once the scan has started.
     *
     * @see #stopNetScan
     */
    public void startNetScan() throws LowpanException {
        Map<String, Object> map = createScanOptionMap();

        ILowpanNetScanCallback binderListener =
                new ILowpanNetScanCallback.Stub() {
                    public void onNetScanBeacon(LowpanBeaconInfo beaconInfo) {
                        Callback callback;
                        Handler handler;

                        synchronized (LowpanScanner.this) {
                            callback = mCallback;
                            handler = mHandler;
                        }

                        if (callback == null) {
                            return;
                        }

                        Runnable runnable = () -> callback.onNetScanBeacon(beaconInfo);

                        if (handler != null) {
                            handler.post(runnable);
                        } else {
                            runnable.run();
                        }
                    }

                    public void onNetScanFinished() {
                        Callback callback;
                        Handler handler;

                        synchronized (LowpanScanner.this) {
                            callback = mCallback;
                            handler = mHandler;
                        }

                        if (callback == null) {
                            return;
                        }

                        Runnable runnable = () -> callback.onScanFinished();

                        if (handler != null) {
                            handler.post(runnable);
                        } else {
                            runnable.run();
                        }
                    }
                };

        try {
            mBinder.startNetScan(map, binderListener);

        } catch (RemoteException x) {
            throw x.rethrowAsRuntimeException();

        } catch (ServiceSpecificException x) {
            throw LowpanException.rethrowFromServiceSpecificException(x);
        }
    }

    /**
     * Stop a network scan currently in progress.
     *
     * @see #startNetScan
     */
    public void stopNetScan() {
        try {
            mBinder.stopNetScan();

        } catch (RemoteException x) {
            throw x.rethrowAsRuntimeException();
        }
    }

    /**
     * Start an energy scan.
     *
     * <p>This method will return once the scan has started.
     *
     * @see #stopEnergyScan
     */
    public void startEnergyScan() throws LowpanException {
        Map<String, Object> map = createScanOptionMap();

        ILowpanEnergyScanCallback binderListener =
                new ILowpanEnergyScanCallback.Stub() {
                    public void onEnergyScanResult(int channel, int rssi) {
                        Callback callback = mCallback;
                        Handler handler = mHandler;

                        if (callback == null) {
                            return;
                        }

                        Runnable runnable =
                                () -> {
                                    if (callback != null) {
                                        LowpanEnergyScanResult result =
                                                new LowpanEnergyScanResult();
                                        result.setChannel(channel);
                                        result.setMaxRssi(rssi);
                                        callback.onEnergyScanResult(result);
                                    }
                                };

                        if (handler != null) {
                            handler.post(runnable);
                        } else {
                            runnable.run();
                        }
                    }

                    public void onEnergyScanFinished() {
                        Callback callback = mCallback;
                        Handler handler = mHandler;

                        if (callback == null) {
                            return;
                        }

                        Runnable runnable = () -> callback.onScanFinished();

                        if (handler != null) {
                            handler.post(runnable);
                        } else {
                            runnable.run();
                        }
                    }
                };

        try {
            mBinder.startEnergyScan(map, binderListener);

        } catch (RemoteException x) {
            throw x.rethrowAsRuntimeException();

        } catch (ServiceSpecificException x) {
            throw LowpanException.rethrowFromServiceSpecificException(x);
        }
    }

    /**
     * Stop an energy scan currently in progress.
     *
     * @see #startEnergyScan
     */
    public void stopEnergyScan() {
        try {
            mBinder.stopEnergyScan();

        } catch (RemoteException x) {
            throw x.rethrowAsRuntimeException();
        }
    }
}
