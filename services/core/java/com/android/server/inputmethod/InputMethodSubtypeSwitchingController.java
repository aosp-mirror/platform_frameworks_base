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

import android.annotation.AnyThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.Context;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Printer;
import android.util.Slog;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodSubtype;

import com.android.internal.annotations.VisibleForTesting;

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
    private static final int NOT_A_SUBTYPE_ID = InputMethodUtils.NOT_A_SUBTYPE_ID;

    public static class ImeSubtypeListItem implements Comparable<ImeSubtypeListItem> {
        public final CharSequence mImeName;
        public final CharSequence mSubtypeName;
        public final InputMethodInfo mImi;
        public final int mSubtypeId;
        public final boolean mIsSystemLocale;
        public final boolean mIsSystemLanguage;

        ImeSubtypeListItem(CharSequence imeName, CharSequence subtypeName,
                InputMethodInfo imi, int subtypeId, String subtypeLocale, String systemLocale) {
            mImeName = imeName;
            mSubtypeName = subtypeName;
            mImi = imi;
            mSubtypeId = subtypeId;
            if (TextUtils.isEmpty(subtypeLocale)) {
                mIsSystemLocale = false;
                mIsSystemLanguage = false;
            } else {
                mIsSystemLocale = subtypeLocale.equals(systemLocale);
                if (mIsSystemLocale) {
                    mIsSystemLanguage = true;
                } else {
                    // TODO: Use Locale#getLanguage or Locale#toLanguageTag
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
         * Note: this class has a natural ordering that is inconsistent with {@link #equals(Object).
         * This method doesn't compare {@link #mSubtypeId} but {@link #equals(Object)} does.
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
            return mImi.getId().compareTo(other.mImi.getId());
        }

        @Override
        public String toString() {
            return "ImeSubtypeListItem{"
                    + "mImeName=" + mImeName
                    + " mSubtypeName=" + mSubtypeName
                    + " mSubtypeId=" + mSubtypeId
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
                return Objects.equals(this.mImi, that.mImi) && this.mSubtypeId == that.mSubtypeId;
            }
            return false;
        }
    }

    static List<ImeSubtypeListItem> getSortedInputMethodAndSubtypeList(
            boolean includeAuxiliarySubtypes, boolean isScreenLocked, boolean forImeMenu,
            @NonNull Context context, @NonNull InputMethodMap methodMap,
            @UserIdInt int userId) {
        final Context userAwareContext = context.getUserId() == userId
                ? context
                : context.createContextAsUser(UserHandle.of(userId), 0 /* flags */);
        final String mSystemLocaleStr = SystemLocaleWrapper.get(userId).get(0).toLanguageTag();
        final InputMethodSettings settings = InputMethodSettings.create(methodMap, userId);

        final ArrayList<InputMethodInfo> imis = settings.getEnabledInputMethodListLocked();
        if (imis.isEmpty()) {
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
                    settings.getEnabledInputMethodSubtypeListLocked(imi, true);
            final ArraySet<String> enabledSubtypeSet = new ArraySet<>();
            for (InputMethodSubtype subtype : explicitlyOrImplicitlyEnabledSubtypeList) {
                enabledSubtypeSet.add(String.valueOf(subtype.hashCode()));
            }
            final CharSequence imeLabel = imi.loadLabel(userAwareContext.getPackageManager());
            if (enabledSubtypeSet.size() > 0) {
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
                imList.add(new ImeSubtypeListItem(imeLabel, null, imi, NOT_A_SUBTYPE_ID, null,
                        mSystemLocaleStr));
            }
        }
        Collections.sort(imList);
        return imList;
    }

    private static int calculateSubtypeId(InputMethodInfo imi, InputMethodSubtype subtype) {
        return subtype != null ? SubtypeUtils.getSubtypeIdFromHashCode(imi, subtype.hashCode())
                : NOT_A_SUBTYPE_ID;
    }

    private static class StaticRotationList {
        private final List<ImeSubtypeListItem> mImeSubtypeList;
        StaticRotationList(final List<ImeSubtypeListItem> imeSubtypeList) {
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
        private int getIndex(InputMethodInfo imi, InputMethodSubtype subtype) {
            final int currentSubtypeId = calculateSubtypeId(imi, subtype);
            final int numSubtypes = mImeSubtypeList.size();
            for (int i = 0; i < numSubtypes; ++i) {
                final ImeSubtypeListItem isli = mImeSubtypeList.get(i);
                // Skip until the current IME/subtype is found.
                if (imi.equals(isli.mImi) && isli.mSubtypeId == currentSubtypeId) {
                    return i;
                }
            }
            return -1;
        }

        public ImeSubtypeListItem getNextInputMethodLocked(boolean onlyCurrentIme,
                InputMethodInfo imi, InputMethodSubtype subtype) {
            if (imi == null) {
                return null;
            }
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

        protected void dump(final Printer pw, final String prefix) {
            final int numSubtypes = mImeSubtypeList.size();
            for (int i = 0; i < numSubtypes; ++i) {
                final int rank = i;
                final ImeSubtypeListItem item = mImeSubtypeList.get(i);
                pw.println(prefix + "rank=" + rank + " item=" + item);
            }
        }
    }

    private static class DynamicRotationList {
        private static final String TAG = DynamicRotationList.class.getSimpleName();
        private final List<ImeSubtypeListItem> mImeSubtypeList;
        private final int[] mUsageHistoryOfSubtypeListItemIndex;

        private DynamicRotationList(final List<ImeSubtypeListItem> imeSubtypeListItems) {
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
        private int getUsageRank(final InputMethodInfo imi, InputMethodSubtype subtype) {
            final int currentSubtypeId = calculateSubtypeId(imi, subtype);
            final int numItems = mUsageHistoryOfSubtypeListItemIndex.length;
            for (int usageRank = 0; usageRank < numItems; usageRank++) {
                final int subtypeListItemIndex = mUsageHistoryOfSubtypeListItemIndex[usageRank];
                final ImeSubtypeListItem subtypeListItem =
                        mImeSubtypeList.get(subtypeListItemIndex);
                if (subtypeListItem.mImi.equals(imi)
                        && subtypeListItem.mSubtypeId == currentSubtypeId) {
                    return usageRank;
                }
            }
            // Not found in the known IME/Subtype list.
            return -1;
        }

        public void onUserAction(InputMethodInfo imi, InputMethodSubtype subtype) {
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

        public ImeSubtypeListItem getNextInputMethodLocked(boolean onlyCurrentIme,
                InputMethodInfo imi, InputMethodSubtype subtype) {
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

        protected void dump(final Printer pw, final String prefix) {
            for (int i = 0; i < mUsageHistoryOfSubtypeListItemIndex.length; ++i) {
                final int rank = mUsageHistoryOfSubtypeListItemIndex[i];
                final ImeSubtypeListItem item = mImeSubtypeList.get(i);
                pw.println(prefix + "rank=" + rank + " item=" + item);
            }
        }
    }

    @VisibleForTesting
    public static class ControllerImpl {
        private final DynamicRotationList mSwitchingAwareRotationList;
        private final StaticRotationList mSwitchingUnawareRotationList;

        public static ControllerImpl createFrom(final ControllerImpl currentInstance,
                final List<ImeSubtypeListItem> sortedEnabledItems) {
            DynamicRotationList switchingAwareRotationList = null;
            {
                final List<ImeSubtypeListItem> switchingAwareImeSubtypes =
                        filterImeSubtypeList(sortedEnabledItems,
                                true /* supportsSwitchingToNextInputMethod */);
                if (currentInstance != null
                        && currentInstance.mSwitchingAwareRotationList != null
                        && Objects.equals(
                                currentInstance.mSwitchingAwareRotationList.mImeSubtypeList,
                                switchingAwareImeSubtypes)) {
                    // Can reuse the current instance.
                    switchingAwareRotationList = currentInstance.mSwitchingAwareRotationList;
                }
                if (switchingAwareRotationList == null) {
                    switchingAwareRotationList = new DynamicRotationList(switchingAwareImeSubtypes);
                }
            }

            StaticRotationList switchingUnawareRotationList = null;
            {
                final List<ImeSubtypeListItem> switchingUnawareImeSubtypes = filterImeSubtypeList(
                        sortedEnabledItems, false /* supportsSwitchingToNextInputMethod */);
                if (currentInstance != null
                        && currentInstance.mSwitchingUnawareRotationList != null
                        && Objects.equals(
                                currentInstance.mSwitchingUnawareRotationList.mImeSubtypeList,
                                switchingUnawareImeSubtypes)) {
                    // Can reuse the current instance.
                    switchingUnawareRotationList = currentInstance.mSwitchingUnawareRotationList;
                }
                if (switchingUnawareRotationList == null) {
                    switchingUnawareRotationList =
                            new StaticRotationList(switchingUnawareImeSubtypes);
                }
            }

            return new ControllerImpl(switchingAwareRotationList, switchingUnawareRotationList);
        }

        private ControllerImpl(final DynamicRotationList switchingAwareRotationList,
                final StaticRotationList switchingUnawareRotationList) {
            mSwitchingAwareRotationList = switchingAwareRotationList;
            mSwitchingUnawareRotationList = switchingUnawareRotationList;
        }

        public ImeSubtypeListItem getNextInputMethod(boolean onlyCurrentIme, InputMethodInfo imi,
                InputMethodSubtype subtype) {
            if (imi == null) {
                return null;
            }
            if (imi.supportsSwitchingToNextInputMethod()) {
                return mSwitchingAwareRotationList.getNextInputMethodLocked(onlyCurrentIme, imi,
                        subtype);
            } else {
                return mSwitchingUnawareRotationList.getNextInputMethodLocked(onlyCurrentIme, imi,
                        subtype);
            }
        }

        public void onUserActionLocked(InputMethodInfo imi, InputMethodSubtype subtype) {
            if (imi == null) {
                return;
            }
            if (imi.supportsSwitchingToNextInputMethod()) {
                mSwitchingAwareRotationList.onUserAction(imi, subtype);
            }
        }

        private static List<ImeSubtypeListItem> filterImeSubtypeList(
                final List<ImeSubtypeListItem> items,
                final boolean supportsSwitchingToNextInputMethod) {
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

        protected void dump(final Printer pw) {
            pw.println("    mSwitchingAwareRotationList:");
            mSwitchingAwareRotationList.dump(pw, "      ");
            pw.println("    mSwitchingUnawareRotationList:");
            mSwitchingUnawareRotationList.dump(pw, "      ");
        }
    }

    private final Context mContext;
    @UserIdInt
    private final int mUserId;
    private ControllerImpl mController;

    private InputMethodSubtypeSwitchingController(@NonNull Context context,
            @NonNull InputMethodMap methodMap, @UserIdInt int userId) {
        mContext = context;
        mUserId = userId;
        mController = ControllerImpl.createFrom(null,
                getSortedInputMethodAndSubtypeList(
                        false /* includeAuxiliarySubtypes */, false /* isScreenLocked */,
                        false /* forImeMenu */, context, methodMap, userId));
    }

    @NonNull
    public static InputMethodSubtypeSwitchingController createInstanceLocked(
            @NonNull Context context,
            @NonNull InputMethodMap methodMap, @UserIdInt int userId) {
        return new InputMethodSubtypeSwitchingController(context, methodMap, userId);
    }

    @AnyThread
    @UserIdInt
    int getUserId() {
        return mUserId;
    }

    public void onUserActionLocked(InputMethodInfo imi, InputMethodSubtype subtype) {
        if (mController == null) {
            if (DEBUG) {
                Slog.e(TAG, "mController shouldn't be null.");
            }
            return;
        }
        mController.onUserActionLocked(imi, subtype);
    }

    public void resetCircularListLocked(@NonNull InputMethodMap methodMap) {
        mController = ControllerImpl.createFrom(mController,
                getSortedInputMethodAndSubtypeList(
                        false /* includeAuxiliarySubtypes */, false /* isScreenLocked */,
                        false /* forImeMenu */, mContext, methodMap, mUserId));
    }

    public ImeSubtypeListItem getNextInputMethodLocked(boolean onlyCurrentIme, InputMethodInfo imi,
            InputMethodSubtype subtype) {
        if (mController == null) {
            if (DEBUG) {
                Slog.e(TAG, "mController shouldn't be null.");
            }
            return null;
        }
        return mController.getNextInputMethod(onlyCurrentIme, imi, subtype);
    }

    public void dump(final Printer pw) {
        if (mController != null) {
            mController.dump(pw);
        } else {
            pw.println("    mController=null");
        }
    }
}
