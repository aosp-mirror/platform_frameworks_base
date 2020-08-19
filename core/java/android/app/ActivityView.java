/**
 * Copyright (c) 2017 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
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
import android.os.Bundle;
import android.os.UserHandle;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.IWindow;
import android.view.IWindowManager;
import android.view.KeyEvent;
import android.view.SurfaceControl;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.window.TaskEmbedder;
import android.window.TaskOrganizerTaskEmbedder;
import android.window.VirtualDisplayTaskEmbedder;

import dalvik.system.CloseGuard;

/**
 * Task container that allows launching activities into itself.
 * <p>Activity launching into this container is restricted by the same rules that apply to launching
 * on VirtualDisplays.
 * @hide
 */
@TestApi
public class ActivityView extends ViewGroup implements android.window.TaskEmbedder.Host {

    private static final String TAG = "ActivityView";

    private android.window.TaskEmbedder mTaskEmbedder;

    private final SurfaceView mSurfaceView;
    private final SurfaceCallback mSurfaceCallback;

    private final CloseGuard mGuard = CloseGuard.get();
    private boolean mOpened; // Protected by mGuard.

    private final SurfaceControl.Transaction mTmpTransaction = new SurfaceControl.Transaction();

    // For Host
    private final Point mWindowPosition = new Point();
    private final int[] mTmpArray = new int[2];
    private final Rect mTmpRect = new Rect();
    private final Matrix mScreenSurfaceMatrix = new Matrix();
    private final Region mTapExcludeRegion = new Region();

    public ActivityView(Context context) {
        this(context, null /* attrs */);
    }

    public ActivityView(Context context, AttributeSet attrs) {
        this(context, attrs, 0 /* defStyle */);
    }

    public ActivityView(Context context, AttributeSet attrs, int defStyle) {
        this(context, attrs, defStyle, false /*singleTaskInstance*/);
    }

    public ActivityView(Context context, AttributeSet attrs, int defStyle,
            boolean singleTaskInstance) {
        this(context, attrs, defStyle, singleTaskInstance, false /* usePublicVirtualDisplay */);
    }

    /**
     * This constructor let's the caller explicitly request a public virtual display as the backing
     * display. Using a public display is not recommended as it exposes it to other applications,
     * but it might be needed for backwards compatibility.
     */
    public ActivityView(
            @NonNull Context context, @NonNull AttributeSet attrs, int defStyle,
            boolean singleTaskInstance, boolean usePublicVirtualDisplay) {
        this(context, attrs, defStyle, singleTaskInstance, usePublicVirtualDisplay,
                false /* disableSurfaceViewBackgroundLayer */);
    }

    /** @hide */
    public ActivityView(
            @NonNull Context context, @NonNull AttributeSet attrs, int defStyle,
            boolean singleTaskInstance, boolean usePublicVirtualDisplay,
            boolean disableSurfaceViewBackgroundLayer) {
        this(context, attrs, defStyle, singleTaskInstance, usePublicVirtualDisplay,
                disableSurfaceViewBackgroundLayer, false /* useTrustedDisplay */);
    }

    // TODO(b/162901735): Refactor ActivityView with Builder
    /** @hide */
    public ActivityView(
            @NonNull Context context, @NonNull AttributeSet attrs, int defStyle,
            boolean singleTaskInstance, boolean usePublicVirtualDisplay,
            boolean disableSurfaceViewBackgroundLayer, boolean useTrustedDisplay) {
        super(context, attrs, defStyle);
        if (useTaskOrganizer()) {
            mTaskEmbedder = new TaskOrganizerTaskEmbedder(context, this);
        } else {
            mTaskEmbedder = new VirtualDisplayTaskEmbedder(context, this, singleTaskInstance,
                    usePublicVirtualDisplay, useTrustedDisplay);
        }
        mSurfaceView = new SurfaceView(context, null, 0, 0, disableSurfaceViewBackgroundLayer);
        // Since ActivityView#getAlpha has been overridden, we should use parent class's alpha
        // as master to synchronize surface view's alpha value.
        mSurfaceView.setAlpha(super.getAlpha());
        mSurfaceView.setUseAlpha();
        mSurfaceCallback = new SurfaceCallback();
        mSurfaceView.getHolder().addCallback(mSurfaceCallback);
        addView(mSurfaceView);

        mOpened = true;
        mGuard.open("release");
    }

