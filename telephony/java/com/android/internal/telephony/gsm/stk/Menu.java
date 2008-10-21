/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.internal.telephony.gsm.stk;

import android.graphics.Bitmap;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.List;

public class Menu implements Parcelable {
    public List<Item> items;
    public String title;
    public List<TextAttribute> titleAttrs;
    public Bitmap titleIcon;
    public int defaultItem;
    public boolean softKeyPreferred;
    public boolean helpAvailable;
    public boolean titleIconSelfExplanatory;
    public boolean itemsIconSelfExplanatory;

    public Menu() {
        // Create an empty list.
        this.items = new ArrayList<Item>();
        this.title = null;
        this.titleAttrs = null;
        this.defaultItem = 0;
        this.softKeyPreferred = false;
        this.helpAvailable = false;
        this.titleIconSelfExplanatory = false;
        this.titleIcon = null;
    }

    public Menu(List<Item> items, String title, List<TextAttribute> titleAttrs,
            boolean softKeyPreferred, boolean helpAvailable, int defaultItem) {
        this.items = items;
        this.title = title;
        this.titleAttrs = titleAttrs;
        this.defaultItem = defaultItem;
        this.softKeyPreferred = softKeyPreferred;
        this.helpAvailable = helpAvailable;
    }

    private Menu(Parcel in) {
        title = in.readString();
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
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(title);
        // write items list to the parcel.
        int size = items.size();
        dest.writeInt(size);
        for (int i=0; i<size; i++) {
            dest.writeParcelable(items.get(i), flags);
        }
        dest.writeInt(defaultItem);
        dest.writeInt(softKeyPreferred ? 1 : 0);
        dest.writeInt(helpAvailable ? 1 : 0);
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
