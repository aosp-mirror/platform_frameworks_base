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

import static android.content.ClipDescription.MIMETYPE_APPLICATION_ACTIVITY;
import static android.content.ClipDescription.MIMETYPE_APPLICATION_SHORTCUT;
import static android.content.ClipDescription.MIMETYPE_APPLICATION_TASK;
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

import android.content.ClipDescription;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
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
import com.android.wm.shell.splitscreen.SplitScreenController;

import java.util.Optional;

/**
 * Handles the global drag and drop handling for the Shell.
 */
public class DragAndDropController implements DisplayController.OnDisplaysChangedListener,
        View.OnDragListener {

    private static final String TAG = DragAndDropController.class.getSimpleName();

    private final Context mContext;
    private final DisplayController mDisplayController;
    private SplitScreenController mSplitScreen;

    private final SparseArray<PerDisplay> mDisplayDropTargets = new SparseArray<>();
    private final SurfaceControl.Transaction mTransaction = new SurfaceControl.Transaction();

    public DragAndDropController(Context context, DisplayController displayController) {
        mContext = context;
        mDisplayController = displayController;
    }

    public void initialize(Optional<SplitScreenController> splitscreen) {
        mSplitScreen = splitscreen.orElse(null);
        mDisplayController.addDisplayWindowListener(this);
    }

    @Override
    public void onDisplayAdded(int displayId) {
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_DRAG_AND_DROP, "Display added: %d", displayId);
        final Context context = mDisplayController.getDisplayContext(displayId)
                .createWindowContext(TYPE_APPLICATION_OVERLAY, null);
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

        FrameLayout rootView = (FrameLayout) LayoutInflater.from(context).inflate(
                R.layout.global_drop_target, null);
        rootView.setOnDragListener(this);
        rootView.setVisibility(View.INVISIBLE);
        DragLayout dragLayout = new DragLayout(context, mSplitScreen);
        rootView.addView(dragLayout,
                new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        try {
            wm.addView(rootView, layoutParams);
            mDisplayDropTargets.put(displayId,
                    new PerDisplay(displayId, context, wm, rootView, dragLayout));
        } catch (WindowManager.InvalidDisplayException e) {
            Slog.w(TAG, "Unable to add view for display id: " + displayId);
        }
    }

    @Override
    public void onDisplayConfigurationChanged(int displayId, Configuration newConfig) {
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_DRAG_AND_DROP, "Display changed: %d", displayId);
        final PerDisplay pd = mDisplayDropTargets.get(displayId);
        if (pd == null) {
            return;
        }
        pd.rootView.requestApplyInsets();
    }

    @Override
    public void onDisplayRemoved(int displayId) {
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_DRAG_AND_DROP, "Display removed: %d", displayId);
        final PerDisplay pd = mDisplayDropTargets.get(displayId);
        if (pd == null) {
            return;
        }
        pd.wm.removeViewImmediate(pd.rootView);
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
        final ClipDescription description = event.getClipDescription();

        if (pd == null) {
            return false;
        }

        if (event.getAction() == ACTION_DRAG_STARTED) {
            final boolean hasValidClipData = event.getClipData().getItemCount() > 0
                    && (description.hasMimeType(MIMETYPE_APPLICATION_ACTIVITY)
                            || description.hasMimeType(MIMETYPE_APPLICATION_SHORTCUT)
                            || description.hasMimeType(MIMETYPE_APPLICATION_TASK));
            pd.isHandlingDrag = hasValidClipData;
            ProtoLog.v(ShellProtoLogGroup.WM_SHELL_DRAG_AND_DROP,
                    "Clip description: handlingDrag=%b itemCount=%d mimeTypes=%s",
                    pd.isHandlingDrag, event.getClipData().getItemCount(),
                    getMimeTypes(description));
        }

        if (!pd.isHandlingDrag) {
            return false;
        }

        switch (event.getAction()) {
            case ACTION_DRAG_STARTED:
                if (pd.activeDragCount != 0) {
                    Slog.w(TAG, "Unexpected drag start during an active drag");
                    return false;
                }
                pd.activeDragCount++;
                pd.dragLayout.prepare(mDisplayController.getDisplayLayout(displayId),
                        event.getClipData());
                setDropTargetWindowVisibility(pd, View.VISIBLE);
                break;
            case ACTION_DRAG_ENTERED:
                pd.dragLayout.show();
                break;
            case ACTION_DRAG_LOCATION:
                pd.dragLayout.update(event);
                break;
            case ACTION_DROP: {
                return handleDrop(event, pd);
            }
            case ACTION_DRAG_EXITED: {
                // Either one of DROP or EXITED will happen, and when EXITED we won't consume
                // the drag surface
                pd.dragLayout.hide(event, null);
                break;
            }
            case ACTION_DRAG_ENDED:
                // TODO(b/169894807): Ensure sure it's not possible to get ENDED without DROP
                // or EXITED
                if (!pd.dragLayout.hasDropped()) {
                    pd.activeDragCount--;
                    pd.dragLayout.hide(event, () -> {
                        if (pd.activeDragCount == 0) {
                            // Hide the window if another drag hasn't been started while animating
                            // the drag-end
                            setDropTargetWindowVisibility(pd, View.INVISIBLE);
                        }
                    });
                }
                break;
        }
        return true;
    }

    /**
     * Handles dropping on the drop target.
     */
    private boolean handleDrop(DragEvent event, PerDisplay pd) {
        final SurfaceControl dragSurface = event.getDragSurface();
        pd.activeDragCount--;
        return pd.dragLayout.drop(event, dragSurface, () -> {
            if (pd.activeDragCount == 0) {
                // Hide the window if another drag hasn't been started while animating the drop
                setDropTargetWindowVisibility(pd, View.INVISIBLE);
            }

            // Clean up the drag surface
            mTransaction.reparent(dragSurface, null);
            mTransaction.apply();
        });
    }

    private void setDropTargetWindowVisibility(PerDisplay pd, int visibility) {
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_DRAG_AND_DROP,
                "Set drop target window visibility: displayId=%d visibility=%d",
                pd.displayId, visibility);
        pd.rootView.setVisibility(visibility);
        if (visibility == View.VISIBLE) {
            pd.rootView.requestApplyInsets();
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
        final FrameLayout rootView;
        final DragLayout dragLayout;

        boolean isHandlingDrag;
        // A count of the number of active drags in progress to ensure that we only hide the window
        // when all the drag animations have completed
        int activeDragCount;

        PerDisplay(int dispId, Context c, WindowManager w, FrameLayout rv, DragLayout dl) {
            displayId = dispId;
            context = c;
            wm = w;
            rootView = rv;
            dragLayout = dl;
        }
    }
}
