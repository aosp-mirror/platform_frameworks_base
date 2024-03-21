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

package android.app;

import android.compat.Compatibility;
import android.os.Process;

import com.android.internal.compat.ChangeReporter;

import java.util.Arrays;

/**
 * App process implementation of the {@link Compatibility} API.
 *
 * @hide
 */
public final class AppCompatCallbacks implements Compatibility.BehaviorChangeDelegate {
    private final long[] mDisabledChanges;
    private final long[] mLoggableChanges;
    private final ChangeReporter mChangeReporter;

    /**
     * Install this class into the current process using the disabled and loggable changes lists.
     *
     * @param disabledChanges Set of compatibility changes that are disabled for this process.
     * @param loggableChanges Set of compatibility changes that we want to log.
     */
    public static void install(long[] disabledChanges, long[] loggableChanges) {
        Compatibility.setBehaviorChangeDelegate(
                new AppCompatCallbacks(disabledChanges, loggableChanges));
    }

    private AppCompatCallbacks(long[] disabledChanges, long[] loggableChanges) {
        mDisabledChanges = Arrays.copyOf(disabledChanges, disabledChanges.length);
        mLoggableChanges = Arrays.copyOf(loggableChanges, loggableChanges.length);
        Arrays.sort(mDisabledChanges);
        Arrays.sort(mLoggableChanges);
        mChangeReporter = new ChangeReporter(ChangeReporter.SOURCE_APP_PROCESS);
    }

    /**
     * Helper to determine if a list contains a changeId.
     *
     * @param list to search through
     * @param changeId for which to search in the list
     * @return true if the given changeId is found in the provided array.
     */
    private boolean changeIdInChangeList(long[] list, long changeId) {
        return Arrays.binarySearch(list, changeId) >= 0;
    }

    public void onChangeReported(long changeId) {
        boolean isLoggable = changeIdInChangeList(mLoggableChanges, changeId);
        reportChange(changeId, ChangeReporter.STATE_LOGGED, isLoggable);
    }

    public boolean isChangeEnabled(long changeId) {
        boolean isEnabled = !changeIdInChangeList(mDisabledChanges, changeId);
        boolean isLoggable = changeIdInChangeList(mLoggableChanges, changeId);
        if (isEnabled) {
            // Not present in the disabled changeId array
            reportChange(changeId, ChangeReporter.STATE_ENABLED, isLoggable);
            return true;
        }
        reportChange(changeId, ChangeReporter.STATE_DISABLED, isLoggable);
        return false;
    }

    private void reportChange(long changeId, int state, boolean isLoggable) {
        int uid = Process.myUid();
        mChangeReporter.reportChange(uid, changeId, state, isLoggable);
    }

}
