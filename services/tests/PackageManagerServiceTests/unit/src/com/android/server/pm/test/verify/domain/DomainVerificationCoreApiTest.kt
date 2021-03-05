/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.pm.test.verify.domain

import android.content.pm.verify.domain.DomainSet
import android.content.pm.verify.domain.DomainVerificationInfo
import android.content.pm.verify.domain.DomainVerificationRequest
import android.content.pm.verify.domain.DomainVerificationUserState
import android.os.Parcel
import android.os.Parcelable
import android.os.UserHandle
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.util.UUID

@RunWith(Parameterized::class)
class DomainVerificationCoreApiTest {

    companion object {
        private val IS_EQUAL_TO: (value: Any, other: Any) -> Unit = { value, other ->
            assertThat(value).isEqualTo(other)
        }
        private val IS_MAP_EQUAL_TO: (value: Map<*, *>, other: Map<*, *>) -> Unit = { value,
                                                                                      other ->
            assertThat(value).containsExactlyEntriesIn(other)
        }

        private val massiveSet by lazy {
            val fragmentOf21 = ".com.example.test.app"
            val list = mutableListOf("prefix$fragmentOf21")
            var totalSize = 0
            // Slightly overshoot a size of 1MB
            while (totalSize < (1024 * 512)) {
                val nextValue = "${list.last()}$fragmentOf21"
                totalSize += nextValue.length
                list += nextValue
            }
            list.toSet()
        }

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun parameters() = arrayOf(
            Parameter(
                testName = "DomainVerificationRequest",
                initial = { DomainVerificationRequest(massiveSet) },
                unparcel = { DomainVerificationRequest.CREATOR.createFromParcel(it) },
                assertion = { first, second ->
                    assertAll<DomainVerificationRequest, Set<String>>(first, second,
                        { it.packageNames }, { it.component1() }) { value, other ->
                        assertThat(value).containsExactlyElementsIn(other)
                    }
                }
            ),
            Parameter(
                testName = "DomainVerificationInfo",
                initial = {
                    DomainVerificationInfo(
                        UUID.fromString("703f6d34-6241-4cfd-8176-2e1d23355811"),
                        "com.test.pkg",
                        massiveSet.withIndex().associate { it.value to it.index }
                    )
                },
                unparcel = { DomainVerificationInfo.CREATOR.createFromParcel(it) },
                assertion = { first, second ->
                    assertAll<DomainVerificationInfo, UUID>(first, second,
                        { it.identifier }, { it.component1() }, IS_EQUAL_TO
                    )
                    assertAll<DomainVerificationInfo, String>(first, second,
                        { it.packageName }, { it.component2() }, IS_EQUAL_TO
                    )
                    assertAll<DomainVerificationInfo, Map<String, Int?>>(first, second,
                        { it.hostToStateMap }, { it.component3() }, IS_MAP_EQUAL_TO
                    )
                }
            ),
            Parameter(
                testName = "DomainVerificationUserState",
                initial = {
                    DomainVerificationUserState(
                        UUID.fromString("703f6d34-6241-4cfd-8176-2e1d23355811"),
                        "com.test.pkg",
                        UserHandle.of(10),
                        true,
                        massiveSet.withIndex()
                            .associate { it.value to (it.index % 3) }
                    )
                },
                unparcel = { DomainVerificationUserState.CREATOR.createFromParcel(it) },
                assertion = { first, second ->
                    assertAll<DomainVerificationUserState, UUID>(first, second,
                        { it.identifier }, { it.component1() }, IS_EQUAL_TO
                    )
                    assertAll<DomainVerificationUserState, String>(first, second,
                        { it.packageName }, { it.component2() }, IS_EQUAL_TO
                    )
                    assertAll<DomainVerificationUserState, UserHandle>(first, second,
                        { it.user }, { it.component3() }, IS_EQUAL_TO
                    )
                    assertAll<DomainVerificationUserState, Boolean>(
                        first, second, { it.isLinkHandlingAllowed },
                        { it.component4() }, IS_EQUAL_TO
                    )
                    assertAll<DomainVerificationUserState, Map<String, Int>>(
                        first, second, { it.hostToStateMap },
                        { it.component5() }, IS_MAP_EQUAL_TO
                    )
                }
            ),
            Parameter(
                testName = "DomainSet",
                initial = { DomainSet(massiveSet) },
                unparcel = { DomainSet.CREATOR.createFromParcel(it) },
                assertion = { first, second ->
                    assertAll<DomainSet, Set<String>>(
                        first, second,
                        { it.domains }, assertion = IS_EQUAL_TO
                    )
                }
            )
        )

        class Parameter<T : Parcelable>(
            val testName: String,
            val initial: () -> T,
            val unparcel: (Parcel) -> T,
            private val assertion: (first: T, second: T) -> Unit
        ) {
            @Suppress("UNCHECKED_CAST")
            fun assert(first: Any, second: Any) = assertion(first as T, second as T)

            override fun toString() = testName
        }

        private fun <T> assertAll(vararg values: T, block: (value: T, other: T) -> Unit) {
            values.indices.drop(1).forEach {
                block(values[0], values[it])
            }
        }

        private fun <T, V : Any> assertAll(
            first: T,
            second: T,
            fieldValue: (T) -> V,
            componentValue: ((T) -> V)? = null,
            assertion: (value: V, other: V) -> Unit
        ) {
            val values = mutableListOf<Any>(fieldValue(first), fieldValue(second))
                .apply {
                    componentValue?.let {
                        add(it(first))
                        add(it(second))
                    }
                }
                .toTypedArray()
            values.indices.drop(1).forEach {
                @Suppress("UNCHECKED_CAST")
                assertion(values[0] as V, values[it] as V)
            }
        }
    }

    @Parameterized.Parameter(0)
    lateinit var parameter: Parameter<*>

    @Test
    fun parcel() {
        val parcel = Parcel.obtain()
        val initial = parameter.initial()
        initial.writeToParcel(parcel, 0)
        parcel.setDataPosition(0)

        val newInitial = parameter.initial()
        val unparceled = parameter.unparcel(parcel)
        parameter.assert(newInitial, unparceled)

        assertAll(initial, newInitial, unparceled) { value: Any, other: Any ->
            assertThat(value).isEqualTo(other)
        }
    }
}
