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

package com.android.wm.shell.onehanded;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.util.Log;
import android.view.SurfaceControl;
import android.view.SurfaceSession;
import android.window.DisplayAreaAppearedInfo;
import android.window.DisplayAreaInfo;
import android.window.DisplayAreaOrganizer;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.internal.annotations.GuardedBy;
import com.android.wm.shell.R;
import com.android.wm.shell.common.DisplayLayout;

import java.io.PrintWriter;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Manages OneHanded color background layer areas.
 * To avoid when turning the Dark theme on, users can not clearly identify
 * the screen has entered one handed mode.
 */
public class OneHandedBackgroundPanelOrganizer extends DisplayAreaOrganizer
        implements OneHandedTransitionCallback {
    private static final String TAG = "OneHandedBackgroundPanelOrganizer";

    private final Object mLock = new Object();
    private final SurfaceSession mSurfaceSession = new SurfaceSession();
    private final float[] mColor;
    private final float mAlpha;
    private final Executor mMainExecutor;
    private final OneHandedSurfaceTransactionHelper.SurfaceControlTransactionFactory
            mSurfaceControlTransactionFactory;

    /**
     * The background to distinguish the boundary of translated windows and empty region when
     * one handed mode triggered.
     */
    private Rect mBkgBounds;
    @VisibleForTesting
    @GuardedBy("mLock")
    boolean mIsShowing;
    @Nullable
    @GuardedBy("mLock")
    private SurfaceControl mBackgroundSurface;
    @Nullable
    @GuardedBy("mLock")
    private SurfaceControl mParentLeash;

    private final OneHandedAnimationCallback mOneHandedAnimationCallback =
            new OneHandedAnimationCallback() {
                @Override
                public void onOneHandedAnimationStart(
                        OneHandedAnimationController.OneHandedTransitionAnimator animator) {
                    mMainExecutor.execute(() -> showBackgroundPanelLayer());
                }
            };

    @Override
    public void onStopFinished(Rect bounds) {
        mMainExecutor.execute(() -> removeBackgroundPanelLayer());
    }

    public OneHandedBackgroundPanelOrganizer(Context context, DisplayLayout displayLayout,
            Executor executor) {
        super(executor);
        final Resources res = context.getResources();
        final float defaultRGB = res.getFloat(R.dimen.config_one_handed_background_rgb);
        mColor = new float[]{defaultRGB, defaultRGB, defaultRGB};
        mAlpha = res.getFloat(R.dimen.config_one_handed_background_alpha);
        // Ensure the mBkgBounds is portrait, due to OHM only support on portrait
        if (displayLayout.height() > displayLayout.width()) {
            mBkgBounds = new Rect(0, 0, displayLayout.width(), displayLayout.height());
        } else {
            mBkgBounds = new Rect(0, 0, displayLayout.height(), displayLayout.width());
        }
        mMainExecutor = executor;
        mSurfaceControlTransactionFactory = SurfaceControl.Transaction::new;
    }

    @Override
    public void onDisplayAreaAppeared(@NonNull DisplayAreaInfo displayAreaInfo,
            @NonNull SurfaceControl leash) {
        synchronized (mLock) {
            if (mParentLeash == null) {
                mParentLeash = leash;
            } else {
                throw new RuntimeException("There should be only one DisplayArea for "
                        + "the one-handed mode background panel");
            }
        }
    }

    OneHandedAnimationCallback getOneHandedAnimationCallback() {
        return mOneHandedAnimationCallback;
    }

    @Override
    public List<DisplayAreaAppearedInfo> registerOrganizer(int displayAreaFeature) {
        synchronized (mLock) {
            final List<DisplayAreaAppearedInfo> displayAreaInfos;
            displayAreaInfos = super.registerOrganizer(displayAreaFeature);
            for (int i = 0; i < displayAreaInfos.size(); i++) {
                final DisplayAreaAppearedInfo info = displayAreaInfos.get(i);
                onDisplayAreaAppeared(info.getDisplayAreaInfo(), info.getLeash());
            }
            return displayAreaInfos;
        }
    }

    @Override
    public void unregisterOrganizer() {
        synchronized (mLock) {
            super.unregisterOrganizer();
            mParentLeash = null;
        }
    }

    @Nullable
    @VisibleForTesting
    SurfaceControl getBackgroundSurface() {
        synchronized (mLock) {
            if (mParentLeash == null) {
                return null;
            }

            if (mBackgroundSurface == null) {
                mBackgroundSurface = new SurfaceControl.Builder(mSurfaceSession)
                        .setParent(mParentLeash)
                        .setBufferSize(mBkgBounds.width(), mBkgBounds.height())
                        .setColorLayer()
                        .setFormat(PixelFormat.RGBA_8888)
                        .setOpaque(false)
                        .setName("one-handed-background-panel")
                        .setCallsite("OneHandedBackgroundPanelOrganizer")
                        .build();
            }
            return mBackgroundSurface;
        }
    }

    @VisibleForTesting
    void showBackgroundPanelLayer() {
        synchronized (mLock) {
            if (mIsShowing) {
                return;
            }

            if (getBackgroundSurface() == null) {
                Log.w(TAG, "mBackgroundSurface is null !");
                return;
            }

            SurfaceControl.Transaction transaction =
                    mSurfaceControlTransactionFactory.getTransaction();
            transaction.setLayer(mBackgroundSurface, -1 /* at bottom-most layer */)
                    .setColor(mBackgroundSurface, mColor)
                    .setAlpha(mBackgroundSurface, mAlpha)
                    .show(mBackgroundSurface)
                    .apply();
            transaction.close();
            mIsShowing = true;
        }
    }

    @VisibleForTesting
    void removeBackgroundPanelLayer() {
        synchronized (mLock) {
            if (mBackgroundSurface == null) {
                return;
            }

            SurfaceControl.Transaction transaction =
                    mSurfaceControlTransactionFactory.getTransaction();
            transaction.remove(mBackgroundSurface).apply();
            transaction.close();
            mBackgroundSurface = null;
            mIsShowing = false;
        }
    }

    void dump(@NonNull PrintWriter pw) {
        final String innerPrefix = "  ";
        pw.println(TAG + "states: ");
        pw.print(innerPrefix + "mIsShowing=");
        pw.println(mIsShowing);
        pw.print(innerPrefix + "mBkgBounds=");
        pw.println(mBkgBounds);
    }
}
