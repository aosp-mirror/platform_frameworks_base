/**
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

package com.android.settingslib.drawer;

import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;

public class DashboardCategory implements Parcelable {

    /**
     * Title of the category that is shown to the user.
     */
    public CharSequence title;

    /**
     * Key used for placing external tiles.
     */
    public String key;

    /**
     * Used to control display order.
     */
    public int priority;

    /**
     * List of the category's children
     */
    public List<Tile> tiles = new ArrayList<Tile>();


    public DashboardCategory() {
        // Empty
    }

    public void addTile(Tile tile) {
        tiles.add(tile);
    }

    public void addTile(int n, Tile tile) {
        tiles.add(n, tile);
    }

    public void removeTile(Tile tile) {
        tiles.remove(tile);
    }

    public void removeTile(int n) {
        tiles.remove(n);
    }

    public int getTilesCount() {
        return tiles.size();
    }

    public Tile getTile(int n) {
        return tiles.get(n);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        TextUtils.writeToParcel(title, dest, flags);
        dest.writeString(key);
        dest.writeInt(priority);

        final int count = tiles.size();
        dest.writeInt(count);

        for (int n = 0; n < count; n++) {
            Tile tile = tiles.get(n);
            tile.writeToParcel(dest, flags);
        }
    }

    public void readFromParcel(Parcel in) {
        title = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(in);
        key = in.readString();
        priority = in.readInt();

        final int count = in.readInt();

        for (int n = 0; n < count; n++) {
            Tile tile = Tile.CREATOR.createFromParcel(in);
            tiles.add(tile);
        }
    }

    DashboardCategory(Parcel in) {
        readFromParcel(in);
    }

    public static final Creator<DashboardCategory> CREATOR = new Creator<DashboardCategory>() {
        public DashboardCategory createFromParcel(Parcel source) {
            return new DashboardCategory(source);
        }

        public DashboardCategory[] newArray(int size) {
            return new DashboardCategory[size];
        }
    };
}
