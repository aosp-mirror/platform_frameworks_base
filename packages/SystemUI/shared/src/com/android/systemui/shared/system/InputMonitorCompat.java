/**
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
package com.android.systemui.shared.system;

import android.os.Bundle;
import android.os.Looper;
import android.os.Parcel;
import android.os.Parcelable;
import android.view.Choreographer;
import android.view.InputMonitor;

import com.android.systemui.shared.system.InputChannelCompat.InputEventListener;
import com.android.systemui.shared.system.InputChannelCompat.InputEventReceiver;

/**
 * @see android.view.InputMonitor
 */
public class InputMonitorCompat implements Parcelable {
    private final InputMonitor mInputMonitor;
    private boolean mForReturn = false;

    private InputMonitorCompat(InputMonitor monitor) {
        mInputMonitor = monitor;
    }

    /**
     * @see InputMonitor#pilferPointers()
     */
    public void pilferPointers() {
        mInputMonitor.pilferPointers();
    }

    /**
     * @see InputMonitor#dispose()
     */
    public void dispose() {
        mInputMonitor.dispose();
    }

    /**
     * @see InputMonitor#getInputChannel()
     */
    public InputEventReceiver getInputReceiver(Looper looper, Choreographer choreographer,
            InputEventListener listener) {
        return new InputEventReceiver(mInputMonitor.getInputChannel(), looper, choreographer,
                listener);
    }

    /**
     * Gets the input monitor stored in a bundle
     */
    public static InputMonitorCompat fromBundle(Bundle bundle, String key) {
        bundle.setClassLoader(InputMonitorCompat.class.getClassLoader());
        return (InputMonitorCompat) bundle.getParcelable(key);
    }

    /**
     * Gets the input monitor compat as the return value.
     */
    public static InputMonitorCompat obtainReturnValue(InputMonitor monitor) {
        final InputMonitorCompat monitorCompat = new InputMonitorCompat(monitor);
        monitorCompat.mForReturn = true;
        return monitorCompat;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        mInputMonitor.writeToParcel(dest,
                mForReturn ? PARCELABLE_WRITE_RETURN_VALUE : flags);
    }

    private InputMonitorCompat(Parcel in) {
        mInputMonitor = InputMonitor.CREATOR.createFromParcel(in);
    }

    public static final Creator<InputMonitorCompat> CREATOR = new Creator<InputMonitorCompat>() {
        @Override
        public InputMonitorCompat createFromParcel(Parcel in) {
            return new InputMonitorCompat(in);
        }

        @Override
        public InputMonitorCompat[] newArray(int size) {
            return new InputMonitorCompat[size];
        }
    };
}
