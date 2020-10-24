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

package com.android.wm.shell.draganddrop;

import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.content.ClipDescription.MIMETYPE_APPLICATION_ACTIVITY;
import static android.view.DragEvent.ACTION_DRAG_ENDED;
import static android.view.DragEvent.ACTION_DRAG_ENTERED;
import static android.view.DragEvent.ACTION_DRAG_EXITED;
import static android.view.DragEvent.ACTION_DRAG_LOCATION;
import static android.view.DragEvent.ACTION_DRAG_STARTED;
import static android.view.DragEvent.ACTION_DROP;
import static android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
import static android.view.WindowManager.LayoutParams.MATCH_PARENT;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_INTERCEPT_GLOBAL_DRAG_AND_DROP;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_NO_MOVE_ANIMATION;
import static android.view.WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;

import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.os.Binder;
import android.util.Slog;
import android.util.SparseArray;
import android.view.DragEvent;
import android.view.LayoutInflater;
import android.view.SurfaceControl;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.android.internal.protolog.common.ProtoLog;
import com.android.wm.shell.R;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.protolog.ShellProtoLogGroup;
import com.android.wm.shell.splitscreen.SplitScreen;

import java.util.Optional;

/**
 * Handles the global drag and drop handling for the Shell.
 */
public class DragAndDropController implements DisplayController.OnDisplaysChangedListener,
        View.OnDragListener {

    private static final String TAG = DragAndDropController.class.getSimpleName();

    private final DisplayController mDisplayController;
    private SplitScreen mSplitScreen;

    private final SparseArray<PerDisplay> mDisplayDropTargets = new SparseArray<>();
    private boolean mIsHandlingDrag;
    private DragLayout mDragLayout;
    private final SurfaceControl.Transaction mTransaction = new SurfaceControl.Transaction();

    public DragAndDropController(DisplayController displayController) {
        mDisplayController = displayController;
        mDisplayController.addDisplayWindowListener(this);
    }

    public void setSplitScreenController(Optional<SplitScreen> splitscreen) {
        mSplitScreen = splitscreen.orElse(null);
    }

    @Override
    public void onDisplayAdded(int displayId) {
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_DRAG_AND_DROP, "Display added: %d", displayId);
        final Context context = mDisplayController.getDisplayContext(displayId);
        final WindowManager wm = context.getSystemService(WindowManager.class);

        // TODO(b/169894807): Figure out the right layer for this, needs to be below the task bar
        final WindowManager.LayoutParams layoutParams = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                TYPE_APPLICATION_OVERLAY,
                FLAG_NOT_FOCUSABLE | FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        layoutParams.privateFlags |= SYSTEM_FLAG_SHOW_FOR_ALL_USERS
                | PRIVATE_FLAG_INTERCEPT_GLOBAL_DRAG_AND_DROP
                | PRIVATE_FLAG_NO_MOVE_ANIMATION;
        layoutParams.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        layoutParams.setFitInsetsTypes(0);
        layoutParams.setTitle("ShellDropTarget");

        FrameLayout dropTarget = (FrameLayout) LayoutInflater.from(context).inflate(
                R.layout.global_drop_target, null);
        dropTarget.setOnDragListener(this);
        dropTarget.setVisibility(View.INVISIBLE);
        wm.addView(dropTarget, layoutParams);
        mDisplayDropTargets.put(displayId, new PerDisplay(displayId, context, wm, dropTarget));
    }

    @Override
    public void onDisplayConfigurationChanged(int displayId, Configuration newConfig) {
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_DRAG_AND_DROP, "Display changed: %d", displayId);
        final PerDisplay pd = mDisplayDropTargets.get(displayId);
        pd.dropTarget.requestApplyInsets();
    }

    @Override
    public void onDisplayRemoved(int displayId) {
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_DRAG_AND_DROP, "Display removed: %d", displayId);
        final PerDisplay pd = mDisplayDropTargets.get(displayId);
        pd.wm.removeViewImmediate(pd.dropTarget);
        mDisplayDropTargets.remove(displayId);
    }

    @Override
    public boolean onDrag(View target, DragEvent event) {
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_DRAG_AND_DROP,
                "Drag event: action=%s x=%f y=%f xOffset=%f yOffset=%f",
                DragEvent.actionToString(event.getAction()), event.getX(), event.getY(),
                event.getOffsetX(), event.getOffsetY());
        final int displayId = target.getDisplay().getDisplayId();
        final PerDisplay pd = mDisplayDropTargets.get(displayId);

        if (event.getAction() == ACTION_DRAG_STARTED) {
            final ClipDescription description = event.getClipDescription();
            final boolean hasValidClipData = description.hasMimeType(MIMETYPE_APPLICATION_ACTIVITY);
            mIsHandlingDrag = hasValidClipData;
            ProtoLog.v(ShellProtoLogGroup.WM_SHELL_DRAG_AND_DROP, "Clip description: %s",
                    getMimeTypes(description));
        }

        if (!mIsHandlingDrag) {
            return false;
        }

        switch (event.getAction()) {
            case ACTION_DRAG_STARTED:
                mDragLayout = new DragLayout(pd.context,
                        mDisplayController.getDisplayLayout(displayId), mSplitScreen);
                pd.dropTarget.addView(mDragLayout,
                        new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
                setDropTargetWindowVisibility(pd, View.VISIBLE);
                break;
            case ACTION_DRAG_ENTERED:
                mDragLayout.show(event);
                break;
            case ACTION_DRAG_LOCATION:
                mDragLayout.update(event);
                break;
            case ACTION_DROP: {
                final SurfaceControl dragSurface = event.getDragSurface();
                final View dragLayout = mDragLayout;
                final ClipData data = event.getClipData();
                return mDragLayout.drop(event, dragSurface, (dropTargetBounds) -> {
                    if (dropTargetBounds != null) {
                        // TODO(b/169894807): Properly handle the drop, for now just launch it
                        if (data.getItemCount() > 0) {
                            Intent intent = data.getItemAt(0).getIntent();
                            PendingIntent pi = intent.getParcelableExtra(
                                    ClipDescription.EXTRA_PENDING_INTENT);
                            try {
                                pi.send();
                            } catch (PendingIntent.CanceledException e) {
                                Slog.e(TAG, "Failed to launch activity", e);
                            }
                        }
                    }

                    setDropTargetWindowVisibility(pd, View.INVISIBLE);
                    pd.dropTarget.removeView(dragLayout);

                    // Clean up the drag surface
                    mTransaction.reparent(dragSurface, null);
                    mTransaction.apply();
                });
            }
            case ACTION_DRAG_EXITED: {
                // Either one of DROP or EXITED will happen, and when EXITED we won't consume
                // the drag surface
                mDragLayout.hide(event, null);
                break;
            }
            case ACTION_DRAG_ENDED:
                // TODO(b/169894807): Ensure sure it's not possible to get ENDED without DROP
                // or EXITED
                if (!mDragLayout.hasDropped()) {
                    final View dragLayout = mDragLayout;
                    mDragLayout.hide(event, () -> {
                        setDropTargetWindowVisibility(pd, View.INVISIBLE);
                        pd.dropTarget.removeView(dragLayout);
                    });
                }
                mDragLayout = null;
                break;
        }
        return true;
    }

    private void setDropTargetWindowVisibility(PerDisplay pd, int visibility) {
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_DRAG_AND_DROP,
                "Set drop target window visibility: displayId=%d visibility=%d",
                pd.displayId, visibility);
        pd.dropTarget.setVisibility(visibility);
        if (visibility == View.VISIBLE) {
            pd.dropTarget.requestApplyInsets();
        }
    }

    private String getMimeTypes(ClipDescription description) {
        String mimeTypes = "";
        for (int i = 0; i < description.getMimeTypeCount(); i++) {
            if (i > 0) {
                mimeTypes += ", ";
            }
            mimeTypes += description.getMimeType(i);
        }
        return mimeTypes;
    }

    private static class PerDisplay {
        final int displayId;
        final Context context;
        final WindowManager wm;
        final FrameLayout dropTarget;

        PerDisplay(int dispId, Context c, WindowManager w, FrameLayout l) {
            displayId = dispId;
            context = c;
            wm = w;
            dropTarget = l;
        }
    }
}
