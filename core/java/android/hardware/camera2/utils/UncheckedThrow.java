/*
 * Copyright (C) 2013 The Android Open Source Project
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

/**
 * @hide
 */
public class UncheckedThrow {

    /**
     * Throw any kind of exception without needing it to be checked
     * @param e any instance of a Exception
     */
    public static void throwAnyException(Exception e) {
        /**
         *  Abuse type erasure by making the compiler think we are throwing RuntimeException,
         *  which is unchecked, but then inserting any exception in there.
         */
        UncheckedThrow.<RuntimeException>throwAnyImpl(e);
    }

    /**
     * Throw any kind of throwable without needing it to be checked
     * @param e any instance of a Throwable
     */
    public static void throwAnyException(Throwable e) {
        /**
         *  Abuse type erasure by making the compiler think we are throwing RuntimeException,
         *  which is unchecked, but then inserting any exception in there.
         */
        UncheckedThrow.<RuntimeException>throwAnyImpl(e);
    }

    @SuppressWarnings("unchecked")
    private static<T extends Throwable> void throwAnyImpl(Throwable e) throws T {
        throw (T) e;
    }
}
