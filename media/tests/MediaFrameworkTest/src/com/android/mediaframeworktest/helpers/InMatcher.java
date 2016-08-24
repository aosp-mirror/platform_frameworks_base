/*
 * Copyright 2016 The Android Open Source Project
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
package com.android.mediaframeworktest.helpers;

import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;
import org.hamcrest.Factory;
import org.hamcrest.Matcher;

import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;

/**
 * A {@link Matcher} class for checking if value contained in a {@link Collection} or array.
 */
/**
 * (non-Javadoc)
 * @see android.hardware.camera2.cts.helpers.InMatcher
 */
public class InMatcher<T> extends BaseMatcher<T> {

    protected Collection<T> mValues;

    public InMatcher(Collection<T> values) {
        Preconditions.checkNotNull("values", values);
        mValues = values;
    }

    public InMatcher(T... values) {
        Preconditions.checkNotNull(values);
        mValues = Arrays.asList(values);
    }

    @SuppressWarnings("unchecked")
    @Override
    public boolean matches(Object o) {
        T obj = (T) o;
        for (T elem : mValues) {
            if (Objects.equals(o, elem)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void describeTo(Description description) {
        description.appendText("in(").appendValue(mValues).appendText(")");
    }

    @Factory
    public static <T> Matcher<T> in(T... operand) {
        return new InMatcher<T>(operand);
    }

    @Factory
    public static <T> Matcher<T> in(Collection<T> operand) {
        return new InMatcher<T>(operand);
    }
}
