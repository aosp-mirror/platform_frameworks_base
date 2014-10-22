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
 * Do nothing when dispatching; follows the null object pattern.
 */
public class NullDispatcher<T> implements Dispatchable<T> {
    /**
     * Create a dispatcher that does nothing when dispatched to.
     */
    public NullDispatcher() {
    }

    /**
     * Do nothing; all parameters are ignored.
     */
    @Override
    public Object dispatch(Method method, Object[] args) {
        return null;
    }
}
