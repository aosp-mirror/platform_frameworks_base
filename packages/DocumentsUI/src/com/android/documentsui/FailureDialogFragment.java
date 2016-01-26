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

package com.android.documentsui;

import static com.android.internal.util.Preconditions.checkArgument;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.Html;

import com.android.documentsui.model.DocumentInfo;
import com.android.documentsui.model.DocumentStack;
import com.android.documentsui.services.FileOperationService;
import com.android.documentsui.services.FileOperations;

import java.util.ArrayList;
import java.util.List;

/**
 * Alert dialog for failed operations.
 */
public class FailureDialogFragment extends DialogFragment {
    private static final String TAG = "FailureDialogFragment";

    public static void show(FragmentManager fm, int failure,
            ArrayList<DocumentInfo> failedSrcList, DocumentStack dstStack, int operationType) {
        // TODO: Add support for other failures than copy.
        if (failure != FileOperationService.FAILURE_COPY) {
            return;
        }

        final Bundle args = new Bundle();
        args.putInt(FileOperationService.EXTRA_FAILURE, failure);
        args.putInt(FileOperationService.EXTRA_OPERATION, operationType);
        args.putParcelableArrayList(FileOperationService.EXTRA_SRC_LIST, failedSrcList);

        final FragmentTransaction ft = fm.beginTransaction();
        final FailureDialogFragment fragment = new FailureDialogFragment();
        fragment.setArguments(args);

        ft.add(fragment, TAG);
        ft.commitAllowingStateLoss();
    }

    @Override
    public Dialog onCreateDialog(Bundle inState) {
        super.onCreate(inState);

        final int operationType = getArguments().getInt(FileOperationService.EXTRA_OPERATION);
        final List<DocumentInfo> failedSrcList = getArguments().getParcelableArrayList(
                FileOperationService.EXTRA_SRC_LIST);

        final StringBuilder list = new StringBuilder("<p>");
        for (DocumentInfo documentInfo : failedSrcList) {
            list.append(String.format("&#8226; %s<br>", documentInfo.displayName));
        }
        list.append("</p>");

        // TODO: Add support for other file operations.
        checkArgument(
                operationType == FileOperationService.OPERATION_COPY
                || operationType == FileOperationService.OPERATION_MOVE);

        int messageId = operationType == FileOperationService.OPERATION_COPY
                ? R.string.copy_failure_alert_content
                : R.string.move_failure_alert_content;

        final String messageFormat = getString(
                messageId);

        final String message = String.format(messageFormat, list.toString());

        return new AlertDialog.Builder(getActivity())
                .setMessage(Html.fromHtml(message))
                .setPositiveButton(R.string.close, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int id) {
                        dialog.dismiss();
                    }
                })
                .create();
    }
}
