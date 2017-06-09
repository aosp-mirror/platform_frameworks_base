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

import static com.android.internal.util.Preconditions.checkNotNull;

import android.hardware.camera2.utils.UncheckedThrow;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Invoke a method on a dispatchable by its name (without knowing the {@code Method} ahead of time).
 *
 * @param <T> destination dispatch type, methods will be looked up in the class of {@code T}
 */
public class MethodNameInvoker<T> {

    private final Dispatchable<T> mTarget;
    private final Class<T> mTargetClass;
    private final Method[] mTargetClassMethods;
    private final ConcurrentHashMap<String, Method> mMethods =
            new ConcurrentHashMap<>();

    /**
     * Create a new method name invoker.
     *
     * @param target destination dispatch type, invokes will be redirected to this dispatcher
     * @param targetClass destination dispatch class, the invoked methods will be from this class
     */
    public MethodNameInvoker(Dispatchable<T> target, Class<T> targetClass) {
        mTargetClass = targetClass;
        mTargetClassMethods = targetClass.getMethods();
        mTarget = target;
    }

    /**
     * Invoke a method by its name.
     *
     * <p>If more than one method exists in {@code targetClass}, the first method with the right
     * number of arguments will be used, and later calls will all use that method.</p>
     *
     * @param methodName
     *          The name of the method, which will be matched 1:1 to the destination method
     * @param params
     *          Variadic parameter list.
     * @return
     *          The same kind of value that would normally be returned by calling {@code methodName}
     *          statically.
     *
     * @throws IllegalArgumentException if {@code methodName} does not exist on the target class
     * @throws Throwable will rethrow anything that the target method would normally throw
     */
    @SuppressWarnings("unchecked")
    public <K> K invoke(String methodName, Object... params) {
        checkNotNull(methodName, "methodName must not be null");

        Method targetMethod = mMethods.get(methodName);
        if (targetMethod == null) {
            for (Method method : mTargetClassMethods) {
                // TODO future: match types of params if possible
                if (method.getName().equals(methodName) &&
                        (params.length == method.getParameterTypes().length) ) {
                    targetMethod = method;
                    mMethods.put(methodName, targetMethod);
                    break;
                }
            }

            if (targetMethod == null) {
                throw new IllegalArgumentException(
                        "Method " + methodName + " does not exist on class " + mTargetClass);
            }
        }

        try {
            return (K) mTarget.dispatch(targetMethod, params);
        } catch (Throwable e) {
            UncheckedThrow.throwAnyException(e);
            // unreachable
            return null;
        }
    }
}
