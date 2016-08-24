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
 * limitations under the License.
 */

package com.android.documentsui;

import android.annotation.IntDef;
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
import com.android.documentsui.services.FileOperationService.OpType;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;

/**
 * Alert dialog for operation dialogs.
 */
public class OperationDialogFragment extends DialogFragment {

    public static final int DIALOG_TYPE_UNKNOWN = 0;
    public static final int DIALOG_TYPE_FAILURE = 1;
    public static final int DIALOG_TYPE_CONVERTED = 2;

    @IntDef(flag = true, value = {
        DIALOG_TYPE_UNKNOWN,
        DIALOG_TYPE_FAILURE,
        DIALOG_TYPE_CONVERTED
    })

    @Retention(RetentionPolicy.SOURCE)
    public @interface DialogType {}

    private static final String TAG = "OperationDialogFragment";

    public static void show(FragmentManager fm, @DialogType int dialogType,
            ArrayList<DocumentInfo> failedSrcList, DocumentStack dstStack,
            @OpType int operationType) {
        final Bundle args = new Bundle();
        args.putInt(FileOperationService.EXTRA_DIALOG_TYPE, dialogType);
        args.putInt(FileOperationService.EXTRA_OPERATION, operationType);
        args.putParcelableArrayList(FileOperationService.EXTRA_SRC_LIST, failedSrcList);

        final FragmentTransaction ft = fm.beginTransaction();
        final OperationDialogFragment fragment = new OperationDialogFragment();
        fragment.setArguments(args);

        ft.add(fragment, TAG);
        ft.commitAllowingStateLoss();
    }

    @Override
    public Dialog onCreateDialog(Bundle inState) {
        super.onCreate(inState);

        final @DialogType int dialogType =
              getArguments().getInt(FileOperationService.EXTRA_DIALOG_TYPE);
        final @OpType int operationType =
              getArguments().getInt(FileOperationService.EXTRA_OPERATION);
        final ArrayList<DocumentInfo> srcList = getArguments().getParcelableArrayList(
                FileOperationService.EXTRA_SRC_LIST);

        final AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
        String messageFormat;

        switch (dialogType) {
            case DIALOG_TYPE_CONVERTED:
                messageFormat = getString(R.string.copy_converted_warning_content);
                break;

            case DIALOG_TYPE_FAILURE:
                switch (operationType) {
                    case FileOperationService.OPERATION_COPY:
                        messageFormat = getString(R.string.copy_failure_alert_content);
                        break;
                    case FileOperationService.OPERATION_DELETE:
                        messageFormat = getString(R.string.delete_failure_alert_content);
                        break;
                    case FileOperationService.OPERATION_MOVE:
                        messageFormat = getString(R.string.move_failure_alert_content);
                        break;
                    default:
                        throw new UnsupportedOperationException();
                }
                break;

            default:
                throw new UnsupportedOperationException();
        }

        final StringBuilder list = new StringBuilder("<p>");
        for (DocumentInfo documentInfo : srcList) {
            list.append(String.format("&#8226; %s<br>", Html.escapeHtml(documentInfo.displayName)));
        }
        list.append("</p>");
        builder.setMessage(Html.fromHtml(String.format(messageFormat, list.toString())));
        builder.setPositiveButton(
                R.string.close,
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int id) {
                        dialog.dismiss();
                    }
                });

        return builder.create();
    }
}
