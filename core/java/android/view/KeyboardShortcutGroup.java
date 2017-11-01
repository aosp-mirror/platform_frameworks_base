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
package android.view;

import static com.android.internal.util.Preconditions.checkNotNull;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A group of {@link KeyboardShortcutInfo}.
 */
public final class KeyboardShortcutGroup implements Parcelable {
    private final CharSequence mLabel;
    private final List<KeyboardShortcutInfo> mItems;
    // The system group looks different UI wise.
    private boolean mSystemGroup;

    /**
     * @param label The title to be used for this group, or null if there is none.
     * @param items The set of items to be included.
     */
    public KeyboardShortcutGroup(@Nullable CharSequence label,
            @NonNull List<KeyboardShortcutInfo> items) {
        mLabel = label;
        mItems = new ArrayList<>(checkNotNull(items));
    }

    /**
     * @param label The title to be used for this group, or null if there is none.
     */
    public KeyboardShortcutGroup(@Nullable CharSequence label) {
        this(label, Collections.<KeyboardShortcutInfo>emptyList());
    }

    /**
     * @param label The title to be used for this group, or null if there is none.
     * @param items The set of items to be included.
     * @param isSystemGroup Set this to {@code true} if this is s system group.
     * @hide
     */
    @TestApi
    public KeyboardShortcutGroup(@Nullable CharSequence label,
            @NonNull List<KeyboardShortcutInfo> items, boolean isSystemGroup) {
        mLabel = label;
        mItems = new ArrayList<>(checkNotNull(items));
        mSystemGroup = isSystemGroup;
    }

    /**
     * @param label The title to be used for this group, or null if there is none.
     * @param isSystemGroup Set this to {@code true} if this is s system group.
     * @hide
     */
    @TestApi
    public KeyboardShortcutGroup(@Nullable CharSequence label, boolean isSystemGroup) {
        this(label, Collections.<KeyboardShortcutInfo>emptyList(), isSystemGroup);
    }

    private KeyboardShortcutGroup(Parcel source) {
        mItems = new ArrayList<>();
        mLabel = source.readCharSequence();
        source.readTypedList(mItems, KeyboardShortcutInfo.CREATOR);
        mSystemGroup = source.readInt() == 1;
    }

    /**
     * Returns the label to be used to describe this group.
     */
    public CharSequence getLabel() {
        return mLabel;
    }

    /**
     * Returns the list of items included in this group.
     */
    public List<KeyboardShortcutInfo> getItems() {
        return mItems;
    }

    /** @hide **/
    @TestApi
    public boolean isSystemGroup() {
        return mSystemGroup;
    }

    /**
     * Adds an item to the existing list.
     *
     * @param item The item to be added.
     */
    public void addItem(KeyboardShortcutInfo item) {
        mItems.add(item);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeCharSequence(mLabel);
        dest.writeTypedList(mItems);
        dest.writeInt(mSystemGroup ? 1 : 0);
    }

    public static final Creator<KeyboardShortcutGroup> CREATOR =
            new Creator<KeyboardShortcutGroup>() {
                public KeyboardShortcutGroup createFromParcel(Parcel source) {
                    return new KeyboardShortcutGroup(source);
                }
                public KeyboardShortcutGroup[] newArray(int size) {
                    return new KeyboardShortcutGroup[size];
                }
            };
}
