/*
 * Copyright (C) 2014 The Android Open Source Project
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
package android.service.notification;

import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.TestApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SharedMemory;
import android.system.ErrnoException;
import android.system.OsConstants;

import androidx.annotation.NonNull;

import com.android.internal.config.sysui.SystemUiSystemPropertiesFlags;

import java.nio.ByteBuffer;

/**
 * Represents an update to notification rankings.
 * @hide
 */
@SuppressLint({"ParcelNotFinal", "ParcelCreator"})
@TestApi
public class NotificationRankingUpdate implements Parcelable {
    private final NotificationListenerService.RankingMap mRankingMap;

    // The ranking map is stored in shared memory when parceled, for sending across the binder.
    // This is done because the ranking map can grow large if there are many notifications.
    private SharedMemory mRankingMapFd = null;
    private final String mSharedMemoryName = "NotificationRankingUpdatedSharedMemory";

    /**
     * @hide
     */
    public NotificationRankingUpdate(NotificationListenerService.Ranking[] rankings) {
        mRankingMap = new NotificationListenerService.RankingMap(rankings);
    }

    /**
     * @hide
     */
    public NotificationRankingUpdate(Parcel in) {
        if (SystemUiSystemPropertiesFlags.getResolver().isEnabled(
                SystemUiSystemPropertiesFlags.NotificationFlags.RANKING_UPDATE_ASHMEM)) {
            // Recover the ranking map from the SharedMemory and store it in mapParcel.
            final Parcel mapParcel = Parcel.obtain();
            ByteBuffer buffer = null;
            try {
                // The ranking map should be stored in shared memory when it is parceled, so we
                // unwrap the SharedMemory object.
                mRankingMapFd = in.readParcelable(getClass().getClassLoader(), SharedMemory.class);

                // In the case that the ranking map can't be read, readParcelable may return null.
                // In this case, we set mRankingMap to null;
                if (mRankingMapFd == null) {
                    mRankingMap = null;
                    return;
                }
                // We only need read-only access to the shared memory region.
                buffer = mRankingMapFd.mapReadOnly();
                if (buffer == null) {
                    mRankingMap = null;
                    return;
                }
                byte[] payload = new byte[buffer.remaining()];
                buffer.get(payload);
                mapParcel.unmarshall(payload, 0, payload.length);
                mapParcel.setDataPosition(0);

                mRankingMap = mapParcel.readParcelable(getClass().getClassLoader(),
                        android.service.notification.NotificationListenerService.RankingMap.class);
            } catch (ErrnoException e) {
                // TODO(b/284297289): remove throw when associated flag is moved to droidfood, to
                // avoid crashes; change to Log.wtf.
                throw new RuntimeException(e);
            } finally {
                mapParcel.recycle();
                if (buffer != null) {
                    mRankingMapFd.unmap(buffer);
                    mRankingMapFd.close();
                }
            }
        } else {
            mRankingMap = in.readParcelable(getClass().getClassLoader(),
                    android.service.notification.NotificationListenerService.RankingMap.class);
        }
    }

    /**
     * Confirms that the SharedMemory file descriptor is closed. Should only be used for testing.
     * @hide
     */
    @TestApi
    public final boolean isFdNotNullAndClosed() {
        return mRankingMapFd != null && mRankingMapFd.getFd() == -1;
    }

    /**
     * @hide
     */
    public NotificationListenerService.RankingMap getRankingMap() {
        return mRankingMap;
    }

    /**
     * @hide
     */
    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * @hide
     */
    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        NotificationRankingUpdate other = (NotificationRankingUpdate) o;
        return mRankingMap.equals(other.mRankingMap);
    }

    /**
     * @hide
     */
    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        if (SystemUiSystemPropertiesFlags.getResolver().isEnabled(
                SystemUiSystemPropertiesFlags.NotificationFlags.RANKING_UPDATE_ASHMEM)) {
            final Parcel mapParcel = Parcel.obtain();
            try {
                // Parcels the ranking map and measures its size.
                mapParcel.writeParcelable(mRankingMap, flags);
                int mapSize = mapParcel.dataSize();

                // Creates a new SharedMemory object with enough space to hold the ranking map.
                SharedMemory mRankingMapFd = SharedMemory.create(mSharedMemoryName, mapSize);
                if (mRankingMapFd == null) {
                    return;
                }

                // Gets a read/write buffer mapping the entire shared memory region.
                final ByteBuffer buffer = mRankingMapFd.mapReadWrite();

                // Puts the ranking map into the shared memory region buffer.
                buffer.put(mapParcel.marshall(), 0, mapSize);

                // Protects the region from being written to, by setting it to be read-only.
                mRankingMapFd.setProtect(OsConstants.PROT_READ);

                // Puts the SharedMemory object in the parcel.
                out.writeParcelable(mRankingMapFd, flags);
            } catch (ErrnoException e) {
                // TODO(b/284297289): remove throw when associated flag is moved to droidfood, to
                // avoid crashes; change to Log.wtf.
                throw new RuntimeException(e);
            } finally {
                mapParcel.recycle();
            }
        } else {
            out.writeParcelable(mRankingMap, flags);
        }
    }

    /**
    * @hide
    */
    public static final @android.annotation.NonNull Parcelable.Creator<NotificationRankingUpdate> CREATOR
            = new Parcelable.Creator<NotificationRankingUpdate>() {
        public NotificationRankingUpdate createFromParcel(Parcel parcel) {
            return new NotificationRankingUpdate(parcel);
        }

        public NotificationRankingUpdate[] newArray(int size) {
            return new NotificationRankingUpdate[size];
        }
    };
}
