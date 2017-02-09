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

package com.android.systemui.statusbar.phone;

import com.android.internal.statusbar.StatusBarIcon;

import java.io.PrintWriter;
import java.util.ArrayList;

public class StatusBarIconList {
    protected ArrayList<String> mSlots = new ArrayList<>();
    protected ArrayList<StatusBarIcon> mIcons = new ArrayList<>();

    public StatusBarIconList(String[] slots) {
        final int N = slots.length;
        for (int i=0; i < N; i++) {
            mSlots.add(slots[i]);
            mIcons.add(null);
        }
    }

    public int getSlotIndex(String slot) {
        final int N = mSlots.size();
        for (int i=0; i<N; i++) {
            if (slot.equals(mSlots.get(i))) {
                return i;
            }
        }
        // Auto insert new items at the beginning.
        mSlots.add(0, slot);
        mIcons.add(0, null);
        return 0;
    }

    public int size() {
        return mSlots.size();
    }

    public void setIcon(int index, StatusBarIcon icon) {
        mIcons.set(index, icon);
    }

    public void removeIcon(int index) {
        mIcons.set(index, null);
    }

    public String getSlot(int index) {
        return mSlots.get(index);
    }

    public StatusBarIcon getIcon(int index) {
        return mIcons.get(index);
    }

    public int getViewIndex(int index) {
        int count = 0;
        for (int i = 0; i < index; i++) {
            if (mIcons.get(i) != null) {
                count++;
            }
        }
        return count;
    }

    public void dump(PrintWriter pw) {
        final int N = mSlots.size();
        pw.println("  icon slots: " + N);
        for (int i=0; i<N; i++) {
            pw.printf("    %2d: (%s) %s\n", i, mSlots.get(i), mIcons.get(i));
        }
    }
}
