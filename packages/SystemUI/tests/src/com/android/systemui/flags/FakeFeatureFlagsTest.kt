/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.flags

import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import java.lang.IllegalStateException
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidTestingRunner::class)
class FakeFeatureFlagsTest : SysuiTestCase() {

    private val unreleasedFlag = UnreleasedFlag(-1000)
    private val releasedFlag = ReleasedFlag(-1001)
    private val stringFlag = StringFlag(-1002)
    private val resourceBooleanFlag = ResourceBooleanFlag(-1003, resourceId = -1)
    private val resourceStringFlag = ResourceStringFlag(-1004, resourceId = -1)
    private val sysPropBooleanFlag = SysPropBooleanFlag(-1005, name = "test")

    /**
     * FakeFeatureFlags does not honor any default values. All flags which are accessed must be
     * specified. If not, an exception is thrown.
     */
    @Test
    fun accessingUnspecifiedFlags_throwsException() {
        val flags: FeatureFlags = FakeFeatureFlags()
        try {
            assertThat(flags.isEnabled(Flags.TEAMFOOD)).isFalse()
            fail("Expected an exception when accessing an unspecified flag.")
        } catch (ex: IllegalStateException) {
            assertThat(ex.message).contains("TEAMFOOD")
        }
        try {
            assertThat(flags.isEnabled(unreleasedFlag)).isFalse()
            fail("Expected an exception when accessing an unspecified flag.")
        } catch (ex: IllegalStateException) {
            assertThat(ex.message).contains("UNKNOWN(id=-1000)")
        }
        try {
            assertThat(flags.isEnabled(releasedFlag)).isFalse()
            fail("Expected an exception when accessing an unspecified flag.")
        } catch (ex: IllegalStateException) {
            assertThat(ex.message).contains("UNKNOWN(id=-1001)")
        }
        try {
            assertThat(flags.isEnabled(resourceBooleanFlag)).isFalse()
            fail("Expected an exception when accessing an unspecified flag.")
        } catch (ex: IllegalStateException) {
            assertThat(ex.message).contains("UNKNOWN(id=-1003)")
        }
        try {
            assertThat(flags.isEnabled(sysPropBooleanFlag)).isFalse()
            fail("Expected an exception when accessing an unspecified flag.")
        } catch (ex: IllegalStateException) {
            assertThat(ex.message).contains("UNKNOWN(id=-1005)")
        }
        try {
            assertThat(flags.getString(stringFlag)).isEmpty()
            fail("Expected an exception when accessing an unspecified flag.")
        } catch (ex: IllegalStateException) {
            assertThat(ex.message).contains("UNKNOWN(id=-1002)")
        }
        try {
            assertThat(flags.getString(resourceStringFlag)).isEmpty()
            fail("Expected an exception when accessing an unspecified flag.")
        } catch (ex: IllegalStateException) {
            assertThat(ex.message).contains("UNKNOWN(id=-1004)")
        }
    }

    @Test
    fun specifiedFlags_returnCorrectValues() {
        val flags = FakeFeatureFlags()
        flags.set(unreleasedFlag, false)
        flags.set(releasedFlag, false)
        flags.set(resourceBooleanFlag, false)
        flags.set(sysPropBooleanFlag, false)
        flags.set(resourceStringFlag, "")

        assertThat(flags.isEnabled(unreleasedFlag)).isFalse()
        assertThat(flags.isEnabled(releasedFlag)).isFalse()
        assertThat(flags.isEnabled(resourceBooleanFlag)).isFalse()
        assertThat(flags.isEnabled(sysPropBooleanFlag)).isFalse()
        assertThat(flags.getString(resourceStringFlag)).isEmpty()

        flags.set(unreleasedFlag, true)
        flags.set(releasedFlag, true)
        flags.set(resourceBooleanFlag, true)
        flags.set(sysPropBooleanFlag, true)
        flags.set(resourceStringFlag, "Android")

        assertThat(flags.isEnabled(unreleasedFlag)).isTrue()
        assertThat(flags.isEnabled(releasedFlag)).isTrue()
        assertThat(flags.isEnabled(resourceBooleanFlag)).isTrue()
        assertThat(flags.isEnabled(sysPropBooleanFlag)).isTrue()
        assertThat(flags.getString(resourceStringFlag)).isEqualTo("Android")
    }

