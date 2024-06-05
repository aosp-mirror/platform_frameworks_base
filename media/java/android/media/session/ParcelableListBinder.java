/*
 * Copyright 2020 The Android Open Source Project
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
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;

import com.android.internal.annotations.GuardedBy;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Binder to receive a list that has a large number of {@link Parcelable} items.
 *
 * It's similar to {@link android.content.pm.ParceledListSlice}, but transactions are performed in
 * the opposite direction.
 *
 * @param <T> the type of {@link Parcelable}
 * @hide
 */
public class ParcelableListBinder<T extends Parcelable> extends Binder {

    private static final int SUGGESTED_MAX_IPC_SIZE = IBinder.getSuggestedMaxIpcSizeBytes();

    private static final int END_OF_PARCEL = 0;
    private static final int ITEM_CONTINUED = 1;

    private final Class<T> mListElementsClass;
    private final Consumer<List<T>> mConsumer;

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private final List<T> mList = new ArrayList<>();

    @GuardedBy("mLock")
    private int mCount;

    @GuardedBy("mLock")
    private boolean mConsumed;

    /**
     * Creates an instance.
     *
     * @param listElementsClass the class of the list elements.
     * @param consumer a consumer that consumes the list received
     */
    public ParcelableListBinder(Class<T> listElementsClass, @NonNull Consumer<List<T>> consumer) {
        mListElementsClass = listElementsClass;
        mConsumer = consumer;
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        if (code != FIRST_CALL_TRANSACTION) {
            return super.onTransact(code, data, reply, flags);
        }
        List<T> listToBeConsumed;
        synchronized (mLock) {
            if (mConsumed) {
                return false;
            }
            int i = mList.size();
            if (i == 0) {
                mCount = data.readInt();
            }
            while (i < mCount && data.readInt() != END_OF_PARCEL) {
                Object object = data.readParcelable(null);
                if (mListElementsClass.isAssignableFrom(object.getClass())) {
                    // Checking list items are of compaitible types to validate against malicious
                    // apps calling it directly via reflection with non compilable items.
                    // See b/317048338 for more details
                    mList.add((T) object);
                }
                i++;
            }
            if (i >= mCount) {
                listToBeConsumed = mList;
                mConsumed = true;
            } else {
                listToBeConsumed = null;
            }
        }
        if (listToBeConsumed != null) {
            mConsumer.accept(listToBeConsumed);
        }
        return true;
    }

    /**
     * Sends a list of {@link Parcelable} to a binder.
     *
     * @param binder a binder interface backed by {@link ParcelableListBinder}
     * @param list a list to send
     */
    public static <T extends Parcelable> void send(@NonNull IBinder binder, @NonNull List<T> list)
            throws RemoteException {
        int count = list.size();
        int i = 0;
        do {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            if (i == 0) {
                data.writeInt(count);
            }
            while (i < count && data.dataSize() < SUGGESTED_MAX_IPC_SIZE) {
                data.writeInt(ITEM_CONTINUED);
                data.writeParcelable(list.get(i), 0);
                i++;
            }
            if (i < count) {
                data.writeInt(END_OF_PARCEL);
            }
            binder.transact(FIRST_CALL_TRANSACTION, data, reply, 0);
            reply.recycle();
            data.recycle();
        } while (i < count);
    }
}
