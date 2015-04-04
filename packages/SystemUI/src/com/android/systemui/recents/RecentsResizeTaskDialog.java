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
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import com.android.systemui.R;
import com.android.systemui.recents.misc.SystemServicesProxy;
import com.android.systemui.recents.model.RecentsTaskLoader;
import com.android.systemui.recents.model.Task;
import com.android.systemui.recents.RecentsActivity;
import com.android.systemui.recents.views.RecentsView;

import java.util.ArrayList;

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
    private static final int PLACE_TOP_LEFT = 5;
    private static final int PLACE_TOP_RIGHT = 6;
    private static final int PLACE_BOTTOM_LEFT = 7;
    private static final int PLACE_BOTTOM_RIGHT = 8;
    private static final int PLACE_FULL = 9;

    // The button resource ID combined with the arrangement command.
    private static final int[][] BUTTON_DEFINITIONS =
           {{R.id.place_left, PLACE_LEFT},
            {R.id.place_right, PLACE_RIGHT},
            {R.id.place_top, PLACE_TOP},
            {R.id.place_bottom, PLACE_BOTTOM},
            {R.id.place_top_left, PLACE_TOP_LEFT},
            {R.id.place_top_right, PLACE_TOP_RIGHT},
            {R.id.place_bottom_left, PLACE_BOTTOM_LEFT},
            {R.id.place_bottom_right, PLACE_BOTTOM_RIGHT},
            {R.id.place_full, PLACE_FULL}};

    // The task we want to resize.
    private FragmentManager mFragmentManager;
    private View mResizeTaskDialogContent;
    private RecentsActivity mRecentsActivity;
    private RecentsView mRecentsView;
    private SystemServicesProxy mSsp;
    private Rect[] mBounds = {new Rect(), new Rect(), new Rect(), new Rect()};
    private Task[] mTasks = {null, null, null, null};

    public RecentsResizeTaskDialog(FragmentManager mgr, RecentsActivity activity) {
        mFragmentManager = mgr;
        mRecentsActivity = activity;
        mSsp = RecentsTaskLoader.getInstance().getSystemServicesProxy();
    }

    /** Shows the resize-task dialog. */
    void showResizeTaskDialog(Task mainTask, RecentsView rv) {
        mTasks[0] = mainTask;
        mRecentsView = rv;

        show(mFragmentManager, TAG);
    }

    /** Creates a new resize-task dialog. */
    private void createResizeTaskDialog(final Context context, LayoutInflater inflater,
            AlertDialog.Builder builder) {
        builder.setTitle(R.string.recents_caption_resize);
        mResizeTaskDialogContent =
                inflater.inflate(R.layout.recents_task_resize_dialog, null, false);

        for (int i = 0; i < BUTTON_DEFINITIONS.length; i++) {
            Button b = (Button)mResizeTaskDialogContent.findViewById(BUTTON_DEFINITIONS[i][0]);
            if (b != null) {
                final int action = BUTTON_DEFINITIONS[i][1];
                b.setOnClickListener(
                        new View.OnClickListener() {
                            public void onClick(View v) {
                                placeTasks(action);
                            }
                        });
            }
        }

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
        Rect rect = mSsp.getWindowRect();
        for (int i = 0; i < mBounds.length; ++i) {
            mBounds[i].set(rect);
            if (i != 0) {
                mTasks[i] = null;
            }
        }
        int additionalTasks = 0;
        switch (arrangement) {
            case PLACE_LEFT:
                mBounds[0].right = mBounds[0].centerX();
                mBounds[1].left = mBounds[0].right;
                additionalTasks = 1;
                break;
            case PLACE_RIGHT:
                mBounds[1].right = mBounds[1].centerX();
                mBounds[0].left = mBounds[1].right;
                additionalTasks = 1;
                break;
            case PLACE_TOP:
                mBounds[0].bottom = mBounds[0].centerY();
                mBounds[1].top = mBounds[0].bottom;
                additionalTasks = 1;
                break;
            case PLACE_BOTTOM:
                mBounds[1].bottom = mBounds[1].centerY();
                mBounds[0].top = mBounds[1].bottom;
                additionalTasks = 1;
                break;
            case PLACE_TOP_LEFT:  // TL, TR, BL, BR
                mBounds[0].right = mBounds[0].centerX();
                mBounds[0].bottom = mBounds[0].centerY();
                mBounds[1].left = mBounds[0].right;
                mBounds[1].bottom = mBounds[0].bottom;
                mBounds[2].right = mBounds[0].right;
                mBounds[2].top = mBounds[0].bottom;
                mBounds[3].left = mBounds[0].right;
                mBounds[3].top = mBounds[0].bottom;
                additionalTasks = 3;
                break;
            case PLACE_TOP_RIGHT:  // TR, TL, BR, BL
                mBounds[0].left = mBounds[0].centerX();
                mBounds[0].bottom = mBounds[0].centerY();
                mBounds[1].right = mBounds[0].left;
                mBounds[1].bottom = mBounds[0].bottom;
                mBounds[2].left = mBounds[0].left;
                mBounds[2].top = mBounds[0].bottom;
                mBounds[3].right = mBounds[0].left;
                mBounds[3].top = mBounds[0].bottom;
                additionalTasks = 3;
                break;
            case PLACE_BOTTOM_LEFT:  // BL, BR, TL, TR
                mBounds[0].right = mBounds[0].centerX();
                mBounds[0].top = mBounds[0].centerY();
                mBounds[1].left = mBounds[0].right;
                mBounds[1].top = mBounds[0].top;
                mBounds[2].right = mBounds[0].right;
                mBounds[2].bottom = mBounds[0].top;
                mBounds[3].left = mBounds[0].right;
                mBounds[3].bottom = mBounds[0].top;
                additionalTasks = 3;
                break;
            case PLACE_BOTTOM_RIGHT:  // BR, BL, TR, TL
                mBounds[0].left = mBounds[0].centerX();
                mBounds[0].top = mBounds[0].centerY();
                mBounds[1].right = mBounds[0].left;
                mBounds[1].top = mBounds[0].top;
                mBounds[2].left = mBounds[0].left;
                mBounds[2].bottom = mBounds[0].top;
                mBounds[3].right = mBounds[0].left;
                mBounds[3].bottom = mBounds[0].top;
                additionalTasks = 3;
                break;
            case PLACE_FULL:
                // Nothing to change.
                break;
        }

        // Get the other tasks.
        for (int i = 1; i <= additionalTasks && mTasks[i - 1] != null; ++i) {
            mTasks[i] = mRecentsView.getNextTaskOrTopTask(mTasks[i - 1]);
            // Do stop if we circled back to the first item.
            if (mTasks[i] == mTasks[0]) {
                mTasks[i] = null;
            }
        }

        // Get rid of the dialog.
        dismiss();
        mRecentsActivity.dismissRecentsToHomeWithoutTransitionAnimation();

        // Resize all tasks beginning from the "oldest" one.
        for (int i = additionalTasks; i >= 0; --i) {
            if (mTasks[i] != null) {
               mSsp.resizeTask(mTasks[i].key.id, mBounds[i]);
            }
        }

        // Show tasks as they might not be currently visible - beginning with the oldest so that
        // the focus ends on the selected one.
        for (int i = additionalTasks; i >= 0; --i) {
            if (mTasks[i] != null) {
                mRecentsView.launchTask(mTasks[i]);
            }
        }
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
