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

package android.hardware.camera2.utils;

import android.os.DeadObjectException;
import android.os.RemoteException;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * Translate camera service status_t return values into exceptions.
 *
 * @see android.hardware.camera2.utils.CameraBinderDecorator#newInstance
 * @hide
 */
public class CameraServiceBinderDecorator extends CameraBinderDecorator {

    private static final String TAG = "CameraServiceBinderDecorator";

    static class CameraServiceBinderDecoratorListener
            extends CameraBinderDecorator.CameraBinderDecoratorListener {

        // Pass through remote exceptions, unlike CameraBinderDecorator
        @Override
        public boolean onCatchException(Method m, Object[] args, Throwable t) {

            if (t instanceof DeadObjectException) {
                // Can sometimes happen (camera service died)
                // Pass on silently
            } else if (t instanceof RemoteException) {
                // Some other kind of remote exception - this is not normal, so let's at least
                // note it before moving on
                Log.e(TAG, "Unexpected RemoteException from camera service call.", t);
            }
            // All other exceptions also get sent onward
            return false;
        }

    }

    /**
     * <p>
     * Wraps the type T with a proxy that will check 'status_t' return codes
     * from the native side of the camera service, and throw Java exceptions
     * automatically based on the code.
     * </p>
     *
     * @param obj object that will serve as the target for all method calls
     * @param <T> the type of the element you want to wrap. This must be an interface.
     * @return a proxy that will intercept all invocations to obj
     */
    public static <T> T newInstance(T obj) {
        return Decorator.<T> newInstance(obj, new CameraServiceBinderDecoratorListener());
    }
}
