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

import static java.lang.String.CASE_INSENSITIVE_ORDER;

import android.content.ComponentName;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class DashboardCategory implements Parcelable {

    private static final String TAG = "DashboardCategory";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

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
    private List<Tile> mTiles = new ArrayList<>();

    DashboardCategory(DashboardCategory in) {
        if (in != null) {
            title = in.title;
            key = in.key;
            priority = in.priority;
            for (Tile tile : in.mTiles) {
                mTiles.add(tile);
            }
        }
    }

    public DashboardCategory() {
        // Empty
    }

    /**
     * Get a copy of the list of the category's children.
     *
     * Note: the returned list serves as a read-only list. If tiles needs to be added or removed
     * from the actual tiles list, it should be done through {@link #addTile}, {@link #removeTile}.
     */
    public synchronized List<Tile> getTiles() {
        final List<Tile> result = new ArrayList<>(mTiles.size());
        for (Tile tile : mTiles) {
            result.add(tile);
        }
        return result;
    }

    public synchronized void addTile(Tile tile) {
        mTiles.add(tile);
    }

    public synchronized void addTile(int n, Tile tile) {
        mTiles.add(n, tile);
    }

    public synchronized void removeTile(Tile tile) {
        mTiles.remove(tile);
    }

    public synchronized void removeTile(int n) {
        mTiles.remove(n);
    }

    public int getTilesCount() {
        return mTiles.size();
    }

    public Tile getTile(int n) {
        return mTiles.get(n);
    }

    public synchronized boolean containsComponent(ComponentName component) {
        for (Tile tile : mTiles) {
            if (TextUtils.equals(tile.intent.getComponent().getClassName(),
                    component.getClassName())) {
                if (DEBUG) {
                    Log.d(TAG,  "category " + key + "contains component" + component);
                }
                return true;
            }
        }
        if (DEBUG) {
            Log.d(TAG,  "category " + key + " does not contain component" + component);
        }
        return false;
    }

    /**
     * Sort priority value for tiles in this category.
     */
    public void sortTiles() {
        Collections.sort(mTiles, TILE_COMPARATOR);
    }

    /**
     * Sort priority value and package name for tiles in this category.
     */
    public synchronized void sortTiles(String skipPackageName) {
        // Sort mTiles based on [priority, package within priority]
        Collections.sort(mTiles, (tile1, tile2) -> {
            final String package1 = tile1.intent.getComponent().getPackageName();
            final String package2 = tile2.intent.getComponent().getPackageName();
            final int packageCompare = CASE_INSENSITIVE_ORDER.compare(package1, package2);
            // First sort by priority
            final int priorityCompare = tile2.priority - tile1.priority;
            if (priorityCompare != 0) {
                return priorityCompare;
            }
            // Then sort by package name, skip package take precedence
            if (packageCompare != 0) {
                if (TextUtils.equals(package1, skipPackageName)) {
                    return -1;
                }
                if (TextUtils.equals(package2, skipPackageName)) {
                    return 1;
                }
            }
            return packageCompare;
        });
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

        final int count = mTiles.size();
        dest.writeInt(count);

        for (int n = 0; n < count; n++) {
            Tile tile = mTiles.get(n);
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
            mTiles.add(tile);
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

    public static final Comparator<Tile> TILE_COMPARATOR =
            new Comparator<Tile>() {
                @Override
                public int compare(Tile lhs, Tile rhs) {
                    return rhs.priority - lhs.priority;
                }
            };

}
