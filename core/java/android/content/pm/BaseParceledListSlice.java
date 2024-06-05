/*
 * Copyright (C) 2011 The Android Open Source Project
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

package android.content.pm;

import android.compat.annotation.UnsupportedAppUsage;
import android.os.BadParcelableException;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Transfer a large list of Parcelable objects across an IPC.  Splits into
 * multiple transactions if needed.
 *
 * Caveat: for efficiency and security, all elements must be the same concrete type.
 * In order to avoid writing the class name of each object, we must ensure that
 * each object is the same type, or else unparceling then reparceling the data may yield
 * a different result if the class name encoded in the Parcelable is a Base type.
 * See b/17671747.
 *
 * @hide
 */
abstract class BaseParceledListSlice<T> implements Parcelable {
    private static final String TAG = "ParceledListSlice";
    private static final boolean DEBUG = false;

    private static final int MAX_IPC_SIZE = IBinder.getSuggestedMaxIpcSizeBytes();

    /**
     * As of 2024 and for some time, max size has been 64KB. If a single
     * element is too large, this class will write too big of Parcels,
     * so log. 64KB/4 is 16KB is still pretty big for a single element
     * (which could result in a ~64KB + 16KB = 80KB transaction). We may
     * want to reduce the warning size just in case. Though, 64KB is
     * already quite large for binder transactions, another strategy may
     * be needed.
     */
    private static final int WARN_ELM_SIZE = MAX_IPC_SIZE / 4;

    private List<T> mList;

    private int mInlineCountLimit = Integer.MAX_VALUE;

    private boolean mHasBeenParceled = false;

    public BaseParceledListSlice(List<T> list) {
        mList = list;
    }

    @SuppressWarnings("unchecked")
    BaseParceledListSlice(Parcel p, ClassLoader loader) {
        final int N = p.readInt();
        mList = new ArrayList<T>(N);
        if (DEBUG) Log.d(TAG, "Retrieving " + N + " items");
        if (N <= 0) {
            return;
        }

        Parcelable.Creator<?> creator = readParcelableCreator(p, loader);
        Class<?> listElementClass = null;

        int i = 0;
        while (i < N) {
            if (p.readInt() == 0) {
                break;
            }
            listElementClass = readVerifyAndAddElement(creator, p, loader, listElementClass);
            if (DEBUG) Log.d(TAG, "Read inline #" + i + ": " + mList.get(mList.size()-1));
            i++;
        }
        if (i >= N) {
            return;
        }
        final IBinder retriever = p.readStrongBinder();
        while (i < N) {
            if (DEBUG) Log.d(TAG, "Reading more @" + i + " of " + N + ": retriever=" + retriever);
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            data.writeInt(i);
            try {
                retriever.transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0);
                reply.readException();
                while (i < N && reply.readInt() != 0) {
                    listElementClass = readVerifyAndAddElement(creator, reply, loader,
                            listElementClass);
                    if (DEBUG) Log.d(TAG, "Read extra #" + i + ": " + mList.get(mList.size()-1));
                    i++;
                }
            } catch (RemoteException e) {
                throw new BadParcelableException(
                        "Failure retrieving array; only received " + i + " of " + N, e);
            } finally {
                reply.recycle();
                data.recycle();
            }
        }
    }

    private Class<?> readVerifyAndAddElement(Parcelable.Creator<?> creator, Parcel p,
            ClassLoader loader, Class<?> listElementClass) {
        final T parcelable = readCreator(creator, p, loader);
        if (listElementClass == null) {
            listElementClass = parcelable.getClass();
        } else {
            verifySameType(listElementClass, parcelable.getClass());
        }
        mList.add(parcelable);
        return listElementClass;
    }

    private T readCreator(Parcelable.Creator<?> creator, Parcel p, ClassLoader loader) {
        if (creator instanceof Parcelable.ClassLoaderCreator<?>) {
            Parcelable.ClassLoaderCreator<?> classLoaderCreator =
                    (Parcelable.ClassLoaderCreator<?>) creator;
            return (T) classLoaderCreator.createFromParcel(p, loader);
        }
        return (T) creator.createFromParcel(p);
    }

    private static void verifySameType(final Class<?> expected, final Class<?> actual) {
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException("Can't unparcel type "
                    + (actual == null ? null : actual.getName()) + " in list of type "
                    + (expected == null ? null : expected.getName()));
        }
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public List<T> getList() {
        return mList;
    }

    /**
     * Set a limit on the maximum number of entries in the array that will be included
     * inline in the initial parcelling of this object.
     */
    public void setInlineCountLimit(int maxCount) {
        mInlineCountLimit = maxCount;
    }

    /**
     * Write this to another Parcel. Note that this discards the internal Parcel
     * and should not be used anymore. This is so we can pass this to a Binder
     * where we won't have a chance to call recycle on this.
     *
     * This method can only be called once per BaseParceledListSlice to ensure that
     * the referenced list can be cleaned up before the recipient cleans up the
     * Binder reference.
     */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        if (mHasBeenParceled) {
            throw new IllegalStateException("Can't Parcel a ParceledListSlice more than once");
        }
        mHasBeenParceled = true;
        final int N = mList.size();
        final int callFlags = flags;
        dest.writeInt(N);
        if (DEBUG) Log.d(TAG, "Writing " + N + " items");
        if (N > 0) {
            final Class<?> listElementClass = mList.get(0).getClass();
            writeParcelableCreator(mList.get(0), dest);
            int i = 0;
            while (i < N && i < mInlineCountLimit && dest.dataSize() < MAX_IPC_SIZE) {
                dest.writeInt(1);

                final T parcelable = mList.get(i);
                verifySameType(listElementClass, parcelable.getClass());
                writeElement(parcelable, dest, callFlags);

                if (DEBUG) Log.d(TAG, "Wrote inline #" + i + ": " + mList.get(i));
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

                            // note: this logic ensures if there are enough elements in the list,
                            // we will always write over the max IPC size. This is dangerous
                            // when there are large elements.
                            while (i < N && reply.dataSize() < MAX_IPC_SIZE) {
                                reply.writeInt(1);

                                int preWriteSize = reply.dataSize();

                                final T parcelable = mList.get(i);
                                verifySameType(listElementClass, parcelable.getClass());
                                writeElement(parcelable, reply, callFlags);

                                int elmSize = reply.dataSize() - preWriteSize;
                                if (elmSize >= WARN_ELM_SIZE) {
                                    Log.w(TAG, "Element #" + i + " is " + elmSize + " bytes.");
                                }

                                if (DEBUG) Log.d(TAG, "Wrote extra #" + i + ": " + mList.get(i));
                                i++;
                            }
                            if (i < N) {
                                if (DEBUG) Log.d(TAG, "Breaking @" + i + " of " + N);
                                reply.writeInt(0);
                            } else {
                                if (DEBUG) Log.d(TAG, "Transfer done, clearing mList reference");
                                mList = null;
                            }
                            if (reply.dataSize() >= MAX_IPC_SIZE + WARN_ELM_SIZE) {
                                Log.w(TAG, "Overly large reply size: " + reply.dataSize());
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

    protected abstract void writeElement(T parcelable, Parcel reply, int callFlags);

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    protected abstract void writeParcelableCreator(T parcelable, Parcel dest);

    protected abstract Parcelable.Creator<?> readParcelableCreator(Parcel from, ClassLoader loader);
}
