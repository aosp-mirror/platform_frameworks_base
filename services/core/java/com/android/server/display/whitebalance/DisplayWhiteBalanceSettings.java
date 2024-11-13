/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.display.whitebalance;

import android.annotation.NonNull;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Slog;

import com.android.server.LocalServices;
import com.android.server.display.color.ColorDisplayService;
import com.android.server.display.color.ColorDisplayService.ColorDisplayServiceInternal;
import com.android.server.display.whitebalance.DisplayWhiteBalanceController.Callbacks;

import java.io.PrintWriter;
import java.util.Objects;

/**
 * The DisplayWhiteBalanceSettings holds the state of all the settings related to
 * display white-balance, and can be used to decide whether to enable the
 * DisplayWhiteBalanceController.
 */
public class DisplayWhiteBalanceSettings implements
        ColorDisplayService.DisplayWhiteBalanceListener {

    protected static final String TAG = "DisplayWhiteBalanceSettings";
    protected boolean mLoggingEnabled;

    private static final int MSG_SET_ACTIVE = 1;

    private final Context mContext;
    private final Handler mHandler;

    private final ColorDisplayServiceInternal mCdsi;

    // To decouple the DisplayPowerController from the DisplayWhiteBalanceSettings, the DPC
    // implements Callbacks and passes itself to the DWBS so it can call back into it without
    // knowing about it.
    private Callbacks mCallbacks;

    private boolean mEnabled;
    private boolean mActive;

    /**
     * @param context
     *      The context in which display white-balance is used.
     * @param handler
     *      The handler used to determine which thread to run on.
     *
     * @throws NullPointerException
     *      - context is null;
     *      - handler is null.
     */
    public DisplayWhiteBalanceSettings(@NonNull Context context, @NonNull Handler handler) {
        validateArguments(context, handler);
        mLoggingEnabled = false;
        mContext = context;
        mHandler = new DisplayWhiteBalanceSettingsHandler(handler.getLooper());
        mCallbacks = null;

        mCdsi = LocalServices.getService(ColorDisplayServiceInternal.class);
        setEnabled(mCdsi.isDisplayWhiteBalanceEnabled());
        final boolean isActive = mCdsi.setDisplayWhiteBalanceListener(this);
        setActive(isActive);
    }

    /**
     * Set an object to call back to when the display white balance state should be updated.
     *
     * @param callbacks
     *      The object to call back to.
     *
     * @return Whether the method succeeded or not.
     */
    public boolean setCallbacks(Callbacks callbacks) {
        if (mCallbacks == callbacks) {
            return false;
        }
        mCallbacks = callbacks;
        return true;
    }

    /**
     * Enable/disable logging.
     *
     * @param loggingEnabled
     *      Whether logging should be on/off.
     *
     * @return Whether the method succeeded or not.
     */
    public boolean setLoggingEnabled(boolean loggingEnabled) {
        if (mLoggingEnabled == loggingEnabled) {
            return false;
        }
        mLoggingEnabled = loggingEnabled;
        return true;
    }

    /**
     * Returns whether display white-balance is enabled.
     *
     * @return Whether display white-balance is enabled.
     */
    public boolean isEnabled() {
        return mEnabled && mActive;
    }

    /**
     * Dump the state.
     *
     * @param writer
     *      The writer used to dump the state.
     */
    public void dump(PrintWriter writer) {
        writer.println("DisplayWhiteBalanceSettings:");
        writer.println("----------------------------");
        writer.println("  mLoggingEnabled=" + mLoggingEnabled);
        writer.println("  mContext=" + mContext);
        writer.println("  mHandler=" + mHandler);
        writer.println("  mEnabled=" + mEnabled);
        writer.println("  mActive=" + mActive);
        writer.println("  mCallbacks=" + mCallbacks);
    }

    @Override
    public void onDisplayWhiteBalanceStatusChanged(boolean activated) {
        Message msg = mHandler.obtainMessage(MSG_SET_ACTIVE, activated ? 1 : 0, 0);
        msg.sendToTarget();
    }

    private void validateArguments(Context context, Handler handler) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(handler, "handler must not be null");
    }

    private void setEnabled(boolean enabled) {
        if (mEnabled == enabled) {
            return;
        }
        if (mLoggingEnabled) {
            Slog.d(TAG, "Setting: " + enabled);
        }
        mEnabled = enabled;
        if (mCallbacks != null) {
            mCallbacks.updateWhiteBalance();
        }
    }

    private void setActive(boolean active) {
        if (mActive == active) {
            return;
        }
        if (mLoggingEnabled) {
            Slog.d(TAG, "Active: " + active);
        }
        mActive = active;
        if (mCallbacks != null) {
            mCallbacks.updateWhiteBalance();
        }
    }

    private final class DisplayWhiteBalanceSettingsHandler extends Handler {
        DisplayWhiteBalanceSettingsHandler(Looper looper) {
            super(looper, null, true /* async */);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_SET_ACTIVE:
                    setActive(msg.arg1 != 0);
                    setEnabled(mCdsi.isDisplayWhiteBalanceEnabled());
                    break;
            }
        }
    }

}