    /** Callback that notifies when the container is ready or destroyed. */
    public abstract static class StateCallback {

        /**
         * Called when the container is ready for launching activities. Calling
         * {@link #startActivity(Intent)} prior to this callback will result in an
         * {@link IllegalStateException}.
         *
         * @see #startActivity(Intent)
         */
        public abstract void onActivityViewReady(ActivityView view);

        /**
         * Called when the container can no longer launch activities. Calling
         * {@link #startActivity(Intent)} after this callback will result in an
         * {@link IllegalStateException}.
         *
         * @see #startActivity(Intent)
         */
        public abstract void onActivityViewDestroyed(ActivityView view);

        /**
         * Called when a task is created inside the container.
         * This is a filtered version of {@link TaskStackListener}
         */
        public void onTaskCreated(int taskId, ComponentName componentName) { }

        /**
         * Called when a task visibility changes.
         * @hide
         */
        public void onTaskVisibilityChanged(int taskId, boolean visible) { }

        /**
         * Called when a task is moved to the front of the stack inside the container.
         * This is a filtered version of {@link TaskStackListener}
         */
        public void onTaskMovedToFront(int taskId) { }

        /**
         * Called when a task is about to be removed from the stack inside the container.
         * This is a filtered version of {@link TaskStackListener}
         */
        public void onTaskRemovalStarted(int taskId) { }

        /**
         * Called when back is pressed on the root activity of the task.
         * @hide
         */
        public void onBackPressedOnTaskRoot(int taskId) { }
    }

    /**
     * Set the callback to be notified about state changes.
     * <p>This class must finish initializing before {@link #startActivity(Intent)} can be called.
     * <p>Note: If the instance was ready prior to this call being made, then
     * {@link StateCallback#onActivityViewReady(ActivityView)} will be called from within
     * this method call.
     *
     * @param callback The callback to report events to.
     *
     * @see StateCallback
     * @see #startActivity(Intent)
     */
    public void setCallback(StateCallback callback) {
        if (callback == null) {
            mTaskEmbedder.setListener(null);
            return;
        }
        mTaskEmbedder.setListener(new StateCallbackAdapter(callback));
    }

    /**
     * Sets the corner radius for the Activity displayed here. The corners will be
     * cropped from the window painted by the contained Activity.
     *
     * @param cornerRadius the radius for the corners, in pixels
     * @hide
     */
    public void setCornerRadius(float cornerRadius) {
        mSurfaceView.setCornerRadius(cornerRadius);
    }

    /**
     * @hide
     */
    public float getCornerRadius() {
        return mSurfaceView.getCornerRadius();
    }

    /**
     * Control whether the surface is clipped to the same bounds as the View. If true, then
     * the bounds set by {@link #setSurfaceClipBounds(Rect)} are applied to the surface as
     * window-crop.
     *
     * @param clippingEnabled whether to enable surface clipping
     * @hide
     */
    public void setSurfaceClippingEnabled(boolean clippingEnabled) {
        mSurfaceView.setEnableSurfaceClipping(clippingEnabled);
    }

    /**
     * Sets an area on the contained surface to which it will be clipped
     * when it is drawn. Setting the value to null will remove the clip bounds
     * and the surface will draw normally, using its full bounds.
     *
     * @param clipBounds The rectangular area, in the local coordinates of
     * this view, to which future drawing operations will be clipped.
     * @hide
     */
    public void setSurfaceClipBounds(Rect clipBounds) {
        mSurfaceView.setClipBounds(clipBounds);
    }

    /**
     * @hide
     */
    public boolean getSurfaceClipBounds(Rect outRect) {
        return mSurfaceView.getClipBounds(outRect);
    }

