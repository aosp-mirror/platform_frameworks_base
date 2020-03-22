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

package com.android.systemui.bubbles;

import static com.android.systemui.bubbles.BubbleDebugConfig.TAG_BUBBLES;
import static com.android.systemui.bubbles.BubbleDebugConfig.TAG_WITH_CLASS_NAME;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.app.TaskEmbedder;
import android.app.TaskOrganizerTaskEmbedder;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Region;
import android.view.IWindow;
import android.view.SurfaceControl;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import dalvik.system.CloseGuard;


public class BubbleTaskView extends SurfaceView implements SurfaceHolder.Callback,
        TaskEmbedder.Host {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "BubbleTaskView" : TAG_BUBBLES;

    private final CloseGuard mGuard = CloseGuard.get();
    private boolean mOpened; // Protected by mGuard.

    private TaskEmbedder mTaskEmbedder;
    private final SurfaceControl.Transaction mTmpTransaction = new SurfaceControl.Transaction();
    private final Rect mTmpRect = new Rect();

    public BubbleTaskView(Context context) {
        super(context);

        mTaskEmbedder = new TaskOrganizerTaskEmbedder(context, this);
        setUseAlpha();
        getHolder().addCallback(this);

        mOpened = true;
        mGuard.open("release");
    }

    public void setCallback(TaskEmbedder.Listener callback) {
        if (callback == null) {
            mTaskEmbedder.setListener(null);
            return;
        }
        mTaskEmbedder.setListener(callback);
    }

    public void startShortcutActivity(@NonNull ShortcutInfo shortcut,
            @NonNull ActivityOptions options, @Nullable Rect sourceBounds) {
        mTaskEmbedder.startShortcutActivity(shortcut, options, sourceBounds);
    }

    public void startActivity(@NonNull PendingIntent pendingIntent, @Nullable Intent fillInIntent,
            @NonNull ActivityOptions options) {
        mTaskEmbedder.startActivity(pendingIntent, fillInIntent, options);
    }

    public void onLocationChanged() {
        mTaskEmbedder.notifyBoundsChanged();
    }

    @Override
    public Rect getScreenBounds() {
        getBoundsOnScreen(mTmpRect);
        return mTmpRect;
    }

    @Override
    public void onTaskBackgroundColorChanged(TaskEmbedder ts, int bgColor) {
        setResizeBackgroundColor(bgColor);
    }

    @Override
    public Region getTapExcludeRegion() {
        // Not used
        return null;
    }

    @Override
    public Matrix getScreenToTaskMatrix() {
        // Not used
        return null;
    }

    @Override
    public IWindow getWindow() {
        // Not used
        return null;
    }

    @Override
    public Point getPositionInWindow() {
        // Not used
        return null;
    }

    @Override
    public boolean canReceivePointerEvents() {
        // Not used
        return false;
    }

    public void release() {
        if (!mTaskEmbedder.isInitialized()) {
            throw new IllegalStateException(
                    "Trying to release container that is not initialized.");
        }
        performRelease();
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

    private void performRelease() {
        if (!mOpened) {
            return;
        }
        getHolder().removeCallback(this);
        mTaskEmbedder.release();
        mTaskEmbedder.setListener(null);

        mGuard.close();
        mOpened = false;
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if (!mTaskEmbedder.isInitialized()) {
            mTaskEmbedder.initialize(getSurfaceControl());
        } else {
            mTmpTransaction.reparent(mTaskEmbedder.getSurfaceControl(),
                    getSurfaceControl()).apply();
        }
        mTaskEmbedder.start();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        mTaskEmbedder.resizeTask(width, height);
        mTaskEmbedder.notifyBoundsChanged();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        mTaskEmbedder.stop();
    }
}
