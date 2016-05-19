/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.app.test;

import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * Utilities for creating Answers for mock objects
 */
public class MockAnswerUtil {

    /**
     * Answer that calls the method in the Answer called "answer" that matches the type signature of
     * the method being answered. An error will be thrown at runtime if the signature does not match
     * exactly.
     */
    public static class AnswerWithArguments implements Answer<Object> {
        @Override
        public final Object answer(InvocationOnMock invocation) throws Throwable {
            Method method = invocation.getMethod();
            try {
                Method implementation = getClass().getMethod("answer", method.getParameterTypes());
                if (!implementation.getReturnType().equals(method.getReturnType())) {
                    throw new RuntimeException("Found answer method does not have expected return "
                            + "type. Expected: " + method.getReturnType() + ", got "
                            + implementation.getReturnType());
                }
                Object[] args = invocation.getArguments();
                try {
                    return implementation.invoke(this, args);
                } catch (IllegalAccessException e) {
                    throw new RuntimeException("Error invoking answer method", e);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            } catch (NoSuchMethodException e) {
                throw new RuntimeException("Could not find answer method with the expected args "
                        + Arrays.toString(method.getParameterTypes()), e);
            }
        }
    }

}
