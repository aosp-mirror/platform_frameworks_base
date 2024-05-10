/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import androidx.annotation.NonNull;

import com.android.keyguard.KeyguardViewController;
import com.android.systemui.Dumpable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dump.DumpManager;

import java.io.PrintWriter;
import java.util.HashSet;
import java.util.Set;

import javax.inject.Inject;

/**
 * Register dialogs to this manager if extraneous affordances (like the UDFPS sensor area)
 * should be hidden from the screen when the dialog shows.
 *
 * Currently, only used if UDFPS is supported on the device; however, can be extended in the future
 * for other use cases.
 */
@SysUISingleton
public class SystemUIDialogManager implements Dumpable {
    private final KeyguardViewController mKeyguardViewController;

    private final Set<SystemUIDialog> mDialogsShowing = new HashSet<>();
    private final Set<Listener> mListeners = new HashSet<>();

    @Inject
    public SystemUIDialogManager(
            DumpManager dumpManager,
            KeyguardViewController keyguardViewController) {
        dumpManager.registerDumpable(this);
        mKeyguardViewController = keyguardViewController;
    }

    /**
     * Whether listeners should hide affordances like the UDFPS sensor icon.
     */
    public boolean shouldHideAffordance() {
        return !mDialogsShowing.isEmpty();
    }

    /**
     * Register a listener to receive callbacks.
     */
    public void registerListener(@NonNull Listener listener) {
        mListeners.add(listener);
    }

    /**
     * Unregister a listener from receiving callbacks.
     */
    public void unregisterListener(@NonNull Listener listener) {
        mListeners.remove(listener);
    }

    void setShowing(SystemUIDialog dialog, boolean showing) {
        final boolean wasHidingAffordances = shouldHideAffordance();
        if (showing) {
            mDialogsShowing.add(dialog);
        } else {
            mDialogsShowing.remove(dialog);
        }

        if (wasHidingAffordances != shouldHideAffordance()) {
            updateDialogListeners();
        }
    }

    private void updateDialogListeners() {
        if (shouldHideAffordance()) {
            mKeyguardViewController.hideAlternateBouncer(true);
        }

        for (Listener listener : mListeners) {
            listener.shouldHideAffordances(shouldHideAffordance());
        }
    }

    @Override
    public void dump(@NonNull PrintWriter pw, @NonNull String[] args) {
        pw.println("listeners:");
        for (Listener listener : mListeners) {
            pw.println("\t" + listener);
        }
        pw.println("dialogs tracked:");
        for (SystemUIDialog dialog : mDialogsShowing) {
            pw.println("\t" + dialog);
        }
    }

    /** SystemUIDialogManagerListener */
    public interface Listener {
        /**
         * Callback where shouldHide=true if listeners should hide their views that may overlap
         * a showing dialog.
         */
        void shouldHideAffordances(boolean shouldHide);
    }
}
