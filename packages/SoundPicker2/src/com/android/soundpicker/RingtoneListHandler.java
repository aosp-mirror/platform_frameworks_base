/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.soundpicker;

import static java.util.Objects.requireNonNull;

import android.annotation.Nullable;
import android.database.Cursor;
import android.media.RingtoneManager;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;

import javax.inject.Inject;

/**
 * Handles ringtone list state and actions. This includes keeping track of the selected item,
 * ringtone manager cursor and added items to the list.
 */
public class RingtoneListHandler {

    // TODO: We're using an empty URI instead of null, because null URIs still produce a sound,
    //  while empty ones don't (Potentially this might be due to empty URIs being perceived as
    //  malformed ones). We will switch to using the official silent URIs (SOUND_OFF, VIBRATION_OFF)
    //  once they become available.
    static final Uri SILENT_URI = Uri.EMPTY;
    static final int ITEM_POSITION_UNKNOWN = -1;

    private static final String TAG = "RingtoneListHandler";

    /** The position in the list of the 'Silent' item. */
    private int mSilentItemPosition = ITEM_POSITION_UNKNOWN;
    /** The position in the list of the 'Default' item. */
    private int mDefaultItemPosition = ITEM_POSITION_UNKNOWN;
    /** The number of fixed items in the list. */
    private int mFixedItemCount;
    /**
     * Stable ID for the ringtone that is currently selected (may be -1 if no ringtone is selected).
     */
    private long mSelectedItemId = -1;
    private int mSelectedItemPosition = ITEM_POSITION_UNKNOWN;

    private RingtoneManager mRingtoneManager;
    private Config mRingtoneListConfig;
    private Cursor mRingtoneCursor;

    /**
     * Holds immutable info on the ringtone list that is displayed.
     */
    static final class Config {
        /**
         * Whether this list has the 'Default' item.
         */
        public final boolean hasDefaultItem;
        /**
         * The Uri to play when the 'Default' item is clicked.
         */
        public final Uri uriForDefaultItem;
        /**
         * Whether this list has the 'Silent' item.
         */
        public final boolean hasSilentItem;
        /**
         * The initially selected uri in the list.
         */
        public final Uri initialSelectedUri;

        Config(boolean hasDefaultItem, Uri uriForDefaultItem, boolean hasSilentItem,
                Uri initialSelectedUri) {
            this.hasDefaultItem = hasDefaultItem;
            this.uriForDefaultItem = uriForDefaultItem;
            this.hasSilentItem = hasSilentItem;
            this.initialSelectedUri = initialSelectedUri;
        }
    }

    @Inject
    RingtoneListHandler() {
    }

    void init(@NonNull Config ringtoneListConfig,
            @NonNull RingtoneManager ringtoneManager, @NonNull Cursor ringtoneCursor) {
        mRingtoneManager = requireNonNull(ringtoneManager);
        mRingtoneListConfig = requireNonNull(ringtoneListConfig);
        mRingtoneCursor = requireNonNull(ringtoneCursor);
    }

    Config getRingtoneListConfig() {
        return mRingtoneListConfig;
    }

    Cursor getRingtoneCursor() {
        requireInitCalled();
        return mRingtoneCursor;
    }

    Uri getRingtoneUri(int position) {
        if (position < 0) {
            Log.w(TAG, "Selected item position is unknown.");
            // When the selected item is ITEM_POSITION_UNKNOWN, it is not the case we expected.
            // We return SILENT_URI for this case.
            return SILENT_URI;
        } else if (position == mDefaultItemPosition) {
            // Use the default Uri that they originally gave us.
            return mRingtoneListConfig.uriForDefaultItem;
        } else if (position == mSilentItemPosition) {
            // Use SILENT_URI for the 'Silent' item.
            return SILENT_URI;
        } else {
            requireInitCalled();
            return mRingtoneManager.getRingtoneUri(mapListPositionToRingtonePosition(position));
        }
    }

    int getRingtonePosition(Uri uri) {
        requireInitCalled();
        return mapRingtonePositionToListPosition(mRingtoneManager.getRingtonePosition(uri));
    }

    void resetFixedItems() {
        mFixedItemCount = 0;
        mDefaultItemPosition = ITEM_POSITION_UNKNOWN;
        mSilentItemPosition = ITEM_POSITION_UNKNOWN;
    }

    int addDefaultItem() {
        if (mDefaultItemPosition < 0) {
            mDefaultItemPosition = addFixedItem();
        }
        return mDefaultItemPosition;
    }

    int getDefaultItemPosition() {
        return mDefaultItemPosition;
    }

    int addSilentItem() {
        if (mSilentItemPosition < 0) {
            mSilentItemPosition = addFixedItem();
        }
        return mSilentItemPosition;
    }

    public int getSilentItemPosition() {
        return mSilentItemPosition;
    }

    int getSelectedItemPosition() {
        return mSelectedItemPosition;
    }

    void setSelectedItemPosition(int selectedItemPosition) {
        mSelectedItemPosition = selectedItemPosition;
    }

    void setSelectedItemId(long selectedItemId) {
        mSelectedItemId = selectedItemId;
    }

    long getSelectedItemId() {
        return mSelectedItemId;
    }

    @Nullable
    Uri getSelectedRingtoneUri() {
        return getRingtoneUri(mSelectedItemPosition);
    }

    /**
     * Maps the item position in the list, to its equivalent position in the RingtoneManager.
     *
     * @param itemPosition the position of item in the list.
     * @return position of the item in the RingtoneManager.
     */
    private int mapListPositionToRingtonePosition(int itemPosition) {
        // If the manager position is less than add items, then return that.
        if (itemPosition < mFixedItemCount) return itemPosition;

        return itemPosition - mFixedItemCount;
    }

    /**
     * Maps the item position in the RingtoneManager, to its equivalent position in the list.
     *
     * @param itemPosition the position of the item in the RingtoneManager.
     * @return position of the item in the list.
     */
    private int mapRingtonePositionToListPosition(int itemPosition) {
        // If the manager position is less than add items, then return that.
        if (itemPosition < 0) return itemPosition;

        return itemPosition + mFixedItemCount;
    }

    /**
     * Increments the number of added fixed items and returns the index of the newest added item.
     * @return index of the newest added fixed item.
     */
    private int addFixedItem() {
        return mFixedItemCount++;
    }

    private void requireInitCalled() {
        requireNonNull(mRingtoneManager);
        requireNonNull(mRingtoneCursor);
    }
}
