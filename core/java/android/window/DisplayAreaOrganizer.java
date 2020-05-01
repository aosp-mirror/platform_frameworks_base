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
import android.annotation.RequiresPermission;
import android.annotation.TestApi;
import android.os.RemoteException;
import android.util.Singleton;
import android.view.SurfaceControl;

/**
 * Interface for WindowManager to delegate control of display areas.
 * @hide
 */
@TestApi
public class DisplayAreaOrganizer extends WindowOrganizer {

    /**
     * The value in display area indicating that no value has been set.
     */
    public static final int FEATURE_UNDEFINED = -1;

    /**
     * The Root display area on a display
     */
    public static final int FEATURE_SYSTEM_FIRST = 0;

    /**
     * The Root display area on a display
     */
    public static final int FEATURE_ROOT = FEATURE_SYSTEM_FIRST;

    /**
     * Display area hosting the default task container.
     */
    public static final int FEATURE_DEFAULT_TASK_CONTAINER = FEATURE_SYSTEM_FIRST + 1;

    /**
     * Display area hosting non-activity window tokens.
     */
    public static final int FEATURE_WINDOW_TOKENS = FEATURE_SYSTEM_FIRST + 2;

    /**
     * Display area for one handed feature
     */
    public static final int FEATURE_ONE_HANDED = FEATURE_SYSTEM_FIRST + 3;

    /**
     * The last boundary of display area for system features
     */
    public static final int FEATURE_SYSTEM_LAST = 10_000;

    /**
     * Vendor specific display area definition can start with this value.
     */
    public static final int FEATURE_VENDOR_FIRST = FEATURE_SYSTEM_LAST + 1;

    @RequiresPermission(android.Manifest.permission.MANAGE_ACTIVITY_STACKS)
    public void registerOrganizer(int displayAreaFeature) {
        try {
            getController().registerOrganizer(mInterface, displayAreaFeature);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_ACTIVITY_STACKS)
    public void unregisterOrganizer() {
        try {
            getController().unregisterOrganizer(mInterface);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public void onDisplayAreaAppeared(@NonNull DisplayAreaInfo displayAreaInfo,
            @NonNull SurfaceControl leash) {}

    public void onDisplayAreaVanished(@NonNull DisplayAreaInfo displayAreaInfo) {}

    /**
     * @hide
     */
    public void onDisplayAreaInfoChanged(@NonNull DisplayAreaInfo displayAreaInfo) {}

    private final IDisplayAreaOrganizer mInterface = new IDisplayAreaOrganizer.Stub() {

        @Override
        public void onDisplayAreaAppeared(@NonNull DisplayAreaInfo displayAreaInfo,
                @NonNull SurfaceControl leash) {
            DisplayAreaOrganizer.this.onDisplayAreaAppeared(displayAreaInfo, leash);
        }

        @Override
        public void onDisplayAreaVanished(@NonNull DisplayAreaInfo displayAreaInfo) {
            DisplayAreaOrganizer.this.onDisplayAreaVanished(displayAreaInfo);
        }

        @Override
        public void onDisplayAreaInfoChanged(@NonNull DisplayAreaInfo displayAreaInfo) {
            DisplayAreaOrganizer.this.onDisplayAreaInfoChanged(displayAreaInfo);
        }
    };

    private static IDisplayAreaOrganizerController getController() {
        return IDisplayAreaOrganizerControllerSingleton.get();
    }

    private static final Singleton<IDisplayAreaOrganizerController>
            IDisplayAreaOrganizerControllerSingleton =
            new Singleton<IDisplayAreaOrganizerController>() {
                @Override
                protected IDisplayAreaOrganizerController create() {
                    try {
                        return getWindowOrganizerController()
                                .getDisplayAreaOrganizerController();
                    } catch (RemoteException e) {
                        return null;
                    }
                }
            };

}
