/**
 * Copyright (C) 2024 The Android Open Source Project
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

import android.content.AttributionSource;

/**
 * Internal interface used to control app-specific gender.
 *
 * <p>Use the {@link android.app.GrammarInflectionManager} class rather than going through
 * this Binder interface directly. See {@link android.app.GrammarInflectionManager} for
 * more complete documentation.
 *
 * @hide
 */
 interface IGrammaticalInflectionManager {

     /**
      * Sets a specified app’s app-specific grammatical gender.
      */
     void setRequestedApplicationGrammaticalGender(String appPackageName, int userId, int gender);

     /**
      * Sets the grammatical gender to system.
      */
     void setSystemWideGrammaticalGender(int gender, int userId);

     /**
      * Gets the grammatical gender from system.
      */
     int getSystemGrammaticalGender(in AttributionSource attributionSource, int userId);

     /**
      * Peeks the grammatical gender from system by user Id.
      */
     int peekSystemGrammaticalGenderByUserId(in AttributionSource attributionSource, int userId);

 }
