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

import android.annotation.SuppressLint;
import android.app.Notification;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SharedMemory;
import android.system.ErrnoException;
import android.system.OsConstants;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents an update to notification rankings.
 *
 * @hide
 */
@SuppressLint({"ParcelNotFinal", "ParcelCreator"})
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
        if (Flags.rankingUpdateAshmem()) {
            // Recover the ranking map from the SharedMemory and store it in mapParcel.
            final Parcel mapParcel = Parcel.obtain();
            ByteBuffer buffer = null;
            try {
                // The ranking map should be stored in shared memory when it is parceled, so we
                // unwrap the SharedMemory object.
                mRankingMapFd = in.readParcelable(getClass().getClassLoader(), SharedMemory.class);
                Bundle smartActionsBundle = in.readBundle(getClass().getClassLoader());

                // In the case that the ranking map can't be read, readParcelable may return null.
                // In this case, we set mRankingMap to null;
                if (mRankingMapFd == null) {
                    mRankingMap = null;
                    return;
                }
                // We only need read-only access to the shared memory region.
                buffer = mRankingMapFd.mapReadOnly();
                byte[] payload = new byte[buffer.remaining()];
                buffer.get(payload);
                mapParcel.unmarshall(payload, 0, payload.length);
                mapParcel.setDataPosition(0);

                mRankingMap =
                        mapParcel.readParcelable(
                                getClass().getClassLoader(),
                                NotificationListenerService.RankingMap.class);

                addSmartActionsFromBundleToRankingMap(smartActionsBundle);

            } catch (ErrnoException e) {
                // TODO(b/284297289): remove throw when associated flag is moved to droidfood, to
                // avoid crashes; change to Log.wtf.
                throw new RuntimeException(e);
            } finally {
                mapParcel.recycle();
                if (buffer != null && mRankingMapFd != null) {
                    SharedMemory.unmap(buffer);
                    mRankingMapFd.close();
                }
            }
        } else {
            mRankingMap = in.readParcelable(getClass().getClassLoader(),
                    android.service.notification.NotificationListenerService.RankingMap.class);
        }
    }

    /**
     * For each key in the rankingMap, extracts lists of smart actions stored in the provided
     * bundle and adds them to the corresponding Ranking object in the provided ranking
     * map, then returns the rankingMap.
     *
     * @hide
     */
    private void addSmartActionsFromBundleToRankingMap(Bundle smartActionsBundle) {
        if (smartActionsBundle == null) {
            return;
        }

        String[] rankingMapKeys = mRankingMap.getOrderedKeys();
        for (int i = 0; i < rankingMapKeys.length; i++) {
            String key = rankingMapKeys[i];
            ArrayList<Notification.Action> smartActions =
                    smartActionsBundle.getParcelableArrayList(key, Notification.Action.class);
            // Get the ranking object from the ranking map.
            NotificationListenerService.Ranking ranking = mRankingMap.getRawRankingObject(key);
            ranking.setSmartActions(smartActions);
        }
    }

    /**
     * Confirms that the SharedMemory file descriptor is closed. Should only be used for testing.
     *
     * @hide
     */
    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
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
        if (Flags.rankingUpdateAshmem()) {
            final Parcel mapParcel = Parcel.obtain();
            ArrayList<NotificationListenerService.Ranking> marshalableRankings = new ArrayList<>();
            Bundle smartActionsBundle = new Bundle();

            // We need to separate the SmartActions from the RankingUpdate objects.
            // SmartActions can contain PendingIntents, which cannot be marshalled,
            // so we extract them to send separately.
            String[] rankingMapKeys = mRankingMap.getOrderedKeys();
            for (int i = 0; i < rankingMapKeys.length; i++) {
                String key = rankingMapKeys[i];
                NotificationListenerService.Ranking ranking = mRankingMap.getRawRankingObject(key);

                // Removes the SmartActions and stores them in a separate map.
                // Note that getSmartActions returns a Collections.emptyList() if there are no
                // smart actions, and we don't want to needlessly store an empty list object, so we
                // check for null before storing.
                List<Notification.Action> smartActions = ranking.getSmartActions();
                if (!smartActions.isEmpty()) {
                    smartActionsBundle.putParcelableList(key, smartActions);
                }

                // Create a copy of the ranking object that doesn't have the smart actions.
                NotificationListenerService.Ranking rankingCopy =
                        new NotificationListenerService.Ranking();
                rankingCopy.populate(ranking);
                rankingCopy.setSmartActions(null);
                marshalableRankings.add(rankingCopy);
            }

            // Create a new marshalable RankingMap.
            NotificationListenerService.RankingMap marshalableRankingMap =
                    new NotificationListenerService.RankingMap(
                            marshalableRankings.toArray(
                                    new NotificationListenerService.Ranking[0]
                            )
                    );
            ByteBuffer buffer = null;

            try {
                // Parcels the ranking map and measures its size.
                mapParcel.writeParcelable(marshalableRankingMap, flags);
                int mapSize = mapParcel.dataSize();

                // Creates a new SharedMemory object with enough space to hold the ranking map.
                mRankingMapFd = SharedMemory.create(mSharedMemoryName, mapSize);

                // Gets a read/write buffer mapping the entire shared memory region.
                buffer = mRankingMapFd.mapReadWrite();
                // Puts the ranking map into the shared memory region buffer.
                buffer.put(mapParcel.marshall(), 0, mapSize);
                // Protects the region from being written to, by setting it to be read-only.
                mRankingMapFd.setProtect(OsConstants.PROT_READ);
                // Puts the SharedMemory object in the parcel.
                out.writeParcelable(mRankingMapFd, flags);
                // Writes the Parceled smartActions separately.
                out.writeBundle(smartActionsBundle);
            } catch (ErrnoException e) {
                // TODO(b/284297289): remove throw when associated flag is moved to droidfood, to
                // avoid crashes; change to Log.wtf.
                throw new RuntimeException(e);
            } finally {
                mapParcel.recycle();
                // To prevent memory leaks, we can close the ranking map fd here.
                // This is safe to do because a reference to this still exists.
                if (buffer != null && mRankingMapFd != null) {
                    SharedMemory.unmap(buffer);
                    mRankingMapFd.close();
                }
            }
        } else {
            out.writeParcelable(mRankingMap, flags);
        }
    }

    /**
     * @hide
     */
    public static final @NonNull Parcelable.Creator<NotificationRankingUpdate> CREATOR
            = new Parcelable.Creator<NotificationRankingUpdate>() {
        public NotificationRankingUpdate createFromParcel(Parcel parcel) {
            return new NotificationRankingUpdate(parcel);
        }

        public NotificationRankingUpdate[] newArray(int size) {
            return new NotificationRankingUpdate[size];
        }
    };
}
