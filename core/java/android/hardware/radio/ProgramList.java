/**
 * Copyright (C) 2018 The Android Open Source Project
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

package android.hardware.radio;

import android.annotation.CallbackExecutor;
import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.ArrayMap;
import android.util.ArraySet;

import com.android.internal.annotations.GuardedBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * @hide
 */
@SystemApi
public final class ProgramList implements AutoCloseable {

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private final ArrayMap<ProgramSelector.Identifier, ArrayMap<UniqueProgramIdentifier,
            RadioManager.ProgramInfo>> mPrograms = new ArrayMap<>();

    @GuardedBy("mLock")
    private final List<ListCallback> mListCallbacks = new ArrayList<>();

    @GuardedBy("mLock")
    private final List<OnCompleteListener> mOnCompleteListeners = new ArrayList<>();

    @GuardedBy("mLock")
    private OnCloseListener mOnCloseListener;

    @GuardedBy("mLock")
    private boolean mIsClosed;

    @GuardedBy("mLock")
    private boolean mIsComplete;

    ProgramList() {}

    /**
     * Callback for list change operations.
     */
    public abstract static class ListCallback {
        /**
         * Called when item was modified or added to the list.
         */
        public void onItemChanged(@NonNull ProgramSelector.Identifier id) { }

        /**
         * Called when item was removed from the list.
         */
        public void onItemRemoved(@NonNull ProgramSelector.Identifier id) { }
    }

    /**
     * Listener of list complete event.
     */
    public interface OnCompleteListener {
        /**
         * Called when the list turned complete (i.e. when the scan process
         * came to an end).
         */
        void onComplete();
    }

    interface OnCloseListener {
        void onClose();
    }

    /**
     * Registers list change callback with executor.
     */
    public void registerListCallback(@NonNull @CallbackExecutor Executor executor,
            @NonNull ListCallback callback) {
        registerListCallback(new ListCallback() {
            public void onItemChanged(@NonNull ProgramSelector.Identifier id) {
                executor.execute(() -> callback.onItemChanged(id));
            }

            public void onItemRemoved(@NonNull ProgramSelector.Identifier id) {
                executor.execute(() -> callback.onItemRemoved(id));
            }
        });
    }

    /**
     * Registers list change callback.
     */
    public void registerListCallback(@NonNull ListCallback callback) {
        synchronized (mLock) {
            if (mIsClosed) return;
            mListCallbacks.add(Objects.requireNonNull(callback));
        }
    }

    /**
     * Unregisters list change callback.
     */
    public void unregisterListCallback(@NonNull ListCallback callback) {
        synchronized (mLock) {
            if (mIsClosed) return;
            mListCallbacks.remove(Objects.requireNonNull(callback));
        }
    }

    /**
     * Adds list complete event listener with executor.
     */
    public void addOnCompleteListener(@NonNull @CallbackExecutor Executor executor,
            @NonNull OnCompleteListener listener) {
        addOnCompleteListener(() -> executor.execute(listener::onComplete));
    }

    /**
     * Adds list complete event listener.
     */
    public void addOnCompleteListener(@NonNull OnCompleteListener listener) {
        synchronized (mLock) {
            if (mIsClosed) return;
            mOnCompleteListeners.add(Objects.requireNonNull(listener));
            if (mIsComplete) listener.onComplete();
        }
    }

    /**
     * Removes list complete event listener.
     */
    public void removeOnCompleteListener(@NonNull OnCompleteListener listener) {
        synchronized (mLock) {
            if (mIsClosed) return;
            mOnCompleteListeners.remove(Objects.requireNonNull(listener));
        }
    }

    void setOnCloseListener(@Nullable OnCloseListener listener) {
        synchronized (mLock) {
            if (mOnCloseListener != null) {
                throw new IllegalStateException("Close callback is already set");
            }
            mOnCloseListener = listener;
        }
    }

    /**
     * Disables list updates and releases all resources.
     */
    public void close() {
        OnCloseListener onCompleteListenersCopied = null;
        synchronized (mLock) {
            if (mIsClosed) return;
            mIsClosed = true;
            mPrograms.clear();
            mListCallbacks.clear();
            mOnCompleteListeners.clear();
            if (mOnCloseListener != null) {
                onCompleteListenersCopied = mOnCloseListener;
                mOnCloseListener = null;
            }
        }

        if (onCompleteListenersCopied != null) {
            onCompleteListenersCopied.onClose();
        }
    }

