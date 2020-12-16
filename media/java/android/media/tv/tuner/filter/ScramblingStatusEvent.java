/*
 * Copyright 2020 The Android Open Source Project
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

package android.media.tv.tuner.filter;

import android.annotation.SystemApi;

/**
 * Scrambling Status event sent from {@link Filter} objects with Scrambling Status type.
 *
 * <p>This event is only sent in Tuner 1.1 or higher version. Use
 * {@link TunerVersionChecker#getTunerVersion()} to get the version information.
 *
 * @hide
 */
@SystemApi
public final class ScramblingStatusEvent extends FilterEvent {
    private final int mScramblingStatus;

    private ScramblingStatusEvent(@Filter.ScramblingStatus int scramblingStatus) {
        mScramblingStatus = scramblingStatus;
    }

    /**
     * Gets Scrambling Status Type.
     *
     * <p>This event field is only sent in Tuner 1.1 or higher version. Use
     * {@link TunerVersionChecker#getTunerVersion()} to get the version information.
     */
    @Filter.ScramblingStatus
    public int getScramblingStatus() {
        return mScramblingStatus;
    }
}
