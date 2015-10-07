/*
 * Copyright (C) 2015 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar;

import com.android.internal.util.Preconditions;
import com.android.systemui.statusbar.phone.StatusBarWindowManager;
import com.android.systemui.statusbar.policy.HeadsUpManager;
import com.android.systemui.statusbar.policy.RemoteInputView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;

/**
 * Keeps track of the currently active {@link RemoteInputView}s.
 */
public class RemoteInputController {

    private final ArrayList<WeakReference<NotificationData.Entry>> mRemoteInputs = new ArrayList<>();
    private final StatusBarWindowManager mStatusBarWindowManager;
    private final HeadsUpManager mHeadsUpManager;

    public RemoteInputController(StatusBarWindowManager sbwm, HeadsUpManager headsUpManager) {
        mStatusBarWindowManager = sbwm;
        mHeadsUpManager = headsUpManager;
    }

    public void addRemoteInput(NotificationData.Entry entry) {
        Preconditions.checkNotNull(entry);

        boolean found = pruneWeakThenRemoveAndContains(
                entry /* contains */, null /* remove */);
        if (!found) {
            mRemoteInputs.add(new WeakReference<>(entry));
        }

        apply(entry);
    }

    public void removeRemoteInput(NotificationData.Entry entry) {
        Preconditions.checkNotNull(entry);

        pruneWeakThenRemoveAndContains(null /* contains */, entry /* remove */);

        apply(entry);
    }

    private void apply(NotificationData.Entry entry) {
        mStatusBarWindowManager.setRemoteInputActive(isRemoteInputActive());
        mHeadsUpManager.setRemoteInputActive(entry, isRemoteInputActive(entry));
    }

    /**
     * @return true if {@param entry} has an active RemoteInput
     */
    public boolean isRemoteInputActive(NotificationData.Entry entry) {
        return pruneWeakThenRemoveAndContains(entry /* contains */, null /* remove */);
    }

    /**
     * @return true if any entry has an active RemoteInput
     */
    public boolean isRemoteInputActive() {
        pruneWeakThenRemoveAndContains(null /* contains */, null /* remove */);
        return !mRemoteInputs.isEmpty();
    }

    /**
     * Prunes dangling weak references, removes entries referring to {@param remove} and returns
     * whether {@param contains} is part of the array in a single loop.
     * @param remove if non-null, removes this entry from the active remote inputs
     * @return true if {@param contains} is in the set of active remote inputs
     */
    private boolean pruneWeakThenRemoveAndContains(
            NotificationData.Entry contains, NotificationData.Entry remove) {
        boolean found = false;
        for (int i = mRemoteInputs.size() - 1; i >= 0; i--) {
            NotificationData.Entry item = mRemoteInputs.get(i).get();
            if (item == null || item == remove) {
                mRemoteInputs.remove(i);
            } else if (item == contains) {
                found = true;
            }
        }
        return found;
    }


}
