/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.net.wifi.nan;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

/**
 * Base class for NAN events callbacks. Should be extended by applications
 * wanting notifications. These are callbacks applying to the NAN connection as
 * a whole - not to specific publish or subscribe sessions - for that see
 * {@link WifiNanSessionCallback}.
 * <p>
 * During registration specify which specific events are desired using a set of
 * {@code NanEventCallback.LISTEN_*} flags OR'd together. Only those events will
 * be delivered to the registered callback. Override those callbacks
 * {@code WifiNanEventCallback.on*} for the registered events.
 *
 * @hide PROPOSED_NAN_API
 */
public class WifiNanEventCallback {
    private static final String TAG = "WifiNanEventCallback";
    private static final boolean DBG = false;
    private static final boolean VDBG = false; // STOPSHIP if true

    /**
     * Configuration completion callback event registration flag. Corresponding
     * callback is {@link WifiNanEventCallback#onConfigCompleted(ConfigRequest)}
     * .
     */
    public static final int FLAG_LISTEN_CONFIG_COMPLETED = 0x1 << 0;

    /**
     * Configuration failed callback event registration flag. Corresponding
     * callback is
     * {@link WifiNanEventCallback#onConfigFailed(ConfigRequest, int)}.
     */
    public static final int FLAG_LISTEN_CONFIG_FAILED = 0x1 << 1;

    /**
     * NAN cluster is down callback event registration flag. Corresponding
     * callback is {@link WifiNanEventCallback#onNanDown(int)}.
     */
    public static final int FLAG_LISTEN_NAN_DOWN = 0x1 << 2;

    /**
     * NAN identity has changed event registration flag. This may be due to
     * joining a cluster, starting a cluster, or discovery interface change. The
     * implication is that peers you've been communicating with may no longer
     * recognize you and you need to re-establish your identity. Corresponding
     * callback is {@link WifiNanEventCallback#onIdentityChanged()}.
     */
    public static final int FLAG_LISTEN_IDENTITY_CHANGED = 0x1 << 3;

    private final Handler mHandler;

    /**
     * Constructs a {@link WifiNanEventCallback} using the looper of the current
     * thread. I.e. all callbacks will be delivered on the current thread.
     */
    public WifiNanEventCallback() {
        this(Looper.myLooper());
    }

    /**
     * Constructs a {@link WifiNanEventCallback} using the specified looper.
     * I.e. all callbacks will delivered on the thread of the specified looper.
     *
     * @param looper The looper on which to execute the callbacks.
     */
    public WifiNanEventCallback(Looper looper) {
        if (VDBG) Log.v(TAG, "ctor: looper=" + looper);
        mHandler = new Handler(looper) {
            @Override
            public void handleMessage(Message msg) {
                if (DBG) Log.d(TAG, "What=" + msg.what + ", msg=" + msg);
                switch (msg.what) {
                    case FLAG_LISTEN_CONFIG_COMPLETED:
                        WifiNanEventCallback.this.onConfigCompleted((ConfigRequest) msg.obj);
                        break;
                    case FLAG_LISTEN_CONFIG_FAILED:
                        WifiNanEventCallback.this.onConfigFailed((ConfigRequest) msg.obj, msg.arg1);
                        break;
                    case FLAG_LISTEN_NAN_DOWN:
                        WifiNanEventCallback.this.onNanDown(msg.arg1);
                        break;
                    case FLAG_LISTEN_IDENTITY_CHANGED:
                        WifiNanEventCallback.this.onIdentityChanged();
                        break;
                }
            }
        };
    }

    /**
     * Called when NAN configuration is completed. Event will only be delivered
     * if registered using {@link WifiNanEventCallback#FLAG_LISTEN_CONFIG_COMPLETED}.
     * A dummy (empty implementation printing out a warning). Make sure to
     * override if registered.
     *
     * @param completedConfig The actual configuration request which was
     *            completed. Note that it may be different from that requested
     *            by the application. The service combines configuration
     *            requests from all applications.
     */
    public void onConfigCompleted(ConfigRequest completedConfig) {
        Log.w(TAG, "onConfigCompleted: called in stub - override if interested or disable");
    }

    /**
     * Called when NAN configuration failed. Event will only be delivered if
     * registered using {@link WifiNanEventCallback#FLAG_LISTEN_CONFIG_FAILED}. A
     * dummy (empty implementation printing out a warning). Make sure to
     * override if registered.
     *
     * @param reason Failure reason code, see
     *            {@code WifiNanSessionCallback.FAIL_*}.
     */
    public void onConfigFailed(ConfigRequest failedConfig, int reason) {
        Log.w(TAG, "onConfigFailed: called in stub - override if interested or disable");
    }

    /**
     * Called when NAN cluster is down. Event will only be delivered if
     * registered using {@link WifiNanEventCallback#FLAG_LISTEN_NAN_DOWN}. A dummy
     * (empty implementation printing out a warning). Make sure to override if
     * registered.
     *
     * @param reason Reason code for event, see
     *            {@code WifiNanSessionCallback.FAIL_*}.
     */
    public void onNanDown(int reason) {
        Log.w(TAG, "onNanDown: called in stub - override if interested or disable");
    }

    /**
     * Called when NAN identity has changed. This may be due to joining a
     * cluster, starting a cluster, or discovery interface change. The
     * implication is that peers you've been communicating with may no longer
     * recognize you and you need to re-establish your identity. Event will only
     * be delivered if registered using
     * {@link WifiNanEventCallback#FLAG_LISTEN_IDENTITY_CHANGED}. A dummy (empty
     * implementation printing out a warning). Make sure to override if
     * registered.
     */
    public void onIdentityChanged() {
        if (VDBG) Log.v(TAG, "onIdentityChanged: called in stub - override if interested");
    }

    /**
     * {@hide}
     */
    public IWifiNanEventCallback callback = new IWifiNanEventCallback.Stub() {
        @Override
        public void onConfigCompleted(ConfigRequest completedConfig) {
            if (VDBG) Log.v(TAG, "onConfigCompleted: configRequest=" + completedConfig);

            Message msg = mHandler.obtainMessage(FLAG_LISTEN_CONFIG_COMPLETED);
            msg.obj = completedConfig;
            mHandler.sendMessage(msg);
        }

        @Override
        public void onConfigFailed(ConfigRequest failedConfig, int reason) {
            if (VDBG) {
                Log.v(TAG, "onConfigFailed: failedConfig=" + failedConfig + ", reason=" + reason);
            }

            Message msg = mHandler.obtainMessage(FLAG_LISTEN_CONFIG_FAILED);
            msg.arg1 = reason;
            msg.obj = failedConfig;
            mHandler.sendMessage(msg);
        }

        @Override
        public void onNanDown(int reason) {
            if (VDBG) Log.v(TAG, "onNanDown: reason=" + reason);

            Message msg = mHandler.obtainMessage(FLAG_LISTEN_NAN_DOWN);
            msg.arg1 = reason;
            mHandler.sendMessage(msg);
        }

        @Override
        public void onIdentityChanged() {
            if (VDBG) Log.v(TAG, "onIdentityChanged");

            Message msg = mHandler.obtainMessage(FLAG_LISTEN_IDENTITY_CHANGED);
            mHandler.sendMessage(msg);
        }
    };
}
