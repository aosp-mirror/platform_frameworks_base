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

import static com.android.systemui.Flags.predictiveBackAnimateDialogs;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewRootImpl;
import android.view.Window;
import android.view.WindowInsets.Type;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;

import androidx.annotation.Nullable;
import androidx.annotation.StyleRes;

import com.android.systemui.Dependency;
import com.android.systemui.animation.DialogTransitionAnimator;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.qualifiers.Application;
import com.android.systemui.model.SysUiState;
import com.android.systemui.res.R;
import com.android.systemui.shared.system.QuickStepContract;
import com.android.systemui.util.DialogKt;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

/**
 * Class for dialogs that should appear over panels and keyguard.
 *
 * DO NOT SUBCLASS THIS. See {@link DialogDelegate} for an interface that enables
 * customizing behavior via composition instead of inheritance. Clients should implement the
 * Delegate class and then pass their implementation into the SystemUIDialog constructor.
 *
 * Optionally provide a {@link SystemUIDialogManager} to its constructor to send signals to
 * listeners on whether this dialog is showing.
 *
 * The SystemUIDialog registers a listener for the screen off / close system dialogs broadcast,
 * and dismisses itself when it receives the broadcast.
 */
public class SystemUIDialog extends AlertDialog implements ViewRootImpl.ConfigChangedCallback {
    public static final int DEFAULT_THEME = R.style.Theme_SystemUI_Dialog;
    // TODO(b/203389579): Remove this once the dialog width on large screens has been agreed on.
    private static final String FLAG_TABLET_DIALOG_WIDTH =
            "persist.systemui.flag_tablet_dialog_width";
    public static final boolean DEFAULT_DISMISS_ON_DEVICE_LOCK = true;

    private final Context mContext;
    private final DialogDelegate<SystemUIDialog> mDelegate;
    @Nullable
    private final DismissReceiver mDismissReceiver;
    private final Handler mHandler = new Handler();
    private final SystemUIDialogManager mDialogManager;
    private final SysUiState mSysUiState;

    private int mLastWidth = Integer.MIN_VALUE;
    private int mLastHeight = Integer.MIN_VALUE;
    private int mLastConfigurationWidthDp = -1;
    private int mLastConfigurationHeightDp = -1;

    private final List<Runnable> mOnCreateRunnables = new ArrayList<>();

    /**
     * @deprecated Don't subclass SystemUIDialog. Please subclass {@link Delegate} and pass it to
     * {@link Factory#create(Delegate)} to create a custom dialog.
     */
    @Deprecated
    public SystemUIDialog(Context context) {
        this(context, DEFAULT_THEME, DEFAULT_DISMISS_ON_DEVICE_LOCK);
    }

    public SystemUIDialog(Context context, int theme) {
        this(context, theme, DEFAULT_DISMISS_ON_DEVICE_LOCK);
    }

    public SystemUIDialog(Context context, int theme, boolean dismissOnDeviceLock) {
        // TODO(b/219008720): Remove those calls to Dependency.get by introducing a
        // SystemUIDialogFactory and make all other dialogs create a SystemUIDialog to which we set
        // the content and attach listeners.
        this(context, theme, dismissOnDeviceLock,
                Dependency.get(SystemUIDialogManager.class),
                Dependency.get(SysUiState.class),
                Dependency.get(BroadcastDispatcher.class),
                Dependency.get(DialogTransitionAnimator.class));
    }

    public static class Factory {
        private final Context mContext;
        private final SystemUIDialogManager mSystemUIDialogManager;
        private final SysUiState mSysUiState;
        private final BroadcastDispatcher mBroadcastDispatcher;
        private final DialogTransitionAnimator mDialogTransitionAnimator;

        @Inject
        public Factory(
                @Application Context context,
                SystemUIDialogManager systemUIDialogManager,
                SysUiState sysUiState,
                BroadcastDispatcher broadcastDispatcher,
                DialogTransitionAnimator dialogTransitionAnimator) {
            mContext = context;
            mSystemUIDialogManager = systemUIDialogManager;
            mSysUiState = sysUiState;
            mBroadcastDispatcher = broadcastDispatcher;
            mDialogTransitionAnimator = dialogTransitionAnimator;
        }

        /**
         * Creates a new instance of {@link SystemUIDialog} with no customized behavior.
         *
         * When you just need a dialog, call this.
         */
        public SystemUIDialog create() {
            return create(new DialogDelegate<>() {
            }, mContext, DEFAULT_THEME);
        }