    void apply(Chunk chunk) {
        List<ProgramSelector.Identifier> removedList = new ArrayList<>();
        Set<ProgramSelector.Identifier> changedSet = new ArraySet<>();
        List<ProgramList.ListCallback> listCallbacksCopied;
        List<OnCompleteListener> onCompleteListenersCopied = new ArrayList<>();
        synchronized (mLock) {
            if (mIsClosed) return;

            mIsComplete = false;
            listCallbacksCopied = new ArrayList<>(mListCallbacks);

            if (chunk.isPurge()) {
                Iterator<Map.Entry<ProgramSelector.Identifier,
                        ArrayMap<UniqueProgramIdentifier, RadioManager.ProgramInfo>>>
                        programsIterator = mPrograms.entrySet().iterator();
                while (programsIterator.hasNext()) {
                    Map.Entry<ProgramSelector.Identifier, ArrayMap<UniqueProgramIdentifier,
                            RadioManager.ProgramInfo>> removed = programsIterator.next();
                    if (removed.getValue() != null) {
                        removedList.add(removed.getKey());
                    }
                    programsIterator.remove();
                }
            }

            Iterator<UniqueProgramIdentifier> removedIterator = chunk.getRemoved().iterator();
            while (removedIterator.hasNext()) {
                removeLocked(removedIterator.next(), removedList);
            }
            Iterator<RadioManager.ProgramInfo> modifiedIterator = chunk.getModified().iterator();
            while (modifiedIterator.hasNext()) {
                putLocked(modifiedIterator.next(), changedSet);
            }

            if (chunk.isComplete()) {
                mIsComplete = true;
                onCompleteListenersCopied = new ArrayList<>(mOnCompleteListeners);
            }
        }

        for (int i = 0; i < removedList.size(); i++) {
            for (int cbIndex = 0; cbIndex < listCallbacksCopied.size(); cbIndex++) {
                listCallbacksCopied.get(cbIndex).onItemRemoved(removedList.get(i));
            }
        }
        Iterator<ProgramSelector.Identifier> changedIterator = changedSet.iterator();
        while (changedIterator.hasNext()) {
            ProgramSelector.Identifier changedId = changedIterator.next();
            for (int cbIndex = 0; cbIndex < listCallbacksCopied.size(); cbIndex++) {
                listCallbacksCopied.get(cbIndex).onItemChanged(changedId);
            }
        }
        if (chunk.isComplete()) {
            for (int cbIndex = 0; cbIndex < onCompleteListenersCopied.size(); cbIndex++) {
                onCompleteListenersCopied.get(cbIndex).onComplete();
            }
        }
    }

    @GuardedBy("mLock")
    private void putLocked(RadioManager.ProgramInfo value,
            Set<ProgramSelector.Identifier> changedIdentifierSet) {
        UniqueProgramIdentifier key = new UniqueProgramIdentifier(
                value.getSelector());
        ProgramSelector.Identifier primaryKey = Objects.requireNonNull(key.getPrimaryId());
        if (!mPrograms.containsKey(primaryKey)) {
            mPrograms.put(primaryKey, new ArrayMap<>());
        }
        mPrograms.get(primaryKey).put(key, value);
        changedIdentifierSet.add(primaryKey);
    }

    @GuardedBy("mLock")
    private void removeLocked(UniqueProgramIdentifier key,
            List<ProgramSelector.Identifier> removedIdentifierList) {
        ProgramSelector.Identifier primaryKey = Objects.requireNonNull(key.getPrimaryId());
        if (!mPrograms.containsKey(primaryKey)) {
            return;
        }
        Map<UniqueProgramIdentifier, RadioManager.ProgramInfo> entries = mPrograms.get(primaryKey);
        RadioManager.ProgramInfo removed = entries.remove(Objects.requireNonNull(key));
        if (removed == null) return;
        if (entries.size() == 0) {
            removedIdentifierList.add(primaryKey);
        }
    }

    /**
     * Converts the program list in its current shape to the static List<>.
     *
     * @return the new List<> object; it won't receive any further updates
     */
    public @NonNull List<RadioManager.ProgramInfo> toList() {
        List<RadioManager.ProgramInfo> list = new ArrayList<>();
        synchronized (mLock) {
            for (int index = 0; index < mPrograms.size(); index++) {
                ArrayMap<UniqueProgramIdentifier, RadioManager.ProgramInfo> entries =
                        mPrograms.valueAt(index);
                list.addAll(entries.values());
            }
        }
        return list;
    }

