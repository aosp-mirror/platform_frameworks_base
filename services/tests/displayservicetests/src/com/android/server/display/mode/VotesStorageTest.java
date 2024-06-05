/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.display.mode;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.util.SparseArray;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class VotesStorageTest {
    private static final int DISPLAY_ID = 100;
    private static final int PRIORITY = Vote.PRIORITY_APP_REQUEST_SIZE;
    private static final Vote VOTE = Vote.forDisableRefreshRateSwitching();
    private static final int DISPLAY_ID_OTHER = 101;
    private static final int PRIORITY_OTHER = Vote.PRIORITY_FLICKER_REFRESH_RATE;
    private static final Vote VOTE_OTHER = Vote.forBaseModeRefreshRate(10f);

    @Mock
    public VotesStorage.Listener mVotesListener;

    private VotesStorage mVotesStorage;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mVotesStorage = new VotesStorage(mVotesListener, null);
    }

    @Test
    public void addsVoteToStorage() {
        // WHEN updateVote is called
        mVotesStorage.updateVote(DISPLAY_ID, PRIORITY, VOTE);
        // THEN vote is added to the storage
        SparseArray<Vote> votes = mVotesStorage.getVotes(DISPLAY_ID);
        assertThat(votes.size()).isEqualTo(1);
        assertThat(votes.get(PRIORITY)).isEqualTo(VOTE);
        assertThat(mVotesStorage.getVotes(DISPLAY_ID_OTHER).size()).isEqualTo(0);
    }

    @Test
    public void notifiesVoteListenerIfVoteAdded() {
        // WHEN updateVote is called
        mVotesStorage.updateVote(DISPLAY_ID, PRIORITY, VOTE);
        // THEN listener is notified
        verify(mVotesListener).onChanged();
    }

    /** Verifies that adding the same vote twice results in a single call to onChanged */
    @Test
    public void notifiesVoteListenerCalledOnceIfVoteUpdatedTwice() {
        // WHEN updateVote is called
        mVotesStorage.updateVote(DISPLAY_ID, PRIORITY, VOTE);
        mVotesStorage.updateVote(DISPLAY_ID, PRIORITY, VOTE);
        mVotesStorage.updateVote(DISPLAY_ID, PRIORITY_OTHER, VOTE_OTHER);
        mVotesStorage.updateVote(DISPLAY_ID, PRIORITY_OTHER, VOTE);
        // THEN listener is notified, but only when vote changes.
        verify(mVotesListener, times(3)).onChanged();
    }

    @Test
    public void addsAnotherVoteToStorageWithDifferentPriority() {
        // GIVEN vote storage with one vote
        mVotesStorage.updateVote(DISPLAY_ID, PRIORITY, VOTE);
        // WHEN updateVote is called with other priority
        mVotesStorage.updateVote(DISPLAY_ID, PRIORITY_OTHER, VOTE_OTHER);
        // THEN another vote is added to storage
        SparseArray<Vote> votes = mVotesStorage.getVotes(DISPLAY_ID);
        assertThat(votes.size()).isEqualTo(2);
        assertThat(votes.get(PRIORITY)).isEqualTo(VOTE);
        assertThat(votes.get(PRIORITY_OTHER)).isEqualTo(VOTE_OTHER);
        assertThat(mVotesStorage.getVotes(DISPLAY_ID_OTHER).size()).isEqualTo(0);
    }

    @Test
    public void replacesVoteInStorageForSamePriority() {
        // GIVEN vote storage with one vote
        mVotesStorage.updateVote(DISPLAY_ID, PRIORITY, VOTE);
        // WHEN updateVote is called with same priority
        mVotesStorage.updateVote(DISPLAY_ID, PRIORITY, VOTE_OTHER);
        // THEN vote is replaced by other vote
        SparseArray<Vote> votes = mVotesStorage.getVotes(DISPLAY_ID);
        assertThat(votes.size()).isEqualTo(1);
        assertThat(votes.get(PRIORITY)).isEqualTo(VOTE_OTHER);
        assertThat(mVotesStorage.getVotes(DISPLAY_ID_OTHER).size()).isEqualTo(0);
    }

    @Test
    public void removesVoteInStorageForSamePriority() {
        // GIVEN vote storage with one vote
        mVotesStorage.updateVote(DISPLAY_ID, PRIORITY, VOTE);
        // WHEN update is called with same priority and null vote
        mVotesStorage.updateVote(DISPLAY_ID, PRIORITY, null);
        // THEN vote removed from the storage
        assertThat(mVotesStorage.getVotes(DISPLAY_ID).size()).isEqualTo(0);
        assertThat(mVotesStorage.getVotes(DISPLAY_ID_OTHER).size()).isEqualTo(0);
    }

    @Test
    public void addsGlobalDisplayVoteToStorage() {
        // WHEN updateGlobalVote is called
        mVotesStorage.updateGlobalVote(PRIORITY, VOTE);
        // THEN it is added to the storage for every display
        SparseArray<Vote> votes = mVotesStorage.getVotes(DISPLAY_ID);
        assertThat(votes.size()).isEqualTo(1);
        assertThat(votes.get(PRIORITY)).isEqualTo(VOTE);
        votes = mVotesStorage.getVotes(DISPLAY_ID_OTHER);
        assertThat(votes.size()).isEqualTo(1);
        assertThat(votes.get(PRIORITY)).isEqualTo(VOTE);
    }

    @Test
    public void ignoresVotesWithLowerThanMinPriority() {
        // WHEN updateVote is called with invalid (lower than min) priority
        mVotesStorage.updateVote(DISPLAY_ID, Vote.MIN_PRIORITY - 1, VOTE);
        // THEN vote is not added to the storage AND listener not notified
        assertThat(mVotesStorage.getVotes(DISPLAY_ID).size()).isEqualTo(0);
        verify(mVotesListener, never()).onChanged();
    }

    @Test
    public void ignoresVotesWithGreaterThanMaxPriority() {
        // WHEN updateVote is called with invalid (greater than max) priority
        mVotesStorage.updateVote(DISPLAY_ID, Vote.MAX_PRIORITY + 1, VOTE);
        // THEN vote is not added to the storage AND listener not notified
        assertThat(mVotesStorage.getVotes(DISPLAY_ID).size()).isEqualTo(0);
        verify(mVotesListener, never()).onChanged();
    }


    @Test
    public void removesAllVotesForPriority() {
        // GIVEN vote storage with votes
        mVotesStorage.updateVote(DISPLAY_ID, PRIORITY, VOTE);
        mVotesStorage.updateVote(DISPLAY_ID, PRIORITY_OTHER, VOTE_OTHER);
        mVotesStorage.updateVote(DISPLAY_ID_OTHER, PRIORITY, VOTE);
        mVotesStorage.updateVote(DISPLAY_ID_OTHER, PRIORITY_OTHER, VOTE_OTHER);
        // WHEN removeAllVotesForPriority is called
        mVotesStorage.removeAllVotesForPriority(PRIORITY);
        // THEN votes with priority are removed from the storage
        SparseArray<Vote> votes = mVotesStorage.getVotes(DISPLAY_ID);
        assertThat(votes.size()).isEqualTo(1);
        assertThat(votes.get(PRIORITY)).isNull();
        votes = mVotesStorage.getVotes(DISPLAY_ID_OTHER);
        assertThat(votes.size()).isEqualTo(1);
        assertThat(votes.get(PRIORITY)).isNull();
    }

    @Test
    public void removesAllVotesForPriority_notifiesListenerOnce() {
        // GIVEN vote storage with votes
        mVotesStorage.updateVote(DISPLAY_ID, PRIORITY, VOTE);
        mVotesStorage.updateVote(DISPLAY_ID, PRIORITY_OTHER, VOTE_OTHER);
        mVotesStorage.updateVote(DISPLAY_ID_OTHER, PRIORITY, VOTE);
        mVotesStorage.updateVote(DISPLAY_ID_OTHER, PRIORITY_OTHER, VOTE_OTHER);
        clearInvocations(mVotesListener);
        // WHEN removeAllVotesForPriority is called
        mVotesStorage.removeAllVotesForPriority(PRIORITY);
        // THEN listener notified once
        verify(mVotesListener).onChanged();
    }

    @Test
    public void removesAllVotesForPriority_noChangesIfNothingRemoved() {
        // GIVEN vote storage with votes
        mVotesStorage.updateVote(DISPLAY_ID, PRIORITY, VOTE);
        clearInvocations(mVotesListener);
        // WHEN removeAllVotesForPriority is called for missing priority
        mVotesStorage.removeAllVotesForPriority(PRIORITY_OTHER);
        // THEN no changes to votes storage
        SparseArray<Vote> votes = mVotesStorage.getVotes(DISPLAY_ID);
        assertThat(votes.size()).isEqualTo(1);
        assertThat(votes.get(PRIORITY)).isEqualTo(VOTE);
        verify(mVotesListener, never()).onChanged();
    }
}
