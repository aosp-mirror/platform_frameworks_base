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

import android.os.Binder;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.ResultReceiver;

import java.util.Objects;

/**
 * Handles incoming commands from {@link MediaSession2} to both {@link MediaController2}.
 * @hide
 */
// @SystemApi
public final class Controller2Link implements Parcelable {
    private static final String TAG = "Controller2Link";
    private static final boolean DEBUG = MediaController2.DEBUG;

    public static final @android.annotation.NonNull Parcelable.Creator<Controller2Link> CREATOR =
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

    @Override
    public int hashCode() {
        return mIController.asBinder().hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof Controller2Link)) {
            return false;
        }
        Controller2Link other = (Controller2Link) obj;
        return Objects.equals(mIController.asBinder(), other.mIController.asBinder());
    }

    /** Interface method for IMediaController2.notifyConnected */
    public void notifyConnected(int seq, Bundle connectionResult) {
        try {
            mIController.notifyConnected(seq, connectionResult);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /** Interface method for IMediaController2.notifyDisonnected */
    public void notifyDisconnected(int seq) {
        try {
            mIController.notifyDisconnected(seq);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /** Interface method for IMediaController2.notifyPlaybackActiveChanged */
    public void notifyPlaybackActiveChanged(int seq, boolean playbackActive) {
        try {
            mIController.notifyPlaybackActiveChanged(seq, playbackActive);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /** Interface method for IMediaController2.sendSessionCommand */
    public void sendSessionCommand(int seq, Session2Command command, Bundle args,
            ResultReceiver resultReceiver) {
        try {
            mIController.sendSessionCommand(seq, command, args, resultReceiver);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /** Interface method for IMediaController2.cancelSessionCommand */
    public void cancelSessionCommand(int seq) {
        try {
            mIController.cancelSessionCommand(seq);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
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

    /** Stub implementation for IMediaController2.notifyPlaybackActiveChanged */
    public void onPlaybackActiveChanged(int seq, boolean playbackActive) {
        mController.onPlaybackActiveChanged(seq, playbackActive);
    }

    /** Stub implementation for IMediaController2.sendSessionCommand */
    public void onSessionCommand(int seq, Session2Command command, Bundle args,
            ResultReceiver resultReceiver) {
        mController.onSessionCommand(seq, command, args, resultReceiver);
    }

    /** Stub implementation for IMediaController2.cancelSessionCommand */
    public void onCancelCommand(int seq) {
        mController.onCancelCommand(seq);
    }

    private class Controller2Stub extends IMediaController2.Stub {
        @Override
        public void notifyConnected(int seq, Bundle connectionResult) {
            final long token = Binder.clearCallingIdentity();
            try {
                Controller2Link.this.onConnected(seq, connectionResult);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void notifyDisconnected(int seq) {
            final long token = Binder.clearCallingIdentity();
            try {
                Controller2Link.this.onDisconnected(seq);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void notifyPlaybackActiveChanged(int seq, boolean playbackActive) {
            final long token = Binder.clearCallingIdentity();
            try {
                Controller2Link.this.onPlaybackActiveChanged(seq, playbackActive);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void sendSessionCommand(int seq, Session2Command command, Bundle args,
                ResultReceiver resultReceiver) {
            final long token = Binder.clearCallingIdentity();
            try {
                Controller2Link.this.onSessionCommand(seq, command, args, resultReceiver);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void cancelSessionCommand(int seq) {
            final long token = Binder.clearCallingIdentity();
            try {
                Controller2Link.this.onCancelCommand(seq);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
    }
}
