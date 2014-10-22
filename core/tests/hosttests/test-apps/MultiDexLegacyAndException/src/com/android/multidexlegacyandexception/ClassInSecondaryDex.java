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

package com.android.multidexlegacyandexception;

public class ClassInSecondaryDex {
    private boolean condition;

    public ClassInSecondaryDex(boolean condition) {
        this.condition = condition;
    }

    public void canThrow1() throws ExceptionInMainDex, ExceptionInMainDex2,
            ExceptionInSecondaryDexWithSuperInMain {
        if (condition) {
            throw new ExceptionInMainDex();
        }
    }

    public void canThrow2() throws ExceptionInSecondaryDex, ExceptionInSecondaryDex2,
            ExceptionInSecondaryDexWithSuperInMain {
        if (condition) {
            throw new ExceptionInSecondaryDex();
        }
    }

    public static void canThrowAll(Throwable toThrow) throws Throwable {
        if (toThrow != null) {
            throw toThrow;
        }
    }

    public int get1() {
        try {
            canThrow1();
            canThrow2();
            return 1;
        } catch (ExceptionInMainDex e) {
            return 10;
        } catch (ExceptionInSecondaryDex e) {
            return 11;
        } catch (OutOfMemoryError e) {
            return 12;
        } catch (CaughtOnlyException e) {
            return 17;
        } catch (SuperExceptionInSecondaryDex|SuperExceptionInMainDex e) {
            return 23;
       }
    }

    public int get2() {
        try {
            canThrow2();
            canThrow1();
            return 1;
        } catch (ExceptionInMainDex e) {
            return 10;
        } catch (ExceptionInSecondaryDex e) {
            return 11;
        } catch (OutOfMemoryError e) {
            return 12;
        } catch (CaughtOnlyException e) {
            return 17;
        } catch (SuperExceptionInSecondaryDex e) {
            return 23;
        } catch (SuperExceptionInMainDex e) {
            return 27;
       }
    }

}
