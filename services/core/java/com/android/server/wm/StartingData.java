/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.server.wm;

import android.annotation.IntDef;

import com.android.server.wm.StartingSurfaceController.StartingSurface;

/**
 * Represents the model about how a starting window should be constructed.
 */
public abstract class StartingData {

    /** Nothing need to do after transaction */
    static final int AFTER_TRANSACTION_IDLE = 0;
    /** Remove the starting window directly after transaction done. */
    static final int AFTER_TRANSACTION_REMOVE_DIRECTLY = 1;
    /** Do copy splash screen to client after transaction done. */
    static final int AFTER_TRANSACTION_COPY_TO_CLIENT = 2;

    @IntDef(prefix = { "AFTER_TRANSACTION" }, value = {
            AFTER_TRANSACTION_IDLE,
            AFTER_TRANSACTION_REMOVE_DIRECTLY,
            AFTER_TRANSACTION_COPY_TO_CLIENT,
    })
    @interface AfterTransaction {}

    protected final WindowManagerService mService;
    protected final int mTypeParams;

    /**
     * Tell whether the launching activity should use
     * {@link android.view.WindowManager.LayoutParams#SOFT_INPUT_IS_FORWARD_NAVIGATION}.
     */
    boolean mIsTransitionForward;

    /**
     * Non-null if the starting window should cover the bounds of associated task. It is assigned
     * when the parent activity of starting window may be put in a partial area of the task.
     */
    Task mAssociatedTask;


    /** Whether the starting window is resized from transfer across activities. */
    boolean mResizedFromTransfer;

    /** Whether the starting window is drawn. */
    boolean mIsDisplayed;

    /**
     * For Shell transition.
     * There will be a transition happen on attached activity, do not remove starting window during
     * this period, because the transaction to show app window may not apply before remove starting
     * window.
     * Note this isn't equal to transition playing, the period should be
     * Sync finishNow -> Start transaction apply.
     * @deprecated TODO(b/362347290): cleanup after fix ramp up
     */
    @Deprecated
    boolean mWaitForSyncTransactionCommit;

    /**
     * For Shell transition.
     * This starting window should be removed after applying the start transaction of transition,
     * which ensures the app window has shown.
     */
    @AfterTransaction int mRemoveAfterTransaction = AFTER_TRANSACTION_IDLE;

    /** Whether to prepare the removal animation. */
    boolean mPrepareRemoveAnimation;

    /** Non-zero if this starting window is added in a collecting transition. */
    int mTransitionId;

    protected StartingData(WindowManagerService service, int typeParams) {
        mService = service;
        mTypeParams = typeParams;
    }

    /**
     * Creates the actual starting window surface.
     *
     * @param activity the app to add the starting window to
     * @return a class implementing {@link StartingSurface} for easy removal with
     *         {@link StartingSurface#remove}
     */
    abstract StartingSurface createStartingSurface(ActivityRecord activity);

    /**
     * @return Whether to apply reveal animation when exiting the starting window.
     */
    abstract boolean needRevealAnimation();

    /** @see android.window.TaskSnapshot#hasImeSurface() */
    boolean hasImeSurface() {
        return false;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{"
                + Integer.toHexString(System.identityHashCode(this))
                + " waitForSyncTransactionCommit=" + mWaitForSyncTransactionCommit
                + " removeAfterTransaction= " + mRemoveAfterTransaction
                + "}";
    }
}
