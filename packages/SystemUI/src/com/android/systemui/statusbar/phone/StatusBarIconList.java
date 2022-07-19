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
import java.util.Collections;
import java.util.List;

/** A class holding the list of all the system icons that could be shown in the status bar. */
public class StatusBarIconList {
    private final ArrayList<Slot> mSlots = new ArrayList<>();
    private final List<Slot> mViewOnlySlots = Collections.unmodifiableList(mSlots);

    public StatusBarIconList(String[] slots) {
        final int N = slots.length;
        for (int i = 0; i < N; i++) {
            mSlots.add(new Slot(slots[i], null));
        }
    }

    /** Returns the list of current slots. */
    public List<Slot> getSlots() {
        return mViewOnlySlots;
    }

    /**
     * Gets the slot with the given {@code name}, or creates a new slot if we don't already have a
     * slot by that name.
     *
     * If a new slot is created, that slot will be inserted at the front of the list.
     *
     * TODO(b/237533036): Rename this to getOrCreateSlot to make it more clear that it could create
     *   a new slot. Other methods in this class will also create a new slot if we don't have one,
     *   should those be re-named too?
     */
    public Slot getSlot(String name) {
        return mSlots.get(findOrInsertSlot(name));
    }

    /**
     * Sets the icon in {@code holder} to be associated with the slot with the given
     * {@code slotName}.
     */
    public void setIcon(String slotName, @NonNull StatusBarIconHolder holder) {
        mSlots.get(findOrInsertSlot(slotName)).addHolder(holder);
    }

    /**
     * Removes the icon holder that we had associated with {@code slotName}'s slot at the given
     * {@code tag}.
     */
    public void removeIcon(String slotName, int tag) {
        mSlots.get(findOrInsertSlot(slotName)).removeForTag(tag);
    }

    /**
     * Returns the icon holder currently associated with {@code slotName}'s slot at the given
     * {@code tag}, or null if we don't have one.
     */
    @Nullable
    public StatusBarIconHolder getIconHolder(String slotName, int tag) {
        return mSlots.get(findOrInsertSlot(slotName)).getHolderForTag(tag);
    }

    /**
     * Returns the index of the icon in {@code slotName}'s slot at the given {@code tag}.
     *
     * Note that a single slot can have multiple icons, and this function takes that into account.
     */
    public int getViewIndex(String slotName, int tag) {
        int slotIndex = findOrInsertSlot(slotName);
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

    private int findOrInsertSlot(String slot) {
        final int N = mSlots.size();
        for (int i = 0; i < N; i++) {
            Slot item = mSlots.get(i);
            if (item.getName().equals(slot)) {
                return i;
            }
        }
        // Auto insert new items at the beginning.
        mSlots.add(0, new Slot(slot, null));
        return 0;
    }


    /**
     * A class representing one slot in the status bar system icons view.
     *
     * Note that one slot can have multiple icons associated with it.
     */
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
