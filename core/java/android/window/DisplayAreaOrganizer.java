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

import android.annotation.CallSuper;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.TestApi;
import android.os.RemoteException;
import android.view.SurfaceControl;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * Interface for WindowManager to delegate control of display areas.
 * @hide
 */
@TestApi
public class DisplayAreaOrganizer extends WindowOrganizer {

    /**
     * Key to specify the {@link com.android.server.wm.RootDisplayArea} to attach a window to.
     * It will be used by the function passed in from
     * {@link com.android.server.wm.DisplayAreaPolicyBuilder#setSelectRootForWindowFunc(BiFunction)}
     * to find the Root DA to attach the window.
     * @hide
     */
    public static final String KEY_ROOT_DISPLAY_AREA_ID = "root_display_area_id";

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
     * Display area that can be magnified in
     * {@link Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW}. It contains all windows
     * below {@link WindowManager.LayoutParams#TYPE_ACCESSIBILITY_MAGNIFICATION_OVERLAY}.
     */
    public static final int FEATURE_WINDOWED_MAGNIFICATION = FEATURE_SYSTEM_FIRST + 4;

    /**
     * Display area that can be magnified in
     * {@link Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN}. This is different from
     * {@link #FEATURE_WINDOWED_MAGNIFICATION} that the whole display will be magnified.
     * @hide
     */
    public static final int FEATURE_FULLSCREEN_MAGNIFICATION = FEATURE_SYSTEM_FIRST + 5;

    /**
     * Display area for hiding display cutout feature
     * @hide
     */
    public static final int FEATURE_HIDE_DISPLAY_CUTOUT = FEATURE_SYSTEM_FIRST + 6;

    /**
     * Display area that the IME container can be placed in. Should be enabled on every root
     * hierarchy if IME container may be reparented to that hierarchy when the IME target changed.
     * @hide
     */
    public static final int FEATURE_IME_PLACEHOLDER = FEATURE_SYSTEM_FIRST + 7;

    /**
     * Display area hosting IME window tokens (@see ImeContainer). By default, IMEs are parented
     * to FEATURE_IME_PLACEHOLDER but can be reparented under other RootDisplayArea.
     *
     * This feature can register organizers in order to disable the reparenting logic and manage
     * the position and settings of the container manually. This is useful for foldable devices
     * which require custom UX rules for the IME position (e.g. IME on one screen and the focused
     * app on another screen).
     */
    public static final int FEATURE_IME = FEATURE_SYSTEM_FIRST + 8;

    /**
     * The last boundary of display area for system features
     */
    public static final int FEATURE_SYSTEM_LAST = 10_000;

    /**
     * Vendor specific display area definition can start with this value.
     */
    public static final int FEATURE_VENDOR_FIRST = FEATURE_SYSTEM_LAST + 1;

    /**
     * Last possible vendor specific display area id.
     * @hide
     */
    public static final int FEATURE_VENDOR_LAST = FEATURE_VENDOR_FIRST + 10_000;

    /**
     * Task display areas that can be created at runtime start with this value.
     * @see #createTaskDisplayArea(int, int, String)
     * @hide
     */
    public static final int FEATURE_RUNTIME_TASK_CONTAINER_FIRST = FEATURE_VENDOR_LAST + 1;

    // Callbacks WM Core are posted on this executor if it isn't null, otherwise direct calls are
    // made on the incoming binder call.
    private final Executor mExecutor;

    /** @hide */
    public DisplayAreaOrganizer(@NonNull Executor executor) {
        mExecutor = executor;
    }

    /**
     * Gets the executor to run callbacks on.
     * @hide
     */
    @NonNull
    public Executor getExecutor() {
        return mExecutor;
    }

    /**
     * Registers a DisplayAreaOrganizer to manage display areas for a given feature. A feature can
     * not be registered by multiple organizers at the same time.
     *
     * @return a list of display areas that should be managed by the organizer.
     * @throws IllegalStateException if the feature has already been registered.
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_ACTIVITY_TASKS)
    @CallSuper
    @NonNull
    public List<DisplayAreaAppearedInfo> registerOrganizer(int displayAreaFeature) {
        try {
            return getController().registerOrganizer(mInterface, displayAreaFeature).getList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_ACTIVITY_TASKS)
    @CallSuper
    public void unregisterOrganizer() {
        try {
            getController().unregisterOrganizer(mInterface);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Creates a persistent {@link com.android.server.wm.TaskDisplayArea}.
     *
     * The new created TDA is organized by the organizer, and will be deleted on calling
     * {@link #deleteTaskDisplayArea(WindowContainerToken)} or {@link #unregisterOrganizer()}.
     *
     * @param displayId the display to create the new TDA in.
     * @param parentFeatureId the parent to create the new TDA in. If it is a
     *                        {@link com.android.server.wm.RootDisplayArea}, the new TDA will be
     *                        placed as the topmost TDA. If it is another TDA, the new TDA will be
     *                        placed as the topmost child.
     *                        Caller can use {@link #FEATURE_ROOT} as the root of the logical
     *                        display, or {@link #FEATURE_DEFAULT_TASK_CONTAINER} as the default
     *                        TDA.
     * @param name the name for the new task display area.
     * @return the new created task display area.
     * @throws IllegalArgumentException if failed to create a new task display area.
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_ACTIVITY_TASKS)
    @CallSuper
    @NonNull
    public DisplayAreaAppearedInfo createTaskDisplayArea(int displayId, int parentFeatureId,
            @NonNull String name) {
        try {
            return getController().createTaskDisplayArea(
                    mInterface, displayId, parentFeatureId, name);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Deletes a persistent task display area. It can only be one that created by an organizer.
     *
     * @throws IllegalArgumentException if failed to delete the task display area.
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_ACTIVITY_TASKS)
    @CallSuper
    public void deleteTaskDisplayArea(@NonNull WindowContainerToken taskDisplayArea) {
        try {
            getController().deleteTaskDisplayArea(taskDisplayArea);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Called when a DisplayArea of the registered window type can be controlled by this organizer.
     * It will not be called for the DisplayAreas that exist when {@link #registerOrganizer(int)} is
     * called.
     */
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
            mExecutor.execute(
                    () -> DisplayAreaOrganizer.this.onDisplayAreaAppeared(displayAreaInfo, leash));
        }

        @Override
        public void onDisplayAreaVanished(@NonNull DisplayAreaInfo displayAreaInfo) {
            mExecutor.execute(
                    () -> DisplayAreaOrganizer.this.onDisplayAreaVanished(displayAreaInfo));
        }

        @Override
        public void onDisplayAreaInfoChanged(@NonNull DisplayAreaInfo displayAreaInfo) {
            mExecutor.execute(
                    () -> DisplayAreaOrganizer.this.onDisplayAreaInfoChanged(displayAreaInfo));
        }
    };

    @RequiresPermission(android.Manifest.permission.MANAGE_ACTIVITY_TASKS)
    private IDisplayAreaOrganizerController getController() {
        try {
            return getWindowOrganizerController().getDisplayAreaOrganizerController();
        } catch (RemoteException e) {
            return null;
        }
    }
}
