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
 *
 * @hide PROPOSED_NAN_API
 */
public class WifiNanEventCallback {
    private static final String TAG = "WifiNanEventCallback";
    private static final boolean DBG = false;
    private static final boolean VDBG = false; // STOPSHIP if true

    /** @hide */
    public static final int CALLBACK_CONFIG_COMPLETED = 0;
    /** @hide */
    public static final int CALLBACK_CONFIG_FAILED = 1;
    /** @hide */
    public static final int CALLBACK_NAN_DOWN = 2;
    /** @hide */
    public static final int CALLBACK_IDENTITY_CHANGED = 3;

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
                    case CALLBACK_CONFIG_COMPLETED:
                        WifiNanEventCallback.this.onConfigCompleted((ConfigRequest) msg.obj);
                        break;
                    case CALLBACK_CONFIG_FAILED:
                        WifiNanEventCallback.this.onConfigFailed((ConfigRequest) msg.obj, msg.arg1);
                        break;
                    case CALLBACK_NAN_DOWN:
                        WifiNanEventCallback.this.onNanDown(msg.arg1);
                        break;
                    case CALLBACK_IDENTITY_CHANGED:
                        WifiNanEventCallback.this.onIdentityChanged();
                        break;
                }
            }
        };
    }

    /**
     * Called when NAN configuration is completed. Event will only be delivered
     * if registered using
     * {@link WifiNanEventCallback#CALLBACK_CONFIG_COMPLETED}. A dummy (empty
     * implementation printing out a warning). Make sure to override if
     * registered.
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
     * registered using {@link WifiNanEventCallback#CALLBACK_CONFIG_FAILED}. A
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
     * registered using {@link WifiNanEventCallback#CALLBACK_NAN_DOWN}. A dummy
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
     * {@link WifiNanEventCallback#CALLBACK_IDENTITY_CHANGED}. A dummy (empty
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

            Message msg = mHandler.obtainMessage(CALLBACK_CONFIG_COMPLETED);
            msg.obj = completedConfig;
            mHandler.sendMessage(msg);
        }

        @Override
        public void onConfigFailed(ConfigRequest failedConfig, int reason) {
            if (VDBG) {
                Log.v(TAG, "onConfigFailed: failedConfig=" + failedConfig + ", reason=" + reason);
            }

            Message msg = mHandler.obtainMessage(CALLBACK_CONFIG_FAILED);
            msg.arg1 = reason;
            msg.obj = failedConfig;
            mHandler.sendMessage(msg);
        }

        @Override
        public void onNanDown(int reason) {
            if (VDBG) Log.v(TAG, "onNanDown: reason=" + reason);

            Message msg = mHandler.obtainMessage(CALLBACK_NAN_DOWN);
            msg.arg1 = reason;
            mHandler.sendMessage(msg);
        }

        @Override
        public void onIdentityChanged() {
            if (VDBG) Log.v(TAG, "onIdentityChanged");

            Message msg = mHandler.obtainMessage(CALLBACK_IDENTITY_CHANGED);
            mHandler.sendMessage(msg);
        }
    };
}
