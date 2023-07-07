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
package com.android.server;

import com.google.common.annotations.GwtIncompatible;
import com.google.common.base.Optional;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multiset;
import com.google.common.collect.Table;
import com.google.common.truth.BigDecimalSubject;
import com.google.common.truth.BooleanSubject;
import com.google.common.truth.ClassSubject;
import com.google.common.truth.ComparableSubject;
import com.google.common.truth.DoubleSubject;
import com.google.common.truth.Expect;
import com.google.common.truth.FloatSubject;
import com.google.common.truth.GuavaOptionalSubject;
import com.google.common.truth.IntegerSubject;
import com.google.common.truth.IterableSubject;
import com.google.common.truth.LongSubject;
import com.google.common.truth.MapSubject;
import com.google.common.truth.MultimapSubject;
import com.google.common.truth.MultisetSubject;
import com.google.common.truth.ObjectArraySubject;
import com.google.common.truth.PrimitiveBooleanArraySubject;
import com.google.common.truth.PrimitiveByteArraySubject;
import com.google.common.truth.PrimitiveCharArraySubject;
import com.google.common.truth.PrimitiveDoubleArraySubject;
import com.google.common.truth.PrimitiveFloatArraySubject;
import com.google.common.truth.PrimitiveIntArraySubject;
import com.google.common.truth.PrimitiveLongArraySubject;
import com.google.common.truth.PrimitiveShortArraySubject;
import com.google.common.truth.StandardSubjectBuilder;
import com.google.common.truth.StringSubject;
import com.google.common.truth.Subject;
import com.google.common.truth.TableSubject;
import com.google.common.truth.ThrowableSubject;

import org.junit.Rule;

import java.math.BigDecimal;
import java.util.Map;

// NOTE: it could be a more generic AbstractTruthTestCase that provide similar methods
// for assertThat() / assertWithMessage(), but then we'd need to remove all static import imports
// from classes that indirectly extend it.
/**
 * Base class to make it easier to use {@code Truth} {@link Expect} assertions.
 */
public abstract class ExpectableTestCase {

    @Rule
    public final Expect mExpect = Expect.create();

    protected final StandardSubjectBuilder expectWithMessage(String msg) {
        return mExpect.withMessage(msg);
    }

    protected final StandardSubjectBuilder expectWithMessage(String format, Object...args) {
        return mExpect.withMessage(format, args);
    }

    protected final <ComparableT extends Comparable<?>> ComparableSubject<ComparableT> expectThat(
            ComparableT actual) {
        return mExpect.that(actual);
    }

    protected final BigDecimalSubject expectThat(BigDecimal actual) {
        return mExpect.that(actual);
    }

    protected final Subject expectThat(Object actual) {
        return mExpect.that(actual);
    }

    @GwtIncompatible("ClassSubject.java")
    protected final ClassSubject expectThat(Class<?> actual) {
        return mExpect.that(actual);
    }

    protected final ThrowableSubject expectThat(Throwable actual) {
        return mExpect.that(actual);
    }

    protected final LongSubject expectThat(Long actual) {
        return mExpect.that(actual);
    }

    protected final DoubleSubject expectThat(Double actual) {
        return mExpect.that(actual);
    }

    protected final FloatSubject expectThat(Float actual) {
        return mExpect.that(actual);
    }

    protected final IntegerSubject expectThat(Integer actual) {
        return mExpect.that(actual);
    }

    protected final BooleanSubject expectThat(Boolean actual) {
        return mExpect.that(actual);
    }

    protected final StringSubject expectThat(String actual) {
        return mExpect.that(actual);
    }

    protected final IterableSubject expectThat(Iterable<?> actual) {
        return mExpect.that(actual);
    }

    protected final <T> ObjectArraySubject<T> expectThat(T[] actual) {
        return mExpect.that(actual);
    }

    protected final PrimitiveBooleanArraySubject expectThat(boolean[] actual) {
        return mExpect.that(actual);
    }

    protected final PrimitiveShortArraySubject expectThat(short[] actual) {
        return mExpect.that(actual);
    }

    protected final PrimitiveIntArraySubject expectThat(int[] actual) {
        return mExpect.that(actual);
    }

    protected final PrimitiveLongArraySubject expectThat(long[] actual) {
        return mExpect.that(actual);
    }

    protected final PrimitiveCharArraySubject expectThat(char[] actual) {
        return mExpect.that(actual);
    }

    protected final PrimitiveByteArraySubject expectThat(byte[] actual) {
        return mExpect.that(actual);
    }

    protected final PrimitiveFloatArraySubject expectThat(float[] actual) {
        return mExpect.that(actual);
    }

    protected final PrimitiveDoubleArraySubject expectThat(double[] actual) {
        return mExpect.that(actual);
    }

    protected final GuavaOptionalSubject expectThat(Optional<?> actual) {
        return mExpect.that(actual);
    }

    protected final MapSubject expectThat(Map<?, ?> actual) {
        return mExpect.that(actual);
    }

    protected final MultimapSubject expectThat(Multimap<?, ?> actual) {
        return mExpect.that(actual);
    }

    protected final MultisetSubject expectThat(Multiset<?> actual) {
        return mExpect.that(actual);
    }

    protected final TableSubject expectThat(Table<?, ?, ?> actual) {
        return mExpect.that(actual);
    }
}
