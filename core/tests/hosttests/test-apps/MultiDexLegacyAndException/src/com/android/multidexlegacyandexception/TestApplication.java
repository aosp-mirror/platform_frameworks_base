/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.android.multidexlegacyandexception;

import android.support.multidex.MultiDexApplication;

public class TestApplication extends MultiDexApplication {

    private static void canThrow1(boolean condition) throws ExceptionInMainDex {
        if (condition) {
            throw new ExceptionInMainDex();
        }
    }


    public static int get(boolean condition) {
        try {
            canThrow1(condition);
            return 1;
        } catch (ExceptionInMainDex e) {
            return 10;
        } catch (OutOfMemoryError e) {
            return 12;
        } catch (CaughtOnlyException e) {
            return 17;
        } catch (SuperExceptionInMainDex e) {
            return 27;
      }
    }

}
