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

package android.webkit;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Message;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import java.net.MalformedURLException;
import java.net.URL;

/**
 * Helper class to create JavaScript dialogs. It is used by
 * different WebView implementations.
 *
 * @hide Helper class for internal use
 */
public class JsDialogHelper {

    private static final String TAG = "JsDialogHelper";

    // Dialog types
    public static final int ALERT   = 1;
    public static final int CONFIRM = 2;
    public static final int PROMPT  = 3;
    public static final int UNLOAD  = 4;

    private final String mDefaultValue;
    private final JsPromptResult mResult;
    private final String mMessage;
    private final int mType;
    private final String mUrl;

    public JsDialogHelper(JsPromptResult result, int type, String defaultValue, String message,
            String url) {
        mResult = result;
        mDefaultValue = defaultValue;
        mMessage = message;
        mType = type;
        mUrl = url;
    }

    public JsDialogHelper(JsPromptResult result, Message msg) {
        mResult = result;
        mDefaultValue = msg.getData().getString("default");
        mMessage = msg.getData().getString("message");
        mType = msg.getData().getInt("type");
        mUrl = msg.getData().getString("url");
    }

    public boolean invokeCallback(WebChromeClient client, WebView webView) {
        switch (mType) {
            case ALERT:
                return client.onJsAlert(webView, mUrl, mMessage, mResult);
            case CONFIRM:
                return client.onJsConfirm(webView, mUrl, mMessage, mResult);
            case UNLOAD:
                return client.onJsBeforeUnload(webView, mUrl, mMessage, mResult);
            case PROMPT:
                return client.onJsPrompt(webView, mUrl, mMessage, mDefaultValue, mResult);
            default:
                throw new IllegalArgumentException("Unexpected type: " + mType);
        }
    }

    public void showDialog(Context context) {
        if (!canShowAlertDialog(context)) {
            Log.w(TAG, "Cannot create a dialog, the WebView context is not an Activity");
            mResult.cancel();
            return;
        }

        String title, displayMessage;
        int positiveTextId, negativeTextId;
        if (mType == UNLOAD) {
            title = context.getString(com.android.internal.R.string.js_dialog_before_unload_title);
            displayMessage = context.getString(
                    com.android.internal.R.string.js_dialog_before_unload, mMessage);
            positiveTextId = com.android.internal.R.string.js_dialog_before_unload_positive_button;
            negativeTextId = com.android.internal.R.string.js_dialog_before_unload_negative_button;
        } else {
            title = getJsDialogTitle(context);
            displayMessage = mMessage;
            positiveTextId = com.android.internal.R.string.ok;
            negativeTextId = com.android.internal.R.string.cancel;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(title);
        builder.setOnCancelListener(new CancelListener());
        if (mType != PROMPT) {
            builder.setMessage(displayMessage);
            builder.setPositiveButton(positiveTextId, new PositiveListener(null));
        } else {
            final View view = LayoutInflater.from(context).inflate(
                    com.android.internal.R.layout.js_prompt, null);
            EditText edit = ((EditText) view.findViewById(com.android.internal.R.id.value));
            edit.setText(mDefaultValue);
            builder.setPositiveButton(positiveTextId, new PositiveListener(edit));
            ((TextView) view.findViewById(com.android.internal.R.id.message)).setText(mMessage);
            builder.setView(view);
        }
        if (mType != ALERT) {
            builder.setNegativeButton(negativeTextId, new CancelListener());
        }
        builder.show();
    }

    private class CancelListener implements DialogInterface.OnCancelListener,
            DialogInterface.OnClickListener {
        @Override
        public void onCancel(DialogInterface dialog) {
            mResult.cancel();
        }
        @Override
        public void onClick(DialogInterface dialog, int which) {
            mResult.cancel();
        }
    }

    private class PositiveListener implements DialogInterface.OnClickListener {
        private final EditText mEdit;

        public PositiveListener(EditText edit) {
            mEdit = edit;
        }

        @Override
        public void onClick(DialogInterface dialog, int which) {
            if (mEdit == null) {
                mResult.confirm();
            } else {
                mResult.confirm(mEdit.getText().toString());
            }
        }
    }

    private String getJsDialogTitle(Context context) {
        String title = mUrl;
        if (URLUtil.isDataUrl(mUrl)) {
            // For data: urls, we just display 'JavaScript' similar to Chrome.
            title = context.getString(com.android.internal.R.string.js_dialog_title_default);
        } else {
            try {
                URL alertUrl = new URL(mUrl);
                // For example: "The page at 'http://www.mit.edu' says:"
                title = context.getString(com.android.internal.R.string.js_dialog_title,
                        alertUrl.getProtocol() + "://" + alertUrl.getHost());
            } catch (MalformedURLException ex) {
                // do nothing. just use the url as the title
            }
        }
        return title;
    }

    private static boolean canShowAlertDialog(Context context) {
        // We can only display the alert dialog if mContext is
        // an Activity context.
        // FIXME: Should we display dialogs if mContext does
        // not have the window focus (e.g. if the user is viewing
        // another Activity when the alert should be displayed) ?
        // See bug 3166409
        return context instanceof Activity;
    }
}