    /**
     * Launch an activity represented by {@link ShortcutInfo} into this container.
     * <p>The owner of this container must be allowed to access the shortcut information,
     * as defined in {@link LauncherApps#hasShortcutHostPermission()} to use this method.
     * <p>Activity resolved by the provided {@link ShortcutInfo} must have
     * {@link android.R.attr#resizeableActivity} attribute set to {@code true} in order to be
     * launched here. Also, if activity is not owned by the owner of this container, it must allow
     * embedding and the caller must have permission to embed.
     * <p>Note: This class must finish initializing and
     * {@link StateCallback#onActivityViewReady(ActivityView)} callback must be triggered before
     * this method can be called.
     *
     * @param shortcut the shortcut used to launch the activity.
     * @param options for the activity.
     * @param sourceBounds the rect containing the source bounds of the clicked icon to open
     *                     this shortcut.
     * @see StateCallback
     * @see LauncherApps#startShortcut(ShortcutInfo, Rect, Bundle)
     *
     * @hide
     */
    public void startShortcutActivity(@NonNull ShortcutInfo shortcut,
            @NonNull ActivityOptions options, @Nullable Rect sourceBounds) {
        mTaskEmbedder.startShortcutActivity(shortcut, options, sourceBounds);
    }

    /**
     * Launch a new activity into this container.
     * <p>Activity resolved by the provided {@link Intent} must have
     * {@link android.R.attr#resizeableActivity} attribute set to {@code true} in order to be
     * launched here. Also, if activity is not owned by the owner of this container, it must allow
     * embedding and the caller must have permission to embed.
     * <p>Note: This class must finish initializing and
     * {@link StateCallback#onActivityViewReady(ActivityView)} callback must be triggered before
     * this method can be called.
     *
     * @param intent Intent used to launch an activity.
     *
     * @see StateCallback
     * @see #startActivity(PendingIntent)
     */
    public void startActivity(@NonNull Intent intent) {
        mTaskEmbedder.startActivity(intent);
    }

    /**
     * Launch a new activity into this container.
     * <p>Activity resolved by the provided {@link Intent} must have
     * {@link android.R.attr#resizeableActivity} attribute set to {@code true} in order to be
     * launched here. Also, if activity is not owned by the owner of this container, it must allow
     * embedding and the caller must have permission to embed.
     * <p>Note: This class must finish initializing and
     * {@link StateCallback#onActivityViewReady(ActivityView)} callback must be triggered before
     * this method can be called.
     *
     * @param intent Intent used to launch an activity.
     * @param user The UserHandle of the user to start this activity for.
     *
     *
     * @see StateCallback
     * @see #startActivity(PendingIntent)
     */
    public void startActivity(@NonNull Intent intent, UserHandle user) {
        mTaskEmbedder.startActivity(intent, user);
    }

    /**
     * Launch a new activity into this container.
     * <p>Activity resolved by the provided {@link PendingIntent} must have
     * {@link android.R.attr#resizeableActivity} attribute set to {@code true} in order to be
     * launched here. Also, if activity is not owned by the owner of this container, it must allow
     * embedding and the caller must have permission to embed.
     * <p>Note: This class must finish initializing and
     * {@link StateCallback#onActivityViewReady(ActivityView)} callback must be triggered before
     * this method can be called.
     *
     * @param pendingIntent Intent used to launch an activity.
     *
     * @see StateCallback
     * @see #startActivity(Intent)
     */
    public void startActivity(@NonNull PendingIntent pendingIntent) {
        mTaskEmbedder.startActivity(pendingIntent);
    }

