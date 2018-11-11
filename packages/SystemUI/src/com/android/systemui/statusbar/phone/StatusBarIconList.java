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

import static com.android.systemui.statusbar.phone.StatusBarIconController.TAG_PRIMARY;

import android.annotation.NonNull;
import android.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

import com.android.systemui.statusbar.policy.NetworkTrafficSB;

public class StatusBarIconList {
    private ArrayList<Slot> mSlots = new ArrayList<>();

    public StatusBarIconList(String[] slots) {
        final int N = slots.length;
        for (int i=0; i < N; i++) {
            mSlots.add(new Slot(slots[i], null));
        }
        // Network traffic slot
        mSlots.add(0, new Slot(NetworkTrafficSB.SLOT, StatusBarIconHolder.fromNetworkTraffic()));
    }

    public int getSlotIndex(String slot) {
        final int N = mSlots.size();
        for (int i=0; i<N; i++) {
            Slot item = mSlots.get(i);
            if (item.getName().equals(slot)) {
                return i;
            }
        }
        // Auto insert new items behind network traffic
        mSlots.add(1, new Slot(slot, null));
        return 0;
    }

    protected ArrayList<Slot> getSlots() {
        return new ArrayList<>(mSlots);
    }

    protected Slot getSlot(String name) {
        return mSlots.get(getSlotIndex(name));
    }

    public int size() {
        return mSlots.size();
    }

    public void setIcon(int index, @NonNull StatusBarIconHolder holder) {
        mSlots.get(index).addHolder(holder);
    }

    public void removeIcon(int index, int tag) {
        mSlots.get(index).removeForTag(tag);
    }

    public String getSlotName(int index) {
        return mSlots.get(index).getName();
    }

    public StatusBarIconHolder getIcon(int index, int tag) {
        return mSlots.get(index).getHolderForTag(tag);
    }

    public int getViewIndex(int slotIndex, int tag) {
        int count = 0;
        for (int i = 0; i < slotIndex; i++) {
            Slot item = mSlots.get(i);
            if (item.hasIconsInSlot()) {
                count += item.numberOfIcons();
            }
        }

        Slot viewItem = mSlots.get(slotIndex);
        return count + viewItem.viewIndexOffsetForTag(tag);
    }

    public void dump(PrintWriter pw) {
        pw.println("StatusBarIconList state:");
        final int N = mSlots.size();
        pw.println("  icon slots: " + N);
        for (int i=0; i<N; i++) {
            pw.printf("    %2d:%s\n", i, mSlots.get(i).toString());
        }
    }

    public static class Slot {
        private final String mName;
        private StatusBarIconHolder mHolder;
        /**
         * Only used if multiple icons are added to the same slot.
         *
         * If there are mSubSlots, then these are structured like:
         *      [ First item | (the rest) ]
         *
         * The tricky thing to keep in mind here is that the list [mHolder, mSubSlots] is ordered
         * ascending, but for view logic we should go backwards through the list. I.e., the first
         * element (mHolder) should be the highest index, because higher priority items go to the
         * right of lower priority items
         */
        private ArrayList<StatusBarIconHolder> mSubSlots;

        public Slot(String name, StatusBarIconHolder iconHolder) {
            mName = name;
            mHolder = iconHolder;
        }

        public String getName() {
            return mName;
        }

        @Nullable
        public StatusBarIconHolder getHolderForTag(int tag) {
            if (tag == TAG_PRIMARY) {
                return mHolder;
            }

            if (mSubSlots != null) {
                for (StatusBarIconHolder holder : mSubSlots) {
                    if (holder.getTag() == tag) {
                        return holder;
                    }
                }
            }

            return null;
        }

        public void addHolder(StatusBarIconHolder holder) {
            int tag = holder.getTag();
            if (tag == TAG_PRIMARY) {
                mHolder = holder;
            } else {
                setSubSlot(holder, tag);
            }
        }

        public void removeForTag(int tag) {
            if (tag == TAG_PRIMARY) {
                mHolder = null;
            } else {
                int index = getIndexForTag(tag);
                if (index != -1) {
                    mSubSlots.remove(index);
                }
            }
        }

        @VisibleForTesting
        public void clear() {
            mHolder = null;
            if (mSubSlots != null) {
                mSubSlots = null;
            }
        }

        private void setSubSlot(StatusBarIconHolder holder, int tag) {
            if (mSubSlots == null) {
                mSubSlots = new ArrayList<>();
                mSubSlots.add(holder);
                return;
            }

            if (getIndexForTag(tag) != -1) {
                // Holder exists for tag; no-op
                return;
            }

            // These holders get added to the end. Confused yet?
            mSubSlots.add(holder);
        }

        private int getIndexForTag(int tag) {
            for (int i = 0; i < mSubSlots.size(); i++) {
                StatusBarIconHolder h = mSubSlots.get(i);
                if (h.getTag() == tag) {
                    return i;
                }
            }

            return -1;
        }

        public boolean hasIconsInSlot() {
            if (mHolder != null) return true;
            if (mSubSlots == null) return false;

            return mSubSlots.size() > 0;
        }

        public int numberOfIcons() {
            int num = mHolder == null ? 0 : 1;
            if (mSubSlots == null) return num;

            return num + mSubSlots.size();
        }

        /**
         * View index is inverted from regular index, because they are laid out back-to-front
         * @param tag the tag of the holder being viewed
         * @return (1 + mSubSlots.size() - indexOfTag)
         */
        public int viewIndexOffsetForTag(int tag) {
            if (mSubSlots == null) {
                return 0;
            }

            int subSlots = mSubSlots.size();
            if (tag == TAG_PRIMARY) {
                return subSlots;
            }

            return subSlots - getIndexForTag(tag) - 1;
        }

        /**
         * Build a list of the {@link StatusBarIconHolder}s in the same order they appear in their
         * view group. This provides a safe list that can be iterated and inserted into its group.
         *
         * @return all holders contained here, in view order
         */
        public List<StatusBarIconHolder> getHolderListInViewOrder() {
            ArrayList<StatusBarIconHolder> holders = new ArrayList<>();
            if (mSubSlots != null) {
                for (int i = mSubSlots.size() - 1; i >= 0; i--) {
                    holders.add(mSubSlots.get(i));
                }
            }

            if (mHolder != null) {
                holders.add(mHolder);
            }

            return holders;
        }

        /**
         * Build a list of the {@link StatusBarIconHolder}s in the same order.
         * This provides a safe list that can be iterated and inserted into its group.
         *
         * @return all holders contained here
         */
        public List<StatusBarIconHolder> getHolderList() {
            ArrayList<StatusBarIconHolder> holders = new ArrayList<>();
            if (mHolder != null) {
                holders.add(mHolder);
            }

            if (mSubSlots != null) {
                holders.addAll(mSubSlots);
            }

            return holders;
        }

        @Override
        public String toString() {
            return String.format("(%s) %s", mName, subSlotsString());
        }

        private String subSlotsString() {
            if (mSubSlots == null) {
                return "";
            }

            return "" + mSubSlots.size() + " subSlots";
        }
    }
}
