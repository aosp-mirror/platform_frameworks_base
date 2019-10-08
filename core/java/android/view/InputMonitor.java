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

package android.view;

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;

/**
 * An {@code InputMonitor} allows privileged applications and components to monitor streams of
 * {@link InputEvent}s without having to be the designated recipient for the event.
 *
 * For example, focus dispatched events would normally only go to the focused window on the
 * targeted display, but an {@code InputMonitor} will also receive a copy of that event if they're
 * registered to monitor that type of event on the targeted display.
 *
 * @hide
 */
public final class InputMonitor implements Parcelable {
    private static final String TAG = "InputMonitor";

    private static final boolean DEBUG = false;

    public static final Parcelable.Creator<InputMonitor> CREATOR =
            new Parcelable.Creator<InputMonitor>() {

            public InputMonitor createFromParcel(Parcel source) {
                return new InputMonitor(source);
            }

            public InputMonitor[] newArray(int size) {
                return new InputMonitor[size];
            }
    };

    @NonNull
    private final String mName;
    @NonNull
    private final InputChannel mChannel;
    @NonNull
    private final IInputMonitorHost mHost;

    public InputMonitor(@NonNull String name, @NonNull InputChannel channel,
            @NonNull IInputMonitorHost host) {
        mName = name;
        mChannel = channel;
        mHost = host;
    }

    public InputMonitor(Parcel in) {
        mName = in.readString();
        mChannel = in.readParcelable(null);
        mHost = IInputMonitorHost.Stub.asInterface(in.readStrongBinder());
    }

    /**
     * Get the {@link InputChannel} corresponding to this InputMonitor
     */
    public InputChannel getInputChannel() {
        return mChannel;
    }

    /**
     * Get the name of this channel.
     */
    public String getName() {
        return mName;
    }


    /**
     * Takes all of the current pointer events streams that are currently being sent to this
     * monitor and generates appropriate cancellations for the windows that would normally get
     * them.
     *
     * This method should be used with caution as unexpected pilfering can break fundamental user
     * interactions.
     */
    public void pilferPointers() {
        try {
            mHost.pilferPointers();
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Disposes the input monitor.
     *
     * Explicitly release all of the resources this monitor is holding on to (e.g. the
     * InputChannel). Once this method is called, this monitor and any resources it's provided may
     * no longer be used.
     */
    public void dispose() {
        mChannel.dispose();
        try {
            mHost.dispose();
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeString(mName);
        out.writeParcelable(mChannel, flags);
        out.writeStrongBinder(mHost.asBinder());
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        return "InputMonitor{mName=" + mName + ", mChannel=" + mChannel + ", mHost=" + mHost + "}";
    }
}
