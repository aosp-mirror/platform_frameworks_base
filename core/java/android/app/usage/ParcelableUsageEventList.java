/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package android.app.usage;

import android.annotation.NonNull;
import android.app.usage.UsageEvents.Event;
import android.content.res.Configuration;
import android.os.BadParcelableException;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * This is a copied version of BaseParceledListSlice with specific
 * {@link UsageEvents.Event} instance that used to transfer the large
 * list of {@link UsageEvents.Event} objects across an IPC. Splits
 * into multiple transactions if needed.
 *
 * @see BasedParceledListSlice
 *
 * @hide
 */
public final class ParcelableUsageEventList implements Parcelable {
    private static final String TAG = "ParcelableUsageEventList";
    private static final boolean DEBUG = false;
    private static final boolean DEBUG_ALL = false;

    private static final int MAX_IPC_SIZE = IBinder.getSuggestedMaxIpcSizeBytes();

    private List<Event> mList;

    public ParcelableUsageEventList(@NonNull List<Event> list) {
        if (list == null) {
            throw new IllegalArgumentException("Empty list");
        }
        mList = list;
    }

    private ParcelableUsageEventList(Parcel in) {
        final int N = in.readInt();
        mList = new ArrayList<>(N);
        if (DEBUG) Log.d(TAG, "Retrieving " + N + " items");
        if (N <= 0) {
            return;
        }

        int i = 0;
        while (i < N) {
            if (in.readInt() == 0) {
                break;
            }
            mList.add(readEventFromParcel(in));
            if (DEBUG_ALL) Log.d(TAG, "Read inline #" + i + ": " + mList.get(mList.size() - 1));
            i++;
        }
        if (DEBUG) {
            Log.d(TAG, "Read " + mList.size() + " inline UsageEvents"
                    + ", total N=" + N + " UsageEvents");
        }
        if (i >= N) {
            return;
        }
        final IBinder retriever = in.readStrongBinder();
        while (i < N) {
            if (DEBUG) Log.d(TAG, "Reading more @" + i + " of " + N + ": retriever=" + retriever);
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            data.writeInt(i);
            try {
                retriever.transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0);
                reply.readException();
                int count = 0;
                while (i < N && reply.readInt() != 0) {
                    mList.add(readEventFromParcel(reply));
                    if (DEBUG_ALL) {
                        Log.d(TAG, "Read extra #" + i + ": " + mList.get(mList.size() - 1));
                    }
                    i++;
                    count++;
                }
                if (DEBUG) Log.d(TAG, "Read extra @" + count + " of " + N);
            } catch (RemoteException e) {
                throw new BadParcelableException(
                    "Failure retrieving array; only received " + i + " of " + N, e);
            } finally {
                reply.recycle();
                data.recycle();
            }
        }
        if (DEBUG) Log.d(TAG, "Finish reading total " + i + " UsageEvents");
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        final int N = mList.size();
        final int callFlags = flags;
        dest.writeInt(N);
        if (DEBUG) Log.d(TAG, "Writing " + N + " items");
        if (N > 0) {
            int i = 0;
            while (i < N && dest.dataSize() < MAX_IPC_SIZE) {
                dest.writeInt(1);

                final Event event = mList.get(i);
                writeEventToParcel(event, dest, callFlags);

                if (DEBUG_ALL) Log.d(TAG, "Wrote inline #" + i + ": " + mList.get(i));
                i++;
            }
            if (i < N) {
                dest.writeInt(0);
                Binder retriever = new Binder() {
                    @Override
                    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                            throws RemoteException {
                        if (code != FIRST_CALL_TRANSACTION) {
                            return super.onTransact(code, data, reply, flags);
                        } else if (mList == null) {
                            throw new IllegalArgumentException("Attempt to transfer null list, "
                                + "did transfer finish?");
                        }
                        int i = data.readInt();

                        if (DEBUG) {
                            Log.d(TAG, "Writing more @" + i + " of " + N + " to "
                                    + Binder.getCallingPid() + ", sender=" + this);
                        }

                        try {
                            reply.writeNoException();
                            int count = 0;
                            while (i < N && reply.dataSize() < MAX_IPC_SIZE) {
                                reply.writeInt(1);

                                final Event event = mList.get(i);
                                writeEventToParcel(event, reply, callFlags);

                                if (DEBUG_ALL) {
                                    Log.d(TAG, "Wrote extra #" + i + ": " + mList.get(i));
                                }
                                i++;
                                count++;
                            }
                            if (i < N) {
                                if (DEBUG) {
                                    Log.d(TAG, "Breaking @" + i + " of " + N
                                            + "(count = " + count + ")");
                                }
                                reply.writeInt(0);
                            } else {
                                if (DEBUG) Log.d(TAG, "Transfer done, clearing mList reference");
                                mList = null;
                            }
                        } catch (RuntimeException e) {
                            if (DEBUG) Log.d(TAG, "Transfer failed, clearing mList reference");
                            mList = null;
                            throw e;
                        }
                        return true;
                    }
                };
                if (DEBUG) Log.d(TAG, "Breaking @" + i + " of " + N + ": retriever=" + retriever);
                dest.writeStrongBinder(retriever);
            }
        }
    }

    public List<Event> getList() {
        return mList;
    }

    public static final Parcelable.Creator<ParcelableUsageEventList> CREATOR =
            new Parcelable.Creator<ParcelableUsageEventList>() {
                public ParcelableUsageEventList createFromParcel(Parcel in) {
                    return new ParcelableUsageEventList(in);
                }

                @Override
                public ParcelableUsageEventList[] newArray(int size) {
                    return new ParcelableUsageEventList[size];
                }
            };

    private Event readEventFromParcel(Parcel in) {
        final Event event = new Event();
        event.mPackage = in.readString();
        event.mClass = in.readString();
        event.mInstanceId = in.readInt();
        event.mTaskRootPackage = in.readString();
        event.mTaskRootClass = in.readString();
        event.mEventType = in.readInt();
        event.mTimeStamp = in.readLong();

        // Fill out the event-dependant fields.
        event.mConfiguration = null;
        event.mShortcutId = null;
        event.mAction = null;
        event.mContentType = null;
        event.mContentAnnotations = null;
        event.mNotificationChannelId = null;
        event.mLocusId = null;
        event.mExtras = null;

        switch (event.mEventType) {
            case Event.CONFIGURATION_CHANGE -> {
                event.mConfiguration = Configuration.CREATOR.createFromParcel(in);
            }
            case Event.SHORTCUT_INVOCATION -> event.mShortcutId = in.readString();
            case Event.CHOOSER_ACTION -> {
                event.mAction = in.readString();
                event.mContentType = in.readString();
                event.mContentAnnotations = in.readStringArray();
            }
            case Event.STANDBY_BUCKET_CHANGED -> event.mBucketAndReason = in.readInt();
            case Event.NOTIFICATION_INTERRUPTION -> event.mNotificationChannelId = in.readString();
            case Event.LOCUS_ID_SET -> event.mLocusId = in.readString();
            case Event.USER_INTERACTION -> {
                if (in.readInt() != 0) {
                    event.mExtras = in.readPersistableBundle(getClass().getClassLoader());
                }
            }
        }
        event.mFlags = in.readInt();

        return event;
    }

    private void writeEventToParcel(@NonNull Event event, @NonNull Parcel dest, int flags) {
        dest.writeString(event.mPackage);
        dest.writeString(event.mClass);
        dest.writeInt(event.mInstanceId);
        dest.writeString(event.mTaskRootPackage);
        dest.writeString(event.mTaskRootClass);
        dest.writeInt(event.mEventType);
        dest.writeLong(event.mTimeStamp);

        switch (event.mEventType) {
            case Event.CONFIGURATION_CHANGE -> event.mConfiguration.writeToParcel(dest, flags);
            case Event.SHORTCUT_INVOCATION -> dest.writeString(event.mShortcutId);
            case Event.CHOOSER_ACTION -> {
                dest.writeString(event.mAction);
                dest.writeString(event.mContentType);
                dest.writeStringArray(event.mContentAnnotations);
            }
            case Event.STANDBY_BUCKET_CHANGED -> dest.writeInt(event.mBucketAndReason);
            case Event.NOTIFICATION_INTERRUPTION -> dest.writeString(event.mNotificationChannelId);
            case Event.LOCUS_ID_SET -> dest.writeString(event.mLocusId);
            case Event.USER_INTERACTION -> {
                if (event.mExtras != null) {
                    dest.writeInt(1);
                    dest.writePersistableBundle(event.mExtras);
                } else {
                    dest.writeInt(0);
                }
            }
        }
        dest.writeInt(event.mFlags);
    }
}
