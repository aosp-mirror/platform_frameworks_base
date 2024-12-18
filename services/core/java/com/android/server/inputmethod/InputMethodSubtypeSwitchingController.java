/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.server.inputmethod;

import static com.android.server.inputmethod.InputMethodUtils.NOT_A_SUBTYPE_INDEX;

import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Printer;
import android.util.Slog;
import android.view.inputmethod.Flags;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodSubtype;

import com.android.internal.annotations.VisibleForTesting;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * InputMethodSubtypeSwitchingController controls the switching behavior of the subtypes.
 *
 * <p>This class is designed to be used from and only from {@link InputMethodManagerService} by
 * using {@link ImfLock ImfLock.class} as a global lock.</p>
 */
final class InputMethodSubtypeSwitchingController {
    private static final String TAG = InputMethodSubtypeSwitchingController.class.getSimpleName();
    private static final boolean DEBUG = false;

    @IntDef(prefix = {"MODE_"}, value = {
            MODE_STATIC,
            MODE_RECENT,
            MODE_AUTO
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SwitchMode {
    }

    /**
     * Switch using the static order (the order of the given list of input methods and subtypes).
     * This order is only set when given a new list, and never updated.
     */
    public static final int MODE_STATIC = 0;

    /**
     * Switch using the recency based order, going from most recent to least recent,
     * updated on {@link #onUserActionLocked user action}.
     */
    public static final int MODE_RECENT = 1;

    /**
     * If there was a {@link #onUserActionLocked user action} since the last
     * {@link #onInputMethodSubtypeChanged() switch}, and direction is forward,
     * use {@link #MODE_RECENT}, otherwise use {@link #MODE_STATIC}.
     */
    public static final int MODE_AUTO = 2;

    public static class ImeSubtypeListItem implements Comparable<ImeSubtypeListItem> {

        @NonNull
        public final CharSequence mImeName;
        @Nullable
        public final CharSequence mSubtypeName;
        @NonNull
        public final InputMethodInfo mImi;
        /**
         * The index of the subtype in the input method's array of subtypes,
         * or {@link InputMethodUtils#NOT_A_SUBTYPE_INDEX} if this item doesn't have a subtype.
         */
        public final int mSubtypeIndex;
        public final boolean mIsSystemLocale;
        public final boolean mIsSystemLanguage;

        ImeSubtypeListItem(@NonNull CharSequence imeName, @Nullable CharSequence subtypeName,
                @NonNull InputMethodInfo imi, int subtypeIndex, @Nullable String subtypeLocale,
                @NonNull String systemLocale) {
            mImeName = imeName;
            mSubtypeName = subtypeName;
            mImi = imi;
            mSubtypeIndex = subtypeIndex;
            if (TextUtils.isEmpty(subtypeLocale)) {
                mIsSystemLocale = false;
                mIsSystemLanguage = false;
            } else {
                mIsSystemLocale = subtypeLocale.equals(systemLocale);
                if (mIsSystemLocale) {
                    mIsSystemLanguage = true;
                } else {
                    final String systemLanguage = LocaleUtils.getLanguageFromLocaleString(
                            systemLocale);
                    final String subtypeLanguage = LocaleUtils.getLanguageFromLocaleString(
                            subtypeLocale);
                    mIsSystemLanguage = systemLanguage.length() >= 2
                            && systemLanguage.equals(subtypeLanguage);
                }
            }
        }

        private static int compareNullableCharSequences(@Nullable CharSequence c1,
                @Nullable CharSequence c2) {
            // For historical reasons, an empty text needs to put at the last.
            final boolean empty1 = TextUtils.isEmpty(c1);
            final boolean empty2 = TextUtils.isEmpty(c2);
            if (empty1 || empty2) {
                return (empty1 ? 1 : 0) - (empty2 ? 1 : 0);
            }
            return c1.toString().compareTo(c2.toString());
        }

        /**
         * Compares this object with the specified object for order. The fields of this class will
         * be compared in the following order.
         * <ol>
         *   <li>{@link #mImeName}</li>
         *   <li>{@link #mIsSystemLocale}</li>
         *   <li>{@link #mIsSystemLanguage}</li>
         *   <li>{@link #mSubtypeName}</li>
         *   <li>{@link #mImi} with {@link InputMethodInfo#getId()}</li>
         * </ol>
         * Note: this class has a natural ordering that is inconsistent with
         * {@link #equals(Object)}. This method doesn't compare {@link #mSubtypeIndex} but
         * {@link #equals(Object)} does.
         *
         * @param other the object to be compared.
         * @return a negative integer, zero, or positive integer as this object is less than, equal
         * to, or greater than the specified <code>other</code> object.
         */
        @Override
        public int compareTo(ImeSubtypeListItem other) {
            int result = compareNullableCharSequences(mImeName, other.mImeName);
            if (result != 0) {
                return result;
            }
            if (!Flags.imeSwitcherRevamp()) {
                // Subtype that has the same locale of the system's has higher priority.
                result = (mIsSystemLocale ? -1 : 0) - (other.mIsSystemLocale ? -1 : 0);
                if (result != 0) {
                    return result;
                }
                // Subtype that has the same language of the system's has higher priority.
                result = (mIsSystemLanguage ? -1 : 0) - (other.mIsSystemLanguage ? -1 : 0);
                if (result != 0) {
                    return result;
                }
                result = compareNullableCharSequences(mSubtypeName, other.mSubtypeName);
                if (result != 0) {
                    return result;
                }
            }
            // This will no longer compare by subtype name, however as {@link Collections.sort} is
            // guaranteed to be a stable sorting, this allows sorting by the IME name (and ID),
            // while maintaining the order of subtypes (given by each IME) at the IME level.
            return mImi.getId().compareTo(other.mImi.getId());
        }

        @Override
        public String toString() {
            return "ImeSubtypeListItem{"
                    + "mImeName=" + mImeName
                    + " mSubtypeName=" + mSubtypeName
                    + " mSubtypeIndex=" + mSubtypeIndex
                    + " mIsSystemLocale=" + mIsSystemLocale
                    + " mIsSystemLanguage=" + mIsSystemLanguage
                    + "}";
        }

        @Override
        public boolean equals(Object o) {
            if (o == this) {
                return true;
            }
            if (o instanceof ImeSubtypeListItem) {
                final ImeSubtypeListItem that = (ImeSubtypeListItem) o;
                return Objects.equals(this.mImi, that.mImi)
                        && this.mSubtypeIndex == that.mSubtypeIndex;
            }
            return false;
        }
    }

    @NonNull
    static List<ImeSubtypeListItem> getSortedInputMethodAndSubtypeList(
            boolean includeAuxiliarySubtypes, boolean isScreenLocked, boolean forImeMenu,
            @NonNull Context context, @NonNull InputMethodSettings settings) {
        final int userId = settings.getUserId();
        final Context userAwareContext = context.getUserId() == userId
                ? context
                : context.createContextAsUser(UserHandle.of(userId), 0 /* flags */);
        final String mSystemLocaleStr = SystemLocaleWrapper.get(userId).get(0).toLanguageTag();

        final ArrayList<InputMethodInfo> imis = settings.getEnabledInputMethodList();
        if (imis.isEmpty()) {
            Slog.w(TAG, "Enabled input method list is empty.");
            return new ArrayList<>();
        }
        if (isScreenLocked && includeAuxiliarySubtypes) {
            if (DEBUG) {
                Slog.w(TAG, "Auxiliary subtypes are not allowed to be shown in lock screen.");
            }
            includeAuxiliarySubtypes = false;
        }
        final ArrayList<ImeSubtypeListItem> imList = new ArrayList<>();
        final int numImes = imis.size();
        for (int i = 0; i < numImes; ++i) {
            final InputMethodInfo imi = imis.get(i);
            if (forImeMenu && !imi.shouldShowInInputMethodPicker()) {
                continue;
            }
            final List<InputMethodSubtype> explicitlyOrImplicitlyEnabledSubtypeList =
                    settings.getEnabledInputMethodSubtypeList(imi, true);
            final ArraySet<String> enabledSubtypeSet = new ArraySet<>();
            for (InputMethodSubtype subtype : explicitlyOrImplicitlyEnabledSubtypeList) {
                enabledSubtypeSet.add(String.valueOf(subtype.hashCode()));
            }
            final CharSequence imeLabel = imi.loadLabel(userAwareContext.getPackageManager());
            if (!enabledSubtypeSet.isEmpty()) {
                final int subtypeCount = imi.getSubtypeCount();
                if (DEBUG) {
                    Slog.v(TAG, "Add subtypes: " + subtypeCount + ", " + imi.getId());
                }
                for (int j = 0; j < subtypeCount; ++j) {
                    final InputMethodSubtype subtype = imi.getSubtypeAt(j);
                    final String subtypeHashCode = String.valueOf(subtype.hashCode());
                    // We show all enabled IMEs and subtypes when an IME is shown.
                    if (enabledSubtypeSet.contains(subtypeHashCode)
                            && (includeAuxiliarySubtypes || !subtype.isAuxiliary())) {
                        final CharSequence subtypeLabel =
                                subtype.overridesImplicitlyEnabledSubtype() ? null : subtype
                                        .getDisplayName(userAwareContext, imi.getPackageName(),
                                                imi.getServiceInfo().applicationInfo);
                        imList.add(new ImeSubtypeListItem(imeLabel,
                                subtypeLabel, imi, j, subtype.getLocale(), mSystemLocaleStr));

                        // Removing this subtype from enabledSubtypeSet because we no
                        // longer need to add an entry of this subtype to imList to avoid
                        // duplicated entries.
                        enabledSubtypeSet.remove(subtypeHashCode);
                    }
                }
            } else {
                imList.add(new ImeSubtypeListItem(imeLabel, null, imi, NOT_A_SUBTYPE_INDEX, null,
                        mSystemLocaleStr));
            }
        }
        Collections.sort(imList);
        return imList;
    }

    @NonNull
    private static List<ImeSubtypeListItem> getInputMethodAndSubtypeListForHardwareKeyboard(
            @NonNull Context context, @NonNull InputMethodSettings settings) {
        if (!Flags.imeSwitcherRevamp()) {
            return new ArrayList<>();
        }
        final int userId = settings.getUserId();
        final Context userAwareContext = context.getUserId() == userId
                ? context
                : context.createContextAsUser(UserHandle.of(userId), 0 /* flags */);
        final String mSystemLocaleStr = SystemLocaleWrapper.get(userId).get(0).toLanguageTag();

        final ArrayList<InputMethodInfo> imis = settings.getEnabledInputMethodList();
        if (imis.isEmpty()) {
            Slog.w(TAG, "Enabled input method list is empty.");
            return new ArrayList<>();
        }

        final ArrayList<ImeSubtypeListItem> imList = new ArrayList<>();
        final int numImes = imis.size();
        for (int i = 0; i < numImes; ++i) {
            final InputMethodInfo imi = imis.get(i);
            if (!imi.shouldShowInInputMethodPicker()) {
                continue;
            }
            final var subtypes = settings.getEnabledInputMethodSubtypeList(imi, true);
            final ArraySet<InputMethodSubtype> enabledSubtypeSet = new ArraySet<>(subtypes);
            final CharSequence imeLabel = imi.loadLabel(userAwareContext.getPackageManager());
            if (!subtypes.isEmpty()) {
                final int subtypeCount = imi.getSubtypeCount();
                if (DEBUG) {
                    Slog.v(TAG, "Add subtypes: " + subtypeCount + ", " + imi.getId());
                }
                for (int j = 0; j < subtypeCount; j++) {
                    final InputMethodSubtype subtype = imi.getSubtypeAt(j);
                    if (enabledSubtypeSet.contains(subtype)
                            && subtype.isSuitableForPhysicalKeyboardLayoutMapping()) {
                        final CharSequence subtypeLabel =
                                subtype.overridesImplicitlyEnabledSubtype() ? null : subtype
                                        .getDisplayName(userAwareContext, imi.getPackageName(),
                                                imi.getServiceInfo().applicationInfo);
                        imList.add(new ImeSubtypeListItem(imeLabel,
                                subtypeLabel, imi, j, subtype.getLocale(), mSystemLocaleStr));
                    }
                }
            } else {
                imList.add(new ImeSubtypeListItem(imeLabel, null, imi, NOT_A_SUBTYPE_INDEX, null,
                        mSystemLocaleStr));
            }
        }
        return imList;
    }

    private static int calculateSubtypeIndex(@NonNull InputMethodInfo imi,
            @Nullable InputMethodSubtype subtype) {
        return subtype != null ? SubtypeUtils.getSubtypeIndexFromHashCode(imi, subtype.hashCode())
                : NOT_A_SUBTYPE_INDEX;
    }

    private static class StaticRotationList {

        @NonNull
        private final List<ImeSubtypeListItem> mImeSubtypeList;

        StaticRotationList(@NonNull List<ImeSubtypeListItem> imeSubtypeList) {
            mImeSubtypeList = imeSubtypeList;
        }

        /**
         * Returns the index of the specified input method and subtype in the given list.
         *
         * @param imi     The {@link InputMethodInfo} to be searched.
         * @param subtype The {@link InputMethodSubtype} to be searched. null if the input method
         *                does not have a subtype.
         * @return The index in the given list. -1 if not found.
         */
        private int getIndex(@NonNull InputMethodInfo imi, @Nullable InputMethodSubtype subtype) {
            final int currentSubtypeIndex = calculateSubtypeIndex(imi, subtype);
            final int numSubtypes = mImeSubtypeList.size();
            for (int i = 0; i < numSubtypes; ++i) {
                final ImeSubtypeListItem item = mImeSubtypeList.get(i);
                // Skip until the current IME/subtype is found.
                if (imi.equals(item.mImi) && item.mSubtypeIndex == currentSubtypeIndex) {
                    return i;
                }
            }
            return -1;
        }

        @Nullable
        public ImeSubtypeListItem getNextInputMethodLocked(boolean onlyCurrentIme,
                @NonNull InputMethodInfo imi, @Nullable InputMethodSubtype subtype) {
            if (mImeSubtypeList.size() <= 1) {
                return null;
            }
            final int currentIndex = getIndex(imi, subtype);
            if (currentIndex < 0) {
                return null;
            }
            final int numSubtypes = mImeSubtypeList.size();
            for (int offset = 1; offset < numSubtypes; ++offset) {
                // Start searching the next IME/subtype from the next of the current index.
                final int candidateIndex = (currentIndex + offset) % numSubtypes;
                final ImeSubtypeListItem candidate = mImeSubtypeList.get(candidateIndex);
                // Skip if searching inside the current IME only, but the candidate is not
                // the current IME.
                if (onlyCurrentIme && !imi.equals(candidate.mImi)) {
                    continue;
                }
                return candidate;
            }
            return null;
        }

        protected void dump(@NonNull Printer pw, @NonNull String prefix) {
            final int numSubtypes = mImeSubtypeList.size();
            for (int rank = 0; rank < numSubtypes; ++rank) {
                final ImeSubtypeListItem item = mImeSubtypeList.get(rank);
                pw.println(prefix + "rank=" + rank + " item=" + item);
            }
        }
    }

    private static class DynamicRotationList {

        private static final String TAG = DynamicRotationList.class.getSimpleName();
        @NonNull
        private final List<ImeSubtypeListItem> mImeSubtypeList;
        @NonNull
        private final int[] mUsageHistoryOfSubtypeListItemIndex;

        private DynamicRotationList(@NonNull List<ImeSubtypeListItem> imeSubtypeListItems) {
            mImeSubtypeList = imeSubtypeListItems;
            mUsageHistoryOfSubtypeListItemIndex = new int[mImeSubtypeList.size()];
            final int numSubtypes = mImeSubtypeList.size();
            for (int i = 0; i < numSubtypes; i++) {
                mUsageHistoryOfSubtypeListItemIndex[i] = i;
            }
        }

        /**
         * Returns the index of the specified object in
         * {@link #mUsageHistoryOfSubtypeListItemIndex}.
         * <p>We call the index of {@link #mUsageHistoryOfSubtypeListItemIndex} as "Usage Rank"
         * so as not to be confused with the index in {@link #mImeSubtypeList}.
         *
         * @return -1 when the specified item doesn't belong to {@link #mImeSubtypeList} actually.
         */
        private int getUsageRank(@NonNull InputMethodInfo imi,
                @Nullable InputMethodSubtype subtype) {
            final int currentSubtypeIndex = calculateSubtypeIndex(imi, subtype);
            final int numItems = mUsageHistoryOfSubtypeListItemIndex.length;
            for (int usageRank = 0; usageRank < numItems; usageRank++) {
                final int subtypeListItemIndex = mUsageHistoryOfSubtypeListItemIndex[usageRank];
                final ImeSubtypeListItem subtypeListItem =
                        mImeSubtypeList.get(subtypeListItemIndex);
                if (subtypeListItem.mImi.equals(imi)
                        && subtypeListItem.mSubtypeIndex == currentSubtypeIndex) {
                    return usageRank;
                }
            }
            // Not found in the known IME/Subtype list.
            return -1;
        }

        public void onUserAction(@NonNull InputMethodInfo imi,
                @Nullable InputMethodSubtype subtype) {
            final int currentUsageRank = getUsageRank(imi, subtype);
            // Do nothing if currentUsageRank == -1 (not found), or currentUsageRank == 0
            if (currentUsageRank <= 0) {
                return;
            }
            final int currentItemIndex = mUsageHistoryOfSubtypeListItemIndex[currentUsageRank];
            System.arraycopy(mUsageHistoryOfSubtypeListItemIndex, 0,
                    mUsageHistoryOfSubtypeListItemIndex, 1, currentUsageRank);
            mUsageHistoryOfSubtypeListItemIndex[0] = currentItemIndex;
        }

        @Nullable
        public ImeSubtypeListItem getNextInputMethodLocked(boolean onlyCurrentIme,
                @NonNull InputMethodInfo imi, @Nullable InputMethodSubtype subtype) {
            int currentUsageRank = getUsageRank(imi, subtype);
            if (currentUsageRank < 0) {
                if (DEBUG) {
                    Slog.d(TAG, "IME/subtype is not found: " + imi.getId() + ", " + subtype);
                }
                return null;
            }
            final int numItems = mUsageHistoryOfSubtypeListItemIndex.length;
            for (int i = 1; i < numItems; i++) {
                final int subtypeListItemRank = (currentUsageRank + i) % numItems;
                final int subtypeListItemIndex =
                        mUsageHistoryOfSubtypeListItemIndex[subtypeListItemRank];
                final ImeSubtypeListItem subtypeListItem =
                        mImeSubtypeList.get(subtypeListItemIndex);
                if (onlyCurrentIme && !imi.equals(subtypeListItem.mImi)) {
                    continue;
                }
                return subtypeListItem;
            }
            return null;
        }

        protected void dump(@NonNull Printer pw, @NonNull String prefix) {
            for (int rank = 0; rank < mUsageHistoryOfSubtypeListItemIndex.length; ++rank) {
                final int index = mUsageHistoryOfSubtypeListItemIndex[rank];
                final ImeSubtypeListItem item = mImeSubtypeList.get(index);
                pw.println(prefix + "rank=" + rank + " item=" + item);
            }
        }
    }

    /**
     * List container that allows getting the next item in either forwards or backwards direction,
     * in either static or recency order, and either in the same IME or not.
     */
    private static class RotationList {

        /**
         * List of items in a static order.
         */
        @NonNull
        private final List<ImeSubtypeListItem> mItems;

        /**
         * Mapping of recency index to static index (in {@link #mItems}), with lower indices being
         * more recent.
         */
        @NonNull
        private final int[] mRecencyMap;

        RotationList(@NonNull List<ImeSubtypeListItem> items) {
            mItems = items;
            mRecencyMap = new int[items.size()];
            for (int i = 0; i < mItems.size(); i++) {
                mRecencyMap[i] = i;
            }
        }

        /**
         * Gets the next input method and subtype from the given ones.
         *
         * <p>If the given input method and subtype are not found, this returns the most recent
         * input method and subtype.</p>
         *
         * @param imi            the input method to find the next value from.
         * @param subtype        the input method subtype to find the next value from, if any.
         * @param onlyCurrentIme whether to consider only subtypes of the current input method.
         * @param useRecency     whether to use the recency order, or the static order.
         * @param forward        whether to search forwards to backwards in the list.
         * @return the next input method and subtype if found, otherwise {@code null}.
         */
        @Nullable
        public ImeSubtypeListItem next(@NonNull InputMethodInfo imi,
                @Nullable InputMethodSubtype subtype, boolean onlyCurrentIme,
                boolean useRecency, boolean forward) {
            if (mItems.isEmpty()) {
                return null;
            }
            final int index = getIndex(imi, subtype, useRecency);
            if (index < 0) {
                Slog.w(TAG, "Trying to switch away from input method: " + imi
                        + " and subtype " + subtype + " which are not in the list,"
                        + " falling back to most recent item in list.");
                return mItems.get(mRecencyMap[0]);
            }

            final int incrementSign = (forward ? 1 : -1);

            final int size = mItems.size();
            for (int i = 1; i < size; i++) {
                final int nextIndex = (index + i * incrementSign + size) % size;
                final int mappedIndex = useRecency ? mRecencyMap[nextIndex] : nextIndex;
                final var nextItem = mItems.get(mappedIndex);
                if (!onlyCurrentIme || nextItem.mImi.equals(imi)) {
                    return nextItem;
                }
            }
            return null;
        }

        /**
         * Sets the given input method and subtype as the most recent one.
         *
         * @param imi     the input method to set as the most recent.
         * @param subtype the input method subtype to set as the most recent, if any.
         * @return {@code true} if the recency was updated, otherwise {@code false}.
         */
        public boolean setMostRecent(@NonNull InputMethodInfo imi,
                @Nullable InputMethodSubtype subtype) {
            if (mItems.isEmpty()) {
                return false;
            }

            final int recencyIndex = getIndex(imi, subtype, true /* useRecency */);
            if (recencyIndex <= 0) {
                // Already most recent or not found.
                return false;
            }
            final int staticIndex = mRecencyMap[recencyIndex];
            System.arraycopy(mRecencyMap, 0, mRecencyMap, 1, recencyIndex);
            mRecencyMap[0] = staticIndex;
            return true;
        }

        /**
         * Gets the index of the given input method and subtype, in either recency or static order.
         *
         * @param imi        the input method to get the index of.
         * @param subtype    the input method subtype to get the index of, if any.
         * @param useRecency whether to get the index in the recency or static order.
         * @return an index in either {@link #mItems} or {@link #mRecencyMap}, or {@code -1}
         * if not found.
         */
        @IntRange(from = -1)
        private int getIndex(@NonNull InputMethodInfo imi, @Nullable InputMethodSubtype subtype,
                boolean useRecency) {
            final int subtypeIndex = calculateSubtypeIndex(imi, subtype);
            for (int i = 0; i < mItems.size(); i++) {
                final int mappedIndex = useRecency ? mRecencyMap[i] : i;
                final var item = mItems.get(mappedIndex);
                if (item.mImi.equals(imi) && item.mSubtypeIndex == subtypeIndex) {
                    return i;
                }
            }
            return -1;
        }

        /** Dumps the state of the list into the given printer. */
        private void dump(@NonNull Printer pw, @NonNull String prefix) {
            pw.println(prefix + "Static order:");
            for (int i = 0; i < mItems.size(); ++i) {
                final var item = mItems.get(i);
                pw.println(prefix + "  i=" + i + " item=" + item);
            }
            pw.println(prefix + "Recency order:");
            for (int i = 0; i < mRecencyMap.length; ++i) {
                final int index = mRecencyMap[i];
                final var item = mItems.get(index);
                pw.println(prefix + "  i=" + i + " item=" + item);
            }
        }
    }

    @NonNull
    private DynamicRotationList mSwitchingAwareRotationList =
            new DynamicRotationList(Collections.emptyList());
    @NonNull
    private StaticRotationList mSwitchingUnawareRotationList =
            new StaticRotationList(Collections.emptyList());
    /** List of input methods and subtypes. */
    @NonNull
    private RotationList mRotationList = new RotationList(Collections.emptyList());
    /** List of input methods and subtypes suitable for hardware keyboards. */
    @NonNull
    private RotationList mHardwareRotationList = new RotationList(Collections.emptyList());

    /**
     * Whether there was a user action since the last input method and subtype switch.
     * Used to determine the switching behaviour for {@link #MODE_AUTO}.
     */
    private boolean mUserActionSinceSwitch;

    /**
     * Updates the list of input methods and subtypes used for switching. If the given items are
     * equal to the existing ones (regardless of recency order), the update is skipped and the
     * current recency order is kept. Otherwise, the recency order is reset.
     *
     * @param sortedEnabledItems    the sorted list of enabled input methods and subtypes.
     * @param hardwareKeyboardItems the unsorted list of enabled input method and subtypes
     *                              suitable for hardware keyboards.
     */
    @VisibleForTesting
    void update(@NonNull List<ImeSubtypeListItem> sortedEnabledItems,
            @NonNull List<ImeSubtypeListItem> hardwareKeyboardItems) {
        final var switchingAwareImeSubtypes = filterImeSubtypeList(sortedEnabledItems,
                true /* supportsSwitchingToNextInputMethod */);
        final var switchingUnawareImeSubtypes = filterImeSubtypeList(sortedEnabledItems,
                false /* supportsSwitchingToNextInputMethod */);

        if (!Objects.equals(mSwitchingAwareRotationList.mImeSubtypeList,
                switchingAwareImeSubtypes)) {
            mSwitchingAwareRotationList = new DynamicRotationList(switchingAwareImeSubtypes);
        }

        if (!Objects.equals(mSwitchingUnawareRotationList.mImeSubtypeList,
                switchingUnawareImeSubtypes)) {
            mSwitchingUnawareRotationList = new StaticRotationList(switchingUnawareImeSubtypes);
        }

        if (Flags.imeSwitcherRevamp()
                && !Objects.equals(mRotationList.mItems, sortedEnabledItems)) {
            mRotationList = new RotationList(sortedEnabledItems);
        }

        if (Flags.imeSwitcherRevamp()
                && !Objects.equals(mHardwareRotationList.mItems, hardwareKeyboardItems)) {
            mHardwareRotationList = new RotationList(hardwareKeyboardItems);
        }
    }

    /**
     * Gets the next input method and subtype, starting from the given ones, in the given direction.
     *
     * <p>If the given input method and subtype are not found, this returns the most recent
     * input method and subtype.</p>
     *
     * @param onlyCurrentIme whether to consider only subtypes of the current input method.
     * @param imi            the input method to find the next value from.
     * @param subtype        the input method subtype to find the next value from, if any.
     * @param mode           the switching mode.
     * @param forward        whether to search search forwards or backwards in the list.
     * @return the next input method and subtype if found, otherwise {@code null}.
     */
    @Nullable
    public ImeSubtypeListItem getNextInputMethodLocked(boolean onlyCurrentIme,
            @NonNull InputMethodInfo imi, @Nullable InputMethodSubtype subtype,
            @SwitchMode int mode, boolean forward) {
        if (Flags.imeSwitcherRevamp()) {
            return mRotationList.next(imi, subtype, onlyCurrentIme,
                    isRecency(mode, forward), forward);
        } else if (imi.supportsSwitchingToNextInputMethod()) {
            return mSwitchingAwareRotationList.getNextInputMethodLocked(onlyCurrentIme, imi,
                    subtype);
        } else {
            return mSwitchingUnawareRotationList.getNextInputMethodLocked(onlyCurrentIme, imi,
                    subtype);
        }
    }

    /**
     * Gets the next input method and subtype suitable for hardware keyboards, starting from the
     * given ones, in the given direction.
     *
     * <p>If the given input method and subtype are not found, this returns the most recent
     * input method and subtype.</p>
     *
     * @param onlyCurrentIme whether to consider only subtypes of the current input method.
     * @param imi            the input method to find the next value from.
     * @param subtype        the input method subtype to find the next value from, if any.
     * @param mode           the switching mode
     * @param forward        whether to search search forwards or backwards in the list.
     * @return the next input method and subtype if found, otherwise {@code null}.
     */
    @Nullable
    public ImeSubtypeListItem getNextInputMethodForHardware(boolean onlyCurrentIme,
            @NonNull InputMethodInfo imi, @Nullable InputMethodSubtype subtype,
            @SwitchMode int mode, boolean forward) {
        if (Flags.imeSwitcherRevamp()) {
            return mHardwareRotationList.next(imi, subtype, onlyCurrentIme,
                    isRecency(mode, forward), forward);
        }
        return null;
    }

    /**
     * Called when the user took an action that should update the recency of the current
     * input method and subtype in the switching list.
     *
     * @param imi     the currently selected input method.
     * @param subtype the currently selected input method subtype, if any.
     * @return {@code true} if the recency was updated, otherwise {@code false}.
     * @see android.inputmethodservice.InputMethodServiceInternal#notifyUserActionIfNecessary()
     */
    public boolean onUserActionLocked(@NonNull InputMethodInfo imi,
            @Nullable InputMethodSubtype subtype) {
        boolean recencyUpdated = false;
        if (Flags.imeSwitcherRevamp()) {
            recencyUpdated |= mRotationList.setMostRecent(imi, subtype);
            recencyUpdated |= mHardwareRotationList.setMostRecent(imi, subtype);
            if (recencyUpdated) {
                mUserActionSinceSwitch = true;
            }
        } else if (imi.supportsSwitchingToNextInputMethod()) {
            mSwitchingAwareRotationList.onUserAction(imi, subtype);
        }
        return recencyUpdated;
    }

    /** Called when the input method and subtype was changed. */
    public void onInputMethodSubtypeChanged() {
        mUserActionSinceSwitch = false;
    }

    /**
     * Whether the given mode and direction result in recency or static order.
     *
     * <p>{@link #MODE_AUTO} resolves to the recency order for the first forwards switch
     * after an {@link #onUserActionLocked user action}, and otherwise to the static order.</p>
     *
     * @param mode    the switching mode.
     * @param forward the switching direction.
     * @return {@code true} for the recency order, otherwise {@code false}.
     */
    private boolean isRecency(@SwitchMode int mode, boolean forward) {
        if (mode == MODE_AUTO && mUserActionSinceSwitch && forward) {
            return true;
        } else {
            return mode == MODE_RECENT;
        }
    }

    @NonNull
    private static List<ImeSubtypeListItem> filterImeSubtypeList(
            @NonNull List<ImeSubtypeListItem> items,
            boolean supportsSwitchingToNextInputMethod) {
        final ArrayList<ImeSubtypeListItem> result = new ArrayList<>();
        final int numItems = items.size();
        for (int i = 0; i < numItems; i++) {
            final ImeSubtypeListItem item = items.get(i);
            if (item.mImi.supportsSwitchingToNextInputMethod()
                    == supportsSwitchingToNextInputMethod) {
                result.add(item);
            }
        }
        return result;
    }

    void dump(@NonNull Printer pw, @NonNull String prefix) {
        pw.println(prefix + "mSwitchingAwareRotationList:");
        mSwitchingAwareRotationList.dump(pw, prefix + "  ");
        pw.println(prefix + "mSwitchingUnawareRotationList:");
        mSwitchingUnawareRotationList.dump(pw, prefix + "  ");
        if (Flags.imeSwitcherRevamp()) {
            pw.println(prefix + "mRotationList:");
            mRotationList.dump(pw, prefix + "  ");
            pw.println(prefix + "mHardwareRotationList:");
            mHardwareRotationList.dump(pw, prefix + "  ");
            pw.println(prefix + "User action since last switch: " + mUserActionSinceSwitch);
        }
    }

    InputMethodSubtypeSwitchingController() {
    }

    public void resetCircularListLocked(@NonNull Context context,
            @NonNull InputMethodSettings settings) {
        update(getSortedInputMethodAndSubtypeList(
                        false /* includeAuxiliarySubtypes */, false /* isScreenLocked */,
                        false /* forImeMenu */, context, settings),
                getInputMethodAndSubtypeListForHardwareKeyboard(context, settings));
    }
}
