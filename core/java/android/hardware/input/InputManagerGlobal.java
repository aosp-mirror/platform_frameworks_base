/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.hardware.input;

import android.content.Context;
import android.os.IBinder;
import android.os.ServiceManager;

/**
 * Manages communication with the input manager service on behalf of
 * an application process.  You're probably looking for {@link InputManager}.
 *
 * @hide
 */
public final class InputManagerGlobal {
    private static final String TAG = "InputManagerGlobal";

    private static InputManagerGlobal sInstance;

    private final IInputManager mIm;

    public InputManagerGlobal(IInputManager im) {
        mIm = im;
    }

    /**
     * Gets an instance of the input manager global singleton.
     *
     * @return The display manager instance, may be null early in system startup
     * before the display manager has been fully initialized.
     */
    public static InputManagerGlobal getInstance() {
        synchronized (InputManagerGlobal.class) {
            if (sInstance == null) {
                IBinder b = ServiceManager.getService(Context.INPUT_SERVICE);
                if (b != null) {
                    sInstance = new InputManagerGlobal(IInputManager.Stub.asInterface(b));
                }
            }
            return sInstance;
        }
    }

    public IInputManager getInputManagerService() {
        return mIm;
    }

    /**
     * Gets an instance of the input manager.
     *
     * @return The input manager instance.
     */
    public static InputManagerGlobal resetInstance(IInputManager inputManagerService) {
        synchronized (InputManager.class) {
            sInstance = new InputManagerGlobal(inputManagerService);
            return sInstance;
        }
    }

    /**
     * Clear the instance of the input manager.
     */
    public static void clearInstance() {
        synchronized (InputManagerGlobal.class) {
            sInstance = null;
        }
    }
}
