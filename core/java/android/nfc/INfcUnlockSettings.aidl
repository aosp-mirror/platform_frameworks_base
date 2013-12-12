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

package android.nfc;

import android.nfc.Tag;
import java.util.List;

/**
 * Interface to NFC unlock functionality.
 *
 * @hide
 */
interface INfcUnlockSettings {

    /**
     * Checks the validity of the tag and attempts to unlock the screen.
     *
     * @return true if the screen was successfuly unlocked.
     */
    boolean tryUnlock(int userId, in Tag tag);

    /**
     * Registers the given tag as an unlock tag. Subsequent calls to {@code tryUnlock}
     * with the same {@code tag} should succeed.
     *
     * @return true if the tag was successfully registered.
     */
    boolean registerTag(int userId, in Tag tag);

    /**
     * Deregisters the tag with the corresponding timestamp.
     * Subsequent calls to {@code tryUnlock} with the same tag should fail.
     *
     * @return true if the tag was successfully deleted.
     */
    boolean deregisterTag(int userId, long timestamp);

    /**
     * Used for user-visible rendering of registered tags.
     *
     * @return a list of the times in millis since epoch when the registered tags were paired.
     */
    long[] getTagRegistryTimes(int userId);

    /**
     * Determines the state of the NFC unlock feature.
     *
     * @return true if NFC unlock is enabled.
     */
    boolean getNfcUnlockEnabled(int userId);

    /**
     * Sets the state [ON | OFF] of the NFC unlock feature.
     */
    void setNfcUnlockEnabled(int userId, boolean enabled);
}
