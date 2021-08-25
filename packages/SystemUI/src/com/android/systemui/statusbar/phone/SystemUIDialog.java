/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.phone;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.UserHandle;
import android.view.Window;
import android.view.WindowInsets.Type;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;

import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.animation.DialogListener;
import com.android.systemui.animation.ListenableDialog;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import java.util.LinkedHashSet;
import java.util.Set;


/**
 * Base class for dialogs that should appear over panels and keyguard.
 * The SystemUIDialog registers a listener for the screen off / close system dialogs broadcast,
 * and dismisses itself when it receives the broadcast.
 */
public class SystemUIDialog extends AlertDialog implements ListenableDialog {
    private final Context mContext;
    private final DismissReceiver mDismissReceiver;
    private final Set<DialogListener> mDialogListeners = new LinkedHashSet<>();

    public SystemUIDialog(Context context) {
        this(context, R.style.Theme_SystemUI_Dialog);
    }

    public SystemUIDialog(Context context, int theme) {
        super(context, theme);
        mContext = context;

        applyFlags(this);
        WindowManager.LayoutParams attrs = getWindow().getAttributes();
        attrs.setTitle(getClass().getSimpleName());
        getWindow().setAttributes(attrs);

        mDismissReceiver = new DismissReceiver(this);
    }

    @Override
    protected void onStart() {
        super.onStart();
        mDismissReceiver.register();
    }

    @Override
    protected void onStop() {
        super.onStop();
        mDismissReceiver.unregister();
    }

    @Override
    public void addListener(DialogListener listener) {
        mDialogListeners.add(listener);
    }

    @Override
    public void removeListener(DialogListener listener) {
        mDialogListeners.remove(listener);
    }

    @Override
    public void dismiss() {
        super.dismiss();

        for (DialogListener listener : new LinkedHashSet<>(mDialogListeners)) {
            listener.onDismiss();
        }
    }

    @Override
    public void hide() {
        super.hide();

        for (DialogListener listener : new LinkedHashSet<>(mDialogListeners)) {
            listener.onHide();
        }
    }

    @Override
    public void show() {
        super.show();

        for (DialogListener listener : new LinkedHashSet<>(mDialogListeners)) {
            listener.onShow();
        }
    }

    public void setShowForAllUsers(boolean show) {
        setShowForAllUsers(this, show);
    }

    public void setMessage(int resId) {
        setMessage(mContext.getString(resId));
    }

    public void setPositiveButton(int resId, OnClickListener onClick) {
        setButton(BUTTON_POSITIVE, mContext.getString(resId), onClick);
    }

    public void setNegativeButton(int resId, OnClickListener onClick) {
        setButton(BUTTON_NEGATIVE, mContext.getString(resId), onClick);
    }

    public void setNeutralButton(int resId, OnClickListener onClick) {
        setButton(BUTTON_NEUTRAL, mContext.getString(resId), onClick);
    }

    public static void setShowForAllUsers(Dialog dialog, boolean show) {
        if (show) {
            dialog.getWindow().getAttributes().privateFlags |=
                    WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS;
        } else {
            dialog.getWindow().getAttributes().privateFlags &=
                    ~WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS;
        }
    }

    public static void setWindowOnTop(Dialog dialog) {
        final Window window = dialog.getWindow();
        window.setType(LayoutParams.TYPE_STATUS_BAR_SUB_PANEL);
        if (Dependency.get(KeyguardStateController.class).isShowing()) {
            window.getAttributes().setFitInsetsTypes(
                    window.getAttributes().getFitInsetsTypes() & ~Type.statusBars());
        }
    }

    public static AlertDialog applyFlags(AlertDialog dialog) {
        final Window window = dialog.getWindow();
        window.setType(WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL);
        window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        window.getAttributes().setFitInsetsTypes(
                window.getAttributes().getFitInsetsTypes() & ~Type.statusBars());
        return dialog;
    }

    /**
     * Registers a listener that dismisses the given dialog when it receives
     * the screen off / close system dialogs broadcast.
     * <p>
     * <strong>Note:</strong> Don't call dialog.setOnDismissListener() after
     * calling this because it causes a leak of BroadcastReceiver.
     *
     * @param dialog The dialog to be associated with the listener.
     */
    public static void registerDismissListener(Dialog dialog) {
        DismissReceiver dismissReceiver = new DismissReceiver(dialog);
        dialog.setOnDismissListener(d -> dismissReceiver.unregister());
        dismissReceiver.register();
    }

    private static class DismissReceiver extends BroadcastReceiver {
        private static final IntentFilter INTENT_FILTER = new IntentFilter();
        static {
            INTENT_FILTER.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
            INTENT_FILTER.addAction(Intent.ACTION_SCREEN_OFF);
        }

        private final Dialog mDialog;
        private boolean mRegistered;
        private final BroadcastDispatcher mBroadcastDispatcher;

        DismissReceiver(Dialog dialog) {
            mDialog = dialog;
            mBroadcastDispatcher = Dependency.get(BroadcastDispatcher.class);
        }

        void register() {
            mBroadcastDispatcher.registerReceiver(this, INTENT_FILTER, null, UserHandle.CURRENT);
            mRegistered = true;
        }

        void unregister() {
            if (mRegistered) {
                mBroadcastDispatcher.unregisterReceiver(this);
                mRegistered = false;
            }
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            mDialog.dismiss();
        }
    }
}
