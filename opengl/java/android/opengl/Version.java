/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.opengl;

import android.os.SystemProperties;

public final class Version {
    /**
     * Return the highest OpenGL ES API level supported by the current device.
     * <p>
     * A device that supports a given API level must also support
     * numerically smaller API levels.
     * <p>
     * A device that supports a given API level may not necessarily
     * support every feature of that API level. API-specific techniques may
     * be used to determine whether specific features are supported.
     *
     * @return the highest OpenGL ES API level supported by the current device.
     */
    public static int getOpenGLESVersion() {
        return SystemProperties.getInt("ro.opengles.version", OPENGLES_11);
    }

    /**
     * The version number for OpenGL ES 1.1.
     */
    public final static int OPENGLES_11 = 11;

    /**
     * The version number for OpenGL ES 2.0.
     */
    public final static int OPENGLES_20 = 20;
}
