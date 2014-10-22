/*
 * Copyright (C) 2014 The Android Open Source Project
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
package android.hardware.camera2.dispatch;

import java.lang.reflect.Method;

/**
 * Dynamically dispatch a method and its argument to some object.
 *
 * <p>This can be used to intercept method calls and do work around them, redirect work,
 * or block calls entirely.</p>
 */
public interface Dispatchable<T> {
    /**
     * Dispatch the method and arguments to this object.
     * @param method a method defined in class {@code T}
     * @param args arguments corresponding to said {@code method}
     * @return the object returned when invoking {@code method}
     * @throws Throwable any exception that might have been raised while invoking the method
     */
    public Object dispatch(Method method, Object[] args) throws Throwable;
}
