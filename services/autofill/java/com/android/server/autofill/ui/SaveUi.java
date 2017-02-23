/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.autofill.ui;

import android.annotation.NonNull;
import android.app.Dialog;
import android.content.Context;
import android.os.Handler;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;
import android.view.LayoutInflater;
import android.view.View;

import com.android.internal.R;
import com.android.server.UiThread;

/**
 * Autofill Save Prompt
 */
final class SaveUi {
    public interface OnSaveListener {
        void onSave();
        void onCancel();
    }

    private static final long LIFETIME_MILLIS = 5 * DateUtils.SECOND_IN_MILLIS;

    private final Handler mHandler = UiThread.getHandler();

    private final @NonNull Dialog mDialog;

    private final @NonNull OnSaveListener mListener;

    private boolean mDestroyed;

    SaveUi(@NonNull Context context, @NonNull CharSequence providerLabel,
            @NonNull OnSaveListener listener) {
        mListener = listener;

        final LayoutInflater inflater = LayoutInflater.from(context);
        final View view = inflater.inflate(R.layout.autofill_save, null);

        final TextView title = (TextView) view.findViewById(R.id.autofill_save_title);
        title.setText(context.getString(R.string.autofill_save_title, providerLabel));

        final View noButton = view.findViewById(R.id.autofill_save_no);
        noButton.setOnClickListener((v) -> mListener.onCancel());

        final View yesButton = view.findViewById(R.id.autofill_save_yes);
        yesButton.setOnClickListener((v) -> mListener.onSave());

        final View closeButton = view.findViewById(R.id.autofill_save_close);
        closeButton.setOnClickListener((v) -> mListener.onCancel());

        mDialog = new Dialog(context, R.style.Theme_Material_Panel);
        mDialog.setContentView(view);

        final Window window = mDialog.getWindow();
        window.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);
        window.setGravity(Gravity.BOTTOM | Gravity.CENTER);
        window.setCloseOnTouchOutside(true);
        window.getAttributes().width = WindowManager.LayoutParams.MATCH_PARENT;

        mDialog.show();

        mHandler.postDelayed(() -> mListener.onCancel(), LIFETIME_MILLIS);
    }

    void destroy() {
        throwIfDestroyed();
        mHandler.removeCallbacksAndMessages(mListener);
        mDialog.dismiss();
        mDestroyed = true;
    }

    private void throwIfDestroyed() {
        if (mDestroyed) {
            throw new IllegalStateException("cannot interact with a destroyed instance");
        }
    }
}
