/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.wm;

import android.annotation.Nullable;
import android.content.Context;
import android.hardware.devicestate.DeviceStateManager;
import android.os.Handler;
import android.os.HandlerExecutor;

import com.android.internal.util.ArrayUtils;

import java.util.function.Consumer;

/**
 * Class that registers callbacks with the {@link DeviceStateManager} and
 * responds to fold state changes by forwarding such events to a delegate.
 */
final class DeviceStateController {
    private final DeviceStateManager mDeviceStateManager;
    private final Context mContext;

    private FoldStateListener mDeviceStateListener;

    public enum FoldState {
        UNKNOWN, OPEN, FOLDED, HALF_FOLDED
    }

    DeviceStateController(Context context, Handler handler, Consumer<FoldState> delegate) {
        mContext = context;
        mDeviceStateManager = mContext.getSystemService(DeviceStateManager.class);
        if (mDeviceStateManager != null) {
            mDeviceStateListener = new FoldStateListener(mContext, delegate);
            mDeviceStateManager
                    .registerCallback(new HandlerExecutor(handler),
                            mDeviceStateListener);
        }
    }

    void unregisterFromDeviceStateManager() {
        if (mDeviceStateListener != null) {
            mDeviceStateManager.unregisterCallback(mDeviceStateListener);
        }
    }

    /**
     * A listener for half-fold device state events that dispatches state changes to a delegate.
     */
    static final class FoldStateListener implements DeviceStateManager.DeviceStateCallback {

        private final int[] mHalfFoldedDeviceStates;
        private final int[] mFoldedDeviceStates;

        @Nullable
        private FoldState mLastResult;
        private final Consumer<FoldState> mDelegate;

        FoldStateListener(Context context, Consumer<FoldState> delegate) {
            mFoldedDeviceStates = context.getResources().getIntArray(
                    com.android.internal.R.array.config_foldedDeviceStates);
            mHalfFoldedDeviceStates = context.getResources().getIntArray(
                    com.android.internal.R.array.config_halfFoldedDeviceStates);
            mDelegate = delegate;
        }

        @Override
        public void onStateChanged(int state) {
            final boolean halfFolded = ArrayUtils.contains(mHalfFoldedDeviceStates, state);
            FoldState result;
            if (halfFolded) {
                result = FoldState.HALF_FOLDED;
            } else {
                final boolean folded = ArrayUtils.contains(mFoldedDeviceStates, state);
                result = folded ? FoldState.FOLDED : FoldState.OPEN;
            }
            if (mLastResult == null || !mLastResult.equals(result)) {
                mLastResult = result;
                mDelegate.accept(result);
            }
        }
    }
}
