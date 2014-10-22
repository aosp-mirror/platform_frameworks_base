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

package com.google.android.test.lib_client;

import android.app.Activity;
import android.os.Bundle;
import com.google.android.test.shared_library.SharedLibraryMain;

public class ActivityMain extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        String[] expectedAnimals = new String[] {
                "Racoon",
                "Rhino",
                "Elephant"
        };

        String[] animals = getResources().getStringArray(com.google.android.test.shared_library.R.array.animals);
        if (animals == null || animals.length != expectedAnimals.length) {
            throw new AssertionError("Animal list from shared library is null or wrong length.");
        }

        for (int i = 0; i < expectedAnimals.length; i++) {
            if (!expectedAnimals[i].equals(animals[i])) {
                throw new AssertionError("Expected '" + expectedAnimals[i]
                        + "' at index " + i + " but got '" + animals[i]);
            }
        }

        SharedLibraryMain.ensureVersion(this, SharedLibraryMain.VERSION_BASE);
    }
}
