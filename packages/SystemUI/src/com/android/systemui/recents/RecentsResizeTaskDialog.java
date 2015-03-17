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

import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.FragmentManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.graphics.Rect;
import android.os.Bundle;
import android.util.MutableInt;
import android.util.SparseArray;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;
import com.android.systemui.R;
import com.android.systemui.recents.misc.SystemServicesProxy;
import com.android.systemui.recents.model.RecentsTaskLoader;
import com.android.systemui.recents.model.Task;

import java.util.List;

/**
 * A helper for the dialogs that show when task debugging is on.
 */
public class RecentsResizeTaskDialog extends DialogFragment {

    static final String TAG = "RecentsResizeTaskDialog";

    // The task we want to resize.
    Task mTaskToResize;
    FragmentManager mFragmentManager;
    View mResizeTaskDialogContent;

    public RecentsResizeTaskDialog() {}

    public RecentsResizeTaskDialog(FragmentManager mgr) {
        mFragmentManager = mgr;
    }

    /** Shows the resize-task dialog. */
    void showResizeTaskDialog(Task t) {
        mTaskToResize = t;
        show(mFragmentManager, TAG);
    }

    /** Creates a new resize-task dialog. */
    private void createResizeTaskDialog(final Context context, LayoutInflater inflater,
            AlertDialog.Builder builder, final SystemServicesProxy ssp) {
        builder.setTitle("Resize Task - Enter new dimensions");
        mResizeTaskDialogContent =
                inflater.inflate(R.layout.recents_multistack_stack_size_dialog, null, false);
        Rect bounds = ssp.getTaskBounds(mTaskToResize.key.stackId);
        setDimensionInEditText(mResizeTaskDialogContent, R.id.inset_left, bounds.left);
        setDimensionInEditText(mResizeTaskDialogContent, R.id.inset_top, bounds.top);
        setDimensionInEditText(mResizeTaskDialogContent, R.id.inset_right, bounds.right);
        setDimensionInEditText(mResizeTaskDialogContent, R.id.inset_bottom, bounds.bottom);
        builder.setView(mResizeTaskDialogContent);
        builder.setPositiveButton("Resize Task", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                int left = getDimensionFromEditText(mResizeTaskDialogContent, R.id.inset_left);
                int top = getDimensionFromEditText(mResizeTaskDialogContent, R.id.inset_top);
                int right = getDimensionFromEditText(mResizeTaskDialogContent, R.id.inset_right);
                int bottom = getDimensionFromEditText(mResizeTaskDialogContent, R.id.inset_bottom);
                if (bottom <= top || right <= left) {
                    Toast.makeText(context, "Invalid dimensions", Toast.LENGTH_SHORT).show();
                    dismiss();
                    return;
                }
                ssp.resizeTask(mTaskToResize.key.id, new Rect(left, top, right, bottom));
                dismiss();
            }
        });
        builder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dismiss();
            }
        });
    }

    /** Helper to get an integer value from an edit text. */
    private int getDimensionFromEditText(View container, int id) {
        String text = ((EditText) container.findViewById(id)).getText().toString();
        if (text.trim().length() != 0) {
            return Integer.parseInt(text.trim());
        }
        return 0;
    }

    /** Helper to set an integer value to an edit text. */
    private void setDimensionInEditText(View container, int id, int value) {
        ((EditText) container.findViewById(id)).setText("" + value);
    }

    @Override
    public Dialog onCreateDialog(Bundle args) {
        final Context context = this.getActivity();
        final SystemServicesProxy ssp = RecentsTaskLoader.getInstance().getSystemServicesProxy();
        LayoutInflater inflater = getActivity().getLayoutInflater();
        AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
        createResizeTaskDialog(context, inflater, builder, ssp);
        return builder.create();
    }
}
