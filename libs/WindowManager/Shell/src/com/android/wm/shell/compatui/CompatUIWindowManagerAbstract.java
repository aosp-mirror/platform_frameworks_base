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

package com.android.wm.shell.compatui;

import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_NO_MOVE_ANIMATION;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;

import android.annotation.Nullable;
import android.app.TaskInfo;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Binder;
import android.util.Log;
import android.view.IWindow;
import android.view.SurfaceControl;
import android.view.SurfaceControlViewHost;
import android.view.SurfaceSession;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowlessWindowManager;

import com.android.internal.annotations.VisibleForTesting;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.DisplayLayout;
import com.android.wm.shell.common.SyncTransactionQueue;

/**
 * A superclass for all Compat UI {@link WindowlessWindowManager}s that holds shared logic and
 * exposes general API for {@link CompatUIController}.
 *
 * <p>Holds view hierarchy of a root surface and helps to inflate and manage layout.
 */
abstract class CompatUIWindowManagerAbstract extends WindowlessWindowManager {

    protected final SyncTransactionQueue mSyncQueue;
    protected final int mDisplayId;
    protected final int mTaskId;

    protected Context mContext;
    protected Configuration mTaskConfig;
    protected ShellTaskOrganizer.TaskListener mTaskListener;
    protected DisplayLayout mDisplayLayout;
    protected final Rect mStableBounds;

    /**
     * Utility class for adding and releasing a View hierarchy for this {@link
     * WindowlessWindowManager} to {@code mLeash}.
     */
    @Nullable
    protected SurfaceControlViewHost mViewHost;

    /**
     * A surface leash to position the layout relative to the task, since we can't set position for
     * the {@code mViewHost} directly.
     */
    @Nullable
    protected SurfaceControl mLeash;

    protected CompatUIWindowManagerAbstract(Context context, Configuration taskConfig,
            SyncTransactionQueue syncQueue, int taskId,
            ShellTaskOrganizer.TaskListener taskListener, DisplayLayout displayLayout) {
        super(taskConfig, null /* rootSurface */, null /* hostInputToken */);
        mContext = context;
        mSyncQueue = syncQueue;
        mTaskConfig = taskConfig;
        mDisplayId = mContext.getDisplayId();
        mTaskId = taskId;
        mTaskListener = taskListener;
        mDisplayLayout = displayLayout;
        mStableBounds = new Rect();
        mDisplayLayout.getStableBounds(mStableBounds);
    }

    /**
     * Returns the z-order of this window which will be passed to the {@link SurfaceControl} once
     * {@link #attachToParentSurface} is called.
     *
     * <p>See {@link SurfaceControl.Transaction#setLayer}.
     */
    protected abstract int getZOrder();

    /** Returns the layout of this window manager. */
    protected abstract @Nullable View getLayout();

    /**
     * Inflates and inits the layout of this window manager on to the root surface if both {@code
     * canShow} and {@link #eligibleToShowLayout} are true.
     *
     * @param canShow whether the layout is allowed to be shown by the parent controller.
     */
    void createLayout(boolean canShow) {
        if (!canShow || !eligibleToShowLayout() || getLayout() != null) {
            // Wait until layout should be visible.
            return;
        }

        if (mViewHost != null) {
            throw new IllegalStateException(
                    "A UI has already been created with this window manager.");
        }

        // Construction extracted into separate methods to allow injection for tests.
        mViewHost = createSurfaceViewHost();
        mViewHost.setView(createLayout(), getWindowLayoutParams());

        updateSurfacePosition();
    }

    /** Inflates and inits the layout of this window manager. */
    protected abstract View createLayout();

    protected abstract void removeLayout();

    /**
     * Whether the layout is eligible to be shown according to the internal state of the subclass.
     * Returns true by default if subclass doesn't override this method.
     */
    protected boolean eligibleToShowLayout() {
        return true;
    }

    @Override
    public void setConfiguration(Configuration configuration) {
        super.setConfiguration(configuration);
        mContext = mContext.createConfigurationContext(configuration);
    }

    @Override
    protected void attachToParentSurface(IWindow window, SurfaceControl.Builder b) {
        String className = getClass().getSimpleName();
        final SurfaceControl.Builder builder = new SurfaceControl.Builder(new SurfaceSession())
                .setContainerLayer()
                .setName(className + "Leash")
                .setHidden(false)
                .setCallsite(className + "#attachToParentSurface");
        attachToParentSurface(builder);
        mLeash = builder.build();
        b.setParent(mLeash);

        initSurface(mLeash);
    }

    /** Inits the z-order of the surface. */
    private void initSurface(SurfaceControl leash) {
        final int z = getZOrder();
        mSyncQueue.runInSync(t -> {
            if (leash == null || !leash.isValid()) {
                Log.w(getTag(), "The leash has been released.");
                return;
            }
            t.setLayer(leash, z);
        });
    }

