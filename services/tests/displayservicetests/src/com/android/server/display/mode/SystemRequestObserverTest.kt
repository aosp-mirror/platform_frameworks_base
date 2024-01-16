/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.display.mode

import android.os.IBinder
import android.os.RemoteException
import androidx.test.filters.SmallTest
import com.google.common.truth.Truth.assertThat
import com.google.testing.junit.testparameterinjector.TestParameterInjector
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.junit.MockitoJUnit
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

private const val DISPLAY_ID = 1
private const val DISPLAY_ID_OTHER = 2

@SmallTest
@RunWith(TestParameterInjector::class)
class SystemRequestObserverTest {


    @get:Rule
    val mockitoRule = MockitoJUnit.rule()

    private val mockToken = mock<IBinder>()
    private val mockOtherToken = mock<IBinder>()

    private val storage = VotesStorage({}, null)

    @Test
    fun `requestDisplayModes adds vote to storage`() {
        val systemRequestObserver = SystemRequestObserver(storage)
        val requestedModes = intArrayOf(1, 2, 3)

        systemRequestObserver.requestDisplayModes(mockToken, DISPLAY_ID, requestedModes)

        val votes = storage.getVotes(DISPLAY_ID)
        assertThat(votes.size()).isEqualTo(1)
        val vote = votes.get(Vote.PRIORITY_SYSTEM_REQUESTED_MODES)
        assertThat(vote).isInstanceOf(SupportedModesVote::class.java)
        val supportedModesVote = vote as SupportedModesVote
        assertThat(supportedModesVote.mModeIds.size).isEqualTo(requestedModes.size)
        for (mode in requestedModes) {
            assertThat(supportedModesVote.mModeIds).contains(mode)
        }
    }

    @Test
    fun `requestDisplayModes overrides votes in storage`() {
        val systemRequestObserver = SystemRequestObserver(storage)

        systemRequestObserver.requestDisplayModes(mockToken, DISPLAY_ID, intArrayOf(1, 2, 3))

        val overrideModes = intArrayOf(10, 20, 30)
        systemRequestObserver.requestDisplayModes(mockToken, DISPLAY_ID, overrideModes)

        val votes = storage.getVotes(DISPLAY_ID)
        assertThat(votes.size()).isEqualTo(1)
        val vote = votes.get(Vote.PRIORITY_SYSTEM_REQUESTED_MODES)
        assertThat(vote).isInstanceOf(SupportedModesVote::class.java)
        val supportedModesVote = vote as SupportedModesVote
        assertThat(supportedModesVote.mModeIds.size).isEqualTo(overrideModes.size)
        for (mode in overrideModes) {
            assertThat(supportedModesVote.mModeIds).contains(mode)
        }
    }

    @Test
    fun `requestDisplayModes removes vote to storage`() {
        val systemRequestObserver = SystemRequestObserver(storage)
        val requestedModes = intArrayOf(1, 2, 3)

        systemRequestObserver.requestDisplayModes(mockToken, DISPLAY_ID, requestedModes)
        systemRequestObserver.requestDisplayModes(mockToken, DISPLAY_ID, null)

        val votes = storage.getVotes(DISPLAY_ID)
        assertThat(votes.size()).isEqualTo(0)
    }

    @Test
    fun `requestDisplayModes calls linkToDeath to token`() {
        val systemRequestObserver = SystemRequestObserver(storage)
        val requestedModes = intArrayOf(1, 2, 3)

        systemRequestObserver.requestDisplayModes(mockToken, DISPLAY_ID, requestedModes)

        verify(mockToken).linkToDeath(any(), eq(0))
    }

    @Test
    fun `does not add votes to storage if binder died when requestDisplayModes called`() {
        val systemRequestObserver = SystemRequestObserver(storage)
        val requestedModes = intArrayOf(1, 2, 3)

        doThrow(RemoteException()).whenever(mockOtherToken).linkToDeath(any(), eq(0))
        systemRequestObserver.requestDisplayModes(mockOtherToken, DISPLAY_ID, requestedModes)

        val votes = storage.getVotes(DISPLAY_ID)
        assertThat(votes.size()).isEqualTo(0)
    }

