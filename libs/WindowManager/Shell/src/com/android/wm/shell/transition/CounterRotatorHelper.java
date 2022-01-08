/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.wm.shell.transition;

import static android.window.TransitionInfo.FLAG_IS_WALLPAPER;

import android.util.ArrayMap;
import android.view.SurfaceControl;
import android.window.TransitionInfo;
import android.window.WindowContainerToken;

import androidx.annotation.NonNull;

import com.android.wm.shell.util.CounterRotator;

import java.util.List;

/**
 * The helper class that performs counter-rotate for all "going-away" window containers if they are
 * still in the old rotation in a transition.
 */
public class CounterRotatorHelper {
    private final ArrayMap<WindowContainerToken, CounterRotator> mRotatorMap = new ArrayMap<>();
    private SurfaceControl mRootLeash;

    /** Puts the surface controls of closing changes to counter-rotated surfaces. */
    public void handleClosingChanges(@NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction startTransaction,
            int rotateDelta, int displayW, int displayH) {
        mRootLeash = info.getRootLeash();
        final List<TransitionInfo.Change> changes = info.getChanges();
        final int numChanges = changes.size();
        for (int i = numChanges - 1; i >= 0; --i) {
            final TransitionInfo.Change change = changes.get(i);
            final WindowContainerToken parent = change.getParent();
            if (!Transitions.isClosingType(change.getMode())
                    || !TransitionInfo.isIndependent(change, info) || parent == null) {
                continue;
            }

            CounterRotator crot = mRotatorMap.get(parent);
            if (crot == null) {
                crot = new CounterRotator();
                crot.setup(startTransaction, info.getChange(parent).getLeash(), rotateDelta,
                        displayW, displayH);
                final SurfaceControl rotatorSc = crot.getSurface();
                if (rotatorSc != null) {
                    // Wallpaper should be placed at the bottom.
                    final int layer = (change.getFlags() & FLAG_IS_WALLPAPER) == 0
                            ? numChanges - i
                            : -1;
                    startTransaction.setLayer(rotatorSc, layer);
                }
                mRotatorMap.put(parent, crot);
            }
            crot.addChild(startTransaction, change.getLeash());
        }
    }

    /** Restores to the original state, i.e. reparent back to transition root. */
    public void cleanUp() {
        for (int i = mRotatorMap.size() - 1; i >= 0; --i) {
            mRotatorMap.valueAt(i).cleanUp(mRootLeash);
        }
        mRotatorMap.clear();
    }
}
