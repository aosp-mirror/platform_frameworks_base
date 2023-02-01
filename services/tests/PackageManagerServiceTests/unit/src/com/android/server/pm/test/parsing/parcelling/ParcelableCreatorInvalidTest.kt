/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.pm.test.parsing.parcelling

import android.os.Parcel
import android.os.Parcelable
import com.android.server.pm.test.parsing.parcelling.java.TestSubWithCreator
import com.android.server.pm.test.parsing.parcelling.java.TestSuperClass
import org.junit.Test
import kotlin.contracts.ExperimentalContracts

/**
 * Verifies the failing side of [ParcelableCreatorValidTest]. The sole difference is the addition
 * of [TestSubWithCreator.CREATOR].
 */
@ExperimentalContracts
class ParcelableCreatorInvalidTest :
    ParcelableComponentTest(TestSuperClass::class, TestSubWithCreator::class) {

    override val defaultImpl = TestSubWithCreator()

    override val creator = object : Parcelable.Creator<Parcelable> {
        override fun createFromParcel(source: Parcel) = TestSubWithCreator(source)
        override fun newArray(size: Int) = Array<TestSubWithCreator?>(size) { null }
    }

    override val excludedMethods = listOf("writeSubToParcel")

    override val baseParams = listOf(TestSuperClass::getSuperString)

    override fun writeToParcel(parcel: Parcel, value: Parcelable) {
        (value as TestSubWithCreator).writeSubToParcel(parcel, 0)
    }

    @Test
    override fun parcellingSize() {
        super.parcellingSize()
        if (expect.hasFailures()) {
            // This is a hack to ignore an expected failure result. Doing it this way, rather than
            // adding a switch in the test itself, prevents it from accidentally passing through a
            // programming error.
            ignoreableExpect.ignore()
        }
    }
}
