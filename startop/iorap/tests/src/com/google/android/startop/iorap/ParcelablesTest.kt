/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.google.android.startop.iorap

import android.net.Uri
import android.os.Parcel
import android.os.Parcelable
import androidx.test.filters.SmallTest
import org.junit.Test
import org.junit.runner.RunWith
import com.google.common.truth.Truth.assertThat
import org.junit.runners.Parameterized

/**
 * Basic unit tests to ensure that all of the [Parcelable]s in [com.google.android.startop.iorap]
 * have a valid-conforming interface implementation.
 */
@SmallTest
@RunWith(Parameterized::class)
class ParcelablesTest<T : Parcelable>(private val inputData: InputData<T>) {
    companion object {
        private val initialRequestId = RequestId.nextValueForSequence()!!

        @JvmStatic
        @Parameterized.Parameters
        fun data() = listOf(
                InputData(
                        newActivityInfo(),
                        newActivityInfo(),
                        ActivityInfo("some package", "some other activity")),
                InputData(
                        ActivityHintEvent(ActivityHintEvent.TYPE_COMPLETED, newActivityInfo()),
                        ActivityHintEvent(ActivityHintEvent.TYPE_COMPLETED, newActivityInfo()),
                        ActivityHintEvent(ActivityHintEvent.TYPE_POST_COMPLETED,
                                newActivityInfo())),
                InputData(
                        AppIntentEvent.createDefaultIntentChanged(newActivityInfo(),
                                newActivityInfoOther()),
                        AppIntentEvent.createDefaultIntentChanged(newActivityInfo(),
                                newActivityInfoOther()),
                        AppIntentEvent.createDefaultIntentChanged(newActivityInfoOther(),
                                newActivityInfo())),
                InputData(
                        PackageEvent.createReplaced(newUri(), "some package"),
                        PackageEvent.createReplaced(newUri(), "some package"),
                        PackageEvent.createReplaced(newUri(), "some other package")
                ),
                InputData(initialRequestId, cloneRequestId(initialRequestId),
                        RequestId.nextValueForSequence()),
                InputData(
                        SystemServiceEvent(SystemServiceEvent.TYPE_BOOT_PHASE),
                        SystemServiceEvent(SystemServiceEvent.TYPE_BOOT_PHASE),
                        SystemServiceEvent(SystemServiceEvent.TYPE_START)),
                InputData(
                        SystemServiceUserEvent(SystemServiceUserEvent.TYPE_START_USER, 12345),
                        SystemServiceUserEvent(SystemServiceUserEvent.TYPE_START_USER, 12345),
                        SystemServiceUserEvent(SystemServiceUserEvent.TYPE_CLEANUP_USER, 12345)),
                InputData(
                        TaskResult(TaskResult.STATE_COMPLETED),
                        TaskResult(TaskResult.STATE_COMPLETED),
                        TaskResult(TaskResult.STATE_ONGOING))
        )

        private fun newActivityInfo(): ActivityInfo {
            return ActivityInfo("some package", "some activity")
        }

        private fun newActivityInfoOther(): ActivityInfo {
            return ActivityInfo("some package 2", "some activity 2")
        }

        private fun newUri(): Uri {
            return Uri.parse("https://www.google.com")
        }

        private fun cloneRequestId(requestId: RequestId): RequestId {
            val constructor = requestId::class.java.declaredConstructors[0]
            constructor.isAccessible = true
            return constructor.newInstance(requestId.requestId) as RequestId
        }
    }

    /**
     * Test for [Object.equals] implementation.
     */
    @Test
    fun testEquality() {
        assertThat(inputData.valid).isEqualTo(inputData.valid)
        assertThat(inputData.valid).isEqualTo(inputData.validCopy)
        assertThat(inputData.valid).isNotEqualTo(inputData.validOther)
    }

    /**
     * Test for [Parcelable] implementation.
     */
    @Test
    fun testParcelRoundTrip() {
        // calling writeToParcel and then T::CREATOR.createFromParcel would return the same data.
        val assertParcels = { it: T, data: InputData<T> ->
            val parcel = Parcel.obtain()
            it.writeToParcel(parcel, 0)
            parcel.setDataPosition(0) // future reads will see all previous writes.
            assertThat(it).isEqualTo(data.createFromParcel(parcel))
            parcel.recycle()
        }

        assertParcels(inputData.valid, inputData)
        assertParcels(inputData.validCopy, inputData)
        assertParcels(inputData.validOther, inputData)
    }

    data class InputData<T : Parcelable>(val valid: T, val validCopy: T, val validOther: T) {
        val kls = valid.javaClass
        init {
            assertThat(valid).isNotSameAs(validCopy)
            // Don't use isInstanceOf because of phantom warnings in intellij about Class!
            assertThat(validCopy.javaClass).isEqualTo(valid.javaClass)
            assertThat(validOther.javaClass).isEqualTo(valid.javaClass)
        }

        fun createFromParcel(parcel: Parcel): T {
            val field = kls.getDeclaredField("CREATOR")
            val creator = field.get(null) as Parcelable.Creator<T>

            return creator.createFromParcel(parcel)
        }
    }
}
