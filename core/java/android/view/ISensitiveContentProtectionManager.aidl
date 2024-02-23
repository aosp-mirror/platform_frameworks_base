/*
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

package android.view;

import android.os.IBinder;

/**
 * @hide
 */
oneway interface ISensitiveContentProtectionManager {
    /**
     * Block projection for a package's window when the window is showing sensitive content on
     * the screen, the projection is unblocked when the window no more shows sensitive content.
     *
     * @param windowToken window where the content is shown.
     * @param packageName package name.
     * @param isShowingSensitiveContent whether the window is showing sensitive content.
     */
    void setSensitiveContentProtection(in IBinder windowToken, in String packageName,
            in boolean isShowingSensitiveContent);
}