        /**
         * Creates a new instance of {@link SystemUIDialog} with no customized behavior.
         *
         * When you just need a dialog created with a specific {@link Context}, call this.
         */
        public SystemUIDialog create(Context context) {
            return create(new DialogDelegate<>() {
            }, context, DEFAULT_THEME);
        }

        /**
         * Creates a new instance of {@link SystemUIDialog} with {@code delegate} as the {@link
         * Delegate}.
         *
         * When you need to customize the dialog, pass it a delegate.
         */
        public SystemUIDialog create(Delegate delegate, Context context) {
            return create(delegate, context, DEFAULT_THEME);
        }

        public SystemUIDialog create(Delegate delegate, Context context, @StyleRes int theme) {
            return create((DialogDelegate<SystemUIDialog>) delegate, context, theme);
        }

        public SystemUIDialog create(Delegate delegate) {
            return create(delegate, mContext);
        }

        private SystemUIDialog create(DialogDelegate<SystemUIDialog> dialogDelegate,
                Context context, @StyleRes int theme) {
            return new SystemUIDialog(
                    context,
                    theme,
                    DEFAULT_DISMISS_ON_DEVICE_LOCK,
                    mSystemUIDialogManager,
                    mSysUiState,
                    mBroadcastDispatcher,
                    mDialogTransitionAnimator,
                    dialogDelegate);
        }
    }

    public SystemUIDialog(
            Context context,
            int theme,
            boolean dismissOnDeviceLock,
            SystemUIDialogManager dialogManager,
            SysUiState sysUiState,
            BroadcastDispatcher broadcastDispatcher,
            DialogTransitionAnimator dialogTransitionAnimator) {
        this(
                context,
                theme,
                dismissOnDeviceLock,
                dialogManager,
                sysUiState,
                broadcastDispatcher,
                dialogTransitionAnimator,
                new DialogDelegate<>() {
                });
    }

    public SystemUIDialog(
            Context context,
            int theme,
            boolean dismissOnDeviceLock,
            SystemUIDialogManager dialogManager,
            SysUiState sysUiState,
            BroadcastDispatcher broadcastDispatcher,
            DialogTransitionAnimator dialogTransitionAnimator,
            Delegate delegate) {
        this(
                context,
                theme,
                dismissOnDeviceLock,
                dialogManager,
                sysUiState,
                broadcastDispatcher,
                dialogTransitionAnimator,
                (DialogDelegate<SystemUIDialog>) delegate);
    }

    public SystemUIDialog(
            Context context,
            int theme,
            boolean dismissOnDeviceLock,
            SystemUIDialogManager dialogManager,
            SysUiState sysUiState,
            BroadcastDispatcher broadcastDispatcher,
            DialogTransitionAnimator dialogTransitionAnimator,
            DialogDelegate<SystemUIDialog> delegate) {
        super(context, theme);
        mContext = context;
        mDelegate = delegate;

        applyFlags(this);
        WindowManager.LayoutParams attrs = getWindow().getAttributes();
        attrs.setTitle(getClass().getSimpleName());
        getWindow().setAttributes(attrs);

        mDismissReceiver = dismissOnDeviceLock ? new DismissReceiver(this, broadcastDispatcher,
                dialogTransitionAnimator) : null;
        mDialogManager = dialogManager;
        mSysUiState = sysUiState;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        mDelegate.beforeCreate(this, savedInstanceState);
        super.onCreate(savedInstanceState);
        mDelegate.onCreate(this, savedInstanceState);

        Configuration config = getContext().getResources().getConfiguration();
        mLastConfigurationWidthDp = config.screenWidthDp;
        mLastConfigurationHeightDp = config.screenHeightDp;
        updateWindowSize();

        for (int i = 0; i < mOnCreateRunnables.size(); i++) {
            mOnCreateRunnables.get(i).run();
        }
        if (predictiveBackAnimateDialogs()) {
            View targetView = getWindow().getDecorView();
            DialogKt.registerAnimationOnBackInvoked(
                    /* dialog = */ this,
                    /* targetView = */ targetView,
                    /* backAnimationSpec= */mDelegate.getBackAnimationSpec(
                            () -> targetView.getResources().getDisplayMetrics())
            );
        }
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

        mDelegate.onConfigurationChanged(this, configuration);
    }