    /**
     * Returns the program with a specified primary identifier.
     *
     * <p>This method only returns the first program from the list return from
     * {@link #getProgramInfos}
     *
     * @param id primary identifier of a program to fetch
     * @return the program info, or null if there is no such program on the list
     *
     * @deprecated Use {@link #getProgramInfos(ProgramSelector.Identifier)} to get all programs
     * with the given primary identifier
     */
    @Deprecated
    public @Nullable RadioManager.ProgramInfo get(@NonNull ProgramSelector.Identifier id) {
        Map<UniqueProgramIdentifier, RadioManager.ProgramInfo> entries;
        synchronized (mLock) {
            entries = mPrograms.get(Objects.requireNonNull(id,
                    "Primary identifier can not be null"));
        }
        if (entries == null) {
            return null;
        }
        return entries.entrySet().iterator().next().getValue();
    }

    /**
     * Returns the program list with a specified primary identifier.
     *
     * @param id primary identifier of a program to fetch
     * @return the program info list with the primary identifier, or empty list if there is no such
     * program identifier on the list
     * @throws NullPointerException if primary identifier is {@code null}
     */
    @FlaggedApi(Flags.FLAG_HD_RADIO_IMPROVED)
    public @NonNull List<RadioManager.ProgramInfo> getProgramInfos(
            @NonNull ProgramSelector.Identifier id) {
        Objects.requireNonNull(id, "Primary identifier can not be null");
        ArrayMap<UniqueProgramIdentifier, RadioManager.ProgramInfo> entries;
        synchronized (mLock) {
            entries = mPrograms.get(id);
        }

        if (entries == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(entries.values());
    }

    /**
     * Filter for the program list.
     */
    public static final class Filter implements Parcelable {
        private final @NonNull Set<Integer> mIdentifierTypes;
        private final @NonNull Set<ProgramSelector.Identifier> mIdentifiers;
        private final boolean mIncludeCategories;
        private final boolean mExcludeModifications;
        private final @Nullable Map<String, String> mVendorFilter;

        /**
         * Constructor of program list filter.
         *
         * Arrays passed to this constructor become owned by this object, do not modify them later.
         *
         * @param identifierTypes see getIdentifierTypes()
         * @param identifiers see getIdentifiers()
         * @param includeCategories see areCategoriesIncluded()
         * @param excludeModifications see areModificationsExcluded()
         */
        public Filter(@NonNull Set<Integer> identifierTypes,
                @NonNull Set<ProgramSelector.Identifier> identifiers,
                boolean includeCategories, boolean excludeModifications) {
            mIdentifierTypes = Objects.requireNonNull(identifierTypes);
            mIdentifiers = Objects.requireNonNull(identifiers);
            mIncludeCategories = includeCategories;
            mExcludeModifications = excludeModifications;
            mVendorFilter = null;
        }

        /**
         * @hide for framework use only
         */
        public Filter() {
            mIdentifierTypes = Collections.emptySet();
            mIdentifiers = Collections.emptySet();
            mIncludeCategories = false;
            mExcludeModifications = false;
            mVendorFilter = null;
        }

        /**
         * @hide for framework use only
         */
        public Filter(@Nullable Map<String, String> vendorFilter) {
            mIdentifierTypes = Collections.emptySet();
            mIdentifiers = Collections.emptySet();
            mIncludeCategories = false;
            mExcludeModifications = false;
            mVendorFilter = vendorFilter;
        }

        private Filter(@NonNull Parcel in) {
            mIdentifierTypes = Utils.createIntSet(in);
            mIdentifiers = Utils.createSet(in, ProgramSelector.Identifier.CREATOR);
            mIncludeCategories = in.readByte() != 0;
            mExcludeModifications = in.readByte() != 0;
            mVendorFilter = Utils.readStringMap(in);
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            Utils.writeIntSet(dest, mIdentifierTypes);
            Utils.writeSet(dest, mIdentifiers);
            dest.writeByte((byte) (mIncludeCategories ? 1 : 0));
            dest.writeByte((byte) (mExcludeModifications ? 1 : 0));
            Utils.writeStringMap(dest, mVendorFilter);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        public static final @android.annotation.NonNull Parcelable.Creator<Filter> CREATOR = new Parcelable.Creator<Filter>() {
            public Filter createFromParcel(Parcel in) {
                return new Filter(in);
            }

            public Filter[] newArray(int size) {
                return new Filter[size];
            }
        };

        /**
         * @hide for framework use only
         */
        public Map<String, String> getVendorFilter() {
            return mVendorFilter;
        }

        /**
         * Returns the list of identifier types that satisfy the filter.
         *
         * If the program list entry contains at least one identifier of the type
         * listed, it satisfies this condition.
         *
         * Empty list means no filtering on identifier type.
         *
         * @return the list of accepted identifier types, must not be modified
         */
        public @NonNull Set<Integer> getIdentifierTypes() {
            return mIdentifierTypes;
        }

        /**
         * Returns the list of identifiers that satisfy the filter.
         *
         * If the program list entry contains at least one listed identifier,
         * it satisfies this condition.
         *
         * Empty list means no filtering on identifier.
         *
         * @return the list of accepted identifiers, must not be modified
         */
        public @NonNull Set<ProgramSelector.Identifier> getIdentifiers() {
            return mIdentifiers;
        }

        /**
         * Checks, if non-tunable entries that define tree structure on the
         * program list (i.e. DAB ensembles) should be included.
         *
         * @see ProgramSelector.Identifier#isCategoryType()
         */
        public boolean areCategoriesIncluded() {
            return mIncludeCategories;
        }

        /**
         * Checks, if updates on entry modifications should be disabled.
         *
         * If true, 'modified' vector of ProgramListChunk must contain list
         * additions only. Once the program is added to the list, it's not
         * updated anymore.
         */
        public boolean areModificationsExcluded() {
            return mExcludeModifications;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mIdentifierTypes, mIdentifiers, mIncludeCategories,
                    mExcludeModifications);
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof Filter)) return false;
            Filter other = (Filter) obj;

            if (mIncludeCategories != other.mIncludeCategories) return false;
            if (mExcludeModifications != other.mExcludeModifications) return false;
            if (!Objects.equals(mIdentifierTypes, other.mIdentifierTypes)) return false;
            if (!Objects.equals(mIdentifiers, other.mIdentifiers)) return false;
            return true;
        }

