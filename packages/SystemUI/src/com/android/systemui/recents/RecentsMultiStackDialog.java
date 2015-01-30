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
 * A helper for the dialogs that show when multistack debugging is on.
 */
public class RecentsMultiStackDialog extends DialogFragment {

    static final String TAG = "RecentsMultiStackDialog";

    public static final int ADD_STACK_DIALOG = 0;
    public static final int ADD_STACK_PICK_APP_DIALOG = 1;
    public static final int REMOVE_STACK_DIALOG = 2;
    public static final int RESIZE_STACK_DIALOG = 3;
    public static final int RESIZE_STACK_PICK_STACK_DIALOG = 4;
    public static final int MOVE_TASK_DIALOG = 5;

    FragmentManager mFragmentManager;
    int mCurrentDialogType;
    MutableInt mTargetStackIndex = new MutableInt(0);
    Task mTaskToMove;
    SparseArray<ActivityManager.StackInfo> mStacks;
    List<ResolveInfo> mLauncherActivities;
    Rect mAddStackRect;
    Intent mAddStackIntent;

    View mAddStackDialogContent;

    public RecentsMultiStackDialog() {}

    public RecentsMultiStackDialog(FragmentManager mgr) {
        mFragmentManager = mgr;
    }

    /** Shows the add-stack dialog. */
    void showAddStackDialog() {
        mCurrentDialogType = ADD_STACK_DIALOG;
        show(mFragmentManager, TAG);
    }