    /**
     * Called when compat info changed.
     *
     * @param canShow whether the layout is allowed to be shown by the parent controller.
     */
    void updateCompatInfo(TaskInfo taskInfo,
            ShellTaskOrganizer.TaskListener taskListener, boolean canShow) {
        final Configuration prevTaskConfig = mTaskConfig;
        final ShellTaskOrganizer.TaskListener prevTaskListener = mTaskListener;
        mTaskConfig = taskInfo.configuration;
        mTaskListener = taskListener;

        // Update configuration.
        setConfiguration(mTaskConfig);

        View layout = getLayout();
        if (layout == null || prevTaskListener != taskListener) {
            // TaskListener changed, recreate the layout for new surface parent.
            release();
            createLayout(canShow);
            return;
        }

        boolean boundsUpdated = !mTaskConfig.windowConfiguration.getBounds().equals(
                prevTaskConfig.windowConfiguration.getBounds());
        boolean layoutDirectionUpdated =
                mTaskConfig.getLayoutDirection() != prevTaskConfig.getLayoutDirection();
        if (boundsUpdated || layoutDirectionUpdated) {
            // Reposition the UI surfaces.
            updateSurfacePosition();
        }

        if (layout != null && layoutDirectionUpdated) {
            // Update layout for RTL.
            layout.setLayoutDirection(mTaskConfig.getLayoutDirection());
        }
    }


    /**
     * Updates the visibility of the layout.
     *
     * @param canShow whether the layout is allowed to be shown by the parent controller.
     */
    void updateVisibility(boolean canShow) {
        View layout = getLayout();
        if (layout == null) {
            // Layout may not have been created because it was hidden previously.
            createLayout(canShow);
            return;
        }

        final int newVisibility = canShow && eligibleToShowLayout() ? View.VISIBLE : View.GONE;
        if (layout.getVisibility() != newVisibility) {
            layout.setVisibility(newVisibility);
        }
    }

    /** Called when display layout changed. */
    void updateDisplayLayout(DisplayLayout displayLayout) {
        final Rect prevStableBounds = mStableBounds;
        final Rect curStableBounds = new Rect();
        displayLayout.getStableBounds(curStableBounds);
        mDisplayLayout = displayLayout;
        if (!prevStableBounds.equals(curStableBounds)) {
            // Stable bounds changed, update UI surface positions.
            updateSurfacePosition();
            mStableBounds.set(curStableBounds);
        }
    }

    /** Called when the surface is ready to be placed under the task surface. */
    @VisibleForTesting
    void attachToParentSurface(SurfaceControl.Builder b) {
        mTaskListener.attachChildSurfaceToTask(mTaskId, b);
    }

    int getDisplayId() {
        return mDisplayId;
    }

    int getTaskId() {
        return mTaskId;
    }

    /** Releases the surface control and tears down the view hierarchy. */
    void release() {
        // Hiding before releasing to avoid flickering when transitioning to the Home screen.
        View layout = getLayout();
        if (layout != null) {
            layout.setVisibility(View.GONE);
        }
        removeLayout();

        if (mViewHost != null) {
            mViewHost.release();
            mViewHost = null;
        }

        if (mLeash != null) {
            final SurfaceControl leash = mLeash;
            mSyncQueue.runInSync(t -> t.remove(leash));
            mLeash = null;
        }
    }

    /** Re-layouts the view host and updates the surface position. */
    void relayout() {
        if (mViewHost == null) {
            return;
        }
        mViewHost.relayout(getWindowLayoutParams());
        updateSurfacePosition();
    }

    /**
     * Updates the position of the surface with respect to the task bounds and display layout
     * stable bounds.
     */
    @VisibleForTesting
    void updateSurfacePosition() {
        if (mLeash == null) {
            return;
        }
        // Use stable bounds to prevent controls from overlapping with system bars.
        final Rect taskBounds = mTaskConfig.windowConfiguration.getBounds();
        final Rect stableBounds = new Rect();
        mDisplayLayout.getStableBounds(stableBounds);
        stableBounds.intersect(taskBounds);

        updateSurfacePosition(taskBounds, stableBounds);
    }

    /**
     * Updates the position of the surface with respect to the given {@code taskBounds} and {@code
     * stableBounds}.
     */
    protected abstract void updateSurfacePosition(Rect taskBounds, Rect stableBounds);

    /**
     * Updates the position of the surface with respect to the given {@code positionX} and {@code
     * positionY}.
     */
    protected void updateSurfacePosition(int positionX, int positionY) {
        mSyncQueue.runInSync(t -> {
            if (mLeash == null || !mLeash.isValid()) {
                Log.w(getTag(), "The leash has been released.");
                return;
            }
            t.setPosition(mLeash, positionX, positionY);
        });
    }

    protected int getLayoutDirection() {
        return mContext.getResources().getConfiguration().getLayoutDirection();
    }

    @VisibleForTesting
    SurfaceControlViewHost createSurfaceViewHost() {
        return new SurfaceControlViewHost(mContext, mContext.getDisplay(), this);
    }

    /** Gets the layout params. */
    private WindowManager.LayoutParams getWindowLayoutParams() {
        View layout = getLayout();
        if (layout == null) {
            return new WindowManager.LayoutParams();
        }
        // Measure how big the hint is since its size depends on the text size.
        layout.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        return getWindowLayoutParams(layout.getMeasuredWidth(), layout.getMeasuredHeight());
    }

    /** Gets the layout params given the width and height of the layout. */
    private WindowManager.LayoutParams getWindowLayoutParams(int width, int height) {
        final WindowManager.LayoutParams winParams = new WindowManager.LayoutParams(
                // Cannot be wrap_content as this determines the actual window size
                width, height,
                TYPE_APPLICATION_OVERLAY,
                FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        winParams.token = new Binder();
        winParams.setTitle(getClass().getSimpleName() + mTaskId);
        winParams.privateFlags |= PRIVATE_FLAG_NO_MOVE_ANIMATION | PRIVATE_FLAG_TRUSTED_OVERLAY;
        return winParams;
    }

    protected final String getTag() {
        return getClass().getSimpleName();
    }
}
