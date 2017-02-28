/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.wm;

import static android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_TASK_SNAPSHOT;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_STARTING;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.app.ActivityManager.TaskDescription;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.GraphicBuffer;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.Slog;
import android.view.IWindowSession;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import android.view.WindowManagerPolicy.StartingSurface;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.view.BaseIWindow;

/**
 * This class represents a starting window that shows a snapshot.
 * <p>
 * DO NOT HOLD THE WINDOW MANAGER LOCK WHEN CALLING METHODS OF THIS CLASS!
 */
class TaskSnapshotSurface implements StartingSurface {

    private static final String TAG = TAG_WITH_CLASS_NAME ? "SnapshotStartingWindow" : TAG_WM;
    private static final int MSG_REPORT_DRAW = 0;
    private static final String TITLE_FORMAT = "SnapshotStartingWindow for taskId=%s";
    private final Window mWindow;
    private final Surface mSurface;
    private final IWindowSession mSession;
    private final WindowManagerService mService;
    private boolean mHasDrawn;
    private boolean mReportNextDraw;
    private Paint mFillBackgroundPaint = new Paint();

    static TaskSnapshotSurface create(WindowManagerService service, AppWindowToken token,
            GraphicBuffer snapshot) {

        final WindowManager.LayoutParams layoutParams = new WindowManager.LayoutParams();
        final Window window = new Window();
        final IWindowSession session = WindowManagerGlobal.getWindowSession();
        window.setSession(session);
        final Surface surface = new Surface();
        final Rect tmpRect = new Rect();
        final Rect tmpFrame = new Rect();
        final Configuration tmpConfiguration = new Configuration();
        int fillBackgroundColor = Color.WHITE;
        synchronized (service.mWindowMap) {
            layoutParams.type = TYPE_APPLICATION_STARTING;
            layoutParams.format = snapshot.getFormat();
            layoutParams.flags = FLAG_LAYOUT_INSET_DECOR
                    | FLAG_LAYOUT_IN_SCREEN
                    | FLAG_NOT_FOCUSABLE
                    | FLAG_NOT_TOUCHABLE
                    | FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
            layoutParams.privateFlags = PRIVATE_FLAG_TASK_SNAPSHOT;
            layoutParams.token = token.token;
            layoutParams.width = LayoutParams.MATCH_PARENT;
            layoutParams.height = LayoutParams.MATCH_PARENT;

            // TODO: Inherit behavior whether to draw behind status bar/nav bar.
            layoutParams.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            final Task task = token.getTask();
            if (task != null) {
                layoutParams.setTitle(String.format(TITLE_FORMAT,task.mTaskId));

                final TaskDescription taskDescription = task.getTaskDescription();
                if (taskDescription != null) {
                    fillBackgroundColor = taskDescription.getBackgroundColor();
                }
            }
        }
        try {
            final int res = session.addToDisplay(window, window.mSeq, layoutParams,
                    View.VISIBLE, token.getDisplayContent().getDisplayId(), tmpRect, tmpRect,
                    tmpRect, null);
            if (res < 0) {
                Slog.w(TAG, "Failed to add snapshot starting window res=" + res);
                return null;
            }
        } catch (RemoteException e) {
            // Local call.
        }
        final TaskSnapshotSurface snapshotSurface = new TaskSnapshotSurface(service, window,
                surface, fillBackgroundColor);
        window.setOuter(snapshotSurface);
        try {
            session.relayout(window, window.mSeq, layoutParams, -1, -1, View.VISIBLE, 0, tmpFrame,
                    tmpRect, tmpRect, tmpRect, tmpRect, tmpRect, tmpRect, tmpConfiguration,
                    surface);
        } catch (RemoteException e) {
            // Local call.
        }
        snapshotSurface.drawSnapshot(snapshot);
        return snapshotSurface;
    }

    @VisibleForTesting
    TaskSnapshotSurface(WindowManagerService service, Window window, Surface surface,
            int fillBackgroundColor) {
        mService = service;
        mSession = WindowManagerGlobal.getWindowSession();
        mWindow = window;
        mSurface = surface;
        mFillBackgroundPaint.setColor(fillBackgroundColor);
    }

    @Override
    public void remove() {
        try {
            mSession.remove(mWindow);
        } catch (RemoteException e) {
            // Local call.
        }
    }

    private void drawSnapshot(GraphicBuffer snapshot) {

        // TODO: Just wrap the buffer here without any copying.
        final Canvas c = mSurface.lockHardwareCanvas();
        final Bitmap b = Bitmap.createHardwareBitmap(snapshot);
        fillEmptyBackground(c, b);
        c.drawBitmap(b, 0, 0, null);
        mSurface.unlockCanvasAndPost(c);
        final boolean reportNextDraw;
        synchronized (mService.mWindowMap) {
            mHasDrawn = true;
            reportNextDraw = mReportNextDraw;
        }
        if (reportNextDraw) {
            reportDrawn();
        }
        mSurface.release();
    }

    @VisibleForTesting
    void fillEmptyBackground(Canvas c, Bitmap b) {
        final boolean fillHorizontally = c.getWidth() > b.getWidth();
        final boolean fillVertically = c.getHeight() > b.getHeight();
        if (fillHorizontally) {
            c.drawRect(b.getWidth(), 0, c.getWidth(), fillVertically
                        ? b.getHeight()
                        : c.getHeight(),
                    mFillBackgroundPaint);
        }
        if (fillVertically) {
            c.drawRect(0, b.getHeight(), c.getWidth(), c.getHeight(), mFillBackgroundPaint);
        }
    }

    private void reportDrawn() {
        synchronized (mService.mWindowMap) {
            mReportNextDraw = false;
        }
        try {
            mSession.finishDrawing(mWindow);
        } catch (RemoteException e) {
            // Local call.
        }
    }

    private static Handler sHandler = new Handler(Looper.getMainLooper()) {

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_REPORT_DRAW:
                    final boolean hasDrawn;
                    final TaskSnapshotSurface surface = (TaskSnapshotSurface) msg.obj;
                    synchronized (surface.mService.mWindowMap) {
                        hasDrawn = surface.mHasDrawn;
                        if (!hasDrawn) {
                            surface.mReportNextDraw = true;
                        }
                    }
                    if (hasDrawn) {
                        surface.reportDrawn();
                    }
                    break;
            }
        }
    };

    private static class Window extends BaseIWindow {

        private TaskSnapshotSurface mOuter;

        public void setOuter(TaskSnapshotSurface outer) {
            mOuter = outer;
        }

        @Override
        public void resized(Rect frame, Rect overscanInsets, Rect contentInsets, Rect visibleInsets,
                Rect stableInsets, Rect outsets, boolean reportDraw, Configuration newConfig,
                Rect backDropFrame, boolean forceLayout, boolean alwaysConsumeNavBar,
                int displayId) {
            if (reportDraw) {
                sHandler.obtainMessage(MSG_REPORT_DRAW, mOuter).sendToTarget();
            }
        }
    }
}
