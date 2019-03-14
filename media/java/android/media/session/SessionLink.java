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
import android.app.PendingIntent;
import android.media.AudioAttributes;
import android.media.MediaMetadata;
import android.media.MediaParceledListSlice;
import android.media.session.MediaSession.QueueItem;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;

import java.util.List;

/**
 * Handles incoming commands from {@link MediaSession}.
 * @hide
 */
public final class SessionLink implements Parcelable {
    public static final @android.annotation.NonNull Parcelable.Creator<SessionLink> CREATOR =
            new Parcelable.Creator<SessionLink>() {
                @Override
                public SessionLink createFromParcel(Parcel in) {
                    return new SessionLink(in.readStrongBinder());
                }

                @Override
                public SessionLink[] newArray(int size) {
                    return new SessionLink[size];
                }
            };

    final SessionStub mSessionStub;
    final ISession mISession;

    /**
     * Constructor for stub (Callee)
     */
    public SessionLink(@NonNull SessionStub sessionStub) {
        mSessionStub = sessionStub;
        mISession = new StubProxy();
    }

    /**
     * Constructor for interface (Caller)
     */
    public SessionLink(IBinder binder) {
        mSessionStub = null;
        mISession = ISession.Stub.asInterface(binder);
    }

    /**
     * Tell system that the session sends an event to all the connected controllers.
     *
     * @param event the name of the event
     * @param extras the extras included with the event
     */
    void sendEvent(@NonNull String event, @Nullable Bundle extras) {
        try {
            mISession.sendEvent(event, extras);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Gets the controller binder from the system.
     */
    @NonNull
    ISessionController getController() {
        try {
            return mISession.getController();
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Tell system that the session sets the flags.
     *
     * @param flags the new session flags
     */
    void setFlags(int flags) {
        try {
            mISession.setFlags(flags);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Tell system that the session is (in)active.
     *
     * @param active the new activeness state
     */
    void setActive(boolean active) {
        try {
            mISession.setActive(active);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Tell system that the session sets the media button receiver.
     *
     * @param mbr the pending intent for media button receiver
     */
    void setMediaButtonReceiver(@Nullable PendingIntent mbr) {
        try {
            mISession.setMediaButtonReceiver(mbr);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Tell system that the session sets the pending intent for launching UI.
     *
     * @param pi the pending intent for launching UI
     */
    void setLaunchPendingIntent(@Nullable PendingIntent pi) {
        try {
            mISession.setLaunchPendingIntent(pi);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Tell system that the session is destroyed.
     */
    void destroySession() {
        try {
            mISession.destroySession();
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Tell system that the session sets the new metadata.
     *
     * @param metadata the new metadata
     * @param duration the duration of the media in milliseconds
     * @param metadataDescription the description of the metadata
     */
    void setMetadata(@Nullable MediaMetadata metadata, long duration,
            @Nullable String metadataDescription) {
        try {
            mISession.setMetadata(metadata, duration, metadataDescription);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Tell system that the session sets the new playback state.
     *
     * @param state the new playback state
     */
    void setPlaybackState(@Nullable PlaybackState state) {
        try {
            mISession.setPlaybackState(state);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Tell system that the session sets the new queue.
     *
     * @param queue the new queue
     */
    void setQueue(@Nullable List<QueueItem> queue) {
        try {
            mISession.setQueue(queue == null ? null : new MediaParceledListSlice(queue));
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Tell system that the session sets the new queue title.
     *
     * @param title the new queue title
     */
    void setQueueTitle(@Nullable CharSequence title) {
        try {
            mISession.setQueueTitle(title);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Tell system that the session sets the new extras.
     *
     * @param extras the new extras
     */
    void setExtras(@Nullable Bundle extras) {
        try {
            mISession.setExtras(extras);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Tell system that the session sets the new rating type of the current media.
     *
     * @param type the rating type.
     */
    void setRatingType(int type) {
        try {
            mISession.setRatingType(type);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Tell system that the session represents a local playback.
     *
     * @param attributes the audio attributes of the local playback.
     */
    void setPlaybackToLocal(@NonNull AudioAttributes attributes) {
        try {
            mISession.setPlaybackToLocal(attributes);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Tell system that the session represents a remote playback.
     *
     * @param control the volume control type
     * @param max the max volume
     */
    void setPlaybackToRemote(int control, int max) {
        try {
            mISession.setPlaybackToRemote(control, max);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Tell system that the session sets the new current volume.
     *
     * @param currentVolume the new current volume
     */
    void setCurrentVolume(int currentVolume) {
        try {
            mISession.setCurrentVolume(currentVolume);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /** Gets the binder */
    @NonNull
    public IBinder getBinder() {
        return mISession.asBinder();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeStrongBinder(mISession.asBinder());
    }

    /**
     * Class for Stub implementation
     */
    public abstract static class SessionStub {
        /** Stub method for ISession.sendEvent */
        public void sendEvent(@NonNull String event, @Nullable Bundle data) {
        }

        /** Stub method for ISession.getController */
        @NonNull
        public ISessionController getController() {
            return null;
        }

        /** Stub method for ISession.setFlags */
        public void setFlags(int flags) {
        }

        /** Stub method for ISession.setActive */
        public void setActive(boolean active) {
        }

        /** Stub method for ISession.setMediaButtonReceiver */
        public void setMediaButtonReceiver(@Nullable PendingIntent mbr) {
        }

        /** Stub method for ISession.setLaunchPendingIntent */
        public void setLaunchPendingIntent(@Nullable PendingIntent pi) {
        }

        /** Stub method for ISession.destroySession */
        public void destroySession() {
        }

        /** Stub method for ISession.setMetadata */
        public void setMetadata(@Nullable MediaMetadata metadata, long duration,
                @Nullable String metadataDescription) {
        }

        /** Stub method for ISession.setPlaybackState */
        public void setPlaybackState(@Nullable PlaybackState state) {
        }

        /** Stub method for ISession.setQueue */
        public void setQueue(@Nullable List<QueueItem> queue) {
        }

        /** Stub method for ISession.setQueueTitle */
        public void setQueueTitle(@Nullable CharSequence title) {
        }

        /** Stub method for ISession.setExtras */
        public void setExtras(@Nullable Bundle extras) {
        }

        /** Stub method for ISession.setRatingType */
        public void setRatingType(int type) {
        }

        /** Stub method for ISession.setPlaybackToLocal */
        public void setPlaybackToLocal(@NonNull AudioAttributes attributes) {
        }

        /** Stub method for ISession.setPlaybackToRemote */
        public void setPlaybackToRemote(int control, int max) {
        }

        /** Stub method for ISession.setCurrentVolume */
        public void setCurrentVolume(int currentVolume) {
        }
    }

    private class StubProxy extends ISession.Stub {
        @Override
        public void sendEvent(String event, Bundle data) {
            mSessionStub.sendEvent(event, data);
        }

        @Override
        public ISessionController getController() {
            return mSessionStub.getController();
        }

        @Override
        public void setFlags(int flags) {
            mSessionStub.setFlags(flags);
        }

        @Override
        public void setActive(boolean active) {
            mSessionStub.setActive(active);
        }

        @Override
        public void setMediaButtonReceiver(PendingIntent mbr) {
            mSessionStub.setMediaButtonReceiver(mbr);
        }

        @Override
        public void setLaunchPendingIntent(PendingIntent pi) {
            mSessionStub.setLaunchPendingIntent(pi);
        }

        @Override
        public void destroySession() {
            mSessionStub.destroySession();
        }

        @Override
        public void setMetadata(MediaMetadata metadata, long duration, String metadataDescription) {
            mSessionStub.setMetadata(metadata, duration, metadataDescription);
        }

        @Override
        public void setPlaybackState(PlaybackState state) {
            mSessionStub.setPlaybackState(state);
        }

        @Override
        public void setQueue(MediaParceledListSlice queue) {
            mSessionStub.setQueue(queue == null ? null : queue.getList());
        }

        @Override
        public void setQueueTitle(CharSequence title) {
            mSessionStub.setQueueTitle(title);
        }

        @Override
        public void setExtras(Bundle extras) {
            mSessionStub.setExtras(extras);
        }

        @Override
        public void setRatingType(int type) {
            mSessionStub.setRatingType(type);
        }

        @Override
        public void setPlaybackToLocal(AudioAttributes attributes) {
            mSessionStub.setPlaybackToLocal(attributes);
        }

        @Override
        public void setPlaybackToRemote(int control, int max) {
            mSessionStub.setPlaybackToRemote(control, max);
        }

        @Override
        public void setCurrentVolume(int currentVolume) {
            mSessionStub.setCurrentVolume(currentVolume);
        }
    }
}
