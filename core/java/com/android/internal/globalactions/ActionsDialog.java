/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.internal.globalactions;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.accessibility.AccessibilityEvent;
import android.view.KeyEvent;
import android.widget.ListView;
import com.android.internal.app.AlertController;

/** A dialog that lists the given Action items to be user selectable. */
public final class ActionsDialog extends Dialog implements DialogInterface {
    private final Context mContext;
    private final AlertController mAlert;
    private final ActionsAdapter mAdapter;

    public ActionsDialog(Context context, AlertController.AlertParams params) {
        super(context, getDialogTheme(context));
        mContext = getContext();
        mAlert = AlertController.create(mContext, this, getWindow());
        mAdapter = (ActionsAdapter) params.mAdapter;
        params.apply(mAlert);
    }

    private static int getDialogTheme(Context context) {
        TypedValue outValue = new TypedValue();
        context.getTheme().resolveAttribute(com.android.internal.R.attr.alertDialogTheme,
                outValue, true);
        return outValue.resourceId;
    }

    @Override
    protected void onStart() {
        super.setCanceledOnTouchOutside(true);
        super.onStart();
    }

    public ListView getListView() {
        return mAlert.getListView();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mAlert.installContent();
    }

    @Override
    public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            for (int i = 0; i < mAdapter.getCount(); ++i) {
                CharSequence label =
                        mAdapter.getItem(i).getLabelForAccessibility(getContext());
                if (label != null) {
                    event.getText().add(label);
                }
            }
        }
        return super.dispatchPopulateAccessibilityEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (mAlert.onKeyDown(keyCode, event)) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (mAlert.onKeyUp(keyCode, event)) {
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }
}
