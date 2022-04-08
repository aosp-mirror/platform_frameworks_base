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

package android.window;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.TestApi;
import android.app.ActivityTaskManager;
import android.os.RemoteException;
import android.util.Singleton;
import android.view.SurfaceControl;

/**
 * Base class for organizing specific types of windows like Tasks and DisplayAreas
 *
 * @hide
 */
@TestApi
public class WindowOrganizer {

    /**
     * Apply multiple WindowContainer operations at once.
     * @param t The transaction to apply.
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_ACTIVITY_STACKS)
    public static void applyTransaction(@NonNull WindowContainerTransaction t) {
        try {
            getWindowOrganizerController().applyTransaction(t);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Apply multiple WindowContainer operations at once.
     * @param t The transaction to apply.
     * @param callback This transaction will use the synchronization scheme described in
     *        BLASTSyncEngine.java. The SurfaceControl transaction containing the effects of this
     *        WindowContainer transaction will be passed to this callback when ready.
     * @return An ID for the sync operation which will later be passed to transactionReady callback.
     *         This lets the caller differentiate overlapping sync operations.
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_ACTIVITY_STACKS)
    public int applySyncTransaction(@NonNull WindowContainerTransaction t,
            @NonNull WindowContainerTransactionCallback callback) {
        try {
            return getWindowOrganizerController().applySyncTransaction(t, callback.mInterface);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Take a screenshot for a specified Window
     * @param token The token for the WindowContainer that should get a screenshot taken.
     * @return A SurfaceControl where the screenshot will be attached, or null if failed.
     *
     * @hide
     */
    @Nullable
    @RequiresPermission(android.Manifest.permission.READ_FRAME_BUFFER)
    public static SurfaceControl takeScreenshot(@NonNull WindowContainerToken token) {
        try {
            SurfaceControl surfaceControl = new SurfaceControl();
            if (getWindowOrganizerController().takeScreenshot(token, surfaceControl)) {
                return surfaceControl;
            } else {
                return null;
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @RequiresPermission(android.Manifest.permission.MANAGE_ACTIVITY_STACKS)
    static IWindowOrganizerController getWindowOrganizerController() {
        return IWindowOrganizerControllerSingleton.get();
    }

    private static final Singleton<IWindowOrganizerController> IWindowOrganizerControllerSingleton =
            new Singleton<IWindowOrganizerController>() {
                @Override
                protected IWindowOrganizerController create() {
                    try {
                        return ActivityTaskManager.getService().getWindowOrganizerController();
                    } catch (RemoteException e) {
                        return null;
                    }
                }
            };
}