    @Test
    fun listenerForBooleanFlag_calledOnlyWhenFlagChanged() {
        val flags = FakeFeatureFlags()
        val listener = VerifyingListener()
        flags.addListener(unreleasedFlag, listener)

        flags.set(unreleasedFlag, true)
        flags.set(unreleasedFlag, true)
        flags.set(unreleasedFlag, false)
        flags.set(unreleasedFlag, false)

        listener.verifyInOrder(unreleasedFlag.id, unreleasedFlag.id)
    }

    @Test
    fun listenerForStringFlag_calledOnlyWhenFlagChanged() {
        val flags = FakeFeatureFlags()
        val listener = VerifyingListener()
        flags.addListener(stringFlag, listener)

        flags.set(stringFlag, "Test")
        flags.set(stringFlag, "Test")

        listener.verifyInOrder(stringFlag.id)
    }

    @Test
    fun listenerForBooleanFlag_notCalledAfterRemoved() {
        val flags = FakeFeatureFlags()
        val listener = VerifyingListener()
        flags.addListener(unreleasedFlag, listener)
        flags.set(unreleasedFlag, true)
        flags.removeListener(listener)
        flags.set(unreleasedFlag, false)

        listener.verifyInOrder(unreleasedFlag.id)
    }

    @Test
    fun listenerForStringFlag_notCalledAfterRemoved() {
        val flags = FakeFeatureFlags()
        val listener = VerifyingListener()

        flags.addListener(stringFlag, listener)
        flags.set(stringFlag, "Test")
        flags.removeListener(listener)
        flags.set(stringFlag, "Other")

        listener.verifyInOrder(stringFlag.id)
    }

    @Test
    fun listenerForMultipleFlags_calledWhenFlagsChange() {
        val flags = FakeFeatureFlags()
        val listener = VerifyingListener()
        flags.addListener(unreleasedFlag, listener)
        flags.addListener(releasedFlag, listener)

        flags.set(releasedFlag, true)
        flags.set(unreleasedFlag, true)

        listener.verifyInOrder(releasedFlag.id, unreleasedFlag.id)
    }

    @Test
    fun listenerForMultipleFlags_notCalledAfterRemoved() {
        val flags = FakeFeatureFlags()
        val listener = VerifyingListener()

        flags.addListener(unreleasedFlag, listener)
        flags.addListener(releasedFlag, listener)
        flags.set(releasedFlag, true)
        flags.set(unreleasedFlag, true)
        flags.removeListener(listener)
        flags.set(releasedFlag, false)
        flags.set(unreleasedFlag, false)

        listener.verifyInOrder(releasedFlag.id, unreleasedFlag.id)
    }

    @Test
    fun multipleListenersForSingleFlag_allAreCalledWhenChanged() {
        val flags = FakeFeatureFlags()
        val listener1 = VerifyingListener()
        val listener2 = VerifyingListener()
        flags.addListener(releasedFlag, listener1)
        flags.addListener(releasedFlag, listener2)

        flags.set(releasedFlag, true)

        listener1.verifyInOrder(releasedFlag.id)
        listener2.verifyInOrder(releasedFlag.id)
    }

    @Test
    fun multipleListenersForSingleFlag_removedListenerNotCalledAfterRemoval() {
        val flags = FakeFeatureFlags()
        val listener1 = VerifyingListener()
        val listener2 = VerifyingListener()
        flags.addListener(releasedFlag, listener1)
        flags.addListener(releasedFlag, listener2)

        flags.set(releasedFlag, true)
        flags.removeListener(listener2)
        flags.set(releasedFlag, false)

        listener1.verifyInOrder(releasedFlag.id, releasedFlag.id)
        listener2.verifyInOrder(releasedFlag.id)
    }

    class VerifyingListener : FlagListenable.Listener {
        var flagEventIds = mutableListOf<Int>()
        override fun onFlagChanged(event: FlagListenable.FlagEvent) {
            flagEventIds.add(event.flagId)
        }

        fun verifyInOrder(vararg eventIds: Int) {
            assertThat(flagEventIds).containsExactlyElementsIn(eventIds.asList())
        }
    }
}
