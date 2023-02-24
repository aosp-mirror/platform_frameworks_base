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

package com.android.systemui.util.mockito

/**
 * Kotlin versions of popular mockito methods that can return null in situations when Kotlin expects
 * a non-null value. Kotlin will throw an IllegalStateException when this takes place ("x must not
 * be null"). To fix this, we can use methods that modify the return type to be nullable. This
 * causes Kotlin to skip the null checks.
 */

import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatcher
import org.mockito.Mockito
import org.mockito.stubbing.OngoingStubbing

/**
 * Returns Mockito.eq() as nullable type to avoid java.lang.IllegalStateException when
 * null is returned.
 *
 * Generic T is nullable because implicitly bounded by Any?.
 */
fun <T> eq(obj: T): T = Mockito.eq<T>(obj)

/**
 * Returns Mockito.any() as nullable type to avoid java.lang.IllegalStateException when
 * null is returned.
 *
 * Generic T is nullable because implicitly bounded by Any?.
 */
fun <T> any(type: Class<T>): T = Mockito.any<T>(type)
inline fun <reified T> any(): T = any(T::class.java)

/**
 * Returns Mockito.argThat() as nullable type to avoid java.lang.IllegalStateException when
 * null is returned.
 *
 * Generic T is nullable because implicitly bounded by Any?.
 */
fun <T> argThat(matcher: ArgumentMatcher<T>): T = Mockito.argThat(matcher)

/**
 * Kotlin type-inferred version of Mockito.nullable()
 */
inline fun <reified T> nullable(): T? = Mockito.nullable(T::class.java)

/**
 * Returns ArgumentCaptor.capture() as nullable type to avoid java.lang.IllegalStateException
 * when null is returned.
 *
 * Generic T is nullable because implicitly bounded by Any?.
 */
fun <T> capture(argumentCaptor: ArgumentCaptor<T>): T = argumentCaptor.capture()

/**
 * Helper function for creating an argumentCaptor in kotlin.
 *
 * Generic T is nullable because implicitly bounded by Any?.
 */
inline fun <reified T : Any> argumentCaptor(): ArgumentCaptor<T> =
        ArgumentCaptor.forClass(T::class.java)

/**
 * Helper function for creating new mocks, without the need to pass in a [Class] instance.
 *
 * Generic T is nullable because implicitly bounded by Any?.
 *
 * @param apply builder function to simplify stub configuration by improving type inference.
 */
inline fun <reified T : Any> mock(apply: T.() -> Unit = {}): T = Mockito.mock(T::class.java)
        .apply(apply)

/**
 * Helper function for stubbing methods without the need to use backticks.
 *
 * @see Mockito.when
 */
fun <T> whenever(methodCall: T): OngoingStubbing<T> = Mockito.`when`(methodCall)

/**
 * A kotlin implemented wrapper of [ArgumentCaptor] which prevents the following exception when
 * kotlin tests are mocking kotlin objects and the methods take non-null parameters:
 *
 *     java.lang.NullPointerException: capture() must not be null
 */
class KotlinArgumentCaptor<T> constructor(clazz: Class<T>) {
    private val wrapped: ArgumentCaptor<T> = ArgumentCaptor.forClass(clazz)
    fun capture(): T = wrapped.capture()
    val value: T
        get() = wrapped.value
    val allValues: List<T>
        get() = wrapped.allValues
}

/**
 * Helper function for creating an argumentCaptor in kotlin.
 *
 * Generic T is nullable because implicitly bounded by Any?.
 */
inline fun <reified T : Any> kotlinArgumentCaptor(): KotlinArgumentCaptor<T> =
        KotlinArgumentCaptor(T::class.java)

/**
 * Helper function for creating and using a single-use ArgumentCaptor in kotlin.
 *
 *    val captor = argumentCaptor<Foo>()
 *    verify(...).someMethod(captor.capture())
 *    val captured = captor.value
 *
 * becomes:
 *
 *    val captured = withArgCaptor<Foo> { verify(...).someMethod(capture()) }
 *
 * NOTE: this uses the KotlinArgumentCaptor to avoid the NullPointerException.
 */
inline fun <reified T : Any> withArgCaptor(block: KotlinArgumentCaptor<T>.() -> Unit): T =
        kotlinArgumentCaptor<T>().apply { block() }.value

/**
 * Variant of [withArgCaptor] for capturing multiple arguments.
 *
 *    val captor = argumentCaptor<Foo>()
 *    verify(...).someMethod(captor.capture())
 *    val captured: List<Foo> = captor.allValues
 *
 * becomes:
 *
 *    val capturedList = captureMany<Foo> { verify(...).someMethod(capture()) }
 */
inline fun <reified T : Any> captureMany(block: KotlinArgumentCaptor<T>.() -> Unit): List<T> =
        kotlinArgumentCaptor<T>().apply{ block() }.allValues