    /**
     * Return this dialog width. This method will be invoked when this dialog is created and when
     * the device configuration changes, and the result will be used to resize this dialog window.
     */
    protected int getWidth() {
        return mDelegate.getWidth(this);
    }

    /**
     * Return this dialog height. This method will be invoked when this dialog is created and when
     * the device configuration changes, and the result will be used to resize this dialog window.
     */
    protected int getHeight() {
        return mDelegate.getHeight(this);
    }

    @Override
    protected final void onStart() {
        super.onStart();

        if (mDismissReceiver != null) {
            mDismissReceiver.register();
        }

        // Listen for configuration changes to resize this dialog window. This is mostly necessary
        // for foldables that often go from large <=> small screen when folding/unfolding.
        ViewRootImpl.addConfigCallback(this);
        mDialogManager.setShowing(this, true);
        mSysUiState.setFlag(QuickStepContract.SYSUI_STATE_DIALOG_SHOWING, true)
                .commitUpdate(mContext.getDisplayId());

        start();
    }

    /**
     * Called when {@link #onStart} is called. Subclasses wishing to override {@link #onStart()}
     * should override this method instead.
     */
    protected void start() {
        mDelegate.onStart(this);
    }

    @Override
    protected final void onStop() {
        super.onStop();

        if (mDismissReceiver != null) {
            mDismissReceiver.unregister();
        }

        ViewRootImpl.removeConfigCallback(this);
        mDialogManager.setShowing(this, false);
        mSysUiState.setFlag(QuickStepContract.SYSUI_STATE_DIALOG_SHOWING, false)
                .commitUpdate(mContext.getDisplayId());

        stop();
    }

    /**
     * Called when {@link #onStop} is called. Subclasses wishing to override {@link #onStop()}
     * should override this method instead.
     */
    protected void stop() {
        mDelegate.onStop(this);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        mDelegate.onWindowFocusChanged(this, hasFocus);
    }

    public void setShowForAllUsers(boolean show) {
        setShowForAllUsers(this, show);
    }

    public void setMessage(int resId) {
        setMessage(mContext.getString(resId));
    }

    /**
     * Set a listener to be invoked when the positive button of the dialog is pressed. The dialog
     * will automatically be dismissed when the button is clicked.
     */
    public void setPositiveButton(int resId, OnClickListener onClick) {
        setPositiveButton(resId, onClick, true /* dismissOnClick */);
    }

    /**
     * Set a listener to be invoked when the positive button of the dialog is pressed. The dialog
     * will be dismissed when the button is clicked iff {@code dismissOnClick} is true.
     */
    public void setPositiveButton(int resId, OnClickListener onClick, boolean dismissOnClick) {
        setButton(BUTTON_POSITIVE, resId, onClick, dismissOnClick);
    }

    /**
     * Set a listener to be invoked when the negative button of the dialog is pressed. The dialog
     * will automatically be dismissed when the button is clicked.
     */
    public void setNegativeButton(int resId, OnClickListener onClick) {
        setNegativeButton(resId, onClick, true /* dismissOnClick */);
    }

    /**
     * Set a listener to be invoked when the negative button of the dialog is pressed. The dialog
     * will be dismissed when the button is clicked iff {@code dismissOnClick} is true.
     */
    public void setNegativeButton(int resId, OnClickListener onClick, boolean dismissOnClick) {
        setButton(BUTTON_NEGATIVE, resId, onClick, dismissOnClick);
    }

    /**
     * Set a listener to be invoked when the neutral button of the dialog is pressed. The dialog
     * will automatically be dismissed when the button is clicked.
     */
    public void setNeutralButton(int resId, OnClickListener onClick) {
        setNeutralButton(resId, onClick, true /* dismissOnClick */);
    }

    /**
     * Set a listener to be invoked when the neutral button of the dialog is pressed. The dialog
     * will be dismissed when the button is clicked iff {@code dismissOnClick} is true.
     */
    public void setNeutralButton(int resId, OnClickListener onClick, boolean dismissOnClick) {
        setButton(BUTTON_NEUTRAL, resId, onClick, dismissOnClick);
    }

