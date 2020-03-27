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

import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL;
import static android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;
import static android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherApps;
import android.content.pm.ShortcutInfo;
import android.graphics.Insets;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Region;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.hardware.input.InputManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.IWindow;
import android.view.IWindowManager;
import android.view.IWindowSession;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.SurfaceControl;
import android.view.WindowManagerGlobal;
import android.view.inputmethod.InputMethodManager;

import dalvik.system.CloseGuard;

import java.util.List;

/**
 * A component which handles embedded display of tasks within another window. The embedded task can
 * be presented using the SurfaceControl provided from {@link #getSurfaceControl()}.
 *
 * @hide
 */
public class TaskEmbedder {
    private static final String TAG = "TaskEmbedder";
    private static final String DISPLAY_NAME = "TaskVirtualDisplay";

    /**
     * A component which will host the task.
     */
    public interface Host {
        /** @return the screen area where touches should be dispatched to the embedded Task */
        Region getTapExcludeRegion();

        /** @return a matrix which transforms from screen-space to the embedded task surface */
        Matrix getScreenToTaskMatrix();

        /** @return the window containing the parent surface, if attached and available */
        @Nullable IWindow getWindow();

        /** @return the x/y offset from the origin of the window to the surface */
        Point getPositionInWindow();

        /** @return whether this surface is able to receive pointer events */
        boolean canReceivePointerEvents();

        /** @return the width of the container for the embedded task */
        int getWidth();

        /** @return the height of the container for the embedded task */
        int getHeight();

        /**
         * Called to inform the host of the task's background color. This can be used to
         * fill unpainted areas if necessary.
         */
        void onTaskBackgroundColorChanged(TaskEmbedder ts, int bgColor);
    }

    /**
     * Describes changes to the state of the TaskEmbedder as well the tasks within.
     */
    public interface Listener {
        /** Called when the container is ready for launching activities. */
        default void onInitialized() {}

        /** Called when the container can no longer launch activities. */
        default void onReleased() {}

        /** Called when a task is created inside the container. */
        default void onTaskCreated(int taskId, ComponentName name) {}

        /** Called when a task is moved to the front of the stack inside the container. */
        default void onTaskMovedToFront(int taskId) {}

        /** Called when a task is about to be removed from the stack inside the container. */
        default void onTaskRemovalStarted(int taskId) {}
    }

    private IActivityTaskManager mActivityTaskManager = ActivityTaskManager.getService();

    private final Context mContext;
    private TaskEmbedder.Host mHost;
    private int mDisplayDensityDpi;
    private final boolean mSingleTaskInstance;
    private SurfaceControl.Transaction mTransaction;
    private SurfaceControl mSurfaceControl;
    private VirtualDisplay mVirtualDisplay;
    private Insets mForwardedInsets;
    private TaskStackListener mTaskStackListener;
    private Listener mListener;
    private boolean mOpened; // Protected by mGuard.
    private DisplayMetrics mTmpDisplayMetrics;

    private final CloseGuard mGuard = CloseGuard.get();


    /**
     * Constructs a new TaskEmbedder.
     *
     * @param context the context
     * @param host the host for this embedded task
     * @param singleTaskInstance whether to apply a single-task constraint to this container
     */
    public TaskEmbedder(Context context, TaskEmbedder.Host host, boolean singleTaskInstance) {
        mContext = context;
        mHost = host;
        mSingleTaskInstance = singleTaskInstance;
    }

    /**
     * Whether this container has been initialized.
     *
     * @return true if initialized
     */
    public boolean isInitialized() {
        return mVirtualDisplay != null;
    }

