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

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.Fragment;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.content.DialogInterface;
import android.net.Uri;
import android.os.Bundle;
import android.text.Html;

import com.android.documentsui.CopyService;
import com.android.documentsui.model.DocumentInfo;
import com.android.documentsui.model.DocumentStack;

import java.io.FileNotFoundException;
import java.util.ArrayList;

/**
 * Alert dialog for failed operations.
 */
public class FailureDialogFragment extends DialogFragment
        implements DialogInterface.OnClickListener {
    private static final String TAG = "FailureDialogFragment";

    private int mFailure;
    private ArrayList<DocumentInfo> mFailedSrcList;

    public static void show(FragmentManager fm, int failure,
            ArrayList<DocumentInfo> failedSrcList, DocumentStack dstStack) {
        // TODO: Add support for other failures than copy.
        if (failure != CopyService.FAILURE_COPY) {
            return;
        }

        final Bundle args = new Bundle();
        args.putInt(CopyService.EXTRA_FAILURE, failure);
        args.putParcelableArrayList(CopyService.EXTRA_SRC_LIST, failedSrcList);

        final FragmentTransaction ft = fm.beginTransaction();
        final FailureDialogFragment fragment = new FailureDialogFragment();
        fragment.setArguments(args);

        ft.add(fragment, TAG);
        ft.commitAllowingStateLoss();
    }

    @Override
    public void onClick(DialogInterface dialog, int whichButton) {
      if (whichButton == DialogInterface.BUTTON_POSITIVE) {
          CopyService.start(getActivity(), mFailedSrcList,
                  (DocumentStack) getActivity().getIntent().getParcelableExtra(
                          CopyService.EXTRA_STACK));
      }
    }

    @Override
    public Dialog onCreateDialog(Bundle inState) {
        super.onCreate(inState);

        mFailure = getArguments().getInt(CopyService.EXTRA_FAILURE);
        mFailedSrcList = getArguments().getParcelableArrayList(CopyService.EXTRA_SRC_LIST);

        final StringBuilder list = new StringBuilder("<p>");
        for (DocumentInfo documentInfo : mFailedSrcList) {
            list.append(String.format("&#8226; %s<br>", documentInfo.displayName));
        }
        list.append("</p>");
        final String message = String.format(getString(R.string.copy_failure_alert_content),
                list.toString());

        return new AlertDialog.Builder(getActivity())
            .setMessage(Html.fromHtml(message))
            .setPositiveButton(R.string.retry, this)
            .setNegativeButton(android.R.string.cancel, this)
            .create();
    }
}
