/*
 * Copyright (C) 2013 The Android Open Source Project
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

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.FragmentManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.DocumentColumns;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import com.android.documentsui.model.Document;

import java.io.FileNotFoundException;

/**
 * Dialog to create a new directory.
 */
public class CreateDirectoryFragment extends DialogFragment {
    private static final String TAG_CREATE_DIRECTORY = "create_directory";

    public static void show(FragmentManager fm) {
        final CreateDirectoryFragment dialog = new CreateDirectoryFragment();
        dialog.show(fm, TAG_CREATE_DIRECTORY);
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        final Context context = getActivity();
        final ContentResolver resolver = context.getContentResolver();

        final AlertDialog.Builder builder = new AlertDialog.Builder(context);
        final LayoutInflater dialogInflater = LayoutInflater.from(builder.getContext());

        final View view = dialogInflater.inflate(R.layout.dialog_create_dir, null, false);
        final EditText text1 = (EditText)view.findViewById(android.R.id.text1);

        builder.setTitle(R.string.menu_create_dir);
        builder.setView(view);

        builder.setPositiveButton(android.R.string.ok, new OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                final String displayName = text1.getText().toString();

                final ContentValues values = new ContentValues();
                values.put(DocumentColumns.MIME_TYPE, DocumentsContract.MIME_TYPE_DIRECTORY);
                values.put(DocumentColumns.DISPLAY_NAME, displayName);

                final DocumentsActivity activity = (DocumentsActivity) getActivity();
                final Document cwd = activity.getCurrentDirectory();

                Uri childUri = resolver.insert(cwd.uri, values);
                try {
                    // Navigate into newly created child
                    final Document childDoc = Document.fromUri(resolver, childUri);
                    activity.onDocumentPicked(childDoc);
                } catch (FileNotFoundException e) {
                    childUri = null;
                }

                if (childUri == null) {
                    Toast.makeText(context, R.string.save_error, Toast.LENGTH_SHORT).show();
                }
            }
        });
        builder.setNegativeButton(android.R.string.cancel, null);

        return builder.create();
    }
}