    @Test
    fun `removes all votes from storage when binder dies`() {
        val systemRequestObserver = SystemRequestObserver(storage)
        val requestedModes = intArrayOf(1, 2, 3)

        systemRequestObserver.requestDisplayModes(mockToken, DISPLAY_ID, requestedModes)
        val deathRecipientCaptor = argumentCaptor<IBinder.DeathRecipient>()
        verify(mockToken).linkToDeath(deathRecipientCaptor.capture(), eq(0))

        deathRecipientCaptor.lastValue.binderDied(mockToken)

        val votes = storage.getVotes(DISPLAY_ID)
        assertThat(votes.size()).isEqualTo(0)
    }

    @Test
    fun `calls unlinkToDeath on token when no votes remaining`() {
        val systemRequestObserver = SystemRequestObserver(storage)
        val requestedModes = intArrayOf(1, 2, 3)

        systemRequestObserver.requestDisplayModes(mockToken, DISPLAY_ID, requestedModes)
        systemRequestObserver.requestDisplayModes(mockToken, DISPLAY_ID, null)

        verify(mockToken).unlinkToDeath(any(), eq(0))
    }

    @Test
    fun `does not call unlinkToDeath on token when votes for other display in storage`() {
        val systemRequestObserver = SystemRequestObserver(storage)
        val requestedModes = intArrayOf(1, 2, 3)

        systemRequestObserver.requestDisplayModes(mockToken, DISPLAY_ID, requestedModes)
        systemRequestObserver.requestDisplayModes(mockToken, DISPLAY_ID_OTHER, requestedModes)
        systemRequestObserver.requestDisplayModes(mockToken, DISPLAY_ID, null)

        verify(mockToken, never()).unlinkToDeath(any(), eq(0))
    }

    @Test
    fun `requestDisplayModes subset modes from different tokens`() {
        val systemRequestObserver = SystemRequestObserver(storage)
        val requestedModes = intArrayOf(1, 2, 3)
        systemRequestObserver.requestDisplayModes(mockToken, DISPLAY_ID, requestedModes)

        val requestedOtherModes = intArrayOf(2, 3, 4)
        systemRequestObserver.requestDisplayModes(mockOtherToken, DISPLAY_ID, requestedOtherModes)

        verify(mockToken).linkToDeath(any(), eq(0))
        verify(mockOtherToken).linkToDeath(any(), eq(0))
        verify(mockToken, never()).unlinkToDeath(any(), eq(0))
        verify(mockOtherToken, never()).unlinkToDeath(any(), eq(0))

        val expectedModes = intArrayOf(2, 3)
        val votes = storage.getVotes(DISPLAY_ID)
        assertThat(votes.size()).isEqualTo(1)
        val vote = votes.get(Vote.PRIORITY_SYSTEM_REQUESTED_MODES)
        assertThat(vote).isInstanceOf(SupportedModesVote::class.java)
        val supportedModesVote = vote as SupportedModesVote
        assertThat(supportedModesVote.mModeIds.size).isEqualTo(expectedModes.size)
        for (mode in expectedModes) {
            assertThat(supportedModesVote.mModeIds).contains(mode)
        }
    }

    @Test
    fun `recalculates vote if one binder dies`() {
        val systemRequestObserver = SystemRequestObserver(storage)
        val requestedModes = intArrayOf(1, 2, 3)
        systemRequestObserver.requestDisplayModes(mockToken, DISPLAY_ID, requestedModes)

        val requestedOtherModes = intArrayOf(2, 3, 4)
        systemRequestObserver.requestDisplayModes(mockOtherToken, DISPLAY_ID, requestedOtherModes)

        val deathRecipientCaptor = argumentCaptor<IBinder.DeathRecipient>()
        verify(mockOtherToken).linkToDeath(deathRecipientCaptor.capture(), eq(0))
        deathRecipientCaptor.lastValue.binderDied(mockOtherToken)

        val votes = storage.getVotes(DISPLAY_ID)
        assertThat(votes.size()).isEqualTo(1)
        val vote = votes.get(Vote.PRIORITY_SYSTEM_REQUESTED_MODES)
        assertThat(vote).isInstanceOf(SupportedModesVote::class.java)
        val supportedModesVote = vote as SupportedModesVote
        assertThat(supportedModesVote.mModeIds.size).isEqualTo(requestedModes.size)
        for (mode in requestedModes) {
            assertThat(supportedModesVote.mModeIds).contains(mode)
        }
    }
}