    private void setButton(int whichButton, int resId, OnClickListener onClick,
            boolean dismissOnClick) {
        if (dismissOnClick) {
            setButton(whichButton, mContext.getString(resId), onClick);
        } else {
            // Set a null OnClickListener to make sure the button is still created and shown.
            setButton(whichButton, mContext.getString(resId), (OnClickListener) null);

            // When the dialog is created, set the click listener but don't dismiss the dialog when
            // it is clicked.
            mOnCreateRunnables.add(() -> getButton(whichButton).setOnClickListener(
                    view -> onClick.onClick(this, whichButton)));
        }
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

    /**
     * Ensure the window type is set properly to show over all other screens
     */
    public static void setWindowOnTop(Dialog dialog, boolean isKeyguardShowing) {
        final Window window = dialog.getWindow();
        window.setType(LayoutParams.TYPE_STATUS_BAR_SUB_PANEL);
        if (isKeyguardShowing) {
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
     * @param dialog        The dialog to be associated with the listener.
     * @param dismissAction An action to run when the dialog is dismissed.
     */
    public static void registerDismissListener(Dialog dialog, @Nullable Runnable dismissAction) {
        // TODO(b/219008720): Remove those calls to Dependency.get.
        DismissReceiver dismissReceiver = new DismissReceiver(dialog,
                Dependency.get(BroadcastDispatcher.class),
                Dependency.get(DialogTransitionAnimator.class));
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
        dialog.getWindow().setLayout(getDefaultDialogWidth(dialog), getDefaultDialogHeight());
    }

    static int getDefaultDialogWidth(Dialog dialog) {
        Context context = dialog.getContext();
        int flagValue = SystemProperties.getInt(FLAG_TABLET_DIALOG_WIDTH, 0);
        if (flagValue == -1) {
            // The width of bottom sheets (624dp).
            return calculateDialogWidthWithInsets(dialog, 624);
        } else if (flagValue == -2) {
            // The suggested small width for all dialogs (348dp)
            return calculateDialogWidthWithInsets(dialog, 348);
        } else if (flagValue > 0) {
            // Any given width.
            return calculateDialogWidthWithInsets(dialog, flagValue);
        } else {
            // By default we use the same width as the notification shade in portrait mode.
            int width = context.getResources().getDimensionPixelSize(R.dimen.large_dialog_width);
            if (width > 0) {
                // If we are neither WRAP_CONTENT or MATCH_PARENT, add the background insets so that
                // the dialog is the desired width.
                width += getHorizontalInsets(dialog);
            }
            return width;
        }
    }

    /**
     * Return the pixel width {@param dialog} should be so that it is {@param widthInDp} wide,
     * taking its background insets into consideration.
     */
    private static int calculateDialogWidthWithInsets(Dialog dialog, int widthInDp) {
        float widthInPixels = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, widthInDp,
                dialog.getContext().getResources().getDisplayMetrics());
        return Math.round(widthInPixels + getHorizontalInsets(dialog));
    }

    private static int getHorizontalInsets(Dialog dialog) {
        View decorView = dialog.getWindow().getDecorView();
        if (decorView == null) {
            return 0;
        }

        // We first look for the background on the dialogContentWithBackground added by
        // DialogTransitionAnimator. If it's not there, we use the background of the DecorView.
        View viewWithBackground = decorView.findViewByPredicate(
                view -> view.getTag(
                        com.android.systemui.animation.R.id.tag_dialog_background) != null);
        Drawable background = viewWithBackground != null ? viewWithBackground.getBackground()
                : decorView.getBackground();
        Insets insets = background != null ? background.getOpticalInsets() : Insets.NONE;
        return insets.left + insets.right;
    }

    static int getDefaultDialogHeight() {
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
        private final DialogTransitionAnimator mDialogTransitionAnimator;

        DismissReceiver(Dialog dialog, BroadcastDispatcher broadcastDispatcher,
                DialogTransitionAnimator dialogTransitionAnimator) {
            mDialog = dialog;
            mBroadcastDispatcher = broadcastDispatcher;
            mDialogTransitionAnimator = dialogTransitionAnimator;
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
            // These broadcast are usually received when locking the device, swiping up to home
            // (which collapses the shade), etc. In those cases, we usually don't want to animate
            // back into the view.
            mDialogTransitionAnimator.disableAllCurrentDialogsExitAnimations();
            mDialog.dismiss();
        }
    }

    /**
     * A delegate class that should be implemented in place of sublcassing {@link SystemUIDialog}.
     *
     * Implement this interface and then pass an instance of your implementation to
     * {@link SystemUIDialog.Factory#create(Delegate)}.
     */
    public interface Delegate extends DialogDelegate<SystemUIDialog> {
        /**
         * Returns a new {@link SystemUIDialog} which has been passed this Delegate in its
         * construction.
         */
        SystemUIDialog createDialog();
    }
}
