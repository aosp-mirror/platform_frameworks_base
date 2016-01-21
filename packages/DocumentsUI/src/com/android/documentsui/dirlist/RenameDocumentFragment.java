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

package com.android.documentsui.dirlist;

import static com.android.documentsui.Shared.TAG;
import static com.android.internal.util.Preconditions.checkArgument;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.FragmentManager;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.support.annotation.Nullable;
import android.support.design.widget.Snackbar;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.TextView.OnEditorActionListener;

import com.android.documentsui.BaseActivity;
import com.android.documentsui.DocumentsApplication;
import com.android.documentsui.model.DocumentInfo;
import com.android.documentsui.R;
import com.android.documentsui.Snackbars;
/**
 * Dialog to rename file or directory.
 */
class RenameDocumentFragment extends DialogFragment {
    private static final String TAG_RENAME_DOCUMENT = "rename_document";
    private DocumentInfo mDocument;

    public static void show(FragmentManager fm, DocumentInfo document) {
        final RenameDocumentFragment dialog = new RenameDocumentFragment();
        dialog.mDocument = document;
        dialog.show(fm, TAG_RENAME_DOCUMENT);
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        Context context = getActivity();
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        LayoutInflater dialogInflater = LayoutInflater.from(builder.getContext());
        View view = dialogInflater.inflate(R.layout.dialog_file_name, null, false);

        final EditText editText = (EditText) view.findViewById(android.R.id.text1);
        editText.setText(mDocument.displayName);

        builder.setTitle(R.string.menu_rename);
        builder.setView(view);

        builder.setPositiveButton(
                android.R.string.ok,
                new OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        renameDocuments(editText.getText().toString());
                    }
                });

        builder.setNegativeButton(android.R.string.cancel, null);

        final AlertDialog dialog = builder.create();

        editText.setOnEditorActionListener(
                new OnEditorActionListener() {
                    @Override
                    public boolean onEditorAction(
                            TextView view, int actionId, @Nullable KeyEvent event) {
                        if (event != null
                                && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                                && event.hasNoModifiers()) {
                            renameDocuments(editText.getText().toString());
                            dialog.dismiss();
                            return true;
                        }
                        return false;
                    }
                });

        return dialog;
    }

    private void renameDocuments(String newDisplayName) {
        BaseActivity activity = (BaseActivity) getActivity();

        new RenameDocumentsTask(activity, newDisplayName).execute(mDocument);
    }

    private class RenameDocumentsTask extends AsyncTask<DocumentInfo, Void, DocumentInfo> {
        private final BaseActivity mActivity;
        private final String mNewDisplayName;

        public RenameDocumentsTask(BaseActivity activity, String newDisplayName) {
            mActivity = activity;
            mNewDisplayName = newDisplayName;
        }

        @Override
        protected void onPreExecute() {
            mActivity.setPending(true);
        }

        @Override
        protected DocumentInfo doInBackground(DocumentInfo... document) {
            checkArgument(document.length == 1);
            final ContentResolver resolver = mActivity.getContentResolver();
            ContentProviderClient client = null;

            try {
                client = DocumentsApplication.acquireUnstableProviderOrThrow(
                        resolver, document[0].derivedUri.getAuthority());
                Uri newUri = DocumentsContract.renameDocument(
                        client, document[0].derivedUri, mNewDisplayName);
                return DocumentInfo.fromUri(resolver, newUri);
            } catch (Exception e) {
                Log.w(TAG, "Failed to rename file", e);
                return null;
            } finally {
                ContentProviderClient.releaseQuietly(client);
            }
        }

        @Override
        protected void onPostExecute(DocumentInfo result) {
            if (result == null) {
                Snackbars.makeSnackbar(mActivity, R.string.rename_error, Snackbar.LENGTH_SHORT)
                        .show();
            }

            mActivity.setPending(false);
        }
    }
}