    /** Creates a new add-stack dialog. */
    private void createAddStackDialog(final Context context, LayoutInflater inflater,
            AlertDialog.Builder builder, final SystemServicesProxy ssp) {
        builder.setTitle("Add Stack - Enter new dimensions");
        mAddStackDialogContent =
                inflater.inflate(R.layout.recents_multistack_stack_size_dialog, null, false);
        Rect windowRect = ssp.getWindowRect();
        setDimensionInEditText(mAddStackDialogContent, R.id.inset_left, windowRect.left);
        setDimensionInEditText(mAddStackDialogContent, R.id.inset_top, windowRect.top);
        setDimensionInEditText(mAddStackDialogContent, R.id.inset_right, windowRect.right);
        setDimensionInEditText(mAddStackDialogContent, R.id.inset_bottom, windowRect.bottom);
        builder.setView(mAddStackDialogContent);
        builder.setPositiveButton("Add Stack", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                int left = getDimensionFromEditText(mAddStackDialogContent, R.id.inset_left);
                int top = getDimensionFromEditText(mAddStackDialogContent, R.id.inset_top);
                int right = getDimensionFromEditText(mAddStackDialogContent, R.id.inset_right);
                int bottom = getDimensionFromEditText(mAddStackDialogContent, R.id.inset_bottom);
                if (bottom <= top || right <= left) {
                    Toast.makeText(context, "Invalid dimensions", Toast.LENGTH_SHORT).show();
                    dismiss();
                    return;
                }

                // Prompt the user for the app to start
                dismiss();
                mCurrentDialogType = ADD_STACK_PICK_APP_DIALOG;
                mAddStackRect = new Rect(left, top, right, bottom);
                show(mFragmentManager, TAG);
            }
        });
        builder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dismiss();
            }
        });
    }

    /** Creates a new add-stack pick-app dialog. */
    private void createAddStackPickAppDialog(final Context context, LayoutInflater inflater,
            AlertDialog.Builder builder, final SystemServicesProxy ssp) {
        mLauncherActivities = ssp.getLauncherApps();
        mAddStackIntent = null;
        int activityCount = mLauncherActivities.size();
        CharSequence[] activityNames = new CharSequence[activityCount];
        for (int i = 0; i < activityCount; i++) {
            activityNames[i] = ssp.getActivityLabel(mLauncherActivities.get(i).activityInfo);
        }
        builder.setTitle("Add Stack - Pick starting app");
        builder.setSingleChoiceItems(activityNames, -1,
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        ActivityInfo ai = mLauncherActivities.get(which).activityInfo;
                        mAddStackIntent = new Intent(Intent.ACTION_MAIN);
                        mAddStackIntent.addCategory(Intent.CATEGORY_LAUNCHER);
                        mAddStackIntent.setComponent(new ComponentName(ai.packageName, ai.name));
                    }
                });
        builder.setPositiveButton("Add Stack", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                // Display 0 = default display
                ssp.createNewStack(0, mAddStackRect, mAddStackIntent);
            }
        });
        builder.setNegativeButton("Skip", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                // Display 0 = default display
                ssp.createNewStack(0, mAddStackRect, null);
            }
        });
    }

    /** Shows the resize-stack dialog. */
    void showResizeStackDialog() {
        mCurrentDialogType = RESIZE_STACK_PICK_STACK_DIALOG;
        show(mFragmentManager, TAG);
    }

    /** Creates a new resize-stack pick-stack dialog. */
    private void createResizeStackPickStackDialog(final Context context, LayoutInflater inflater,
            AlertDialog.Builder builder, final SystemServicesProxy ssp) {
        mStacks = ssp.getAllStackInfos();
        mTargetStackIndex.value = -1;
        CharSequence[] stackNames = getAllStacksDescriptions(mStacks, -1, null);
        builder.setTitle("Resize Stack - Pick stack");
        builder.setSingleChoiceItems(stackNames, mTargetStackIndex.value,
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        mTargetStackIndex.value = which;
                    }
                });
        builder.setPositiveButton("Resize Stack", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                if (mTargetStackIndex.value != -1) {
                    // Prompt the user for the new dimensions
                    dismiss();
                    mCurrentDialogType = RESIZE_STACK_DIALOG;
                    show(mFragmentManager, TAG);
                }
            }
        });
        builder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dismiss();
            }
        });
    }

    /** Creates a new resize-stack dialog. */
    private void createResizeStackDialog(final Context context, LayoutInflater inflater,
            AlertDialog.Builder builder, final SystemServicesProxy ssp) {
        builder.setTitle("Resize Stack - Enter new dimensions");
        final ActivityManager.StackInfo stack = mStacks.valueAt(mTargetStackIndex.value);
        mAddStackDialogContent =
                inflater.inflate(R.layout.recents_multistack_stack_size_dialog, null, false);
        setDimensionInEditText(mAddStackDialogContent, R.id.inset_left, stack.bounds.left);
        setDimensionInEditText(mAddStackDialogContent, R.id.inset_top, stack.bounds.top);
        setDimensionInEditText(mAddStackDialogContent, R.id.inset_right, stack.bounds.right);
        setDimensionInEditText(mAddStackDialogContent, R.id.inset_bottom, stack.bounds.bottom);
        builder.setView(mAddStackDialogContent);
        builder.setPositiveButton("Resize Stack", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                int left = getDimensionFromEditText(mAddStackDialogContent, R.id.inset_left);
                int top = getDimensionFromEditText(mAddStackDialogContent, R.id.inset_top);
                int right = getDimensionFromEditText(mAddStackDialogContent, R.id.inset_right);
                int bottom = getDimensionFromEditText(mAddStackDialogContent, R.id.inset_bottom);
                if (bottom <= top || right <= left) {
                    Toast.makeText(context, "Invalid dimensions", Toast.LENGTH_SHORT).show();
                    dismiss();
                    return;
                }
                ssp.resizeStack(stack.stackId, new Rect(left, top, right, bottom));
            }
        });
        builder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dismiss();
            }
        });
    }

    /** Shows the remove-stack dialog. */
    void showRemoveStackDialog() {
        mCurrentDialogType = REMOVE_STACK_DIALOG;
        show(mFragmentManager, TAG);
    }

    /** Shows the move-task dialog. */
    void showMoveTaskDialog(Task task) {
        mCurrentDialogType = MOVE_TASK_DIALOG;
        mTaskToMove = task;
        show(mFragmentManager, TAG);
    }

    /** Creates a new move-stack dialog. */
    private void createMoveTaskDialog(final Context context, LayoutInflater inflater,
                                AlertDialog.Builder builder, final SystemServicesProxy ssp) {
        mStacks = ssp.getAllStackInfos();
        mTargetStackIndex.value = -1;
        CharSequence[] stackNames = getAllStacksDescriptions(mStacks, mTaskToMove.key.stackId,
                mTargetStackIndex);
        builder.setTitle("Move Task to Stack");
        builder.setSingleChoiceItems(stackNames, mTargetStackIndex.value,
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        mTargetStackIndex.value = which;
                    }
                });
        builder.setPositiveButton("Move Task", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                if (mTargetStackIndex.value != -1) {
                    ActivityManager.StackInfo toStack = mStacks.valueAt(mTargetStackIndex.value);
                    if (toStack.stackId != mTaskToMove.key.stackId) {
                        ssp.moveTaskToStack(mTaskToMove.key.id, toStack.stackId, true);
                        mTaskToMove.setStackId(toStack.stackId);
                    }
                }
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

    /** Gets a list of all the stacks. */
    private CharSequence[] getAllStacksDescriptions(SparseArray<ActivityManager.StackInfo> stacks,
            int targetStackId, MutableInt indexOfTargetStackId) {
        int stackCount = stacks.size();
        CharSequence[] stackNames = new CharSequence[stackCount];
        for (int i = 0; i < stackCount; i++) {
            ActivityManager.StackInfo stack = stacks.valueAt(i);
            Rect b = stack.bounds;
            String desc = "Stack " + stack.stackId + " / " +
                    "" + (stack.taskIds.length > 0 ? stack.taskIds.length : "No") + " tasks\n" +
                    "(" + b.left + ", " + b.top + ")-(" + b.right + ", " + b.bottom + ")\n";
            stackNames[i] = desc;
            if (targetStackId != -1 && stack.stackId == targetStackId) {
                indexOfTargetStackId.value = i;
            }
        }
        return stackNames;
    }

    @Override
    public Dialog onCreateDialog(Bundle args) {
        final Context context = this.getActivity();
        final SystemServicesProxy ssp = RecentsTaskLoader.getInstance().getSystemServicesProxy();
        LayoutInflater inflater = getActivity().getLayoutInflater();
        AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
        switch(mCurrentDialogType) {
            case ADD_STACK_DIALOG:
                createAddStackDialog(context, inflater, builder, ssp);
                break;
            case ADD_STACK_PICK_APP_DIALOG:
                createAddStackPickAppDialog(context, inflater, builder, ssp);
                break;
            case MOVE_TASK_DIALOG:
                createMoveTaskDialog(context, inflater, builder, ssp);
                break;
            case RESIZE_STACK_PICK_STACK_DIALOG:
                createResizeStackPickStackDialog(context, inflater, builder, ssp);
                break;
            case RESIZE_STACK_DIALOG:
                createResizeStackDialog(context, inflater, builder, ssp);
                break;
        }
        return builder.create();
    }
}
