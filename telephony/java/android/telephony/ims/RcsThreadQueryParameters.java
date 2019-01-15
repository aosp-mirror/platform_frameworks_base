/*
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

package android.telephony.ims;

import android.annotation.CheckResult;
import android.os.Parcel;
import android.os.Parcelable;

import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * The parameters to pass into {@link RcsMessageStore#getRcsThreads(RcsThreadQueryParameters)} in
 * order to select a subset of {@link RcsThread}s present in the message store.
 * @hide TODO - make the Builder and builder() public. The rest should stay internal only.
 */
public class RcsThreadQueryParameters implements Parcelable {
    private final boolean mIsGroup;
    private final Set<RcsParticipant> mRcsParticipants;
    private final int mLimit;
    private final boolean mIsAscending;

    RcsThreadQueryParameters(boolean isGroup, Set<RcsParticipant> participants, int limit,
            boolean isAscending) {
        mIsGroup = isGroup;
        mRcsParticipants = participants;
        mLimit = limit;
        mIsAscending = isAscending;
    }

    /**
     * Returns a new builder to build a query with.
     * TODO - make public
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * This is used in {@link com.android.internal.telephony.ims.RcsMessageStoreController} to get
     * the list of participants.
     * @hide
     */
    public Set<RcsParticipant> getRcsParticipants() {
        return mRcsParticipants;
    }

    /**
     * This is used in {@link com.android.internal.telephony.ims.RcsMessageStoreController} to get
     * whether group threads should be queried
     * @hide
     */
    public boolean isGroupThread() {
        return mIsGroup;
    }

    /**
     * This is used in {@link com.android.internal.telephony.ims.RcsMessageStoreController} to get
     * the number of tuples the result query should be limited to.
     */
    public int getLimit() {
        return mLimit;
    }

    /**
     * This is used in {@link com.android.internal.telephony.ims.RcsMessageStoreController} to
     * determine the sort order.
     */
    public boolean isAscending() {
        return mIsAscending;
    }

    /**
     * A helper class to build the {@link RcsThreadQueryParameters}.
     */
    public static class Builder {
        private boolean mIsGroupThread;
        private Set<RcsParticipant> mParticipants;
        private int mLimit = 100;
        private boolean mIsAscending;

        /**
         * Package private constructor for {@link RcsThreadQueryParameters.Builder}. To obtain this,
         * {@link RcsThreadQueryParameters#builder()} needs to be called.
         */
        Builder() {
            mParticipants = new HashSet<>();
        }

        /**
         * Limits the query to only return group threads.
         * @param isGroupThread Whether to limit the query result to group threads.
         * @return The same instance of the builder to chain parameters.
         */
        @CheckResult
        public Builder isGroupThread(boolean isGroupThread) {
            mIsGroupThread = isGroupThread;
            return this;
        }

        /**
         * Limits the query to only return threads that contain the given participant.
         * @param participant The participant that must be included in all of the returned threads.
         * @return The same instance of the builder to chain parameters.
         */
        @CheckResult
        public Builder withParticipant(RcsParticipant participant) {
            mParticipants.add(participant);
            return this;
        }

        /**
         * Limits the query to only return threads that contain the given list of participants.
         * @param participants An iterable list of participants that must be included in all of the
         *                     returned threads.
         * @return The same instance of the builder to chain parameters.
         */
        @CheckResult
        public Builder withParticipants(Iterable<RcsParticipant> participants) {
            for (RcsParticipant participant : participants) {
                mParticipants.add(participant);
            }
            return this;
        }

        /**
         * Desired number of threads to be returned from the query. Passing in 0 will return all
         * existing threads at once. The limit defaults to 100.
         * @param limit The number to limit the query result to.
         * @return The same instance of the builder to chain parameters.
         * @throws InvalidParameterException If the given limit is negative.
         */
        @CheckResult
        public Builder limitResultsTo(int limit) throws InvalidParameterException {
            if (limit < 0) {
                throw new InvalidParameterException("The query limit must be non-negative");
            }

            mLimit = limit;
            return this;
        }

        /**
         * Sorts the results returned from the query via thread IDs.
         *
         * TODO - add sorting support for other fields
         *
         * @param isAscending whether to sort in ascending order or not
         * @return The same instance of the builder to chain parameters.
         */
        @CheckResult
        public Builder sort(boolean isAscending) {
            mIsAscending = isAscending;
            return this;
        }

        /**
         * Builds the {@link RcsThreadQueryParameters} to use in
         * {@link RcsMessageStore#getRcsThreads(RcsThreadQueryParameters)}
         *
         * @return An instance of {@link RcsThreadQueryParameters} to use with the thread query.
         */
        public RcsThreadQueryParameters build() {
            return new RcsThreadQueryParameters(
                    mIsGroupThread, mParticipants, mLimit, mIsAscending);
        }
    }

    /**
     * Parcelable boilerplate below.
     */
    protected RcsThreadQueryParameters(Parcel in) {
        mIsGroup = in.readBoolean();

        ArrayList<RcsParticipant> participantArrayList = new ArrayList<>();
        in.readTypedList(participantArrayList, RcsParticipant.CREATOR);
        mRcsParticipants = new HashSet<>(participantArrayList);

        mLimit = in.readInt();
        mIsAscending = in.readBoolean();
    }

    public static final Creator<RcsThreadQueryParameters> CREATOR =
            new Creator<RcsThreadQueryParameters>() {
                @Override
                public RcsThreadQueryParameters createFromParcel(Parcel in) {
                    return new RcsThreadQueryParameters(in);
                }

                @Override
                public RcsThreadQueryParameters[] newArray(int size) {
                    return new RcsThreadQueryParameters[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeBoolean(mIsGroup);
        dest.writeTypedList(new ArrayList<>(mRcsParticipants));
        dest.writeInt(mLimit);
        dest.writeBoolean(mIsAscending);
    }

}
