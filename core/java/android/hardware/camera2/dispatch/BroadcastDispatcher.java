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
import java.util.Arrays;
import java.util.List;

import static com.android.internal.util.Preconditions.*;

/**
 * Broadcast a single dispatch into multiple other dispatchables.
 *
 * <p>Every time {@link #dispatch} is invoked, all the broadcast targets will
 * see the same dispatch as well. The first target's return value is returned.</p>
 *
 * <p>This enables a single listener to be converted into a multi-listener.</p>
 */
public class BroadcastDispatcher<T> implements Dispatchable<T> {

    private final List<Dispatchable<T>> mDispatchTargets;

    /**
     * Create a broadcast dispatcher from the supplied dispatch targets.
     *
     * @param dispatchTargets one or more targets to dispatch to
     */
    @SafeVarargs
    public BroadcastDispatcher(Dispatchable<T>... dispatchTargets) {
        mDispatchTargets = Arrays.asList(
                checkNotNull(dispatchTargets, "dispatchTargets must not be null"));
    }

    @Override
    public Object dispatch(Method method, Object[] args) throws Throwable {
        Object result = null;
        boolean gotResult = false;

        for (Dispatchable<T> dispatchTarget : mDispatchTargets) {
            Object localResult = dispatchTarget.dispatch(method, args);

            if (!gotResult) {
                gotResult = true;
                result = localResult;
            }
        }

        return result;
    }
}
