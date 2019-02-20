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
import android.media.MediaMetadata;
import android.media.MediaParceledListSlice;
import android.media.Rating;
import android.media.session.MediaController.PlaybackInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.view.KeyEvent;

import java.util.List;
import java.util.Objects;

/**
 * Handles incoming commands from {@link MediaController}.
 * @hide
 */
public final class ControllerLink implements Parcelable {
    public static final Parcelable.Creator<ControllerLink> CREATOR =
            new Parcelable.Creator<ControllerLink>() {
                @Override
                public ControllerLink createFromParcel(Parcel in) {
                    return new ControllerLink(in.readStrongBinder());
                }

                @Override
                public ControllerLink[] newArray(int size) {
                    return new ControllerLink[size];
                }
            };

    final ControllerStub mControllerStub;
    final ISessionController mISessionController;

    /**
     * Constructor for stub (Callee)
     */
    public ControllerLink(@NonNull ControllerStub controllerStub) {
        mControllerStub = controllerStub;
        mISessionController = new StubProxy();
    }

    /**
     * Constructor for interface (Caller)
     */
    public ControllerLink(IBinder binder) {
        mControllerStub = null;
        mISessionController = ISessionController.Stub.asInterface(binder);
    }

    /**
     * Tell system that a controller sends a command.
     *
     * @param packageName the package name of the controller
     * @param caller the {@link ControllerCallbackLink} of the controller
     * @param command the name of the command
     * @param args the arguments included with the command
     * @param cb the result receiver for getting the result of the command
     */
    void sendCommand(@NonNull String packageName, @NonNull ControllerCallbackLink caller,
            @NonNull String command, @Nullable Bundle args, @Nullable ResultReceiver cb) {
        try {
            mISessionController.sendCommand(packageName, caller, command, args, cb);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Tell system that a controller sends a media button event.
     *
     * @param packageName the package name of the controller
     * @param caller the {@link ControllerCallbackLink} of the controller
     * @param mediaButton the media button key event
     */
    boolean sendMediaButton(@NonNull String packageName,
            @NonNull ControllerCallbackLink caller, @NonNull KeyEvent mediaButton) {
        try {
            return mISessionController.sendMediaButton(packageName, caller, mediaButton);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Registers a controller callback link to the system.
     *
     * @param packageName the package name of the controller
     * @param cb the controller callback link to register
     */
    void registerCallback(@NonNull String packageName, @NonNull ControllerCallbackLink cb) {
        try {
            mISessionController.registerCallback(packageName, cb);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Unregisters a controller callback link from the system.
     *
     * @param cb the controller callback link to register
     */
    void unregisterCallback(@NonNull ControllerCallbackLink cb) {
        try {
            mISessionController.unregisterCallback(cb);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Gets the package name of the connected session.
     */
    @NonNull
    String getPackageName() {
        try {
            return mISessionController.getPackageName();
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Gets the tag of the connected session.
     */
    @NonNull
    String getTag() {
        try {
            return mISessionController.getTag();
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Gets the session info of the connected session.
     */
    @Nullable
    Bundle getSessionInfo() {
        try {
            return mISessionController.getSessionInfo();
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Gets the {@link PendingIntent} for launching UI of the connected session.
     */
    @Nullable
    PendingIntent getLaunchPendingIntent() {
        try {
            return mISessionController.getLaunchPendingIntent();
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Gets the flags of the connected session.
     */
    long getFlags() {
        try {
            return mISessionController.getFlags();
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Gets the volume attributes of the connected session.
     */
    @NonNull
    PlaybackInfo getVolumeAttributes() {
        try {
            return mISessionController.getVolumeAttributes();
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Tell system that a controller requests adjusting the volume.
     *
     * @param packageName the package name of the controller
     * @param opPackageName the op package name of this request
     * @param caller the {@link ControllerCallbackLink} of the controller
     * @param direction the direction to adjust the volume in
     * @param flags the flags with this volume change request
     */
    void adjustVolume(@NonNull String packageName, @NonNull String opPackageName,
            @NonNull ControllerCallbackLink caller, int direction,
            int flags) {
        try {
            mISessionController.adjustVolume(packageName, opPackageName, caller, direction, flags);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Tell system that a controller requests setting the volume.
     *
     * @param packageName the package name of the controller
     * @param opPackageName the op package name of this request
     * @param caller the {@link ControllerCallbackLink} of the controller
     * @param flags the flags with this volume change request
     */
    void setVolumeTo(@NonNull String packageName, @NonNull String opPackageName,
            @NonNull ControllerCallbackLink caller, int value, int flags) {
        try {
            mISessionController.setVolumeTo(packageName, opPackageName, caller, value, flags);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Tell system that a controller requests preparing media.
     *
     * @param packageName the package name of the controller
     * @param caller the {@link ControllerCallbackLink} of the controller
     */
    void prepare(@NonNull String packageName, @NonNull ControllerCallbackLink caller) {
        try {
            mISessionController.prepare(packageName, caller);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Tell system that a controller requests preparing media from given media ID.
     *
     * @param packageName the package name of the controller
     * @param caller the {@link ControllerCallbackLink} of the controller
     * @param mediaId the ID of the media
     * @param extras the extras included with this request.
     */
    void prepareFromMediaId(@NonNull String packageName,
            @NonNull ControllerCallbackLink caller, @NonNull String mediaId,
            @Nullable Bundle extras) {
        try {
            mISessionController.prepareFromMediaId(packageName, caller, mediaId, extras);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Tell system that a controller requests preparing media from given search query.
     *
     * @param packageName the package name of the controller
     * @param caller the {@link ControllerCallbackLink} of the controller
     * @param query the search query
     * @param extras the extras included with this request.
     */
    void prepareFromSearch(@NonNull String packageName,
            @NonNull ControllerCallbackLink caller, @NonNull String query,
            @Nullable Bundle extras) {
        try {
            mISessionController.prepareFromSearch(packageName, caller, query, extras);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Tell system that a controller requests preparing media from given uri.
     *
     * @param packageName the package name of the controller
     * @param caller the {@link ControllerCallbackLink} of the controller
     * @param uri the uri of the media
     * @param extras the extras included with this request.
     */
    void prepareFromUri(@NonNull String packageName, @NonNull ControllerCallbackLink caller,
            @NonNull Uri uri, @Nullable Bundle extras) {
        try {
            mISessionController.prepareFromUri(packageName, caller, uri, extras);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Tell system that a controller requests playing media.
     *
     * @param packageName the package name of the controller
     * @param caller the {@link ControllerCallbackLink} of the controller
     */
    void play(@NonNull String packageName, @NonNull ControllerCallbackLink caller) {
        try {
            mISessionController.play(packageName, caller);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Tell system that a controller requests playing media from given media ID.
     *
     * @param packageName the package name of the controller
     * @param caller the {@link ControllerCallbackLink} of the controller
     * @param mediaId the ID of the media
     * @param extras the extras included with this request.
     */
    void playFromMediaId(@NonNull String packageName, @NonNull ControllerCallbackLink caller,
            @NonNull String mediaId, @Nullable Bundle extras) {
        try {
            mISessionController.playFromMediaId(packageName, caller, mediaId, extras);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Tell system that a controller requests playing media from given search query.
     *
     * @param packageName the package name of the controller
     * @param caller the {@link ControllerCallbackLink} of the controller
     * @param query the search query
     * @param extras the extras included with this request.
     */
    void playFromSearch(@NonNull String packageName, @NonNull ControllerCallbackLink caller,
            @NonNull String query, @Nullable Bundle extras) {
        try {
            mISessionController.playFromSearch(packageName, caller, query, extras);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Tell system that a controller requests playing media from given uri.
     *
     * @param packageName the package name of the controller
     * @param caller the {@link ControllerCallbackLink} of the controller
     * @param uri the uri of the media
     * @param extras the extras included with this request.
     */
    void playFromUri(@NonNull String packageName, @NonNull ControllerCallbackLink caller,
            @NonNull Uri uri, @Nullable Bundle extras) {
        try {
            mISessionController.playFromUri(packageName, caller, uri, extras);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Tell system that a controller requests skipping to the queue item with given ID.
     *
     * @param packageName the package name of the controller
     * @param caller the {@link ControllerCallbackLink} of the controller
     * @param id the queue id of the item
     */
    void skipToQueueItem(@NonNull String packageName, @NonNull ControllerCallbackLink caller,
            long id) {
        try {
            mISessionController.skipToQueueItem(packageName, caller, id);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Tell system that a controller requests pausing media.
     *
     * @param packageName the package name of the controller
     * @param caller the {@link ControllerCallbackLink} of the controller
     */
    void pause(@NonNull String packageName, @NonNull ControllerCallbackLink caller) {
        try {
            mISessionController.pause(packageName, caller);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Tell system that a controller requests stopping media.
     *
     * @param packageName the package name of the controller
     * @param caller the {@link ControllerCallbackLink} of the controller
     */
    void stop(@NonNull String packageName, @NonNull ControllerCallbackLink caller) {
        try {
            mISessionController.stop(packageName, caller);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Tell system that a controller requests skipping to the next queue item.
     *
     * @param packageName the package name of the controller
     * @param caller the {@link ControllerCallbackLink} of the controller
     */
    void next(@NonNull String packageName, @NonNull ControllerCallbackLink caller) {
        try {
            mISessionController.next(packageName, caller);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Tell system that a controller requests skipping to the previous queue item.
     *
     * @param packageName the package name of the controller
     * @param caller the {@link ControllerCallbackLink} of the controller
     */
    void previous(@NonNull String packageName, @NonNull ControllerCallbackLink caller) {
        try {
            mISessionController.previous(packageName, caller);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Tell system that a controller requests fast-forwarding.
     *
     * @param packageName the package name of the controller
     * @param caller the {@link ControllerCallbackLink} of the controller
     */
    void fastForward(@NonNull String packageName, @NonNull ControllerCallbackLink caller) {
        try {
            mISessionController.fastForward(packageName, caller);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Tell system that a controller requests rewinding.
     *
     * @param packageName the package name of the controller
     * @param caller the {@link ControllerCallbackLink} of the controller
     */
    void rewind(@NonNull String packageName, @NonNull ControllerCallbackLink caller) {
        try {
            mISessionController.rewind(packageName, caller);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Tell system that a controller requests seeking to the specific position.
     *
     * @param packageName the package name of the controller
     * @param caller the {@link ControllerCallbackLink} of the controller
     * @param pos the position to move to, in milliseconds
     */
    void seekTo(@NonNull String packageName, @NonNull ControllerCallbackLink caller,
            long pos) {
        try {
            mISessionController.seekTo(packageName, caller, pos);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Tell system that a controller requests rating of the current media.
     *
     * @param packageName the package name of the controller
     * @param caller the {@link ControllerCallbackLink} of the controller
     * @param rating the rating of the current media
     */
    void rate(@NonNull String packageName, @NonNull ControllerCallbackLink caller,
            @NonNull Rating rating) {
        try {
            mISessionController.rate(packageName, caller, rating);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Tell system that a controller requests changing the playback speed.
     *
     * @param packageName the package name of the controller
     * @param caller the {@link ControllerCallbackLink} of the controller
     * @param speed the playback speed
     */
    void setPlaybackSpeed(@NonNull String packageName, @NonNull ControllerCallbackLink caller,
            float speed) {
        try {
            mISessionController.setPlaybackSpeed(packageName, caller, speed);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Tell system that a controller sends a custom action.
     *
     * @param packageName the package name of the controller
     * @param caller the {@link ControllerCallbackLink} of the controller
     * @param action the name of the action
     * @param args the arguments included with this action
     */
    void sendCustomAction(@NonNull String packageName,
            @NonNull ControllerCallbackLink caller, @NonNull String action, @Nullable Bundle args) {
        try {
            mISessionController.sendCustomAction(packageName, caller, action, args);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Gets the current metadata of the connected session.
     */
    @Nullable
    public MediaMetadata getMetadata() {
        try {
            return mISessionController.getMetadata();
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Gets the current playback state of the connected session.
     */
    @Nullable
    public PlaybackState getPlaybackState() {
        try {
            return mISessionController.getPlaybackState();
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Gets the current queue of the connected session.
     */
    @Nullable
    public List<MediaSession.QueueItem> getQueue() {
        try {
            MediaParceledListSlice queue = mISessionController.getQueue();
            return queue == null ? null : queue.getList();
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Gets the current queue title of the connected session.
     */
    @Nullable
    public CharSequence getQueueTitle() {
        try {
            return mISessionController.getQueueTitle();
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Gets the current extras of the connected session.
     */
    @Nullable
    public Bundle getExtras() {
        try {
            return mISessionController.getExtras();
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Gets the current rating type of the connected session.
     */
    public int getRatingType() {
        try {
            return mISessionController.getRatingType();
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /** Gets the binder */
    @NonNull
    public IBinder getBinder() {
        return mISessionController.asBinder();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeStrongBinder(mISessionController.asBinder());
    }

    @Override
    public int hashCode() {
        return mISessionController.asBinder().hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof ControllerLink)) {
            return false;
        }
        ControllerLink other = (ControllerLink) obj;
        return Objects.equals(getBinder(), other.getBinder());
    }

    /**
     * Class for Stub implementation
     */
    public abstract static class ControllerStub {
        /** Stub method for ISessionController.sendCommand */
        public void sendCommand(@NonNull String packageName, @NonNull ControllerCallbackLink caller,
                @NonNull String command, @Nullable Bundle args, @Nullable ResultReceiver cb) {
        }

        /** Stub method for ISessionController.sendMediaButton */
        public boolean sendMediaButton(@NonNull String packageName,
                @NonNull ControllerCallbackLink caller, @NonNull KeyEvent mediaButton) {
            return false;
        }

        /** Stub method for ISessionController.registerCallback */
        public void registerCallback(@NonNull String packageName,
                @NonNull ControllerCallbackLink cb) {
        }

        /** Stub method for ISessionController.unregisterCallback */
        public void unregisterCallback(@NonNull ControllerCallbackLink cb) {
        }

        /** Stub method for ISessionController.getPackageName */
        @NonNull
        public String getPackageName() {
            return null;
        }

        /** Stub method for ISessionController.getTag */
        @NonNull
        public String getTag() {
            return null;
        }

        /** Stub method for ISessionController.getSessionInfo */
        @Nullable
        public Bundle getSessionInfo() {
            return null;
        }

        /** Stub method for ISessionController.getLaunchPendingIntent */
        @Nullable
        public PendingIntent getLaunchPendingIntent() {
            return null;
        }

        /** Stub method for ISessionController.getFlags */
        public long getFlags() {
            return 0;
        }

        /** Stub method for ISessionController.getVolumeAttributes */
        @NonNull
        public PlaybackInfo getVolumeAttributes() {
            return null;
        }

        /** Stub method for ISessionController.adjustVolume */
        public void adjustVolume(@NonNull String packageName, @NonNull String opPackageName,
                @NonNull ControllerCallbackLink caller, int direction, int flags) {
        }

        /** Stub method for ISessionController.setVolumeTo */
        public void setVolumeTo(@NonNull String packageName, @NonNull String opPackageName,
                @NonNull ControllerCallbackLink caller, int value, int flags) {
        }

        /** Stub method for ISessionController.prepare */
        public void prepare(@NonNull String packageName, @NonNull ControllerCallbackLink caller) {
        }

        /** Stub method for ISessionController.prepareFromMediaId */
        public void prepareFromMediaId(@NonNull String packageName,
                @NonNull ControllerCallbackLink caller, @NonNull String mediaId,
                @Nullable Bundle extras) {
        }

        /** Stub method for ISessionController.prepareFromSearch */
        public void prepareFromSearch(@NonNull String packageName,
                @NonNull ControllerCallbackLink caller, @NonNull String query,
                @Nullable Bundle extras) {
        }

        /** Stub method for ISessionController.prepareFromUri */
        public void prepareFromUri(@NonNull String packageName,
                @NonNull ControllerCallbackLink caller, @NonNull Uri uri, @Nullable Bundle extras) {
        }

        /** Stub method for ISessionController.play */
        public void play(@NonNull String packageName, @NonNull ControllerCallbackLink caller) {
        }

        /** Stub method for ISessionController.playFromMediaId */
        public void playFromMediaId(@NonNull String packageName,
                @NonNull ControllerCallbackLink caller, @NonNull String mediaId,
                @Nullable Bundle extras) {
        }

        /** Stub method for ISessionController.playFromSearch */
        public void playFromSearch(@NonNull String packageName,
                @NonNull ControllerCallbackLink caller, @NonNull String query,
                @Nullable Bundle extras) {
        }

        /** Stub method for ISessionController.playFromUri */
        public void playFromUri(@NonNull String packageName, @NonNull ControllerCallbackLink caller,
                @NonNull Uri uri, @Nullable Bundle extras) {
        }

        /** Stub method for ISessionController.skipToQueueItem */
        public void skipToQueueItem(@NonNull String packageName,
                @NonNull ControllerCallbackLink caller, long id) {
        }

        /** Stub method for ISessionController.pause */
        public void pause(@NonNull String packageName, @NonNull ControllerCallbackLink caller) {
        }

        /** Stub method for ISessionController.stop */
        public void stop(@NonNull String packageName, @NonNull ControllerCallbackLink caller) {
        }

        /** Stub method for ISessionController.next */
        public void next(@NonNull String packageName, @NonNull ControllerCallbackLink caller) {
        }

        /** Stub method for ISessionController.previous */
        public void previous(@NonNull String packageName, @NonNull ControllerCallbackLink caller) {
        }

        /** Stub method for ISessionController.fastForward */
        public void fastForward(@NonNull String packageName,
                @NonNull ControllerCallbackLink caller) {
        }

        /** Stub method for ISessionController.rewind */
        public void rewind(@NonNull String packageName, @NonNull ControllerCallbackLink caller) {
        }

        /** Stub method for ISessionController.seekTo */
        public void seekTo(@NonNull String packageName, @NonNull ControllerCallbackLink caller,
                long pos) {
        }

        /** Stub method for ISessionController.rate */
        public void rate(@NonNull String packageName, @NonNull ControllerCallbackLink caller,
                @NonNull Rating rating) {
        }

        /** Stub method for ISessionController.setPlaybackSpeed */
        public void setPlaybackSpeed(@NonNull String packageName,
                @NonNull ControllerCallbackLink caller, float speed) {
        }

        /** Stub method for ISessionController.sendCustomAction */
        public void sendCustomAction(@NonNull String packageName,
                @NonNull ControllerCallbackLink caller, @NonNull String action,
                @Nullable Bundle args) {
        }

        /** Stub method for ISessionController.getMetadata */
        @Nullable
        public MediaMetadata getMetadata() {
            return null;
        }

        /** Stub method for ISessionController.getPlaybackState */
        @Nullable
        public PlaybackState getPlaybackState() {
            return null;
        }

        /** Stub method for ISessionController.getQueue */
        @Nullable
        public List<MediaSession.QueueItem> getQueue() {
            return null;
        }

        /** Stub method for ISessionController.getQueueTitle */
        @Nullable
        public CharSequence getQueueTitle() {
            return null;
        }

        /** Stub method for ISessionController.getExtras */
        @Nullable
        public Bundle getExtras() {
            return null;
        }

        /** Stub method for ISessionController.getRatingType */
        public int getRatingType() {
            return Rating.RATING_NONE;
        }
    }

    private class StubProxy extends ISessionController.Stub {
        @Override
        public void sendCommand(String packageName, ControllerCallbackLink caller,
                String command, Bundle args, ResultReceiver cb) {
            mControllerStub.sendCommand(packageName, caller, command, args, cb);
        }

        @Override
        public boolean sendMediaButton(String packageName, ControllerCallbackLink caller,
                KeyEvent mediaButton) {
            return mControllerStub.sendMediaButton(packageName, caller, mediaButton);
        }

        @Override
        public void registerCallback(String packageName, ControllerCallbackLink cb) {
            mControllerStub.registerCallback(packageName, cb);
        }

        @Override
        public void unregisterCallback(ControllerCallbackLink cb) {
            mControllerStub.unregisterCallback(cb);
        }

        @Override
        public String getPackageName() {
            return mControllerStub.getPackageName();
        }

        @Override
        public String getTag() {
            return mControllerStub.getTag();
        }

        @Override
        public Bundle getSessionInfo() {
            return mControllerStub.getSessionInfo();
        }

        @Override
        public PendingIntent getLaunchPendingIntent() {
            return mControllerStub.getLaunchPendingIntent();
        }

        @Override
        public long getFlags() {
            return mControllerStub.getFlags();
        }

        @Override
        public PlaybackInfo getVolumeAttributes() {
            return mControllerStub.getVolumeAttributes();
        }

        @Override
        public void adjustVolume(String packageName, String opPackageName,
                ControllerCallbackLink caller, int direction, int flags) {
            mControllerStub.adjustVolume(packageName, opPackageName, caller, direction, flags);
        }

        @Override
        public void setVolumeTo(String packageName, String opPackageName,
                ControllerCallbackLink caller, int value, int flags) {
            mControllerStub.setVolumeTo(packageName, opPackageName, caller, value, flags);
        }

        @Override
        public void prepare(String packageName, ControllerCallbackLink caller) {
            mControllerStub.prepare(packageName, caller);
        }

        @Override
        public void prepareFromMediaId(String packageName, ControllerCallbackLink caller,
                String mediaId, Bundle extras) {
            mControllerStub.prepareFromMediaId(packageName, caller, mediaId, extras);
        }

        @Override
        public void prepareFromSearch(String packageName, ControllerCallbackLink caller,
                String query, Bundle extras) {
            mControllerStub.prepareFromSearch(packageName, caller, query, extras);
        }

        @Override
        public void prepareFromUri(String packageName, ControllerCallbackLink caller,
                Uri uri, Bundle extras) {
            mControllerStub.prepareFromUri(packageName, caller, uri, extras);
        }

        @Override
        public void play(String packageName, ControllerCallbackLink caller) {
            mControllerStub.play(packageName, caller);
        }

        @Override
        public void playFromMediaId(String packageName, ControllerCallbackLink caller,
                String mediaId, Bundle extras) {
            mControllerStub.playFromMediaId(packageName, caller, mediaId, extras);
        }

        @Override
        public void playFromSearch(String packageName, ControllerCallbackLink caller,
                String query, Bundle extras) {
            mControllerStub.playFromSearch(packageName, caller, query, extras);
        }

        @Override
        public void playFromUri(String packageName, ControllerCallbackLink caller,
                Uri uri, Bundle extras) {
            mControllerStub.playFromUri(packageName, caller, uri, extras);
        }

        @Override
        public void skipToQueueItem(String packageName, ControllerCallbackLink caller, long id) {
            mControllerStub.skipToQueueItem(packageName, caller, id);
        }

        @Override
        public void pause(String packageName, ControllerCallbackLink caller) {
            mControllerStub.pause(packageName, caller);
        }

        @Override
        public void stop(String packageName, ControllerCallbackLink caller) {
            mControllerStub.stop(packageName, caller);
        }

        @Override
        public void next(String packageName, ControllerCallbackLink caller) {
            mControllerStub.next(packageName, caller);
        }

        @Override
        public void previous(String packageName, ControllerCallbackLink caller) {
            mControllerStub.previous(packageName, caller);
        }

        @Override
        public void fastForward(String packageName, ControllerCallbackLink caller) {
            mControllerStub.fastForward(packageName, caller);
        }

        @Override
        public void rewind(String packageName, ControllerCallbackLink caller) {
            mControllerStub.rewind(packageName, caller);
        }

        @Override
        public void seekTo(String packageName, ControllerCallbackLink caller, long pos) {
            mControllerStub.seekTo(packageName, caller, pos);
        }

        @Override
        public void rate(String packageName, ControllerCallbackLink caller, Rating rating) {
            mControllerStub.rate(packageName, caller, rating);
        }

        @Override
        public void setPlaybackSpeed(String packageName, ControllerCallbackLink caller,
                float speed) {
            mControllerStub.setPlaybackSpeed(packageName, caller, speed);
        }

        @Override
        public void sendCustomAction(String packageName, ControllerCallbackLink caller,
                String action, Bundle args) {
            mControllerStub.sendCustomAction(packageName, caller, action, args);
        }

        @Override
        public MediaMetadata getMetadata() {
            return mControllerStub.getMetadata();
        }

        @Override
        public PlaybackState getPlaybackState() {
            return mControllerStub.getPlaybackState();
        }

        @Override
        public MediaParceledListSlice getQueue() {
            List<MediaSession.QueueItem> queue = mControllerStub.getQueue();
            return queue == null ? null : new MediaParceledListSlice(queue);
        }

        @Override
        public CharSequence getQueueTitle() {
            return mControllerStub.getQueueTitle();
        }

        @Override
        public Bundle getExtras() {
            return mControllerStub.getExtras();
        }

        @Override
        public int getRatingType() {
            return mControllerStub.getRatingType();
        }
    }
}
