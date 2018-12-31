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

package android.media.session;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.content.Intent;
import android.media.Rating;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.ResultReceiver;

/**
 * Handles incoming commands to {@link MediaSession.Callback}.
 * @hide
 */
@SystemApi
public final class SessionCallbackLink implements Parcelable {
    CallbackStub mCallbackStub;
    ISessionCallback mISessionCallback;

    /**
     * Creator for stub (Callee)
     */
    public SessionCallbackLink(@NonNull CallbackStub callbackStub) {
        mCallbackStub = callbackStub;
        mISessionCallback = new CallbackStubProxy();
    }

    /**
     * Creator for interface (Caller)
     */
    SessionCallbackLink(Parcel in) {
        mCallbackStub = null;
        mISessionCallback = ISessionCallback.Stub.asInterface(in.readStrongBinder());
    }

    /** Interface method for ISessionCallback.notifyCommand */
    public void notifyCommand(String packageName, int pid, int uid,
            ControllerCallbackLink caller, String command, Bundle args, ResultReceiver cb) {
        try {
            mISessionCallback.notifyCommand(packageName, pid, uid, caller, command, args, cb);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /** Interface method for ISessionCallback.notifyMediaButton */
    public void notifyMediaButton(String packageName, int pid, int uid,
            Intent mediaButtonIntent, int sequenceNumber, ResultReceiver cb) {
        try {
            mISessionCallback.notifyMediaButton(packageName, pid, uid, mediaButtonIntent,
                    sequenceNumber, cb);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /** Interface method for ISessionCallback.notifyMediaButtonFromController */
    public void notifyMediaButtonFromController(String packageName, int pid, int uid,
            ControllerCallbackLink caller, Intent mediaButtonIntent) {
        try {
            mISessionCallback.notifyMediaButtonFromController(packageName, pid, uid, caller,
                    mediaButtonIntent);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /** Interface method for ISessionCallback.notifyPrepare */
    public void notifyPrepare(String packageName, int pid, int uid,
            ControllerCallbackLink caller) {
        try {
            mISessionCallback.notifyPrepare(packageName, pid, uid, caller);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /** Interface method for ISessionCallback.notifyPrepareFromMediaId */
    public void notifyPrepareFromMediaId(String packageName, int pid, int uid,
            ControllerCallbackLink caller, String mediaId, Bundle extras) {
        try {
            mISessionCallback.notifyPrepareFromMediaId(packageName, pid, uid, caller, mediaId,
                    extras);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /** Interface method for ISessionCallback.notifyPrepareFromSearch */
    public void notifyPrepareFromSearch(String packageName, int pid, int uid,
            ControllerCallbackLink caller, String query, Bundle extras) {
        try {
            mISessionCallback.notifyPrepareFromSearch(packageName, pid, uid, caller, query, extras);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /** Interface method for ISessionCallback.notifyPrepareFromUri */
    public void notifyPrepareFromUri(String packageName, int pid, int uid,
            ControllerCallbackLink caller, Uri uri, Bundle extras) {
        try {
            mISessionCallback.notifyPrepareFromUri(packageName, pid, uid, caller, uri, extras);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /** Interface method for ISessionCallback.notifyPlay */
    public void notifyPlay(String packageName, int pid, int uid,
            ControllerCallbackLink caller) {
        try {
            mISessionCallback.notifyPlay(packageName, pid, uid, caller);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /** Interface method for ISessionCallback.notifyPlayFromMediaId */
    public void notifyPlayFromMediaId(String packageName, int pid, int uid,
            ControllerCallbackLink caller, String mediaId, Bundle extras) {
        try {
            mISessionCallback.notifyPlayFromMediaId(packageName, pid, uid, caller, mediaId, extras);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /** Interface method for ISessionCallback.notifyPlayFromSearch */
    public void notifyPlayFromSearch(String packageName, int pid, int uid,
            ControllerCallbackLink caller, String query, Bundle extras) {
        try {
            mISessionCallback.notifyPlayFromSearch(packageName, pid, uid, caller, query, extras);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /** Interface method for ISessionCallback.notifyPlayFromUri */
    public void notifyPlayFromUri(String packageName, int pid, int uid,
            ControllerCallbackLink caller, Uri uri, Bundle extras) {
        try {
            mISessionCallback.notifyPlayFromUri(packageName, pid, uid, caller, uri, extras);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /** Interface method for ISessionCallback.notifySkipToTrack */
    public void notifySkipToTrack(String packageName, int pid, int uid,
            ControllerCallbackLink caller, long id) {
        try {
            mISessionCallback.notifySkipToTrack(packageName, pid, uid, caller, id);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /** Interface method for ISessionCallback.notifyPause */
    public void notifyPause(String packageName, int pid, int uid,
            ControllerCallbackLink caller) {
        try {
            mISessionCallback.notifyPause(packageName, pid, uid, caller);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /** Interface method for ISessionCallback.notifyStop */
    public void notifyStop(String packageName, int pid, int uid,
            ControllerCallbackLink caller) {
        try {
            mISessionCallback.notifyStop(packageName, pid, uid, caller);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /** Interface method for ISessionCallback.notifyNext */
    public void notifyNext(String packageName, int pid, int uid,
            ControllerCallbackLink caller) {
        try {
            mISessionCallback.notifyNext(packageName, pid, uid, caller);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /** Interface method for ISessionCallback.notifyPrevious */
    public void notifyPrevious(String packageName, int pid, int uid,
            ControllerCallbackLink caller) {
        try {
            mISessionCallback.notifyPrevious(packageName, pid, uid, caller);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /** Interface method for ISessionCallback.notifyFastForward */
    public void notifyFastForward(String packageName, int pid, int uid,
            ControllerCallbackLink caller) {
        try {
            mISessionCallback.notifyFastForward(packageName, pid, uid, caller);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /** Interface method for ISessionCallback.notifyRewind */
    public void notifyRewind(String packageName, int pid, int uid,
            ControllerCallbackLink caller) {
        try {
            mISessionCallback.notifyRewind(packageName, pid, uid, caller);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /** Interface method for ISessionCallback.notifySeekTo */
    public void notifySeekTo(String packageName, int pid, int uid,
            ControllerCallbackLink caller, long pos) {
        try {
            mISessionCallback.notifySeekTo(packageName, pid, uid, caller, pos);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /** Interface method for ISessionCallback.notifyRate */
    public void notifyRate(String packageName, int pid, int uid, ControllerCallbackLink caller,
            Rating rating) {
        try {
            mISessionCallback.notifyRate(packageName, pid, uid, caller, rating);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /** Interface method for ISessionCallback.notifyCustomAction */
    public void notifyCustomAction(String packageName, int pid, int uid,
            ControllerCallbackLink caller, String action, Bundle args) {
        try {
            mISessionCallback.notifyCustomAction(packageName, pid, uid, caller, action, args);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /** Interface method for ISessionCallback.notifyAdjustVolume */
    public void notifyAdjustVolume(String packageName, int pid, int uid,
            ControllerCallbackLink caller, int direction) {
        try {
            mISessionCallback.notifyAdjustVolume(packageName, pid, uid, caller, direction);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /** Interface method for ISessionCallback.notifySetVolumeTo */
    public void notifySetVolumeTo(String packageName, int pid, int uid,
            ControllerCallbackLink caller, int value) {
        try {
            mISessionCallback.notifySetVolumeTo(packageName, pid, uid, caller, value);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /** Gets the binder */
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
                    return new SessionCallbackLink(in);
                }

                @Override
                public SessionCallbackLink[] newArray(int size) {
                    return new SessionCallbackLink[size];
                }
            };

    /**
     * Class for Stub implementation
     */
    public abstract static class CallbackStub {
        /** Stub method for ISessionCallback.notifyCommand */
        public void onCommand(String packageName, int pid, int uid, ControllerCallbackLink caller,
                String command, Bundle args, ResultReceiver cb) {
        }

        /** Stub method for ISessionCallback.notifyMediaButton */
        public void onMediaButton(String packageName, int pid, int uid, Intent mediaButtonIntent,
                int sequenceNumber, ResultReceiver cb) {
        }

        /** Stub method for ISessionCallback.notifyMediaButtonFromController */
        public void onMediaButtonFromController(String packageName, int pid, int uid,
                ControllerCallbackLink caller, Intent mediaButtonIntent) {
        }

        /** Stub method for ISessionCallback.notifyPrepare */
        public void onPrepare(String packageName, int pid, int uid, ControllerCallbackLink caller) {
        }

        /** Stub method for ISessionCallback.notifyPrepareFromMediaId */
        public void onPrepareFromMediaId(String packageName, int pid, int uid,
                ControllerCallbackLink caller, String mediaId, Bundle extras) {
        }

        /** Stub method for ISessionCallback.notifyPrepareFromSearch */
        public void onPrepareFromSearch(String packageName, int pid, int uid,
                ControllerCallbackLink caller, String query, Bundle extras) {
        }

        /** Stub method for ISessionCallback.notifyPrepareFromUri */
        public void onPrepareFromUri(String packageName, int pid, int uid,
                ControllerCallbackLink caller, Uri uri, Bundle extras) {
        }

        /** Stub method for ISessionCallback.notifyPlay */
        public void onPlay(String packageName, int pid, int uid, ControllerCallbackLink caller) {
        }

        /** Stub method for ISessionCallback.notifyPlayFromMediaId */
        public void onPlayFromMediaId(String packageName, int pid, int uid,
                ControllerCallbackLink caller, String mediaId, Bundle extras) {
        }

        /** Stub method for ISessionCallback.notifyPlayFromSearch */
        public void onPlayFromSearch(String packageName, int pid, int uid,
                ControllerCallbackLink caller, String query, Bundle extras) {
        }

        /** Stub method for ISessionCallback.notifyPlayFromUri */
        public void onPlayFromUri(String packageName, int pid, int uid,
                ControllerCallbackLink caller, Uri uri, Bundle extras) {
        }

        /** Stub method for ISessionCallback.notifySkipToTrack */
        public void onSkipToTrack(String packageName, int pid, int uid,
                ControllerCallbackLink caller, long id) {
        }

        /** Stub method for ISessionCallback.notifyPause */
        public void onPause(String packageName, int pid, int uid, ControllerCallbackLink caller) {
        }

        /** Stub method for ISessionCallback.notifyStop */
        public void onStop(String packageName, int pid, int uid, ControllerCallbackLink caller) {
        }

        /** Stub method for ISessionCallback.notifyNext */
        public void onNext(String packageName, int pid, int uid, ControllerCallbackLink caller) {
        }

        /** Stub method for ISessionCallback.notifyPrevious */
        public void onPrevious(String packageName, int pid, int uid,
                ControllerCallbackLink caller) {
        }

        /** Stub method for ISessionCallback.notifyFastForward */
        public void onFastForward(String packageName, int pid, int uid,
                ControllerCallbackLink caller) {
        }

        /** Stub method for ISessionCallback.notifyRewind */
        public void onRewind(String packageName, int pid, int uid, ControllerCallbackLink caller) {
        }

        /** Stub method for ISessionCallback.notifySeekTo */
        public void onSeekTo(String packageName, int pid, int uid, ControllerCallbackLink caller,
                long pos) {
        }

        /** Stub method for ISessionCallback.notifyRate */
        public void onRate(String packageName, int pid, int uid, ControllerCallbackLink caller,
                Rating rating) {
        }

        /** Stub method for ISessionCallback.notifyCustomAction */
        public void onCustomAction(String packageName, int pid, int uid,
                ControllerCallbackLink caller, String action, Bundle args) {
        }

        /** Stub method for ISessionCallback.notifyAdjustVolume */
        public void onAdjustVolume(String packageName, int pid, int uid,
                ControllerCallbackLink caller, int direction) {
        }

        /** Stub method for ISessionCallback.notifySetVolumeTo */
        public void onSetVolumeTo(String packageName, int pid, int uid,
                ControllerCallbackLink caller, int value) {
        }
    }

    private class CallbackStubProxy extends ISessionCallback.Stub {
        @Override
        public void notifyCommand(String packageName, int pid, int uid,
                ControllerCallbackLink caller, String command, Bundle args, ResultReceiver cb) {
            mCallbackStub.onCommand(packageName, pid, uid, caller, command, args, cb);
        }

        @Override
        public void notifyMediaButton(String packageName, int pid, int uid,
                Intent mediaButtonIntent, int sequenceNumber, ResultReceiver cb) {
            mCallbackStub.onMediaButton(packageName, pid, uid, mediaButtonIntent, sequenceNumber,
                    cb);
        }

        @Override
        public void notifyMediaButtonFromController(String packageName, int pid, int uid,
                ControllerCallbackLink caller, Intent mediaButtonIntent) {
            mCallbackStub.onMediaButtonFromController(packageName, pid, uid, caller,
                    mediaButtonIntent);
        }

        @Override
        public void notifyPrepare(String packageName, int pid, int uid,
                ControllerCallbackLink caller) {
            mCallbackStub.onPrepare(packageName, pid, uid, caller);
        }

        @Override
        public void notifyPrepareFromMediaId(String packageName, int pid, int uid,
                ControllerCallbackLink caller, String mediaId, Bundle extras) {
            mCallbackStub.onPrepareFromMediaId(packageName, pid, uid, caller, mediaId, extras);
        }

        @Override
        public void notifyPrepareFromSearch(String packageName, int pid, int uid,
                ControllerCallbackLink caller, String query, Bundle extras) {
            mCallbackStub.onPrepareFromSearch(packageName, pid, uid, caller, query, extras);
        }

        @Override
        public void notifyPrepareFromUri(String packageName, int pid, int uid,
                ControllerCallbackLink caller, Uri uri, Bundle extras) {
            mCallbackStub.onPrepareFromUri(packageName, pid, uid, caller, uri, extras);
        }

        @Override
        public void notifyPlay(String packageName, int pid, int uid,
                ControllerCallbackLink caller) {
            mCallbackStub.onPlay(packageName, pid, uid, caller);
        }

        @Override
        public void notifyPlayFromMediaId(String packageName, int pid, int uid,
                ControllerCallbackLink caller, String mediaId, Bundle extras) {
            mCallbackStub.onPlayFromMediaId(packageName, pid, uid, caller, mediaId, extras);
        }

        @Override
        public void notifyPlayFromSearch(String packageName, int pid, int uid,
                ControllerCallbackLink caller, String query, Bundle extras) {
            mCallbackStub.onPlayFromSearch(packageName, pid, uid, caller, query, extras);
        }

        @Override
        public void notifyPlayFromUri(String packageName, int pid, int uid,
                ControllerCallbackLink caller, Uri uri, Bundle extras) {
            mCallbackStub.onPlayFromUri(packageName, pid, uid, caller, uri, extras);
        }

        @Override
        public void notifySkipToTrack(String packageName, int pid, int uid,
                ControllerCallbackLink caller, long id) {
            mCallbackStub.onSkipToTrack(packageName, pid, uid, caller, id);
        }

        @Override
        public void notifyPause(String packageName, int pid, int uid,
                ControllerCallbackLink caller) {
            mCallbackStub.onPause(packageName, pid, uid, caller);
        }

        @Override
        public void notifyStop(String packageName, int pid, int uid,
                ControllerCallbackLink caller) {
            mCallbackStub.onStop(packageName, pid, uid, caller);
        }

        @Override
        public void notifyNext(String packageName, int pid, int uid,
                ControllerCallbackLink caller) {
            mCallbackStub.onNext(packageName, pid, uid, caller);
        }

        @Override
        public void notifyPrevious(String packageName, int pid, int uid,
                ControllerCallbackLink caller) {
            mCallbackStub.onPrevious(packageName, pid, uid, caller);
        }

        @Override
        public void notifyFastForward(String packageName, int pid, int uid,
                ControllerCallbackLink caller) {
            mCallbackStub.onFastForward(packageName, pid, uid, caller);
        }

        @Override
        public void notifyRewind(String packageName, int pid, int uid,
                ControllerCallbackLink caller) {
            mCallbackStub.onRewind(packageName, pid, uid, caller);
        }

        @Override
        public void notifySeekTo(String packageName, int pid, int uid,
                ControllerCallbackLink caller, long pos) {
            mCallbackStub.onSeekTo(packageName, pid, uid, caller, pos);
        }

        @Override
        public void notifyRate(String packageName, int pid, int uid, ControllerCallbackLink caller,
                Rating rating) {
            mCallbackStub.onRate(packageName, pid, uid, caller, rating);
        }

        @Override
        public void notifyCustomAction(String packageName, int pid, int uid,
                ControllerCallbackLink caller, String action, Bundle args) {
            mCallbackStub.onCustomAction(packageName, pid, uid, caller, action, args);
        }

        @Override
        public void notifyAdjustVolume(String packageName, int pid, int uid,
                ControllerCallbackLink caller, int direction) {
            mCallbackStub.onAdjustVolume(packageName, pid, uid, caller, direction);
        }

        @Override
        public void notifySetVolumeTo(String packageName, int pid, int uid,
                ControllerCallbackLink caller, int value) {
            mCallbackStub.onSetVolumeTo(packageName, pid, uid, caller, value);
        }
    }
}