    /**
     * Launch a new activity into this container.
     * <p>Activity resolved by the provided {@link PendingIntent} must have
     * {@link android.R.attr#resizeableActivity} attribute set to {@code true} in order to be
     * launched here. Also, if activity is not owned by the owner of this container, it must allow
     * embedding and the caller must have permission to embed.
     * <p>Note: This class must finish initializing and
     * {@link StateCallback#onActivityViewReady(ActivityView)} callback must be triggered before
     * this method can be called.
     *
     * @param pendingIntent Intent used to launch an activity.
     * @param fillInIntent Additional Intent data, see {@link Intent#fillIn Intent.fillIn()}.
     * @param options options for the activity
     *
     * @see StateCallback
     * @see #startActivity(Intent)
     */
    public void startActivity(@NonNull PendingIntent pendingIntent, @Nullable Intent fillInIntent,
            @NonNull ActivityOptions options) {
        mTaskEmbedder.startActivity(pendingIntent, fillInIntent, options);
    }

    /**
     * Release this container if it is initialized. Activity launching will no longer be permitted.
     */
    public void release() {
        performRelease();
    }

    /**
     * Triggers an update of {@link ActivityView}'s location in window to properly set tap exclude
     * regions and avoid focus switches by touches on this view.
     */
    public void onLocationChanged() {
        mTaskEmbedder.notifyBoundsChanged();
    }

    @Override
    public void onLayout(boolean changed, int l, int t, int r, int b) {
        mSurfaceView.layout(0 /* left */, 0 /* top */, r - l /* right */, b - t /* bottom */);
    }

    /**
     * Sets the alpha value when the content of {@link SurfaceView} needs to show or hide.
     * <p>Note: The surface view may ignore the alpha value in some cases. Refer to
     * {@link SurfaceView#setAlpha} for more details.
     *
     * @param alpha The opacity of the view.
     */
    @Override
    public void setAlpha(float alpha) {
        super.setAlpha(alpha);

        if (mSurfaceView != null) {
            mSurfaceView.setAlpha(alpha);
        }
    }

    @Override
    public float getAlpha() {
        return mSurfaceView.getAlpha();
    }

    @Override
    public boolean gatherTransparentRegion(Region region) {
        return mTaskEmbedder.gatherTransparentRegion(region)
                || super.gatherTransparentRegion(region);
    }

    private class SurfaceCallback implements SurfaceHolder.Callback {
        private final DisplayInfo mTempDisplayInfo = new DisplayInfo();
        private final DisplayMetrics mTempMetrics = new DisplayMetrics();

        @Override
        public void surfaceCreated(SurfaceHolder surfaceHolder) {
            if (!mTaskEmbedder.isInitialized()) {
                initTaskEmbedder(mSurfaceView.getSurfaceControl());
            } else {
                mTmpTransaction.reparent(mTaskEmbedder.getSurfaceControl(),
                        mSurfaceView.getSurfaceControl()).apply();
            }
            mTaskEmbedder.resizeTask(getWidth(), getHeight());
            mTaskEmbedder.start();
        }

        @Override
        public void surfaceChanged(SurfaceHolder surfaceHolder, int format, int width, int height) {
            final Display display = getVirtualDisplay().getDisplay();
            if (!display.getDisplayInfo(mTempDisplayInfo)) {
                return;
            }
            mTempDisplayInfo.getAppMetrics(mTempMetrics);
            if (width != mTempMetrics.widthPixels || height != mTempMetrics.heightPixels) {
                mTaskEmbedder.resizeTask(width, height);
                mTaskEmbedder.notifyBoundsChanged();
            }
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder surfaceHolder) {
            mTaskEmbedder.stop();
        }
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        mSurfaceView.setVisibility(visibility);
    }

    /**
     * @return the display id of the virtual display.
     */
    public int getVirtualDisplayId() {
        return mTaskEmbedder.getDisplayId();
    }

    /**
     * @hide
     * @return virtual display.
     */
    public VirtualDisplay getVirtualDisplay() {
        return mTaskEmbedder.getVirtualDisplay();
    }

    /**
     * Injects a pair of down/up key events with keycode {@link KeyEvent#KEYCODE_BACK} to the
     * virtual display.
     */
    public void performBackPress() {
        mTaskEmbedder.performBackPress();
    }

    /**
     * Initializes the task embedder.
     *
     * @param parent control for the surface to parent to
     * @return true if the task embedder has been initialized
     */
    private boolean initTaskEmbedder(SurfaceControl parent) {
        if (!mTaskEmbedder.initialize(parent)) {
            Log.e(TAG, "Failed to initialize ActivityView");
            return false;
        }
        return true;
    }

