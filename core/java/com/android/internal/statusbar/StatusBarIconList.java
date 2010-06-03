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

package com.android.internal.statusbar;

import android.os.Parcel;
import android.os.Parcelable;

import java.io.PrintWriter;

public class StatusBarIconList implements Parcelable {
    private String[] mSlots;
    private StatusBarIcon[] mIcons;

    public StatusBarIconList() {
    }

    public StatusBarIconList(Parcel in) {
        readFromParcel(in);
    }
    
    public void readFromParcel(Parcel in) {
        this.mSlots = in.readStringArray();
        final int N = in.readInt();
        if (N < 0) {
            mIcons = null;
        } else {
            mIcons = new StatusBarIcon[N];
            for (int i=0; i<N; i++) {
                if (in.readInt() != 0) {
                    mIcons[i] = new StatusBarIcon(in);
                }
            }
        }
    }

    public void writeToParcel(Parcel out, int flags) {
        out.writeStringArray(mSlots);
        if (mIcons == null) {
            out.writeInt(-1);
        } else {
            final int N = mIcons.length;
            out.writeInt(N);
            for (int i=0; i<N; i++) {
                StatusBarIcon ic = mIcons[i];
                if (ic == null) {
                    out.writeInt(0);
                } else {
                    out.writeInt(1);
                    ic.writeToParcel(out, flags);
                }
            }
        }
    }

    public int describeContents() {
        return 0;
    }

    /**
     * Parcelable.Creator that instantiates StatusBarIconList objects
     */
    public static final Parcelable.Creator<StatusBarIconList> CREATOR
            = new Parcelable.Creator<StatusBarIconList>()
    {
        public StatusBarIconList createFromParcel(Parcel parcel)
        {
            return new StatusBarIconList(parcel);
        }

        public StatusBarIconList[] newArray(int size)
        {
            return new StatusBarIconList[size];
        }
    };

    public void defineSlots(String[] slots) {
        final int N = slots.length;
        String[] s = mSlots = new String[N];
        for (int i=0; i<N; i++) {
            s[i] = slots[i];
        }
        mIcons = new StatusBarIcon[N];
    }

    public int getSlotIndex(String slot) {
        final int N = mSlots.length;
        for (int i=0; i<N; i++) {
            if (slot.equals(mSlots[i])) {
                return i;
            }
        }
        return -1;
    }

    public int size() {
        return mSlots.length;
    }

    public void setIcon(int index, StatusBarIcon icon) {
        mIcons[index] = icon.clone();
    }

    public void removeIcon(int index) {
        mIcons[index] = null;
    }

    public String getSlot(int index) {
        return mSlots[index];
    }

    public StatusBarIcon getIcon(int index) {
        return mIcons[index];
    }

    public int getViewIndex(int index) {
        int count = 0;
        for (int i=0; i<index; i++) {
            if (mIcons[i] != null) {
                count++;
            }
        }
        return count;
    }

    public void copyFrom(StatusBarIconList that) {
        if (that.mSlots == null) {
            this.mSlots = null;
            this.mIcons = null;
        } else {
            final int N = that.mSlots.length;
            this.mSlots = new String[N];
            this.mIcons = new StatusBarIcon[N];
            for (int i=0; i<N; i++) {
                this.mSlots[i] = that.mSlots[i];
                this.mIcons[i] = that.mIcons[i] != null ? that.mIcons[i].clone() : null;
            }
        }
    }

    public void dump(PrintWriter pw) {
        final int N = mSlots.length;
        pw.println("Icon list:");
        for (int i=0; i<N; i++) {
            pw.printf("  %2d: (%s) %s\n", i, mSlots[i], mIcons[i]);
        }
    }
}
