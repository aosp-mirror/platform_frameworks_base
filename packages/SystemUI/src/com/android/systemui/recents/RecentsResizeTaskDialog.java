/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.systemui.recents;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.FragmentManager;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import com.android.systemui.R;
import com.android.systemui.recents.misc.SystemServicesProxy;
import com.android.systemui.recents.model.RecentsTaskLoader;
import com.android.systemui.recents.model.Task;
import com.android.systemui.recents.RecentsActivity;
import com.android.systemui.recents.views.RecentsView;

/**
 * A helper for the dialogs that show when task debugging is on.
 */
public class RecentsResizeTaskDialog extends DialogFragment {

    static final String TAG = "RecentsResizeTaskDialog";

    // The various window arrangements we can handle.
    private static final int PLACE_LEFT = 1;
    private static final int PLACE_RIGHT = 2;
    private static final int PLACE_TOP = 3;
    private static final int PLACE_BOTTOM = 4;
    private static final int PLACE_FULL = 5;

    // The task we want to resize.
    private Task mTaskToResize;
    private Task mNextTaskToResize;
    private FragmentManager mFragmentManager;
    private View mResizeTaskDialogContent;
    private RecentsActivity mRecentsActivity;
    private RecentsView mRecentsView;
    private SystemServicesProxy mSsp;

    public RecentsResizeTaskDialog(FragmentManager mgr, RecentsActivity activity) {
        mFragmentManager = mgr;
        mRecentsActivity = activity;
        mSsp = RecentsTaskLoader.getInstance().getSystemServicesProxy();
    }

    /** Shows the resize-task dialog. */
    void showResizeTaskDialog(Task mainTask, RecentsView rv) {
        mTaskToResize = mainTask;
        mRecentsView = rv;
        mNextTaskToResize = mRecentsView.getNextTaskOrTopTask(mainTask);

        show(mFragmentManager, TAG);
    }

    /** Creates a new resize-task dialog. */
    private void createResizeTaskDialog(final Context context, LayoutInflater inflater,
            AlertDialog.Builder builder) {
        builder.setTitle(R.string.recents_caption_resize);
        mResizeTaskDialogContent =
                inflater.inflate(R.layout.recents_task_resize_dialog, null, false);

        ((Button)mResizeTaskDialogContent.findViewById(R.id.place_left)).setOnClickListener(
                new View.OnClickListener() {
            public void onClick(View v) {
                placeTasks(PLACE_LEFT);
            }
        });
        ((Button)mResizeTaskDialogContent.findViewById(R.id.place_right)).setOnClickListener(
                new View.OnClickListener() {
            public void onClick(View v) {
                placeTasks(PLACE_RIGHT);
            }
        });
        ((Button)mResizeTaskDialogContent.findViewById(R.id.place_top)).setOnClickListener(
                new View.OnClickListener() {
            public void onClick(View v) {
                placeTasks(PLACE_TOP);
            }
        });
        ((Button)mResizeTaskDialogContent.findViewById(R.id.place_bottom)).setOnClickListener(
                new View.OnClickListener() {
            public void onClick(View v) {
                placeTasks(PLACE_BOTTOM);
            }
        });
        ((Button)mResizeTaskDialogContent.findViewById(R.id.place_full)).setOnClickListener(
                new View.OnClickListener() {
            public void onClick(View v) {
                placeTasks(PLACE_FULL);
            }
        });

        builder.setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dismiss();
            }
        });

        builder.setView(mResizeTaskDialogContent);
    }

    /** Helper function to place window(s) on the display according to an arrangement request. */
    private void placeTasks(int arrangement) {
        Rect focusedBounds = mSsp.getWindowRect();
        Rect otherBounds = new Rect(focusedBounds);

        switch (arrangement) {
            case PLACE_LEFT:
                focusedBounds.right = focusedBounds.centerX();
                otherBounds.left = focusedBounds.right;
                break;
            case PLACE_RIGHT:
                otherBounds.right = otherBounds.centerX();
                focusedBounds.left = otherBounds.right;
                break;
            case PLACE_TOP:
                focusedBounds.bottom = focusedBounds.centerY();
                otherBounds.top = focusedBounds.bottom;
                break;
            case PLACE_BOTTOM:
                otherBounds.bottom = otherBounds.centerY();
                focusedBounds.top = otherBounds.bottom;
                break;
            case PLACE_FULL:
                // Null the rectangle to avoid the other task to show up.
                otherBounds = new Rect();
                break;
        }

        // Resize all other tasks to go to the other side.
        if (mNextTaskToResize != null && !otherBounds.isEmpty()) {
            mSsp.resizeTask(mNextTaskToResize.key.id, otherBounds);
        }
        mSsp.resizeTask(mTaskToResize.key.id, focusedBounds);

        // Get rid of the dialog.
        dismiss();
        mRecentsActivity.dismissRecentsToHomeRaw(false);

        // Show tasks - beginning with the other first so that the focus ends on the selected one.
        // TODO: Remove this once issue b/19893373 is resolved.
        if (mNextTaskToResize != null && !otherBounds.isEmpty()) {
            mRecentsView.launchTask(mNextTaskToResize);
        }
        mRecentsView.launchTask(mTaskToResize);
    }

    @Override
    public Dialog onCreateDialog(Bundle args) {
        final Context context = this.getActivity();
        LayoutInflater inflater = getActivity().getLayoutInflater();
        AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
        createResizeTaskDialog(context, inflater, builder);
        return builder.create();
    }
}
