/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.clipboardoverlay;

import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;

import com.android.systemui.R;

class IntentCreator {
    private static final String EXTRA_EDIT_SOURCE_CLIPBOARD = "edit_source_clipboard";
    private static final String REMOTE_COPY_ACTION = "android.intent.action.REMOTE_COPY";

    static Intent getTextEditorIntent(Context context) {
        Intent intent = new Intent(context, EditTextActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        return intent;
    }

    static Intent getShareIntent(ClipData clipData, Context context) {
        Intent shareIntent = new Intent(Intent.ACTION_SEND);

        // From the ACTION_SEND docs:
        //   "If using EXTRA_TEXT, the MIME type should be "text/plain"; otherwise it should be the
        //    MIME type of the data in EXTRA_STREAM"
        if (clipData.getItemAt(0).getUri() != null) {
            shareIntent.setDataAndType(
                    clipData.getItemAt(0).getUri(), clipData.getDescription().getMimeType(0));
            shareIntent.putExtra(Intent.EXTRA_STREAM, clipData.getItemAt(0).getUri());
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            shareIntent.putExtra(
                    Intent.EXTRA_TEXT, clipData.getItemAt(0).coerceToText(context).toString());
            shareIntent.setType("text/plain");
        }
        Intent chooserIntent = Intent.createChooser(shareIntent, null)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        return chooserIntent;
    }

    static Intent getImageEditIntent(Uri uri, Context context) {
        String editorPackage = context.getString(R.string.config_screenshotEditor);
        Intent editIntent = new Intent(Intent.ACTION_EDIT);
        if (!TextUtils.isEmpty(editorPackage)) {
            editIntent.setComponent(ComponentName.unflattenFromString(editorPackage));
        }
        editIntent.setDataAndType(uri, "image/*");
        editIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        editIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        editIntent.putExtra(EXTRA_EDIT_SOURCE_CLIPBOARD, true);
        return editIntent;
    }

    static Intent getRemoteCopyIntent(ClipData clipData, Context context) {
        Intent nearbyIntent = new Intent(REMOTE_COPY_ACTION);

        String remoteCopyPackage = context.getString(R.string.config_remoteCopyPackage);
        if (!TextUtils.isEmpty(remoteCopyPackage)) {
            nearbyIntent.setComponent(ComponentName.unflattenFromString(remoteCopyPackage));
        }

        nearbyIntent.setClipData(clipData);
        nearbyIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        nearbyIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        return nearbyIntent;
    }
}
