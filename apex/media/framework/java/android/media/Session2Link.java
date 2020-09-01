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

import android.annotation.NonNull;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.util.Log;

import java.util.Objects;

/**
 * Handles incoming commands from {@link MediaController2} to {@link MediaSession2}.
 * @hide
 */
// @SystemApi
public final class Session2Link implements Parcelable {
    private static final String TAG = "Session2Link";
    private static final boolean DEBUG = MediaSession2.DEBUG;

    public static final @android.annotation.NonNull Parcelable.Creator<Session2Link> CREATOR =
            new Parcelable.Creator<Session2Link>() {
                @Override
                public Session2Link createFromParcel(Parcel in) {
                    return new Session2Link(in);
                }

                @Override
                public Session2Link[] newArray(int size) {
                    return new Session2Link[size];
                }
            };

    private final MediaSession2 mSession;
    private final IMediaSession2 mISession;

    public Session2Link(MediaSession2 session) {
        mSession = session;
        mISession = new Session2Stub();
    }

    Session2Link(Parcel in) {
        mSession = null;
        mISession = IMediaSession2.Stub.asInterface(in.readStrongBinder());
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeStrongBinder(mISession.asBinder());
    }

    @Override
    public int hashCode() {
        return mISession.asBinder().hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof Session2Link)) {
            return false;
        }
        Session2Link other = (Session2Link) obj;
        return Objects.equals(mISession.asBinder(), other.mISession.asBinder());
    }

    /** Link to death with mISession */
    public void linkToDeath(@NonNull IBinder.DeathRecipient recipient, int flags) {
        if (mISession != null) {
            try {
                mISession.asBinder().linkToDeath(recipient, flags);
            } catch (RemoteException e) {
                if (DEBUG) {
                    Log.d(TAG, "Session died too early.", e);
                }
            }
        }
    }

    /** Unlink to death with mISession */
    public boolean unlinkToDeath(@NonNull IBinder.DeathRecipient recipient, int flags) {
        if (mISession != null) {
            return mISession.asBinder().unlinkToDeath(recipient, flags);
        }
        return true;
    }

    /** Interface method for IMediaSession2.connect */
    public void connect(final Controller2Link caller, int seq, Bundle connectionRequest) {
        try {
            mISession.connect(caller, seq, connectionRequest);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /** Interface method for IMediaSession2.disconnect */
    public void disconnect(final Controller2Link caller, int seq) {
        try {
            mISession.disconnect(caller, seq);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /** Interface method for IMediaSession2.sendSessionCommand */
    public void sendSessionCommand(final Controller2Link caller, final int seq,
            final Session2Command command, final Bundle args, ResultReceiver resultReceiver) {
        try {
            mISession.sendSessionCommand(caller, seq, command, args, resultReceiver);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /** Interface method for IMediaSession2.sendSessionCommand */
    public void cancelSessionCommand(final Controller2Link caller, final int seq) {
        try {
            mISession.cancelSessionCommand(caller, seq);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /** Stub implementation for IMediaSession2.connect */
    public void onConnect(final Controller2Link caller, int pid, int uid, int seq,
            Bundle connectionRequest) {
        mSession.onConnect(caller, pid, uid, seq, connectionRequest);
    }

    /** Stub implementation for IMediaSession2.disconnect */
    public void onDisconnect(final Controller2Link caller, int seq) {
        mSession.onDisconnect(caller, seq);
    }

    /** Stub implementation for IMediaSession2.sendSessionCommand */
    public void onSessionCommand(final Controller2Link caller, final int seq,
            final Session2Command command, final Bundle args, ResultReceiver resultReceiver) {
        mSession.onSessionCommand(caller, seq, command, args, resultReceiver);
    }

    /** Stub implementation for IMediaSession2.cancelSessionCommand */
    public void onCancelCommand(final Controller2Link caller, final int seq) {
        mSession.onCancelCommand(caller, seq);
    }

    private class Session2Stub extends IMediaSession2.Stub {
        @Override
        public void connect(final Controller2Link caller, int seq, Bundle connectionRequest) {
            if (caller == null || connectionRequest == null) {
                return;
            }
            final int pid = Binder.getCallingPid();
            final int uid = Binder.getCallingUid();
            final long token = Binder.clearCallingIdentity();
            try {
                Session2Link.this.onConnect(caller, pid, uid, seq, connectionRequest);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void disconnect(final Controller2Link caller, int seq) {
            if (caller == null) {
                return;
            }
            final long token = Binder.clearCallingIdentity();
            try {
                Session2Link.this.onDisconnect(caller, seq);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void sendSessionCommand(final Controller2Link caller, final int seq,
                final Session2Command command, final Bundle args, ResultReceiver resultReceiver) {
            if (caller == null) {
                return;
            }
            final long token = Binder.clearCallingIdentity();
            try {
                Session2Link.this.onSessionCommand(caller, seq, command, args, resultReceiver);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void cancelSessionCommand(final Controller2Link caller, final int seq) {
            if (caller == null) {
                return;
            }
            final long token = Binder.clearCallingIdentity();
            try {
                Session2Link.this.onCancelCommand(caller, seq);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
    }
}