    private void performRelease() {
        if (!mOpened) {
            return;
        }
        mSurfaceView.getHolder().removeCallback(mSurfaceCallback);
        if (mTaskEmbedder.isInitialized()) {
            mTaskEmbedder.release();
        }
        mTaskEmbedder.setListener(null);

        mGuard.close();
        mOpened = false;
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

    /**
     * Set forwarded insets on the virtual display.
     *
     * @see IWindowManager#setForwardedInsets
     */
    public void setForwardedInsets(Insets insets) {
        mTaskEmbedder.setForwardedInsets(insets);
    }

    // Host

    /** @hide */
    @Override
    public void onTaskBackgroundColorChanged(android.window.TaskEmbedder ts, int bgColor) {
        if (mSurfaceView != null) {
            mSurfaceView.setResizeBackgroundColor(bgColor);
        }
    }

    /** @hide */
    @Override
    public Region getTapExcludeRegion() {
        if (isAttachedToWindow() && canReceivePointerEvents()) {
            Point windowPos = getPositionInWindow();
            mTapExcludeRegion.set(
                    windowPos.x,
                    windowPos.y,
                    windowPos.x + getWidth(),
                    windowPos.y + getHeight());
            // There might be views on top of us. We need to subtract those areas from the tap
            // exclude region.
            final ViewParent parent = getParent();
            if (parent != null) {
                parent.subtractObscuredTouchableRegion(mTapExcludeRegion, this);
            }
        } else {
            mTapExcludeRegion.setEmpty();
        }
        return mTapExcludeRegion;
    }

    /** @hide */
    @Override
    public Matrix getScreenToTaskMatrix() {
        getLocationOnScreen(mTmpArray);
        mScreenSurfaceMatrix.set(getMatrix());
        mScreenSurfaceMatrix.postTranslate(mTmpArray[0], mTmpArray[1]);
        return mScreenSurfaceMatrix;
    }

    /** @hide */
    @Override
    public Point getPositionInWindow() {
        getLocationInWindow(mTmpArray);
        mWindowPosition.set(mTmpArray[0], mTmpArray[1]);
        return mWindowPosition;
    }

    /** @hide */
    @Override
    public Rect getScreenBounds() {
        getBoundsOnScreen(mTmpRect);
        return mTmpRect;
    }

    /** @hide */
    @Override
    public IWindow getWindow() {
        return super.getWindow();
    }

    /** @hide */
    @Override
    public boolean canReceivePointerEvents() {
        return super.canReceivePointerEvents();
    }

    /**
     * Overridden by instances that require the use of the task organizer implementation instead of
     * the virtual display implementation.  Not for general use.
     * @hide
     */
    protected boolean useTaskOrganizer() {
        return false;
    }

    private final class StateCallbackAdapter implements TaskEmbedder.Listener {
        private final StateCallback mCallback;

        private StateCallbackAdapter(ActivityView.StateCallback cb) {
            mCallback = cb;
        }

        @Override
        public void onInitialized() {
            mCallback.onActivityViewReady(ActivityView.this);
        }

        @Override
        public void onReleased() {
            mCallback.onActivityViewDestroyed(ActivityView.this);
        }

        @Override
        public void onTaskCreated(int taskId, ComponentName name) {
            mCallback.onTaskCreated(taskId, name);
        }

        @Override
        public void onTaskVisibilityChanged(int taskId, boolean visible) {
            mCallback.onTaskVisibilityChanged(taskId, visible);
        }

        @Override
        public void onTaskMovedToFront(int taskId) {
            mCallback.onTaskMovedToFront(taskId);
        }

        @Override
        public void onTaskRemovalStarted(int taskId) {
            mCallback.onTaskRemovalStarted(taskId);
        }

        @Override
        public void onBackPressedOnTaskRoot(int taskId) {
            mCallback.onBackPressedOnTaskRoot(taskId);
        }
    }
}
