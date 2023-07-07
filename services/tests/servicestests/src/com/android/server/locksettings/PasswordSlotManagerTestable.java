/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.server.locksettings;

import androidx.test.InstrumentationRegistry;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;

public class PasswordSlotManagerTestable extends PasswordSlotManager {

    private int mGsiImageNumber;
    private String mSlotMapDir;

    public PasswordSlotManagerTestable() {
        mGsiImageNumber = 0;
    }

    @Override
    protected int getGsiImageNumber() {
        return mGsiImageNumber;
    }

    @Override
    protected String getSlotMapDir() {
        if (mSlotMapDir == null) {
            final File testDir = InstrumentationRegistry.getContext().getFilesDir();
            if (!testDir.exists()) {
                testDir.mkdirs();
            }

            mSlotMapDir = testDir.getPath();
        }
        return mSlotMapDir;
    }

    void setGsiImageNumber(int gsiImageNumber) {
        mGsiImageNumber = gsiImageNumber;
    }

    void cleanup() {
        try {
            Files.delete(Paths.get(getSlotMapDir(), "slot_map"));
        } catch (Exception e) {
        }
    }
}
