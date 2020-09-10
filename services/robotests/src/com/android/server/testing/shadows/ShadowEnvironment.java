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

package com.android.server.testing.shadows;

import android.annotation.Nullable;
import android.os.Environment;

import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Resetter;

import java.io.File;
import java.nio.file.Path;

/** Implementation mimics {@link org.robolectric.shadows.ShadowEnvironment}. */
@Implements(Environment.class)
public class ShadowEnvironment extends org.robolectric.shadows.ShadowEnvironment {
    @Nullable private static Path sDataDirectory;

    /** @see Environment#getDataDirectory() */
    @Implementation
    public static File getDataDirectory() {
        if (sDataDirectory == null) {
            sDataDirectory = RuntimeEnvironment.getTempDirectory().create("data");
        }
        return sDataDirectory.toFile();
    }

    /** Resets static state. */
    @Resetter
    public static void reset() {
        org.robolectric.shadows.ShadowEnvironment.reset();
        sDataDirectory = null;
    }
}
