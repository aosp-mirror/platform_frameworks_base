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

import static com.android.internal.util.Preconditions.*;

/**
 * A dispatcher that replaces one argument with another; replaces any argument at an index
 * with another argument.
 *
 * <p>For example, we can override an {@code void onSomething(int x)} calls to have {@code x} always
 * equal to 1. Or, if using this with a duck typing dispatcher, we could even overwrite {@code x} to
 * be something
 * that's not an {@code int}.</p>
 *
 * @param <T>
 *          source dispatch type, whose methods with {@link #dispatch} will be called
 * @param <TArg>
 *          argument replacement type, args in {@link #dispatch} matching {@code argumentIndex}
 *          will be overriden to objects of this type
 */
public class ArgumentReplacingDispatcher<T, TArg> implements Dispatchable<T> {

    private final Dispatchable<T> mTarget;
    private final int mArgumentIndex;
    private final TArg mReplaceWith;

    /**
     * Create a new argument replacing dispatcher; dispatches are forwarded to {@code target}
     * after the argument is replaced.
     *
     * <p>For example, if a method {@code onAction(T1 a, Integer b, T2 c)} is invoked, and we wanted
     * to replace all occurrences of {@code b} with {@code 0xDEADBEEF}, we would set
     * {@code argumentIndex = 1} and {@code replaceWith = 0xDEADBEEF}.</p>
     *
     * <p>If a method dispatched has less arguments than {@code argumentIndex}, it is
     * passed through with the arguments unchanged.</p>
     *
     * @param target destination dispatch type, methods will be redirected to this dispatcher
     * @param argumentIndex the numeric index of the argument {@code >= 0}
     * @param replaceWith arguments matching {@code argumentIndex} will be replaced with this object
     */
    public ArgumentReplacingDispatcher(Dispatchable<T> target, int argumentIndex,
            TArg replaceWith) {
        mTarget = checkNotNull(target, "target must not be null");
        mArgumentIndex = checkArgumentNonnegative(argumentIndex,
                "argumentIndex must not be negative");
        mReplaceWith = checkNotNull(replaceWith, "replaceWith must not be null");
    }

    @Override
    public Object dispatch(Method method, Object[] args) throws Throwable {

        if (args.length > mArgumentIndex) {
            args = arrayCopy(args); // don't change in-place since it can affect upstream dispatches
            args[mArgumentIndex] = mReplaceWith;
        }

        return mTarget.dispatch(method, args);
    }

    private static Object[] arrayCopy(Object[] array) {
        int length = array.length;
        Object[] newArray = new Object[length];
        for (int i = 0; i < length; ++i) {
            newArray[i] = array[i];
        }
        return newArray;
    }
}