        @NonNull
        @Override
        public String toString() {
            return "Filter [mIdentifierTypes=" + mIdentifierTypes
                    + ", mIdentifiers=" + mIdentifiers
                    + ", mIncludeCategories=" + mIncludeCategories
                    + ", mExcludeModifications=" + mExcludeModifications + "]";
        }
    }

    /**
     * @hide This is a transport class used for internal communication between
     *       Broadcast Radio Service and RadioManager.
     *       Do not use it directly.
     */
    public static final class Chunk implements Parcelable {
        private final boolean mPurge;
        private final boolean mComplete;
        private final @NonNull Set<RadioManager.ProgramInfo> mModified;
        private final @NonNull Set<UniqueProgramIdentifier> mRemoved;

        public Chunk(boolean purge, boolean complete,
                @Nullable Set<RadioManager.ProgramInfo> modified,
                @Nullable Set<UniqueProgramIdentifier> removed) {
            mPurge = purge;
            mComplete = complete;
            mModified = (modified != null) ? modified : Collections.emptySet();
            mRemoved = (removed != null) ? removed : Collections.emptySet();
        }

        private Chunk(@NonNull Parcel in) {
            mPurge = in.readByte() != 0;
            mComplete = in.readByte() != 0;
            mModified = Utils.createSet(in, RadioManager.ProgramInfo.CREATOR);
            mRemoved = Utils.createSet(in, UniqueProgramIdentifier.CREATOR);
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeByte((byte) (mPurge ? 1 : 0));
            dest.writeByte((byte) (mComplete ? 1 : 0));
            Utils.writeSet(dest, mModified);
            Utils.writeSet(dest, mRemoved);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        public static final @android.annotation.NonNull Parcelable.Creator<Chunk> CREATOR = new Parcelable.Creator<Chunk>() {
            public Chunk createFromParcel(Parcel in) {
                return new Chunk(in);
            }

            public Chunk[] newArray(int size) {
                return new Chunk[size];
            }
        };

        public boolean isPurge() {
            return mPurge;
        }

        public boolean isComplete() {
            return mComplete;
        }

        public @NonNull Set<RadioManager.ProgramInfo> getModified() {
            return mModified;
        }

        public @NonNull Set<UniqueProgramIdentifier> getRemoved() {
            return mRemoved;
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof Chunk)) return false;
            Chunk other = (Chunk) obj;

            if (mPurge != other.mPurge) return false;
            if (mComplete != other.mComplete) return false;
            if (!Objects.equals(mModified, other.mModified)) return false;
            if (!Objects.equals(mRemoved, other.mRemoved)) return false;
            return true;
        }

        @Override
        public String toString() {
            return "Chunk [mPurge=" + mPurge + ", mComplete=" + mComplete
                    + ", mModified=" + mModified + ", mRemoved=" + mRemoved + "]";
        }
    }
}
