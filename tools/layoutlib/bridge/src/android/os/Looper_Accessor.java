/*
 * Copyright (C) 2011 The Android Open Source Project
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
package android.os;

import java.lang.reflect.Field;

/**
 * Class allowing access to package-protected methods/fields.
 */
public class Looper_Accessor {

    public static void cleanupThread() {
        // clean up the looper
        Looper.sThreadLocal.remove();
        try {
            Field sMainLooper = Looper.class.getDeclaredField("sMainLooper");
            sMainLooper.setAccessible(true);
            sMainLooper.set(null, null);
        } catch (SecurityException e) {
            catchReflectionException();
        } catch (IllegalArgumentException e) {
            catchReflectionException();
        } catch (NoSuchFieldException e) {
            catchReflectionException();
        } catch (IllegalAccessException e) {
            catchReflectionException();
        }

    }

    private static void catchReflectionException() {
        assert(false);
    }
}
