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

package com.android.wm.shell.sizecompatui;

import android.annotation.Nullable;
import android.content.Context;
import android.content.res.Configuration;
import android.view.IWindow;
import android.view.LayoutInflater;
import android.view.SurfaceControl;
import android.view.SurfaceControlViewHost;
import android.view.SurfaceSession;
import android.view.View;
import android.view.WindowlessWindowManager;

import com.android.wm.shell.R;

/**
 * Holds view hierarchy of a root surface and helps to inflate {@link SizeCompatRestartButton} or
 * {@link SizeCompatHintPopup}.
 */
class SizeCompatUIWindowManager extends WindowlessWindowManager {

    private Context mContext;
    private final SizeCompatUILayout mLayout;

    @Nullable
    private SurfaceControlViewHost mViewHost;
    @Nullable
    private SurfaceControl mLeash;

    SizeCompatUIWindowManager(Context context, Configuration config, SizeCompatUILayout layout) {
        super(config, null /* rootSurface */, null /* hostInputToken */);
        mContext = context;
        mLayout = layout;
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
                .setName("SizeCompatUILeash")
                .setHidden(false)
                .setCallsite("SizeCompatUIWindowManager#attachToParentSurface");
        mLayout.attachToParentSurface(builder);
        mLeash = builder.build();
        b.setParent(mLeash);
    }

    /** Inflates {@link SizeCompatRestartButton} on to the root surface. */
    SizeCompatRestartButton createSizeCompatButton() {
        if (mViewHost != null) {
            throw new IllegalStateException(
                    "A UI has already been created with this window manager.");
        }

        mViewHost = new SurfaceControlViewHost(mContext, mContext.getDisplay(), this);

        final SizeCompatRestartButton button = (SizeCompatRestartButton)
                LayoutInflater.from(mContext).inflate(R.layout.size_compat_ui, null);
        button.inject(mLayout);
        mViewHost.setView(button, mLayout.getButtonWindowLayoutParams());
        return button;
    }

    /** Inflates {@link SizeCompatHintPopup} on to the root surface. */
    SizeCompatHintPopup createSizeCompatHint() {
        if (mViewHost != null) {
            throw new IllegalStateException(
                    "A UI has already been created with this window manager.");
        }

        mViewHost = new SurfaceControlViewHost(mContext, mContext.getDisplay(), this);

        final SizeCompatHintPopup hint = (SizeCompatHintPopup)
                LayoutInflater.from(mContext).inflate(R.layout.size_compat_mode_hint, null);
        // Measure how big the hint is.
        hint.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        hint.inject(mLayout);
        mViewHost.setView(hint, mLayout.getHintWindowLayoutParams(hint));
        return hint;
    }

    /** Releases the surface control and tears down the view hierarchy. */
    void release() {
        if (mViewHost != null) {
            mViewHost.release();
            mViewHost = null;
        }

        if (mLeash != null) {
            final SurfaceControl leash = mLeash;
            mLayout.mSyncQueue.runInSync(t -> t.remove(leash));
            mLeash = null;
        }
    }

    /**
     * Gets {@link SurfaceControl} of the surface holding size compat UI view. @return {@code null}
     * if not feasible.
     */
    @Nullable
    SurfaceControl getSurfaceControl() {
        return mLeash;
    }
}
