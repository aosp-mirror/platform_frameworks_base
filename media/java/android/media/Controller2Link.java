/*
 * Copyright 2018 The Android Open Source Project
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

package android.media;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;

/**
 * Handles incoming commands from {@link MediaSession2} and {@link MediaLibrarySession}
 * to both {@link MediaController2} and {@link MediaBrowser2}.
 * @hide
 */
// @SystemApi
public final class Controller2Link implements Parcelable {
    private static final String TAG = "Controller2Link";
    private static final boolean DEBUG = MediaController2.DEBUG;

    public static final Parcelable.Creator<Controller2Link> CREATOR =
            new Parcelable.Creator<Controller2Link>() {
                @Override
                public Controller2Link createFromParcel(Parcel in) {
                    return new Controller2Link(in);
                }

                @Override
                public Controller2Link[] newArray(int size) {
                    return new Controller2Link[size];
                }
            };


    private final MediaController2 mController;
    private final IMediaController2 mIController;

    public Controller2Link(MediaController2 controller) {
        mController = controller;
        mIController = new Controller2Stub();
    }

    Controller2Link(Parcel in) {
        mController = null;
        mIController = IMediaController2.Stub.asInterface(in.readStrongBinder());
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeStrongBinder(mIController.asBinder());
    }

    /** Interface method for IMediaController2.notifyConnected */
    public void notifyConnected(int seq, Bundle connectionResult) {
        try {
            mIController.notifyConnected(seq, connectionResult);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** Interface method for IMediaController2.notifyDisonnected */
    public void notifyDisconnected(int seq) {
        try {
            mIController.notifyDisconnected(seq);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** Interface method for IMediaController2.sendSessionCommand */
    public void sendSessionCommand(int seq, Session2Command command, Bundle args) {
        try {
            mIController.sendSessionCommand(seq, command, args);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** Stub implementation for IMediaController2.notifyConnected */
    public void onConnected(int seq, Bundle connectionResult) {
        if (connectionResult == null) {
            onDisconnected(seq);
            return;
        }
        mController.onConnected(seq, connectionResult);
    }

    /** Stub implementation for IMediaController2.notifyDisonnected */
    public void onDisconnected(int seq) {
        mController.onDisconnected(seq);
    }

    /** Stub implementation for IMediaController2.sendSessionCommand */
    public void onSessionCommand(int seq, Session2Command command, Bundle args) {
        mController.onSessionCommand(seq, command, args);
    }

    private class Controller2Stub extends IMediaController2.Stub {
        @Override
        public void notifyConnected(int seq, Bundle connectionResult) {
            Controller2Link.this.onConnected(seq, connectionResult);
        }

        @Override
        public void notifyDisconnected(int seq) {
            Controller2Link.this.onDisconnected(seq);
        }

        @Override
        public void sendSessionCommand(int seq, Session2Command command, Bundle args) {
            Controller2Link.this.onSessionCommand(seq, command, args);
        }
    }
}
