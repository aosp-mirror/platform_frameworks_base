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
import com.android.server.pm.test.parsing.parcelling.java.TestSubWithoutCreator
import com.android.server.pm.test.parsing.parcelling.java.TestSuperClass
import kotlin.contracts.ExperimentalContracts

/**
 * Tests the [Parcelable] CREATOR verification by using a mock object with known differences to
 * ensure that the method succeeds/fails.
 */
@ExperimentalContracts
class ParcelableCreatorValidTest :
    ParcelableComponentTest(TestSuperClass::class, TestSubWithoutCreator::class) {

    override val defaultImpl = TestSubWithoutCreator()

    override val creator = object : Parcelable.Creator<Parcelable> {
        override fun createFromParcel(source: Parcel) = TestSubWithoutCreator(source)
        override fun newArray(size: Int) = Array<TestSubWithoutCreator?>(size) { null }
    }

    override val excludedMethods = listOf("writeSubToParcel")

    override val baseParams = listOf(TestSuperClass::getSuperString)

    override fun writeToParcel(parcel: Parcel, value: Parcelable) {
        (value as TestSubWithoutCreator).writeSubToParcel(parcel, 0)
    }
}
