/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.net;

import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.Range;

import java.util.Collection;
import java.util.Set;

/**
 * An inclusive range of UIDs.
 *
 * @hide
 */
public final class UidRange implements Parcelable {
    public final int start;
    public final int stop;

    public UidRange(int startUid, int stopUid) {
        if (startUid < 0) throw new IllegalArgumentException("Invalid start UID.");
        if (stopUid < 0) throw new IllegalArgumentException("Invalid stop UID.");
        if (startUid > stopUid) throw new IllegalArgumentException("Invalid UID range.");
        start = startUid;
        stop  = stopUid;
    }

    /** Creates a UidRange for the specified user. */
    public static UidRange createForUser(UserHandle user) {
        final UserHandle nextUser = UserHandle.of(user.getIdentifier() + 1);
        final int start = UserHandle.getUid(user, 0 /* appId */);
        final int end = UserHandle.getUid(nextUser, 0) - 1;
        return new UidRange(start, end);
    }

    /** Returns the smallest user Id which is contained in this UidRange */
    public int getStartUser() {
        return UserHandle.getUserHandleForUid(start).getIdentifier();
    }

    /** Returns the largest user Id which is contained in this UidRange */
    public int getEndUser() {
        return UserHandle.getUserHandleForUid(stop).getIdentifier();
    }

    /** Returns whether the UidRange contains the specified UID. */
    public boolean contains(int uid) {
        return start <= uid && uid <= stop;
    }

    /**
     * Returns the count of UIDs in this range.
     */
    public int count() {
        return 1 + stop - start;
    }

    /**
     * @return {@code true} if this range contains every UID contained by the {@code other} range.
     */
    public boolean containsRange(UidRange other) {
        return start <= other.start && other.stop <= stop;
    }

    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + start;
        result = 31 * result + stop;
        return result;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (o instanceof UidRange) {
            UidRange other = (UidRange) o;
            return start == other.start && stop == other.stop;
        }
        return false;
    }

    @Override
    public String toString() {
        return start + "-" + stop;
    }

    // Implement the Parcelable interface
    // TODO: Consider making this class no longer parcelable, since all users are likely in the
    // system server.
    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(start);
        dest.writeInt(stop);
    }

    public static final @android.annotation.NonNull Creator<UidRange> CREATOR =
            new Creator<UidRange>() {
        @Override
        public UidRange createFromParcel(Parcel in) {
            int start = in.readInt();
            int stop = in.readInt();

            return new UidRange(start, stop);
        }
        @Override
        public UidRange[] newArray(int size) {
            return new UidRange[size];
        }
    };

    /**
     * Returns whether any of the UidRange in the collection contains the specified uid
     *
     * @param ranges The collection of UidRange to check
     * @param uid the uid in question
     * @return {@code true} if the uid is contained within the ranges, {@code false} otherwise
     *
     * @see UidRange#contains(int)
     */
    public static boolean containsUid(Collection<UidRange> ranges, int uid) {
        if (ranges == null) return false;
        for (UidRange range : ranges) {
            if (range.contains(uid)) {
                return true;
            }
        }
        return false;
    }

    /**
     *  Convert a set of {@code Range<Integer>} to a set of {@link UidRange}.
     */
    @Nullable
    public static ArraySet<UidRange> fromIntRanges(@Nullable Set<Range<Integer>> ranges) {
        if (null == ranges) return null;

        final ArraySet<UidRange> uids = new ArraySet<>();
        for (Range<Integer> range : ranges) {
            uids.add(new UidRange(range.getLower(), range.getUpper()));
        }
        return uids;
    }

    /**
     *  Convert a set of {@link UidRange} to a set of {@code Range<Integer>}.
     */
    @Nullable
    public static ArraySet<Range<Integer>> toIntRanges(@Nullable Set<UidRange> ranges) {
        if (null == ranges) return null;

        final ArraySet<Range<Integer>> uids = new ArraySet<>();
        for (UidRange range : ranges) {
            uids.add(new Range<Integer>(range.start, range.stop));
        }
        return uids;
    }
}
