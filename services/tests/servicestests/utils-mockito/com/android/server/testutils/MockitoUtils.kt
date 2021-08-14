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

package com.android.server.testutils

import org.mockito.Answers
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.mockito.stubbing.Stubber

object MockitoUtils {
    val ANSWER_THROWS = Answer<Any?> {
        when (val name = it.method.name) {
            "toString" -> return@Answer try {
                Answers.CALLS_REAL_METHODS.answer(it)
            } catch (e: Exception) {
                "failure calling toString"
            }
            else -> {
                val arguments = it.arguments
                        ?.takeUnless { it.isEmpty() }
                        ?.mapIndexed { index, arg -> arg.attemptToString(index) }
                        ?.joinToString { it.attemptToString(null) }
                        ?.let { "with $it" }
                        .orEmpty()

                throw UnsupportedOperationException("${it.mock::class.java.simpleName}#$name " +
                        "$arguments should not be called")
            }
        }
    }

    // Sometimes mocks won't have a toString method, so try-catch and return some default
    private fun Any?.attemptToString(id: Any? = null): String {
        return try {
            toString()
        } catch (e: Exception) {
            if (id == null) {
                e.message ?: "ERROR"
            } else {
                "$id ${e.message}"
            }
        }
    }
}

inline fun <reified T> mock(block: T.() -> Unit = {}) = Mockito.mock(T::class.java).apply(block)

fun <T> spy(value: T, block: T.() -> Unit = {}) = Mockito.spy(value).apply(block)

fun <Type> Stubber.whenever(mock: Type) = this.`when`(mock)
fun <Type : Any?> whenever(mock: Type) = Mockito.`when`(mock)

@Suppress("UNCHECKED_CAST")
fun <Type : Any?> whenever(mock: Type, block: InvocationOnMock.() -> Any?) =
        Mockito.`when`(mock).thenAnswer { block(it) }

fun whenever(mock: Unit) = Mockito.`when`(mock).thenAnswer { }

inline fun <reified T> spyThrowOnUnmocked(value: T?, block: T.() -> Unit = {}): T {
    val swappingAnswer = object : Answer<Any?> {
        var delegate: Answer<*> = Answers.RETURNS_DEFAULTS

        override fun answer(invocation: InvocationOnMock?): Any? {
            return delegate.answer(invocation)
        }
    }

    return Mockito.mock(T::class.java, Mockito.withSettings().spiedInstance(value)
            .defaultAnswer(swappingAnswer)).apply(block)
            .also {
                // To allow when() usage inside block, only swap to throwing afterwards
                swappingAnswer.delegate = MockitoUtils.ANSWER_THROWS
            }
}

inline fun <reified T> mockThrowOnUnmocked(block: T.() -> Unit = {}) =
        spyThrowOnUnmocked<T>(null, block)

inline fun <reified T : Any> nullable() = ArgumentMatchers.nullable(T::class.java)

/**
 * Returns Mockito.any() as nullable type to avoid java.lang.IllegalStateException when
 * null is returned.
 *
 * Generic T is nullable because implicitly bounded by Any?.
 */
fun <T> any(type: Class<T>): T = Mockito.any<T>(type)

/**
 * Wrapper around [Mockito.any] for generic types.
 */
inline fun <reified T> any() = any(T::class.java)

/**
 * Returns Mockito.eq() as nullable type to avoid java.lang.IllegalStateException when
 * null is returned.
 *
 * Generic T is nullable because implicitly bounded by Any?.
 */
fun <T> eq(obj: T): T = Mockito.eq<T>(obj)
