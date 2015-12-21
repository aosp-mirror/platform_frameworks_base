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

    private ComponentName mComponentName;
    private Icon mIcon;
    private CharSequence mLabel;
    private CharSequence mContentDescription;

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