    /**
     * Initialize this container.
     *
     * @param parent the surface control for the parent surface
     * @return true if initialized successfully
     */
    public boolean initialize(SurfaceControl parent) {
        if (mVirtualDisplay != null) {
            throw new IllegalStateException("Trying to initialize for the second time.");
        }

        mTransaction = new SurfaceControl.Transaction();

        final DisplayManager displayManager = mContext.getSystemService(DisplayManager.class);
        mDisplayDensityDpi = getBaseDisplayDensity();

        mVirtualDisplay = displayManager.createVirtualDisplay(
                DISPLAY_NAME + "@" + System.identityHashCode(this), mHost.getWidth(),
                mHost.getHeight(), mDisplayDensityDpi, null,
                VIRTUAL_DISPLAY_FLAG_PUBLIC | VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                        | VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL);

        if (mVirtualDisplay == null) {
            Log.e(TAG, "Failed to initialize TaskEmbedder");
            return false;
        }

        // Create a container surface to which the DisplayContent will be reparented
        final String name = "TaskEmbedder - " + Integer.toHexString(System.identityHashCode(this));
        mSurfaceControl = new SurfaceControl.Builder()
                .setContainerLayer()
                .setParent(parent)
                .setName(name)
                .build();

        final int displayId = getDisplayId();

        final IWindowManager wm = WindowManagerGlobal.getWindowManagerService();
        try {
            // TODO: Find a way to consolidate these calls to the server.
            WindowManagerGlobal.getWindowSession().reparentDisplayContent(
                    mHost.getWindow(), mSurfaceControl, displayId);
            wm.dontOverrideDisplayInfo(displayId);
            if (mSingleTaskInstance) {
                mContext.getSystemService(ActivityTaskManager.class)
                        .setDisplayToSingleTaskInstance(displayId);
            }
            setForwardedInsets(mForwardedInsets);
            if (mHost.getWindow() != null) {
                updateLocationAndTapExcludeRegion();
            }
            mTaskStackListener = new TaskStackListenerImpl();
            try {
                mActivityTaskManager.registerTaskStackListener(mTaskStackListener);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to register task stack listener", e);
            }
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
        if (mListener != null && mVirtualDisplay != null) {
            mListener.onInitialized();
        }
        mOpened = true;
        mGuard.open("release");
        return true;
    }

    /**
     * Returns the surface control for the task surface. This should be parented to a screen
     * surface for display/embedding purposes.
     *
     * @return the surface control for the task
     */
    public SurfaceControl getSurfaceControl() {
        return mSurfaceControl;
    }

    /**
     * Set forwarded insets on the virtual display.
     *
     * @see IWindowManager#setForwardedInsets
     */
    public void setForwardedInsets(Insets insets) {
        mForwardedInsets = insets;
        if (mVirtualDisplay == null) {
            return;
        }
        try {
            final IWindowManager wm = WindowManagerGlobal.getWindowManagerService();
            wm.setForwardedInsets(getDisplayId(), mForwardedInsets);
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
    }

    /** An opaque unique identifier for this task surface among others being managed by the app. */
    public int getId() {
        return getDisplayId();
    }

    int getDisplayId() {
        if (mVirtualDisplay != null) {
            return mVirtualDisplay.getDisplay().getDisplayId();
        }
        return Display.INVALID_DISPLAY;
    }

    /**
     * Set the callback to be notified about state changes.
     * <p>This class must finish initializing before {@link #startActivity(Intent)} can be called.
     * <p>Note: If the instance was ready prior to this call being made, then
     * {@link Listener#onInitialized()} will be called from within this method call.
     *
     * @param listener The listener to report events to.
     *
     * @see ActivityView.StateCallback
     * @see #startActivity(Intent)
     */
    public void setListener(TaskEmbedder.Listener listener) {
        mListener = listener;
        if (mListener != null && isInitialized()) {
            mListener.onInitialized();
        }
    }

    /**
     * Launch a new activity into this container.
     *
     * @param intent Intent used to launch an activity
     *
     * @see #startActivity(PendingIntent)
     */
    public void startActivity(@NonNull Intent intent) {
        final ActivityOptions options = prepareActivityOptions(null);
        mContext.startActivity(intent, options.toBundle());
    }

    /**
     * Launch a new activity into this container.
     *
     * @param intent Intent used to launch an activity
     * @param user The UserHandle of the user to start this activity for
     *
     * @see #startActivity(PendingIntent)
     */
    public void startActivity(@NonNull Intent intent, UserHandle user) {
        final ActivityOptions options = prepareActivityOptions(null);
        mContext.startActivityAsUser(intent, options.toBundle(), user);
    }

    /**
     * Launch a new activity into this container.
     *
     * @param pendingIntent Intent used to launch an activity
     *
     * @see #startActivity(Intent)
     */
    public void startActivity(@NonNull PendingIntent pendingIntent) {
        final ActivityOptions options = prepareActivityOptions(null);
        try {
            pendingIntent.send(null /* context */, 0 /* code */, null /* intent */,
                    null /* onFinished */, null /* handler */, null /* requiredPermission */,
                    options.toBundle());
        } catch (PendingIntent.CanceledException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Launch a new activity into this container.
     *
     * @param pendingIntent Intent used to launch an activity
     * @param fillInIntent Additional Intent data, see {@link Intent#fillIn Intent.fillIn()}
     * @param options options for the activity
     *
     * @see #startActivity(Intent)
     */
    public void startActivity(@NonNull PendingIntent pendingIntent, @Nullable Intent fillInIntent,
            @NonNull ActivityOptions options) {
        prepareActivityOptions(options);
        try {
            pendingIntent.send(mContext, 0 /* code */, fillInIntent,
                    null /* onFinished */, null /* handler */, null /* requiredPermission */,
                    options.toBundle());
        } catch (PendingIntent.CanceledException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Launch an activity represented by {@link ShortcutInfo} into this container.
     * <p>The owner of this container must be allowed to access the shortcut information,
     * as defined in {@link LauncherApps#hasShortcutHostPermission()} to use this method.
     *
     * @param shortcut the shortcut used to launch the activity.
     * @param options options for the activity.
     * @param sourceBounds the rect containing the source bounds of the clicked icon to open
     *                     this shortcut.
     *
     * @see #startActivity(Intent)
     */
    public void startShortcutActivity(@NonNull ShortcutInfo shortcut,
            @NonNull ActivityOptions options, @Nullable Rect sourceBounds) {
        LauncherApps service =
                (LauncherApps) mContext.getSystemService(Context.LAUNCHER_APPS_SERVICE);
        prepareActivityOptions(options);
        service.startShortcut(shortcut, sourceBounds, options.toBundle());
    }

    /**
     * Check if container is ready to launch and modify {@param options} to target the virtual
     * display, creating them if necessary.
     */
    private ActivityOptions prepareActivityOptions(ActivityOptions options) {
        if (mVirtualDisplay == null) {
            throw new IllegalStateException(
                    "Trying to start activity before ActivityView is ready.");
        }
        if (options == null) {
            options = ActivityOptions.makeBasic();
        }
        options.setLaunchDisplayId(getDisplayId());
        options.setLaunchWindowingMode(WINDOWING_MODE_MULTI_WINDOW);
        options.setTaskAlwaysOnTop(true);
        return options;
    }

    /**
     * Stops presentation of tasks in this container.
     */
    public void stop() {
        if (mVirtualDisplay != null) {
            mVirtualDisplay.setDisplayState(false);
            clearActivityViewGeometryForIme();
            clearTapExcludeRegion();
        }
    }

    /**
     * Starts presentation of tasks in this container.
     */
    public void start() {
        if (mVirtualDisplay != null) {
            mVirtualDisplay.setDisplayState(true);
            updateLocationAndTapExcludeRegion();
        }
    }

    /**
     * This should be called whenever the position or size of the surface changes
     * or if touchable areas above the surface are added or removed.
     */
    public void notifyBoundsChanged() {
        updateLocationAndTapExcludeRegion();
    }

    /**
     * Updates position and bounds information needed by WM and IME to manage window
     * focus and touch events properly.
     * <p>
     * This should be called whenever the position or size of the surface changes
     * or if touchable areas above the surface are added or removed.
     */
    private void updateLocationAndTapExcludeRegion() {
        if (mVirtualDisplay == null || mHost.getWindow() == null) {
            return;
        }
        reportLocation(mHost.getScreenToTaskMatrix(), mHost.getPositionInWindow());
        applyTapExcludeRegion(mHost.getWindow(), mHost.getTapExcludeRegion());
    }

    /**
     * Call to update the position and transform matrix for the embedded surface.
     * <p>
     * This should not normally be called directly, but through
     * {@link #updateLocationAndTapExcludeRegion()}. This method
     * is provided as an optimization when managing multiple TaskSurfaces within a view.
     *
     * @param screenToViewMatrix the matrix/transform from screen space to view space
     * @param positionInWindow the window-relative position of the surface
     *
     * @see InputMethodManager#reportActivityView(int, Matrix)
     */
    private void reportLocation(Matrix screenToViewMatrix, Point positionInWindow) {
        try {
            final int displayId = getDisplayId();
            mContext.getSystemService(InputMethodManager.class)
                    .reportActivityView(displayId, screenToViewMatrix);
            IWindowSession session = WindowManagerGlobal.getWindowSession();
            session.updateDisplayContentLocation(mHost.getWindow(), positionInWindow.x,
                    positionInWindow.y, displayId);
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
    }

    /**
     * Call to update the tap exclude region for the window.
     * <p>
     * This should not normally be called directly, but through
     * {@link #updateLocationAndTapExcludeRegion()}. This method
     * is provided as an optimization when managing multiple TaskSurfaces within a view.
     *
     * @see IWindowSession#updateTapExcludeRegion(IWindow, Region)
     */
    private void applyTapExcludeRegion(IWindow window, @Nullable Region tapExcludeRegion) {
        try {
            IWindowSession session = WindowManagerGlobal.getWindowSession();
            session.updateTapExcludeRegion(window, tapExcludeRegion);
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
    }

    /**
     * @see InputMethodManager#reportActivityView(int, Matrix)
     */
    private void clearActivityViewGeometryForIme() {
        final int displayId = getDisplayId();
        mContext.getSystemService(InputMethodManager.class).reportActivityView(displayId, null);
    }

    /**
     * Removes the tap exclude region set by {@link #updateLocationAndTapExcludeRegion()}.
     */
    private void clearTapExcludeRegion() {
        if (mHost.getWindow() == null) {
            Log.w(TAG, "clearTapExcludeRegion: not attached to window!");
            return;
        }
        applyTapExcludeRegion(mHost.getWindow(), null);
    }

    /**
     * Called to update the dimensions whenever the host size changes.
     *
     * @param width the new width of the surface
     * @param height the new height of the surface
     */
    public void resizeTask(int width, int height) {
        mDisplayDensityDpi = getBaseDisplayDensity();
        if (mVirtualDisplay != null) {
            mVirtualDisplay.resize(width, height, mDisplayDensityDpi);
        }
    }

    /**
     * Injects a pair of down/up key events with keycode {@link KeyEvent#KEYCODE_BACK} to the
     * virtual display.
     */
    public void performBackPress() {
        if (mVirtualDisplay == null) {
            return;
        }
        final int displayId = mVirtualDisplay.getDisplay().getDisplayId();
        final InputManager im = InputManager.getInstance();
        im.injectInputEvent(createKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, displayId),
                InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
        im.injectInputEvent(createKeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, displayId),
                InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
    }

    private static KeyEvent createKeyEvent(int action, int code, int displayId) {
        long when = SystemClock.uptimeMillis();
        final KeyEvent ev = new KeyEvent(when, when, action, code, 0 /* repeat */,
                0 /* metaState */, KeyCharacterMap.VIRTUAL_KEYBOARD, 0 /* scancode */,
                KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_VIRTUAL_HARD_KEY,
                InputDevice.SOURCE_KEYBOARD);
        ev.setDisplayId(displayId);
        return ev;
    }

    /**
     * Releases the resources for this TaskEmbedder. Tasks will no longer be launchable
     * within this container.
     *
     * <p>Note: Calling this method is allowed after {@link Listener#onInitialized()} callback is
     * triggered and before {@link Listener#onReleased()}.
     */
    public void release() {
        if (mVirtualDisplay == null) {
            throw new IllegalStateException(
                    "Trying to release container that is not initialized.");
        }
        performRelease();
    }

    private boolean performRelease() {
        if (!mOpened) {
            return false;
        }
        mTransaction.reparent(mSurfaceControl, null).apply();
        mSurfaceControl.release();

        // Clear activity view geometry for IME on this display
        clearActivityViewGeometryForIme();

        // Clear tap-exclude region (if any) for this window.
        clearTapExcludeRegion();

        if (mTaskStackListener != null) {
            try {
                mActivityTaskManager.unregisterTaskStackListener(mTaskStackListener);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to unregister task stack listener", e);
            }
            mTaskStackListener = null;
        }

        boolean reportReleased = false;
        if (mVirtualDisplay != null) {
            mVirtualDisplay.release();
            mVirtualDisplay = null;
            reportReleased = true;

        }

        if (mListener != null && reportReleased) {
            mListener.onReleased();
        }
        mOpened = false;
        mGuard.close();
        return true;
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            if (mGuard != null) {
                mGuard.warnIfOpen();
                performRelease();
            }
        } finally {
            super.finalize();
        }
    }

    /** Get density of the hosting display. */
    private int getBaseDisplayDensity() {
        if (mTmpDisplayMetrics == null) {
            mTmpDisplayMetrics = new DisplayMetrics();
        }
        mContext.getDisplayNoVerify().getRealMetrics(mTmpDisplayMetrics);
        return mTmpDisplayMetrics.densityDpi;
    }

    /**
     * A task change listener that detects background color change of the topmost stack on our
     * virtual display and updates the background of the surface view. This background will be shown
     * when surface view is resized, but the app hasn't drawn its content in new size yet.
     * It also calls StateCallback.onTaskMovedToFront to notify interested parties that the stack
     * associated with the {@link ActivityView} has had a Task moved to the front. This is useful
     * when needing to also bring the host Activity to the foreground at the same time.
     */
    private class TaskStackListenerImpl extends TaskStackListener {

        @Override
        public void onTaskDescriptionChanged(ActivityManager.RunningTaskInfo taskInfo)
                throws RemoteException {
            if (!isInitialized()
                    || taskInfo.displayId != getDisplayId()) {
                return;
            }

            ActivityManager.StackInfo stackInfo = getTopMostStackInfo();
            if (stackInfo == null) {
                return;
            }
            // Found the topmost stack on target display. Now check if the topmost task's
            // description changed.
            if (taskInfo.taskId == stackInfo.taskIds[stackInfo.taskIds.length - 1]) {
                mHost.onTaskBackgroundColorChanged(TaskEmbedder.this,
                        taskInfo.taskDescription.getBackgroundColor());
            }
        }

        @Override
        public void onTaskMovedToFront(ActivityManager.RunningTaskInfo taskInfo)
                throws RemoteException {
            if (!isInitialized() || mListener == null
                    || taskInfo.displayId != getDisplayId()) {
                return;
            }

            ActivityManager.StackInfo stackInfo = getTopMostStackInfo();
            // if StackInfo was null or unrelated to the "move to front" then there's no use
            // notifying the callback
            if (stackInfo != null
                    && taskInfo.taskId == stackInfo.taskIds[stackInfo.taskIds.length - 1]) {
                mListener.onTaskMovedToFront(taskInfo.taskId);
            }
        }

        @Override
        public void onTaskCreated(int taskId, ComponentName componentName) throws RemoteException {
            if (mListener == null || !isInitialized()) {
                return;
            }

            ActivityManager.StackInfo stackInfo = getTopMostStackInfo();
            // if StackInfo was null or unrelated to the task creation then there's no use
            // notifying the callback
            if (stackInfo != null
                    && taskId == stackInfo.taskIds[stackInfo.taskIds.length - 1]) {
                mListener.onTaskCreated(taskId, componentName);
            }
        }

        @Override
        public void onTaskRemovalStarted(ActivityManager.RunningTaskInfo taskInfo)
                throws RemoteException {
            if (mListener == null || !isInitialized()
                    || taskInfo.displayId != getDisplayId()) {
                return;
            }
            mListener.onTaskRemovalStarted(taskInfo.taskId);
        }

        private ActivityManager.StackInfo getTopMostStackInfo() throws RemoteException {
            // Find the topmost task on our virtual display - it will define the background
            // color of the surface view during resizing.
            final int displayId = getDisplayId();
            final List<ActivityManager.StackInfo> stackInfoList =
                    mActivityTaskManager.getAllStackInfosOnDisplay(displayId);
            if (stackInfoList.isEmpty()) {
                return null;
            }
            return stackInfoList.get(0);
        }
    }
}
