/*
 * Copyright 2019 The Android Open Source Project
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

package android.media.session;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.media.MediaMetadata;
import android.media.session.MediaController.PlaybackInfo;
import android.media.session.MediaSession.QueueItem;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;

import java.util.List;

/**
 * Handles incoming commands to {@link MediaController.Callback}.
 * <p>
 * This API is not generally intended for third party application developers.
 */
public final class ControllerCallbackLink implements Parcelable {
    final CallbackStub mCallbackStub;
    final ISessionControllerCallback mIControllerCallback;

    /**
     * Constructor for stub (Callee)
     * @hide
     */
    @SystemApi
    public ControllerCallbackLink(@NonNull CallbackStub callbackStub) {
        mCallbackStub = callbackStub;
        mIControllerCallback = new CallbackStubProxy();
    }

    /**
     * Constructor for interface (Caller)
     */
    ControllerCallbackLink(Parcel in) {
        mCallbackStub = null;
        mIControllerCallback = ISessionControllerCallback.Stub.asInterface(in.readStrongBinder());
    }

    /**
     * Notify controller that the connected session is destroyed.
     */
    public void notifySessionDestroyed() {
        try {
            mIControllerCallback.notifySessionDestroyed();
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Notify controller that the connected session sends an event.
     *
     * @param event the name of the event
     * @param extras the extras included with the event
     */
    public void notifyEvent(@NonNull String event, @Nullable Bundle extras) {
        try {
            mIControllerCallback.notifyEvent(event, extras);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Notify controller that the current playback state is changed.
     *
     * @param state the new playback state
     */
    public void notifyPlaybackStateChanged(@Nullable PlaybackState state) {
        try {
            mIControllerCallback.notifyPlaybackStateChanged(state);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Notify controller that the current metadata is changed.
     *
     * @param metadata the new metadata
     */
    public void notifyMetadataChanged(@Nullable MediaMetadata metadata) {
        try {
            mIControllerCallback.notifyMetadataChanged(metadata);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Notify controller that the current queue is changed.
     *
     * @param queue the new queue
     */
    public void notifyQueueChanged(@Nullable List<QueueItem> queue) {
        try {
            mIControllerCallback.notifyQueueChanged(queue);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Notify controller that the current queue title is changed.
     *
     * @param title the new queue title
     */
    public void notifyQueueTitleChanged(@Nullable CharSequence title) {
        try {
            mIControllerCallback.notifyQueueTitleChanged(title);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Notify controller that the extras are changed.
     *
     * @param extras the new extras
     */
    public void notifyExtrasChanged(@Nullable Bundle extras) {
        try {
            mIControllerCallback.notifyExtrasChanged(extras);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Notify controller that the playback info is changed.
     *
     * @param info the new playback info
     */
    public void notifyVolumeInfoChanged(@NonNull PlaybackInfo info) {
        try {
            mIControllerCallback.notifyVolumeInfoChanged(info);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /** Gets the binder */
    @NonNull
    public IBinder getBinder() {
        return mIControllerCallback.asBinder();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeStrongBinder(mIControllerCallback.asBinder());
    }

    public static final Parcelable.Creator<ControllerCallbackLink> CREATOR =
            new Parcelable.Creator<ControllerCallbackLink>() {
        @Override
        public ControllerCallbackLink createFromParcel(Parcel in) {
            return new ControllerCallbackLink(in);
        }

        @Override
        public ControllerCallbackLink[] newArray(int size) {
            return new ControllerCallbackLink[size];
        }
    };

    /**
     * Class for Stub implementation
     * @hide
     */
    @SystemApi
    public abstract static class CallbackStub {
        /** Stub method for ISessionControllerCallback.notifySessionDestroyed */
        public void onSessionDestroyed() {
        }

        /** Stub method for ISessionControllerCallback.notifyEvent */
        public void onEvent(@NonNull String event, @Nullable Bundle extras) {
        }

        /** Stub method for ISessionControllerCallback.notifyPlaybackStateChanged */
        public void onPlaybackStateChanged(@Nullable PlaybackState state) {
        }

        /** Stub method for ISessionControllerCallback.notifyMetadataChanged */
        public void onMetadataChanged(@Nullable MediaMetadata metadata) {
        }

        /** Stub method for ISessionControllerCallback.notifyQueueChanged */
        public void onQueueChanged(@Nullable List<QueueItem> queue) {
        }

        /** Stub method for ISessionControllerCallback.notifyQueueTitleChanged */
        public void onQueueTitleChanged(@Nullable CharSequence title) {
        }

        /** Stub method for ISessionControllerCallback.notifyExtrasChanged */
        public void onExtrasChanged(@Nullable Bundle extras) {
        }

        /** Stub method for ISessionControllerCallback.notifyVolumeInfoChanged */
        public void onVolumeInfoChanged(@NonNull PlaybackInfo info) {
        }
    }

    private class CallbackStubProxy extends ISessionControllerCallback.Stub {
        @Override
        public void notifyEvent(String event, Bundle extras) {
            mCallbackStub.onEvent(event, extras);
        }

        @Override
        public void notifySessionDestroyed() {
            mCallbackStub.onSessionDestroyed();
        }

        @Override
        public void notifyPlaybackStateChanged(PlaybackState state) {
            mCallbackStub.onPlaybackStateChanged(state);
        }

        @Override
        public void notifyMetadataChanged(MediaMetadata metadata) {
            mCallbackStub.onMetadataChanged(metadata);
        }

        @Override
        public void notifyQueueChanged(List<QueueItem> queue) {
            mCallbackStub.onQueueChanged(queue);
        }

        @Override
        public void notifyQueueTitleChanged(CharSequence title) {
            mCallbackStub.onQueueTitleChanged(title);
        }

        @Override
        public void notifyExtrasChanged(Bundle extras) {
            mCallbackStub.onExtrasChanged(extras);
        }

        @Override
        public void notifyVolumeInfoChanged(PlaybackInfo info) {
            mCallbackStub.onVolumeInfoChanged(info);
        }
    }
}
