
/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.app;

import android.os.LocaleList;

/**
 * Internal interface used to control app-specific locales.
 *
 * <p>Use the {@link android.app.LocaleManager} class rather than going through
 * this Binder interface directly. See {@link android.app.LocaleManager} for
 * more complete documentation.
 *
 * @hide
 */
 interface ILocaleManager {

     /**
      * Sets a specified app’s app-specific UI locales.
      */
     void setApplicationLocales(String packageName, int userId, in LocaleList locales);

     /**
      * Returns the specified app's app-specific locales.
      */
     LocaleList getApplicationLocales(String packageName, int userId);

     /**
       * Returns the current system locales.
       */
     LocaleList getSystemLocales();

 }