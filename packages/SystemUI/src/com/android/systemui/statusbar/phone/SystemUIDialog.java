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
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.view.ViewRootImpl;
import android.view.Window;
import android.view.WindowInsets.Type;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;

import androidx.annotation.Nullable;

import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.statusbar.policy.KeyguardStateController;

/**
 * Base class for dialogs that should appear over panels and keyguard.
 *
 * Optionally provide a {@link SystemUIDialogManager} to its constructor to send signals to
 * listeners on whether this dialog is showing.
 *
 * The SystemUIDialog registers a listener for the screen off / close system dialogs broadcast,
 * and dismisses itself when it receives the broadcast.
 */
public class SystemUIDialog extends AlertDialog implements ViewRootImpl.ConfigChangedCallback {
    // TODO(b/203389579): Remove this once the dialog width on large screens has been agreed on.
    private static final String FLAG_TABLET_DIALOG_WIDTH =
            "persist.systemui.flag_tablet_dialog_width";

    private final Context mContext;
    @Nullable private final DismissReceiver mDismissReceiver;
    private final Handler mHandler = new Handler();
    @Nullable private final SystemUIDialogManager mDialogManager;

    private int mLastWidth = Integer.MIN_VALUE;
    private int mLastHeight = Integer.MIN_VALUE;
    private int mLastConfigurationWidthDp = -1;
    private int mLastConfigurationHeightDp = -1;

    public SystemUIDialog(Context context) {
        this(context, R.style.Theme_SystemUI_Dialog);
    }

    public SystemUIDialog(Context context, SystemUIDialogManager dialogManager) {
        this(context, R.style.Theme_SystemUI_Dialog, true, dialogManager);
    }

    public SystemUIDialog(Context context, int theme) {
        this(context, theme, true /* dismissOnDeviceLock */);
    }
    public SystemUIDialog(Context context, int theme, boolean dismissOnDeviceLock) {
        this(context, theme, dismissOnDeviceLock, null);
    }

    /**
     * @param udfpsDialogManager If set, UDFPS will hide if this dialog is showing.
     */
    public SystemUIDialog(Context context, int theme, boolean dismissOnDeviceLock,
            SystemUIDialogManager dialogManager) {
        super(context, theme);
        mContext = context;

        applyFlags(this);
        WindowManager.LayoutParams attrs = getWindow().getAttributes();
        attrs.setTitle(getClass().getSimpleName());
        getWindow().setAttributes(attrs);

        mDismissReceiver = dismissOnDeviceLock ? new DismissReceiver(this) : null;
        mDialogManager = dialogManager;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Configuration config = getContext().getResources().getConfiguration();
        mLastConfigurationWidthDp = config.screenWidthDp;
        mLastConfigurationHeightDp = config.screenHeightDp;
        updateWindowSize();
    }

    private void updateWindowSize() {
        // Only the thread that created this dialog can update its window size.
        if (Looper.myLooper() != mHandler.getLooper()) {
            mHandler.post(this::updateWindowSize);
            return;
        }

        int width = getWidth();
        int height = getHeight();
        if (width == mLastWidth && height == mLastHeight) {
            return;
        }

        mLastWidth = width;
        mLastHeight = height;
        getWindow().setLayout(width, height);
    }

    @Override
    public void onConfigurationChanged(Configuration configuration) {
        if (mLastConfigurationWidthDp != configuration.screenWidthDp
                || mLastConfigurationHeightDp != configuration.screenHeightDp) {
            mLastConfigurationWidthDp = configuration.screenWidthDp;
            mLastConfigurationHeightDp = configuration.compatScreenWidthDp;

            updateWindowSize();
        }
    }

    /**
     * Return this dialog width. This method will be invoked when this dialog is created and when
     * the device configuration changes, and the result will be used to resize this dialog window.
     */
    protected int getWidth() {
        return getDefaultDialogWidth(mContext);
    }

    /**
     * Return this dialog height. This method will be invoked when this dialog is created and when
     * the device configuration changes, and the result will be used to resize this dialog window.
     */
    protected int getHeight() {
        return getDefaultDialogHeight();
    }

    @Override
    protected void onStart() {
        super.onStart();

        if (mDismissReceiver != null) {
            mDismissReceiver.register();
        }

        if (mDialogManager != null) {
            mDialogManager.setShowing(this, true);
        }

        // Listen for configuration changes to resize this dialog window. This is mostly necessary
        // for foldables that often go from large <=> small screen when folding/unfolding.
        ViewRootImpl.addConfigCallback(this);
    }

    @Override
    protected void onStop() {
        super.onStop();

        if (mDismissReceiver != null) {
            mDismissReceiver.unregister();
        }

        if (mDialogManager != null) {
            mDialogManager.setShowing(this, false);
        }

        ViewRootImpl.removeConfigCallback(this);
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
     * calling this because it causes a leak of BroadcastReceiver. Instead, call the version that
     * takes an extra Runnable as a parameter.
     *
     * @param dialog The dialog to be associated with the listener.
     */
    public static void registerDismissListener(Dialog dialog) {
        registerDismissListener(dialog, null);
    }


    /**
     * Registers a listener that dismisses the given dialog when it receives
     * the screen off / close system dialogs broadcast.
     * <p>
     * <strong>Note:</strong> Don't call dialog.setOnDismissListener() after
     * calling this because it causes a leak of BroadcastReceiver.
     *
     * @param dialog The dialog to be associated with the listener.
     * @param dismissAction An action to run when the dialog is dismissed.
     */
    public static void registerDismissListener(Dialog dialog, @Nullable Runnable dismissAction) {
        DismissReceiver dismissReceiver = new DismissReceiver(dialog);
        dialog.setOnDismissListener(d -> {
            dismissReceiver.unregister();
            if (dismissAction != null) dismissAction.run();
        });
        dismissReceiver.register();
    }

    /** Set an appropriate size to {@code dialog} depending on the current configuration. */
    public static void setDialogSize(Dialog dialog) {
        // We need to create the dialog first, otherwise the size will be overridden when it is
        // created.
        dialog.create();
        dialog.getWindow().setLayout(getDefaultDialogWidth(dialog.getContext()),
                getDefaultDialogHeight());
    }

    private static int getDefaultDialogWidth(Context context) {
        boolean isOnTablet = context.getResources().getConfiguration().smallestScreenWidthDp >= 600;
        if (!isOnTablet) {
            return ViewGroup.LayoutParams.MATCH_PARENT;
        }

        int flagValue = SystemProperties.getInt(FLAG_TABLET_DIALOG_WIDTH, 0);
        if (flagValue == -1) {
            // The width of bottom sheets (624dp).
            return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 624,
                    context.getResources().getDisplayMetrics()));
        } else if (flagValue == -2) {
            // The suggested small width for all dialogs (348dp)
            return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 348,
                    context.getResources().getDisplayMetrics()));
        } else if (flagValue > 0) {
            // Any given width.
            return Math.round(
                    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, flagValue,
                            context.getResources().getDisplayMetrics()));
        } else {
            // By default we use the same width as the notification shade in portrait mode (504dp).
            return context.getResources().getDimensionPixelSize(R.dimen.large_dialog_width);
        }
    }

    private static int getDefaultDialogHeight() {
        return ViewGroup.LayoutParams.WRAP_CONTENT;
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
