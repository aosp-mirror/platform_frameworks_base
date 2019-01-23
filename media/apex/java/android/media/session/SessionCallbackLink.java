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

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.Rating;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;

/**
 * Handles incoming commands to {@link MediaSession.Callback}.
 * @hide
 */
@SystemApi
public final class SessionCallbackLink implements Parcelable {
    final Context mContext;
    final CallbackStub mCallbackStub;
    final ISessionCallback mISessionCallback;

    /**
     * Constructor for stub (Callee)
     */
    SessionCallbackLink(@NonNull Context context, @NonNull CallbackStub callbackStub) {
        mContext = context;
        mCallbackStub = callbackStub;
        mISessionCallback = new CallbackStubProxy();
    }

    /**
     * Constructor for interface (Caller)
     */
    public SessionCallbackLink(IBinder binder) {
        mContext = null;
        mCallbackStub = null;
        mISessionCallback = ISessionCallback.Stub.asInterface(binder);
    }

    /**
     * Notify session that a controller sends a command.
     *
     * @param packageName the package name of the controller
     * @param pid the pid of the controller
     * @param uid the uid of the controller
     * @param caller the {@link ControllerCallbackLink} of the controller
     * @param command the name of the command
     * @param args the arguments included with the command
     * @param cb the result receiver for getting the result of the command
     */
    @RequiresPermission(Manifest.permission.MEDIA_CONTENT_CONTROL)
    public void notifyCommand(@NonNull String packageName, int pid, int uid,
            @NonNull ControllerCallbackLink caller, @NonNull String command,
            @Nullable Bundle args, @Nullable ResultReceiver cb) {
        try {
            mISessionCallback.notifyCommand(packageName, pid, uid, caller, command, args, cb);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Notify session that the android system sends a media button event.
     *
     * @param packageName the package name of the controller
     * @param pid the pid of the controller
     * @param uid the uid of the controller
     * @param mediaButtonIntent the media button intent
     * @param sequenceNumber the sequence number of this call
     * @param cb the result receiver for getting the result of the command
     */
    @RequiresPermission(Manifest.permission.MEDIA_CONTENT_CONTROL)
    public void notifyMediaButton(@NonNull String packageName, int pid, int uid,
            @NonNull Intent mediaButtonIntent, int sequenceNumber,
            @Nullable ResultReceiver cb) {
        try {
            mISessionCallback.notifyMediaButton(packageName, pid, uid, mediaButtonIntent,
                    sequenceNumber, cb);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Notify session that a controller sends a media button event.
     *
     * @param packageName the package name of the controller
     * @param pid the pid of the controller
     * @param uid the uid of the controller
     * @param caller the {@link ControllerCallbackLink} of the controller
     * @param mediaButtonIntent the media button intent
     */
    @RequiresPermission(Manifest.permission.MEDIA_CONTENT_CONTROL)
    public void notifyMediaButtonFromController(@NonNull String packageName, int pid, int uid,
            @NonNull ControllerCallbackLink caller, @NonNull Intent mediaButtonIntent) {
        try {
            mISessionCallback.notifyMediaButtonFromController(packageName, pid, uid, caller,
                    mediaButtonIntent);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Notify session that a controller requests preparing media.
     *
     * @param packageName the package name of the controller
     * @param pid the pid of the controller
     * @param uid the uid of the controller
     * @param caller the {@link ControllerCallbackLink} of the controller
     */
    @RequiresPermission(Manifest.permission.MEDIA_CONTENT_CONTROL)
    public void notifyPrepare(@NonNull String packageName, int pid, int uid,
            @NonNull ControllerCallbackLink caller) {
        try {
            mISessionCallback.notifyPrepare(packageName, pid, uid, caller);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Notify session that a controller requests preparing media from given media ID.
     *
     * @param packageName the package name of the controller
     * @param pid the pid of the controller
     * @param uid the uid of the controller
     * @param caller the {@link ControllerCallbackLink} of the controller
     * @param mediaId the ID of the media
     * @param extras the extras included with this request.
     */
    @RequiresPermission(Manifest.permission.MEDIA_CONTENT_CONTROL)
    public void notifyPrepareFromMediaId(@NonNull String packageName, int pid, int uid,
            @NonNull ControllerCallbackLink caller, @NonNull String mediaId,
            @Nullable Bundle extras) {
        try {
            mISessionCallback.notifyPrepareFromMediaId(packageName, pid, uid, caller, mediaId,
                    extras);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Notify session that a controller requests preparing media from given search query.
     *
     * @param packageName the package name of the controller
     * @param pid the pid of the controller
     * @param uid the uid of the controller
     * @param caller the {@link ControllerCallbackLink} of the controller
     * @param query the search query
     * @param extras the extras included with this request.
     */
    @RequiresPermission(Manifest.permission.MEDIA_CONTENT_CONTROL)
    public void notifyPrepareFromSearch(@NonNull String packageName, int pid, int uid,
            @NonNull ControllerCallbackLink caller, @NonNull String query,
            @Nullable Bundle extras) {
        try {
            mISessionCallback.notifyPrepareFromSearch(packageName, pid, uid, caller, query, extras);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Notify session that a controller requests preparing media from given uri.
     *
     * @param packageName the package name of the controller
     * @param pid the pid of the controller
     * @param uid the uid of the controller
     * @param caller the {@link ControllerCallbackLink} of the controller
     * @param uri the uri of the media
     * @param extras the extras included with this request.
     */
    @RequiresPermission(Manifest.permission.MEDIA_CONTENT_CONTROL)
    public void notifyPrepareFromUri(@NonNull String packageName, int pid, int uid,
            @NonNull ControllerCallbackLink caller, @NonNull Uri uri, @Nullable Bundle extras) {
        try {
            mISessionCallback.notifyPrepareFromUri(packageName, pid, uid, caller, uri, extras);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Notify session that a controller requests playing media.
     *
     * @param packageName the package name of the controller
     * @param pid the pid of the controller
     * @param uid the uid of the controller
     * @param caller the {@link ControllerCallbackLink} of the controller
     */
    @RequiresPermission(Manifest.permission.MEDIA_CONTENT_CONTROL)
    public void notifyPlay(@NonNull String packageName, int pid, int uid,
            @NonNull ControllerCallbackLink caller) {
        try {
            mISessionCallback.notifyPlay(packageName, pid, uid, caller);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Notify session that a controller requests playing media from given media ID.
     *
     * @param packageName the package name of the controller
     * @param pid the pid of the controller
     * @param uid the uid of the controller
     * @param caller the {@link ControllerCallbackLink} of the controller
     * @param mediaId the ID of the media
     * @param extras the extras included with this request.
     */
    @RequiresPermission(Manifest.permission.MEDIA_CONTENT_CONTROL)
    public void notifyPlayFromMediaId(@NonNull String packageName, int pid, int uid,
            @NonNull ControllerCallbackLink caller, @NonNull String mediaId,
            @Nullable Bundle extras) {
        try {
            mISessionCallback.notifyPlayFromMediaId(packageName, pid, uid, caller, mediaId, extras);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Notify session that a controller requests playing media from given search query.
     *
     * @param packageName the package name of the controller
     * @param pid the pid of the controller
     * @param uid the uid of the controller
     * @param caller the {@link ControllerCallbackLink} of the controller
     * @param query the search query
     * @param extras the extras included with this request.
     */
    @RequiresPermission(Manifest.permission.MEDIA_CONTENT_CONTROL)
    public void notifyPlayFromSearch(@NonNull String packageName, int pid, int uid,
            @NonNull ControllerCallbackLink caller, @NonNull String query,
            @Nullable Bundle extras) {
        try {
            mISessionCallback.notifyPlayFromSearch(packageName, pid, uid, caller, query, extras);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Notify session that a controller requests playing media from given uri.
     *
     * @param packageName the package name of the controller
     * @param pid the pid of the controller
     * @param uid the uid of the controller
     * @param caller the {@link ControllerCallbackLink} of the controller
     * @param uri the uri of the media
     * @param extras the extras included with this request.
     */
    @RequiresPermission(Manifest.permission.MEDIA_CONTENT_CONTROL)
    public void notifyPlayFromUri(@NonNull String packageName, int pid, int uid,
            @NonNull ControllerCallbackLink caller, @NonNull Uri uri, @Nullable Bundle extras) {
        try {
            mISessionCallback.notifyPlayFromUri(packageName, pid, uid, caller, uri, extras);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Notify session that a controller requests skipping to the queue item with given ID.
     *
     * @param packageName the package name of the controller
     * @param pid the pid of the controller
     * @param uid the uid of the controller
     * @param caller the {@link ControllerCallbackLink} of the controller
     * @param id the queue id of the item
     */
    @RequiresPermission(Manifest.permission.MEDIA_CONTENT_CONTROL)
    public void notifySkipToTrack(@NonNull String packageName, int pid, int uid,
            @NonNull ControllerCallbackLink caller, long id) {
        try {
            mISessionCallback.notifySkipToTrack(packageName, pid, uid, caller, id);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Notify session that a controller requests pausing media.
     *
     * @param packageName the package name of the controller
     * @param pid the pid of the controller
     * @param uid the uid of the controller
     * @param caller the {@link ControllerCallbackLink} of the controller
     */
    @RequiresPermission(Manifest.permission.MEDIA_CONTENT_CONTROL)
    public void notifyPause(@NonNull String packageName, int pid, int uid,
            @NonNull ControllerCallbackLink caller) {
        try {
            mISessionCallback.notifyPause(packageName, pid, uid, caller);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Notify session that a controller requests stopping media.
     *
     * @param packageName the package name of the controller
     * @param pid the pid of the controller
     * @param uid the uid of the controller
     * @param caller the {@link ControllerCallbackLink} of the controller
     */
    @RequiresPermission(Manifest.permission.MEDIA_CONTENT_CONTROL)
    public void notifyStop(@NonNull String packageName, int pid, int uid,
            @NonNull ControllerCallbackLink caller) {
        try {
            mISessionCallback.notifyStop(packageName, pid, uid, caller);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Notify session that a controller requests skipping to the next queue item.
     *
     * @param packageName the package name of the controller
     * @param pid the pid of the controller
     * @param uid the uid of the controller
     * @param caller the {@link ControllerCallbackLink} of the controller
     */
    @RequiresPermission(Manifest.permission.MEDIA_CONTENT_CONTROL)
    public void notifyNext(@NonNull String packageName, int pid, int uid,
            @NonNull ControllerCallbackLink caller) {
        try {
            mISessionCallback.notifyNext(packageName, pid, uid, caller);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Notify session that a controller requests skipping to the previous queue item.
     *
     * @param packageName the package name of the controller
     * @param pid the pid of the controller
     * @param uid the uid of the controller
     * @param caller the {@link ControllerCallbackLink} of the controller
     */
    @RequiresPermission(Manifest.permission.MEDIA_CONTENT_CONTROL)
    public void notifyPrevious(@NonNull String packageName, int pid, int uid,
            @NonNull ControllerCallbackLink caller) {
        try {
            mISessionCallback.notifyPrevious(packageName, pid, uid, caller);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Notify session that a controller requests fast-forwarding.
     *
     * @param packageName the package name of the controller
     * @param pid the pid of the controller
     * @param uid the uid of the controller
     * @param caller the {@link ControllerCallbackLink} of the controller
     */
    @RequiresPermission(Manifest.permission.MEDIA_CONTENT_CONTROL)
    public void notifyFastForward(@NonNull String packageName, int pid, int uid,
            @NonNull ControllerCallbackLink caller) {
        try {
            mISessionCallback.notifyFastForward(packageName, pid, uid, caller);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Notify session that a controller requests rewinding.
     *
     * @param packageName the package name of the controller
     * @param pid the pid of the controller
     * @param uid the uid of the controller
     * @param caller the {@link ControllerCallbackLink} of the controller
     */
    @RequiresPermission(Manifest.permission.MEDIA_CONTENT_CONTROL)
    public void notifyRewind(@NonNull String packageName, int pid, int uid,
            @NonNull ControllerCallbackLink caller) {
        try {
            mISessionCallback.notifyRewind(packageName, pid, uid, caller);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Notify session that a controller requests seeking to the specific position.
     *
     * @param packageName the package name of the controller
     * @param pid the pid of the controller
     * @param uid the uid of the controller
     * @param caller the {@link ControllerCallbackLink} of the controller
     * @param pos the position to move to, in milliseconds
     */
    @RequiresPermission(Manifest.permission.MEDIA_CONTENT_CONTROL)
    public void notifySeekTo(@NonNull String packageName, int pid, int uid,
            @NonNull ControllerCallbackLink caller, long pos) {
        try {
            mISessionCallback.notifySeekTo(packageName, pid, uid, caller, pos);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Notify session that a controller requests rating of the current media.
     *
     * @param packageName the package name of the controller
     * @param pid the pid of the controller
     * @param uid the uid of the controller
     * @param caller the {@link ControllerCallbackLink} of the controller
     * @param rating the rating of the current media
     */
    @RequiresPermission(Manifest.permission.MEDIA_CONTENT_CONTROL)
    public void notifyRate(@NonNull String packageName, int pid, int uid,
            @NonNull ControllerCallbackLink caller, @NonNull Rating rating) {
        try {
            mISessionCallback.notifyRate(packageName, pid, uid, caller, rating);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Notify session that a controller sends a custom action.
     *
     * @param packageName the package name of the controller
     * @param pid the pid of the controller
     * @param uid the uid of the controller
     * @param caller the {@link ControllerCallbackLink} of the controller
     * @param action the name of the action
     * @param args the arguments included with this action
     */
    @RequiresPermission(Manifest.permission.MEDIA_CONTENT_CONTROL)
    public void notifyCustomAction(@NonNull String packageName, int pid, int uid,
            @NonNull ControllerCallbackLink caller, @NonNull String action, @Nullable Bundle args) {
        try {
            mISessionCallback.notifyCustomAction(packageName, pid, uid, caller, action, args);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Notify session that a controller requests adjusting volume.
     *
     * @param packageName the package name of the controller
     * @param pid the pid of the controller
     * @param uid the uid of the controller
     * @param caller the {@link ControllerCallbackLink} of the controller
     * @param direction the direction of the volume change.
     */
    @RequiresPermission(Manifest.permission.MEDIA_CONTENT_CONTROL)
    public void notifyAdjustVolume(@NonNull String packageName, int pid, int uid,
            @NonNull ControllerCallbackLink caller, int direction) {
        try {
            mISessionCallback.notifyAdjustVolume(packageName, pid, uid, caller, direction);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Notify session that a controller requests setting volume.
     *
     * @param packageName the package name of the controller
     * @param pid the pid of the controller
     * @param uid the uid of the controller
     * @param caller the {@link ControllerCallbackLink} of the controller
     * @param value the volume value to set
     */
    @RequiresPermission(Manifest.permission.MEDIA_CONTENT_CONTROL)
    public void notifySetVolumeTo(@NonNull String packageName, int pid, int uid,
            @NonNull ControllerCallbackLink caller, int value) {
        try {
            mISessionCallback.notifySetVolumeTo(packageName, pid, uid, caller, value);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /** Gets the binder */
    @NonNull
    public IBinder getBinder() {
        return mISessionCallback.asBinder();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeStrongBinder(mISessionCallback.asBinder());
    }

    public static final Parcelable.Creator<SessionCallbackLink> CREATOR =
            new Parcelable.Creator<SessionCallbackLink>() {
                @Override
                public SessionCallbackLink createFromParcel(Parcel in) {
                    return new SessionCallbackLink(in.readStrongBinder());
                }

                @Override
                public SessionCallbackLink[] newArray(int size) {
                    return new SessionCallbackLink[size];
                }
            };

    /**
     * Class for Stub implementation
     */
    abstract static class CallbackStub {
        /** Stub method for ISessionCallback.notifyCommand */
        public void onCommand(@NonNull String packageName, int pid, int uid,
                @NonNull ControllerCallbackLink caller, @NonNull String command,
                @Nullable Bundle args, @Nullable ResultReceiver cb) {
        }

        /** Stub method for ISessionCallback.notifyMediaButton */
        public void onMediaButton(@NonNull String packageName, int pid, int uid,
                @NonNull Intent mediaButtonIntent, int sequenceNumber,
                @Nullable ResultReceiver cb) {
        }

        /** Stub method for ISessionCallback.notifyMediaButtonFromController */
        public void onMediaButtonFromController(@NonNull String packageName, int pid, int uid,
                @NonNull ControllerCallbackLink caller, @NonNull Intent mediaButtonIntent) {
        }

        /** Stub method for ISessionCallback.notifyPrepare */
        public void onPrepare(@NonNull String packageName, int pid, int uid,
                @NonNull ControllerCallbackLink caller) {
        }

        /** Stub method for ISessionCallback.notifyPrepareFromMediaId */
        public void onPrepareFromMediaId(@NonNull String packageName, int pid, int uid,
                @NonNull ControllerCallbackLink caller, @NonNull String mediaId,
                @Nullable Bundle extras) {
        }

        /** Stub method for ISessionCallback.notifyPrepareFromSearch */
        public void onPrepareFromSearch(@NonNull String packageName, int pid, int uid,
                @NonNull ControllerCallbackLink caller, String query, @Nullable Bundle extras) {
        }

        /** Stub method for ISessionCallback.notifyPrepareFromUri */
        public void onPrepareFromUri(@NonNull String packageName, int pid, int uid,
                @NonNull ControllerCallbackLink caller, @NonNull Uri uri, @Nullable Bundle extras) {
        }

        /** Stub method for ISessionCallback.notifyPlay */
        public void onPlay(@NonNull String packageName, int pid, int uid,
                @NonNull ControllerCallbackLink caller) {
        }

        /** Stub method for ISessionCallback.notifyPlayFromMediaId */
        public void onPlayFromMediaId(@NonNull String packageName, int pid, int uid,
                @NonNull ControllerCallbackLink caller, @NonNull String mediaId,
                @Nullable Bundle extras) {
        }

        /** Stub method for ISessionCallback.notifyPlayFromSearch */
        public void onPlayFromSearch(@NonNull String packageName, int pid, int uid,
                @NonNull ControllerCallbackLink caller, String query, @Nullable Bundle extras) {
        }

        /** Stub method for ISessionCallback.notifyPlayFromUri */
        public void onPlayFromUri(@NonNull String packageName, int pid, int uid,
                @NonNull ControllerCallbackLink caller, @NonNull Uri uri, @Nullable Bundle extras) {
        }

        /** Stub method for ISessionCallback.notifySkipToTrack */
        public void onSkipToTrack(@NonNull String packageName, int pid, int uid,
                @NonNull ControllerCallbackLink caller, long id) {
        }

        /** Stub method for ISessionCallback.notifyPause */
        public void onPause(@NonNull String packageName, int pid, int uid,
                @NonNull ControllerCallbackLink caller) {
        }

        /** Stub method for ISessionCallback.notifyStop */
        public void onStop(@NonNull String packageName, int pid, int uid,
                @NonNull ControllerCallbackLink caller) {
        }

        /** Stub method for ISessionCallback.notifyNext */
        public void onNext(@NonNull String packageName, int pid, int uid,
                @NonNull ControllerCallbackLink caller) {
        }

        /** Stub method for ISessionCallback.notifyPrevious */
        public void onPrevious(@NonNull String packageName, int pid, int uid,
                @NonNull ControllerCallbackLink caller) {
        }

        /** Stub method for ISessionCallback.notifyFastForward */
        public void onFastForward(@NonNull String packageName, int pid, int uid,
                @NonNull ControllerCallbackLink caller) {
        }

        /** Stub method for ISessionCallback.notifyRewind */
        public void onRewind(@NonNull String packageName, int pid, int uid,
                @NonNull ControllerCallbackLink caller) {
        }

        /** Stub method for ISessionCallback.notifySeekTo */
        public void onSeekTo(@NonNull String packageName, int pid, int uid,
                @NonNull ControllerCallbackLink caller, long pos) {
        }

        /** Stub method for ISessionCallback.notifyRate */
        public void onRate(@NonNull String packageName, int pid, int uid,
                @NonNull ControllerCallbackLink caller, @NonNull Rating rating) {
        }

        /** Stub method for ISessionCallback.notifyCustomAction */
        public void onCustomAction(@NonNull String packageName, int pid, int uid,
                @NonNull ControllerCallbackLink caller, @NonNull String action,
                @Nullable Bundle args) {
        }

        /** Stub method for ISessionCallback.notifyAdjustVolume */
        public void onAdjustVolume(@NonNull String packageName, int pid, int uid,
                @NonNull ControllerCallbackLink caller, int direction) {
        }

        /** Stub method for ISessionCallback.notifySetVolumeTo */
        public void onSetVolumeTo(@NonNull String packageName, int pid, int uid,
                @NonNull ControllerCallbackLink caller, int value) {
        }
    }

    private class CallbackStubProxy extends ISessionCallback.Stub {
        @Override
        public void notifyCommand(String packageName, int pid, int uid,
                ControllerCallbackLink caller, String command, Bundle args, ResultReceiver cb) {
            ensureMediaControlPermission();
            final long token = Binder.clearCallingIdentity();
            try {
                mCallbackStub.onCommand(packageName, pid, uid, caller, command, args, cb);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void notifyMediaButton(String packageName, int pid, int uid,
                Intent mediaButtonIntent, int sequenceNumber, ResultReceiver cb) {
            ensureMediaControlPermission();
            final long token = Binder.clearCallingIdentity();
            try {
                mCallbackStub.onMediaButton(packageName, pid, uid, mediaButtonIntent,
                        sequenceNumber, cb);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void notifyMediaButtonFromController(String packageName, int pid, int uid,
                ControllerCallbackLink caller, Intent mediaButtonIntent) {
            ensureMediaControlPermission();
            final long token = Binder.clearCallingIdentity();
            try {
                mCallbackStub.onMediaButtonFromController(packageName, pid, uid, caller,
                        mediaButtonIntent);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void notifyPrepare(String packageName, int pid, int uid,
                ControllerCallbackLink caller) {
            ensureMediaControlPermission();
            final long token = Binder.clearCallingIdentity();
            try {
                mCallbackStub.onPrepare(packageName, pid, uid, caller);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void notifyPrepareFromMediaId(String packageName, int pid, int uid,
                ControllerCallbackLink caller, String mediaId, Bundle extras) {
            ensureMediaControlPermission();
            final long token = Binder.clearCallingIdentity();
            try {
                mCallbackStub.onPrepareFromMediaId(packageName, pid, uid, caller, mediaId, extras);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void notifyPrepareFromSearch(String packageName, int pid, int uid,
                ControllerCallbackLink caller, String query, Bundle extras) {
            ensureMediaControlPermission();
            final long token = Binder.clearCallingIdentity();
            try {
                mCallbackStub.onPrepareFromSearch(packageName, pid, uid, caller, query, extras);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void notifyPrepareFromUri(String packageName, int pid, int uid,
                ControllerCallbackLink caller, Uri uri, Bundle extras) {
            ensureMediaControlPermission();
            final long token = Binder.clearCallingIdentity();
            try {
                mCallbackStub.onPrepareFromUri(packageName, pid, uid, caller, uri, extras);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void notifyPlay(String packageName, int pid, int uid,
                ControllerCallbackLink caller) {
            ensureMediaControlPermission();
            final long token = Binder.clearCallingIdentity();
            try {
                mCallbackStub.onPlay(packageName, pid, uid, caller);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void notifyPlayFromMediaId(String packageName, int pid, int uid,
                ControllerCallbackLink caller, String mediaId, Bundle extras) {
            ensureMediaControlPermission();
            final long token = Binder.clearCallingIdentity();
            try {
                mCallbackStub.onPlayFromMediaId(packageName, pid, uid, caller, mediaId, extras);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void notifyPlayFromSearch(String packageName, int pid, int uid,
                ControllerCallbackLink caller, String query, Bundle extras) {
            ensureMediaControlPermission();
            final long token = Binder.clearCallingIdentity();
            try {
                mCallbackStub.onPlayFromSearch(packageName, pid, uid, caller, query, extras);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void notifyPlayFromUri(String packageName, int pid, int uid,
                ControllerCallbackLink caller, Uri uri, Bundle extras) {
            ensureMediaControlPermission();
            final long token = Binder.clearCallingIdentity();
            try {
                mCallbackStub.onPlayFromUri(packageName, pid, uid, caller, uri, extras);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void notifySkipToTrack(String packageName, int pid, int uid,
                ControllerCallbackLink caller, long id) {
            ensureMediaControlPermission();
            final long token = Binder.clearCallingIdentity();
            try {
                mCallbackStub.onSkipToTrack(packageName, pid, uid, caller, id);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void notifyPause(String packageName, int pid, int uid,
                ControllerCallbackLink caller) {
            ensureMediaControlPermission();
            final long token = Binder.clearCallingIdentity();
            try {
                mCallbackStub.onPause(packageName, pid, uid, caller);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void notifyStop(String packageName, int pid, int uid,
                ControllerCallbackLink caller) {
            ensureMediaControlPermission();
            final long token = Binder.clearCallingIdentity();
            try {
                mCallbackStub.onStop(packageName, pid, uid, caller);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void notifyNext(String packageName, int pid, int uid,
                ControllerCallbackLink caller) {
            ensureMediaControlPermission();
            final long token = Binder.clearCallingIdentity();
            try {
                mCallbackStub.onNext(packageName, pid, uid, caller);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void notifyPrevious(String packageName, int pid, int uid,
                ControllerCallbackLink caller) {
            ensureMediaControlPermission();
            final long token = Binder.clearCallingIdentity();
            try {
                mCallbackStub.onPrevious(packageName, pid, uid, caller);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void notifyFastForward(String packageName, int pid, int uid,
                ControllerCallbackLink caller) {
            ensureMediaControlPermission();
            final long token = Binder.clearCallingIdentity();
            try {
                mCallbackStub.onFastForward(packageName, pid, uid, caller);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void notifyRewind(String packageName, int pid, int uid,
                ControllerCallbackLink caller) {
            ensureMediaControlPermission();
            final long token = Binder.clearCallingIdentity();
            try {
                mCallbackStub.onRewind(packageName, pid, uid, caller);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void notifySeekTo(String packageName, int pid, int uid,
                ControllerCallbackLink caller, long pos) {
            ensureMediaControlPermission();
            final long token = Binder.clearCallingIdentity();
            try {
                mCallbackStub.onSeekTo(packageName, pid, uid, caller, pos);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void notifyRate(String packageName, int pid, int uid, ControllerCallbackLink caller,
                Rating rating) {
            ensureMediaControlPermission();
            final long token = Binder.clearCallingIdentity();
            try {
                mCallbackStub.onRate(packageName, pid, uid, caller, rating);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        public void notifyCustomAction(String packageName, int pid, int uid,
                ControllerCallbackLink caller, String action, Bundle args) {
            ensureMediaControlPermission();
            final long token = Binder.clearCallingIdentity();
            try {
                mCallbackStub.onCustomAction(packageName, pid, uid, caller, action, args);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void notifyAdjustVolume(String packageName, int pid, int uid,
                ControllerCallbackLink caller, int direction) {
            ensureMediaControlPermission();
            final long token = Binder.clearCallingIdentity();
            try {
                mCallbackStub.onAdjustVolume(packageName, pid, uid, caller, direction);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void notifySetVolumeTo(String packageName, int pid, int uid,
                ControllerCallbackLink caller, int value) {
            ensureMediaControlPermission();
            final long token = Binder.clearCallingIdentity();
            try {
                mCallbackStub.onSetVolumeTo(packageName, pid, uid, caller, value);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        private void ensureMediaControlPermission() {
            // Check if it's system server or has MEDIA_CONTENT_CONTROL.
            // Note that system server doesn't have MEDIA_CONTENT_CONTROL, so we need extra
            // check here.
            if (getCallingUid() == Process.SYSTEM_UID || mContext.checkCallingPermission(
                    android.Manifest.permission.MEDIA_CONTENT_CONTROL)
                    == PackageManager.PERMISSION_GRANTED) {
                return;
            }
            throw new SecurityException("Must hold the MEDIA_CONTENT_CONTROL permission.");
        }
    }
}
