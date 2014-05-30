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

import android.hardware.camera2.utils.UncheckedThrow;
import android.util.Log;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static com.android.internal.util.Preconditions.*;


public class InvokeDispatcher<T> implements Dispatchable<T> {

    private static final String TAG = "InvocationSink";
    private final T mTarget;

    public InvokeDispatcher(T target) {
        mTarget = checkNotNull(target, "target must not be null");
    }

    @Override
    public Object dispatch(Method method, Object[] args) {
        try {
            return method.invoke(mTarget, args);
        } catch (InvocationTargetException e) {
            Throwable t = e.getTargetException();
            // Potential UB. Hopefully 't' is a runtime exception.
            UncheckedThrow.throwAnyException(t);
        } catch (IllegalAccessException e) {
            // Impossible
            Log.wtf(TAG, "IllegalAccessException while invoking " + method, e);
        } catch (IllegalArgumentException e) {
            // Impossible
            Log.wtf(TAG, "IllegalArgumentException while invoking " + method, e);
        }

        // unreachable
        return null;
    }
}
