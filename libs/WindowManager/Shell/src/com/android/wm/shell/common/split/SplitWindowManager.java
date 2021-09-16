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

package com.android.wm.shell.common.split;

import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
import static android.view.WindowManager.LayoutParams.FLAG_SLIPPERY;
import static android.view.WindowManager.LayoutParams.FLAG_SPLIT_TOUCH;
import static android.view.WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_NO_MOVE_ANIMATION;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_DOCK_DIVIDER;

import android.app.ActivityTaskManager;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;
import android.view.IWindow;
import android.view.InsetsState;
import android.view.LayoutInflater;
import android.view.SurfaceControl;
import android.view.SurfaceControlViewHost;
import android.view.SurfaceSession;
import android.view.WindowManager;
import android.view.WindowlessWindowManager;

import androidx.annotation.Nullable;

import com.android.wm.shell.R;

/**
 * Holds view hierarchy of a root surface and helps to inflate {@link DividerView} for a split.
 */
public final class SplitWindowManager extends WindowlessWindowManager {
    private static final String TAG = SplitWindowManager.class.getSimpleName();

    private final String mWindowName;
    private final ParentContainerCallbacks mParentContainerCallbacks;
    private Context mContext;
    private SurfaceControlViewHost mViewHost;
    private SurfaceControl mLeash;
    private boolean mResizingSplits;
    private DividerView mDividerView;

    public interface ParentContainerCallbacks {
        void attachToParentSurface(SurfaceControl.Builder b);
        void onLeashReady(SurfaceControl leash);
    }

    public SplitWindowManager(String windowName, Context context, Configuration config,
            ParentContainerCallbacks parentContainerCallbacks) {
        super(config, null /* rootSurface */, null /* hostInputToken */);
        mContext = context.createConfigurationContext(config);
        mParentContainerCallbacks = parentContainerCallbacks;
        mWindowName = windowName;
    }

    @Override
    public void setTouchRegion(IBinder window, Region region) {
        super.setTouchRegion(window, region);
    }

    @Override
    public SurfaceControl getSurfaceControl(IWindow window) {
        return super.getSurfaceControl(window);
    }

    @Override
    public void setConfiguration(Configuration configuration) {
        super.setConfiguration(configuration);
        mContext = mContext.createConfigurationContext(configuration);
    }

    @Override
    protected void attachToParentSurface(IWindow window, SurfaceControl.Builder b) {
        // Can't set position for the ViewRootImpl SC directly. Create a leash to manipulate later.
        final SurfaceControl.Builder builder = new SurfaceControl.Builder(new SurfaceSession())
                .setContainerLayer()
                .setName(TAG)
                .setHidden(true)
                .setCallsite("SplitWindowManager#attachToParentSurface");
        mParentContainerCallbacks.attachToParentSurface(builder);
        mLeash = builder.build();
        mParentContainerCallbacks.onLeashReady(mLeash);
        b.setParent(mLeash);
    }

    /** Inflates {@link DividerView} on to the root surface. */
    void init(SplitLayout splitLayout, InsetsState insetsState) {
        if (mDividerView != null || mViewHost != null) {
            throw new UnsupportedOperationException(
                    "Try to inflate divider view again without release first");
        }

        mViewHost = new SurfaceControlViewHost(mContext, mContext.getDisplay(), this);
        mDividerView = (DividerView) LayoutInflater.from(mContext)
                .inflate(R.layout.split_divider, null /* root */);

        final Rect dividerBounds = splitLayout.getDividerBounds();
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                dividerBounds.width(), dividerBounds.height(), TYPE_DOCK_DIVIDER,
                FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL | FLAG_WATCH_OUTSIDE_TOUCH
                        | FLAG_SPLIT_TOUCH | FLAG_SLIPPERY,
                PixelFormat.TRANSLUCENT);
        lp.token = new Binder();
        lp.setTitle(mWindowName);
        lp.privateFlags |= PRIVATE_FLAG_NO_MOVE_ANIMATION | PRIVATE_FLAG_TRUSTED_OVERLAY;
        mViewHost.setView(mDividerView, lp);
        mDividerView.setup(splitLayout, mViewHost, insetsState);
    }

    /**
     * Releases the surface control of the current {@link DividerView} and tear down the view
     * hierarchy.
     */
    void release() {
        if (mDividerView != null) {
            mDividerView = null;
        }

        if (mViewHost != null){
            mViewHost.release();
            mViewHost = null;
        }

        if (mLeash != null) {
            new SurfaceControl.Transaction().remove(mLeash).apply();
            mLeash = null;
        }
    }

    void setInteractive(boolean interactive) {
        if (mDividerView == null) return;
        mDividerView.setInteractive(interactive);
    }

    void setResizingSplits(boolean resizing) {
        if (resizing == mResizingSplits) return;
        try {
            ActivityTaskManager.getService().setSplitScreenResizing(resizing);
            mResizingSplits = resizing;
        } catch (RemoteException e) {
            Slog.w(TAG, "Error calling setSplitScreenResizing", e);
        }
    }

    /**
     * Gets {@link SurfaceControl} of the surface holding divider view. @return {@code null} if not
     * feasible.
     */
    @Nullable
    SurfaceControl getSurfaceControl() {
        return mLeash;
    }

    void onInsetsChanged(InsetsState insetsState) {
        if (mDividerView != null) {
            mDividerView.onInsetsChanged(insetsState, true /* animate */);
        }
    }
}
