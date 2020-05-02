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

import static android.view.Display.INVALID_DISPLAY;

import android.annotation.CallSuper;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityOptions;
import android.app.ActivityTaskManager;
import android.app.IActivityTaskManager;
import android.app.PendingIntent;
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
import android.hardware.display.VirtualDisplay;
import android.os.UserHandle;
import android.view.IWindow;
import android.view.IWindowManager;
import android.view.KeyEvent;
import android.view.SurfaceControl;

import dalvik.system.CloseGuard;

/**
 * A component which handles embedded display of tasks within another window. The embedded task can
 * be presented using the SurfaceControl provided from {@link #getSurfaceControl()}.
 *
 * @hide
 */
public abstract class TaskEmbedder {
    private static final String TAG = "TaskEmbedder";

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

        /** @return the screen bounds of the host */
        Rect getScreenBounds();

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

        /**
         * Posts a runnable to be run on the host's handler.
         */
        boolean post(Runnable r);
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

        /** Called when a task visibility changes. */
        default void onTaskVisibilityChanged(int taskId, boolean visible) {}

        /** Called when a task is moved to the front of the stack inside the container. */
        default void onTaskMovedToFront(int taskId) {}

        /** Called when a task is about to be removed from the stack inside the container. */
        default void onTaskRemovalStarted(int taskId) {}

        /** Called when a task is created inside the container. */
        default void onBackPressedOnTaskRoot(int taskId) {}
    }

    protected IActivityTaskManager mActivityTaskManager = ActivityTaskManager.getService();

    protected final Context mContext;
    protected TaskEmbedder.Host mHost;

    protected SurfaceControl.Transaction mTransaction;
    protected SurfaceControl mSurfaceControl;
    protected Listener mListener;
    protected boolean mOpened; // Protected by mGuard.

    private final CloseGuard mGuard = CloseGuard.get();


    /**
     * Constructs a new TaskEmbedder.
     *
     * @param context the context
     * @param host the host for this embedded task
     */
    public TaskEmbedder(Context context, TaskEmbedder.Host host) {
        mContext = context;
        mHost = host;
    }

    /**
     * Initialize this container when the ActivityView's SurfaceView is first created.
     *
     * @param parent the surface control for the parent surface
     * @return true if initialized successfully
     */
    public boolean initialize(SurfaceControl parent) {
        if (isInitialized()) {
            throw new IllegalStateException("Trying to initialize for the second time.");
        }

        mTransaction = new SurfaceControl.Transaction();
        // Create a container surface to which the task content will be reparented
        final String name = "TaskEmbedder - " + Integer.toHexString(System.identityHashCode(this));
        mSurfaceControl = new SurfaceControl.Builder()
                .setContainerLayer()
                .setParent(parent)
                .setName(name)
                .build();

        if (!onInitialize()) {
            return false;
        }
        if (mListener != null && isInitialized()) {
            mListener.onInitialized();
        }
        mOpened = true;
        mGuard.open("release");
        mTransaction.show(getSurfaceControl()).apply();
        return true;
    }

    /**
     * Whether this container has been initialized.
     *
     * @return true if initialized
     */
    public abstract boolean isInitialized();

    /**
     * Called when the task embedder should be initialized.
     * @return whether to report whether the embedder was initialized.
     */
    public abstract boolean onInitialize();

    /**
     * Called when the task embedder should be released.
     * @return whether to report whether the embedder was released.
     */
    protected abstract boolean onRelease();

    /**
     * Starts presentation of tasks in this container.
     */
    public abstract void start();

    /**
     * Stops presentation of tasks in this container.
     */
    public abstract void stop();

    /**
     * This should be called whenever the position or size of the surface changes
     * or if touchable areas above the surface are added or removed.
     */
    public abstract void notifyBoundsChanged();

    /**
     * Called to update the dimensions whenever the host size changes.
     *
     * @param width the new width of the surface
     * @param height the new height of the surface
     */
    public void resizeTask(int width, int height) {
        // Do nothing
    }

    /**
     * Injects a pair of down/up key events with keycode {@link KeyEvent#KEYCODE_BACK} to the
     * virtual display.
     */
    public abstract void performBackPress();

    /**
     * An opaque unique identifier for this task surface among others being managed by the app.
     */
    public abstract int getId();

    /**
     * Calculates and updates the {@param region} with the transparent region for this task
     * embedder.
     */
    public boolean gatherTransparentRegion(Region region) {
        // Do nothing
        return false;
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

    public int getDisplayId() {
        return INVALID_DISPLAY;
    }

    public VirtualDisplay getVirtualDisplay() {
        return null;
    }

    /**
     * Set forwarded insets on the task content.
     *
     * @see IWindowManager#setForwardedInsets
     */
    public void setForwardedInsets(Insets insets) {
        // Do nothing
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
    @CallSuper
    protected ActivityOptions prepareActivityOptions(ActivityOptions options) {
        if (!isInitialized()) {
            throw new IllegalStateException(
                    "Trying to start activity before ActivityView is ready.");
        }
        if (options == null) {
            options = ActivityOptions.makeBasic();
        }
        return options;
    }

    /**
     * Releases the resources for this TaskEmbedder. Tasks will no longer be launchable
     * within this container.
     *
     * <p>Note: Calling this method is allowed after {@link Listener#onInitialized()} callback is
     * triggered and before {@link Listener#onReleased()}.
     */
    public void release() {
        if (!isInitialized()) {
            throw new IllegalStateException("Trying to release container that is not initialized.");
        }
        performRelease();
    }

    private boolean performRelease() {
        if (!mOpened) {
            return false;
        }

        mTransaction.reparent(mSurfaceControl, null).apply();
        mSurfaceControl.release();

        boolean reportReleased = onRelease();
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
}
