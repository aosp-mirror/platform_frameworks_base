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

package android.hardware.camera2.legacy;

import android.hardware.camera2.utils.CameraBinderDecorator;
import android.util.AndroidException;

/**
 * Utility class containing exception handling used solely by the compatibility mode shim.
 */
public class LegacyExceptionUtils {
    private static final String TAG = "LegacyExceptionUtils";

    /**
     * Checked exception thrown when a BufferQueue has been abandoned by its consumer.
     */
    public static class BufferQueueAbandonedException extends AndroidException {
        public BufferQueueAbandonedException () {}

        public BufferQueueAbandonedException(String name) {
            super(name);
        }

        public BufferQueueAbandonedException(String name, Throwable cause) {
            super(name, cause);
        }

        public BufferQueueAbandonedException(Exception cause) {
            super(cause);
        }
    }

    /**
     * Throw error codes used by legacy device methods as exceptions.
     *
     * <p>Non-negative return values are passed through, negative return values are thrown as
     * exceptions.</p>
     *
     * @param errorFlag error to throw as an exception.
     * @throws {@link BufferQueueAbandonedException} for {@link CameraBinderDecorator#ENODEV}.
     * @throws {@link UnsupportedOperationException} for an unknown negative error code.
     * @return {@code errorFlag} if the value was non-negative, throws otherwise.
     */
    public static int throwOnError(int errorFlag) throws BufferQueueAbandonedException {
        switch (errorFlag) {
            case CameraBinderDecorator.NO_ERROR: {
                return CameraBinderDecorator.NO_ERROR;
            }
            case CameraBinderDecorator.ENODEV: {
                throw new BufferQueueAbandonedException();
            }
        }

        if (errorFlag < 0) {
            throw new UnsupportedOperationException("Unknown error " + errorFlag);
        }
        return errorFlag;
    }

    private LegacyExceptionUtils() {
        throw new AssertionError();
    }
}
