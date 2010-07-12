/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.internal.telephony.cat;

import android.graphics.Bitmap;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.List;

/**
 * Container class for CAT menu (SET UP MENU, SELECT ITEM) parameters.
 *
 */
public class Menu implements Parcelable {
    public List<Item> items;
    public List<TextAttribute> titleAttrs;
    public PresentationType presentationType;
    public String title;
    public Bitmap titleIcon;
    public int defaultItem;
    public boolean softKeyPreferred;
    public boolean helpAvailable;
    public boolean titleIconSelfExplanatory;
    public boolean itemsIconSelfExplanatory;

    public Menu() {
        // Create an empty list.
        items = new ArrayList<Item>();
        title = null;
        titleAttrs = null;
        defaultItem = 0;
        softKeyPreferred = false;
        helpAvailable = false;
        titleIconSelfExplanatory = false;
        itemsIconSelfExplanatory = false;
        titleIcon = null;
        // set default style to be navigation menu.
        presentationType = PresentationType.NAVIGATION_OPTIONS;
    }

    private Menu(Parcel in) {
        title = in.readString();
        titleIcon = in.readParcelable(null);
        // rebuild items list.
        items = new ArrayList<Item>();
        int size = in.readInt();
        for (int i=0; i<size; i++) {
            Item item = in.readParcelable(null);
            items.add(item);
        }
        defaultItem = in.readInt();
        softKeyPreferred = in.readInt() == 1 ? true : false;
        helpAvailable = in.readInt() == 1 ? true : false;
        titleIconSelfExplanatory = in.readInt() == 1 ? true : false;
        itemsIconSelfExplanatory = in.readInt() == 1 ? true : false;
        presentationType = PresentationType.values()[in.readInt()];
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(title);
        dest.writeParcelable(titleIcon, flags);
        // write items list to the parcel.
        int size = items.size();
        dest.writeInt(size);
        for (int i=0; i<size; i++) {
            dest.writeParcelable(items.get(i), flags);
        }
        dest.writeInt(defaultItem);
        dest.writeInt(softKeyPreferred ? 1 : 0);
        dest.writeInt(helpAvailable ? 1 : 0);
        dest.writeInt(titleIconSelfExplanatory ? 1 : 0);
        dest.writeInt(itemsIconSelfExplanatory ? 1 : 0);
        dest.writeInt(presentationType.ordinal());
    }

    public static final Parcelable.Creator<Menu> CREATOR = new Parcelable.Creator<Menu>() {
        public Menu createFromParcel(Parcel in) {
            return new Menu(in);
        }

        public Menu[] newArray(int size) {
            return new Menu[size];
        }
    };
}
