/*
 * Copyright (C) 2015 The Android Open Source Project
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
package android.service.quicksettings;

import android.content.ComponentName;
import android.graphics.drawable.Icon;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;

/**
 * A Tile holds the state of a tile that will be displayed
 * in Quick Settings.
 *
 * A tile in Quick Settings exists as an icon with an accompanied label.
 * It also may have content description for accessibility usability.
 * The style and layout of the tile may change to match a given
 * device.
 */
public final class Tile implements Parcelable {

    private static final String TAG = "Tile";

    /**
     * An unavailable state indicates that for some reason this tile is not currently
     * available to the user for some reason, and will have no click action.  The tile's
     * icon will be tinted differently to reflect this state.
     */
    public static final int STATE_UNAVAILABLE = 0;

    /**
     * This represents a tile that is currently in a disabled state but is still interactable.
     *
     * A disabled state indicates that the tile is not currently active (e.g. wifi disconnected or
     * bluetooth disabled), but is still interactable by the user to modify this state.  Tiles
     * that have boolean states should use this to represent one of their states.  The tile's
     * icon will be tinted differently to reflect this state, but still be distinct from unavailable.
     */
    public static final int STATE_INACTIVE = 1;

    /**
     * This represents a tile that is currently active. (e.g. wifi is connected, bluetooth is on,
     * cast is casting).  This is the default state.
     */
    public static final int STATE_ACTIVE = 2;

    private ComponentName mComponentName;
    private Icon mIcon;
    private CharSequence mLabel;
    private CharSequence mContentDescription;
    // Default to active until clients of the new API can update.
    private int mState = STATE_ACTIVE;

    private IQSService mService;

    /**
     * @hide
     */
    public Tile(Parcel source) {
        readFromParcel(source);
    }

    /**
     * @hide
     */
    public Tile(ComponentName componentName) {
        mComponentName = componentName;
    }

    /**
     * @hide
     */
    public void setService(IQSService service) {
        mService = service;
    }

    /**
     * @hide
     */
    public ComponentName getComponentName() {
        return mComponentName;
    }

    /**
     * @hide
     */
    public IQSService getQsService() {
        return mService;
    }

    /**
     * The current state of the tile.
     *
     * @see #STATE_UNAVAILABLE
     * @see #STATE_INACTIVE
     * @see #STATE_ACTIVE
     */
    public int getState() {
        return mState;
    }

    /**
     * Sets the current state for the tile.
     *
     * Does not take effect until {@link #updateTile()} is called.
     *
     * @param state One of {@link #STATE_UNAVAILABLE}, {@link #STATE_INACTIVE},
     * {@link #STATE_ACTIVE}
     */
    public void setState(int state) {
        mState = state;
    }

    /**
     * Gets the current icon for the tile.
     */
    public Icon getIcon() {
        return mIcon;
    }

    /**
     * Sets the current icon for the tile.
     *
     * This icon is expected to be white on alpha, and may be
     * tinted by the system to match it's theme.
     *
     * Does not take effect until {@link #updateTile()} is called.
     *
     * @param icon New icon to show.
     */
    public void setIcon(Icon icon) {
        this.mIcon = icon;
    }

    /**
     * Gets the current label for the tile.
     */
    public CharSequence getLabel() {
        return mLabel;
    }

    /**
     * Sets the current label for the tile.
     *
     * Does not take effect until {@link #updateTile()} is called.
     *
     * @param label New label to show.
     */
    public void setLabel(CharSequence label) {
        this.mLabel = label;
    }

    /**
     * Gets the current content description for the tile.
     */
    public CharSequence getContentDescription() {
        return mContentDescription;
    }

    /**
     * Sets the current content description for the tile.
     *
     * Does not take effect until {@link #updateTile()} is called.
     *
     * @param contentDescription New content description to use.
     */
    public void setContentDescription(CharSequence contentDescription) {
        this.mContentDescription = contentDescription;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Pushes the state of the Tile to Quick Settings to be displayed.
     */
    public void updateTile() {
        try {
            mService.updateQsTile(this);
        } catch (RemoteException e) {
            Log.e(TAG, "Couldn't update tile");
        }
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        if (mComponentName != null) {
            dest.writeByte((byte) 1);
            mComponentName.writeToParcel(dest, flags);
        } else {
            dest.writeByte((byte) 0);
        }
        if (mIcon != null) {
            dest.writeByte((byte) 1);
            mIcon.writeToParcel(dest, flags);
        } else {
            dest.writeByte((byte) 0);
        }
        dest.writeInt(mState);
        TextUtils.writeToParcel(mLabel, dest, flags);
        TextUtils.writeToParcel(mContentDescription, dest, flags);
    }

    private void readFromParcel(Parcel source) {
        if (source.readByte() != 0) {
            mComponentName = ComponentName.CREATOR.createFromParcel(source);
        } else {
            mComponentName = null;
        }
        if (source.readByte() != 0) {
            mIcon = Icon.CREATOR.createFromParcel(source);
        } else {
            mIcon = null;
        }
        mState = source.readInt();
        mLabel = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(source);
        mContentDescription = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(source);
    }

    public static final Creator<Tile> CREATOR = new Creator<Tile>() {
        @Override
        public Tile createFromParcel(Parcel source) {
            return new Tile(source);
        }

        @Override
        public Tile[] newArray(int size) {
            return new Tile[size];
        }
    };
}