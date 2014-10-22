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

package com.android.framework.multidexlegacytestservices;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Offer an indirection to some Big0xx classes and have their initialization
 * spread along a period of time.
 */
public class ReflectIntermediateClass {

    public static int get(int from, int to, int sleepMillis) throws ClassNotFoundException,
            SecurityException, NoSuchMethodException, IllegalArgumentException,
            IllegalAccessException, InvocationTargetException, InstantiationException {
        int value = 0;
        for (int i = from; i <= to; i++) {
            Class<?> bigClass = Class.forName(
                    "com.android.framework.multidexlegacytestservices.manymethods.Big0" + i);
            Method get = bigClass.getMethod("get" + i);
            value += ((Integer) get.invoke(bigClass.newInstance())).intValue();
            try {
                Thread.sleep(sleepMillis);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        return value;
    }

}
