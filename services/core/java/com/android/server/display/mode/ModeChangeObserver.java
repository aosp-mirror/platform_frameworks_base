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

package com.android.server.display.mode;

import android.os.Looper;
import android.util.Slog;
import android.util.SparseArray;
import android.view.Display;
import android.view.DisplayAddress;
import android.view.DisplayEventReceiver;

import com.android.internal.annotations.KeepForWeakReference;

import java.util.HashSet;
import java.util.Set;

final class ModeChangeObserver {
    private static final String TAG = "ModeChangeObserver";

    private final VotesStorage mVotesStorage;
    private final DisplayModeDirector.Injector mInjector;

    @SuppressWarnings("unused")
    @KeepForWeakReference
    private DisplayEventReceiver mModeChangeListener;
    private final SparseArray<Set<Integer>> mRejectedModesByDisplay = new SparseArray<>();
    private Looper mLooper;

    ModeChangeObserver(VotesStorage votesStorage, DisplayModeDirector.Injector injector,
                    Looper looper) {
        mVotesStorage = votesStorage;
        mInjector = injector;
        mLooper = looper;
    }

    void observe() {
        mModeChangeListener = new DisplayEventReceiver(mLooper) {
            @Override
            public void onModeRejected(long physicalDisplayId, int modeId) {
                Slog.d(TAG, "Mode Rejected event received");
                int displayId = getLogicalDisplayId(physicalDisplayId);
                if (displayId < 0) {
                    Slog.e(TAG, "Logical Display Id not found");
                    return;
                }
                populateRejectedModesListByDisplay(displayId, modeId);
            }

            @Override
            public void onHotplug(long timestampNanos, long physicalDisplayId, boolean connected) {
                Slog.d(TAG, "Hotplug event received");
                if (!connected) {
                    int displayId = getLogicalDisplayId(physicalDisplayId);
                    if (displayId < 0) {
                        Slog.e(TAG, "Logical Display Id not found");
                        return;
                    }
                    clearRejectedModesListByDisplay(displayId);
                }
            }
        };
    }

    private int getLogicalDisplayId(long rejectedModePhysicalDisplayId) {
        Display[] displays = mInjector.getDisplays();

        for (Display display : displays) {
            DisplayAddress address = display.getAddress();
            if (address instanceof DisplayAddress.Physical physical) {
                long physicalDisplayId = physical.getPhysicalDisplayId();
                if (physicalDisplayId == rejectedModePhysicalDisplayId) {
                    return display.getDisplayId();
                }
            }
        }
        return -1;
    }

    private void populateRejectedModesListByDisplay(int displayId, int rejectedModeId) {
        Set<Integer> alreadyRejectedModes = mRejectedModesByDisplay.get(displayId);
        if (alreadyRejectedModes == null) {
            alreadyRejectedModes = new HashSet<>();
            mRejectedModesByDisplay.put(displayId, alreadyRejectedModes);
        }
        alreadyRejectedModes.add(rejectedModeId);
        mVotesStorage.updateVote(displayId, Vote.PRIORITY_REJECTED_MODES,
                Vote.forRejectedModes(alreadyRejectedModes));
    }

    private void clearRejectedModesListByDisplay(int displayId) {
        mRejectedModesByDisplay.remove(displayId);
        mVotesStorage.updateVote(displayId, Vote.PRIORITY_REJECTED_MODES, null);
    }
}
