/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.power;

import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManagerInternal;
import android.hardware.display.DisplayManagerInternal.DisplayPowerRequest;
import android.os.Handler;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.view.Display;

import com.android.internal.annotations.GuardedBy;

/**
 * Responsible for creating {@link DisplayPowerRequest}s and associating them with
 * {@link com.android.server.display.DisplayGroup}s.
 *
 * Each {@link com.android.server.display.DisplayGroup} has a single {@link DisplayPowerRequest}
 * which is used to request power state changes to every display in the group.
 */
class DisplayPowerRequestMapper {

    private final Object mLock = new Object();

    /** A mapping from LogicalDisplay Id to DisplayGroup Id. */
    @GuardedBy("mLock")
    private final SparseIntArray mDisplayGroupIds = new SparseIntArray();

    /** A mapping from DisplayGroup Id to DisplayPowerRequest. */
    @GuardedBy("mLock")
    private final SparseArray<DisplayPowerRequest> mDisplayPowerRequests = new SparseArray<>();

    private final DisplayManagerInternal mDisplayManagerInternal;

    private final DisplayManager.DisplayListener mDisplayListener =
            new DisplayManager.DisplayListener() {

                @Override
                public void onDisplayAdded(int displayId) {
                    synchronized (mLock) {
                        if (mDisplayGroupIds.indexOfKey(displayId) >= 0) {
                            return;
                        }
                        final int displayGroupId = mDisplayManagerInternal.getDisplayGroupId(
                                displayId);
                        if (!mDisplayPowerRequests.contains(displayGroupId)) {
                            // A new DisplayGroup was created; create a new DisplayPowerRequest.
                            mDisplayPowerRequests.append(displayGroupId, new DisplayPowerRequest());
                        }
                        mDisplayGroupIds.append(displayId, displayGroupId);
                    }
                }

                @Override
                public void onDisplayRemoved(int displayId) {
                    synchronized (mLock) {
                        final int index = mDisplayGroupIds.indexOfKey(displayId);
                        if (index < 0) {
                            return;
                        }
                        final int displayGroupId = mDisplayGroupIds.valueAt(index);
                        mDisplayGroupIds.removeAt(index);

                        if (mDisplayGroupIds.indexOfValue(displayGroupId) < 0) {
                            // The DisplayGroup no longer exists; delete the DisplayPowerRequest.
                            mDisplayPowerRequests.delete(displayGroupId);
                        }
                    }
                }

                @Override
                public void onDisplayChanged(int displayId) {
                    synchronized (mLock) {
                        final int newDisplayGroupId = mDisplayManagerInternal.getDisplayGroupId(
                                displayId);
                        final int oldDisplayGroupId = mDisplayGroupIds.get(displayId);

                        if (!mDisplayPowerRequests.contains(newDisplayGroupId)) {
                            // A new DisplayGroup was created; create a new DisplayPowerRequest.
                            mDisplayPowerRequests.append(newDisplayGroupId,
                                    new DisplayPowerRequest());
                        }
                        mDisplayGroupIds.put(displayId, newDisplayGroupId);

                        if (mDisplayGroupIds.indexOfValue(oldDisplayGroupId) < 0) {
                            // The DisplayGroup no longer exists; delete the DisplayPowerRequest.
                            mDisplayPowerRequests.delete(oldDisplayGroupId);
                        }
                    }
                }
            };

    DisplayPowerRequestMapper(DisplayManager displayManager,
            DisplayManagerInternal displayManagerInternal, Handler handler) {
        mDisplayManagerInternal = displayManagerInternal;
        displayManager.registerDisplayListener(mDisplayListener, handler);
        mDisplayPowerRequests.append(Display.DEFAULT_DISPLAY_GROUP, new DisplayPowerRequest());
        mDisplayGroupIds.append(Display.DEFAULT_DISPLAY, Display.DEFAULT_DISPLAY_GROUP);
    }

    DisplayPowerRequest get(int displayId) {
        synchronized (mLock) {
            return mDisplayPowerRequests.get(mDisplayGroupIds.get(displayId));
        }
    }
}
