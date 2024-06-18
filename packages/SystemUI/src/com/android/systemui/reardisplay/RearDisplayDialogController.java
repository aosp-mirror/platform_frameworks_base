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

package com.android.systemui.reardisplay;

import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.TestApi;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.hardware.devicestate.DeviceStateManager;
import android.hardware.devicestate.DeviceStateManagerGlobal;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.widget.LinearLayout;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.CoreStartable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.phone.SystemUIDialog;
import com.android.systemui.statusbar.policy.ConfigurationController;

import com.airbnb.lottie.LottieAnimationView;
import com.airbnb.lottie.LottieDrawable;

import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * Provides an educational dialog to the user alerting them to what
 * they may need to do to enter rear display mode. This may be to open the
 * device if it is currently folded, or to confirm that they would like
 * the content to move to the screen on their device that is aligned with
 * the rear camera. This includes a device animation to provide more context
 * to the user.
 *
 * We are suppressing lint for the VisibleForTests check because the use of
 * DeviceStateManagerGlobal as in this file should not be encouraged for other use-cases.
 * The lint check will notify any other use-cases that they are possibly doing something
 * incorrectly.
 */
@SuppressLint("VisibleForTests") // TODO(b/260264542) Migrate away from DeviceStateManagerGlobal
@SysUISingleton
public class RearDisplayDialogController implements
        CoreStartable,
        ConfigurationController.ConfigurationListener,
        CommandQueue.Callbacks {

    private int[] mFoldedStates;
    private boolean mStartedFolded;
    private boolean mServiceNotified = false;
    private int mAnimationRepeatCount = LottieDrawable.INFINITE;

    private DeviceStateManagerGlobal mDeviceStateManagerGlobal;
    private DeviceStateManager.DeviceStateCallback mDeviceStateManagerCallback =
            new DeviceStateManagerCallback();

    private final CommandQueue mCommandQueue;
    private final Executor mExecutor;
    private final Resources mResources;
    private final LayoutInflater mLayoutInflater;
    private final SystemUIDialog.Factory mSystemUIDialogFactory;

    private SystemUIDialog mRearDisplayEducationDialog;
    @Nullable LinearLayout mDialogViewContainer;

    @Inject
    public RearDisplayDialogController(
            CommandQueue commandQueue,
            @Main Executor executor,
            @Main Resources resources,
            LayoutInflater layoutInflater,
            SystemUIDialog.Factory systemUIDialogFactory) {
        mCommandQueue = commandQueue;
        mExecutor = executor;
        mResources = resources;
        mLayoutInflater = layoutInflater;
        mSystemUIDialogFactory = systemUIDialogFactory;
    }

    @Override
    public void start() {
        mCommandQueue.addCallback(this);
    }

    @Override
    public void showRearDisplayDialog(int currentBaseState) {
        initializeValues(currentBaseState);
        createAndShowDialog();
    }

    @Override
    public void onConfigChanged(Configuration newConfig) {
        if (mRearDisplayEducationDialog != null && mRearDisplayEducationDialog.isShowing()
                && mDialogViewContainer != null) {
            // Refresh the dialog view when configuration is changed.
            View dialogView = createDialogView(mRearDisplayEducationDialog.getContext());
            mDialogViewContainer.removeAllViews();
            mDialogViewContainer.addView(dialogView);
        }
    }

    private void createAndShowDialog() {
        mServiceNotified = false;
        Context dialogContext = mRearDisplayEducationDialog.getContext();
        View dialogView = createDialogView(dialogContext);
        mDialogViewContainer = new LinearLayout(dialogContext);
        mDialogViewContainer.setLayoutParams(
                new LinearLayout.LayoutParams(
                        LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        mDialogViewContainer.setOrientation(LinearLayout.VERTICAL);
        mDialogViewContainer.addView(dialogView);

        mRearDisplayEducationDialog.setView(mDialogViewContainer);

        configureDialogButtons();

        mRearDisplayEducationDialog.show();
    }

    private View createDialogView(Context context) {
        View dialogView;
        LayoutInflater inflater = mLayoutInflater.cloneInContext(context);
        if (mStartedFolded) {
            dialogView = inflater.inflate(R.layout.activity_rear_display_education, null);
        } else {
            dialogView = inflater.inflate(
                    R.layout.activity_rear_display_education_opened, null);
        }
        LottieAnimationView animationView = dialogView.findViewById(
                R.id.rear_display_folded_animation);
        animationView.setRepeatCount(mAnimationRepeatCount);
        return dialogView;
    }

    /**
     * Configures the buttons on the dialog depending on the starting device posture
     */
    private void configureDialogButtons() {
        // If we are open, we need to provide a confirm option
        if (!mStartedFolded) {
            mRearDisplayEducationDialog.setPositiveButton(
                    R.string.rear_display_bottom_sheet_confirm,
                    (dialog, which) -> closeOverlayAndNotifyService(false), true);
        }
        mRearDisplayEducationDialog.setNegativeButton(R.string.rear_display_bottom_sheet_cancel,
                (dialog, which) -> closeOverlayAndNotifyService(true), true);
        mRearDisplayEducationDialog.setOnDismissListener(dialog -> {
            // Dialog is being dismissed before we've notified the system server
            if (!mServiceNotified) {
                closeOverlayAndNotifyService(true);
            }
        });
    }

    /**
     * Initializes properties and values we need when getting ready to show the dialog.
     *
     * Ensures we're not using old values from when the dialog may have been shown previously.
     */
    private void initializeValues(int startingBaseState) {
        mRearDisplayEducationDialog = mSystemUIDialogFactory.create();
        if (mFoldedStates == null) {
            mFoldedStates = mResources.getIntArray(
                    com.android.internal.R.array.config_foldedDeviceStates);
        }
        mStartedFolded = isFoldedState(startingBaseState);
        mDeviceStateManagerGlobal = DeviceStateManagerGlobal.getInstance();
        mDeviceStateManagerGlobal.registerDeviceStateCallback(mDeviceStateManagerCallback,
                mExecutor);
    }

    private boolean isFoldedState(int state) {
        for (int i = 0; i < mFoldedStates.length; i++) {
            if (mFoldedStates[i] == state) return true;
        }
        return false;
    }

    /**
     * Closes the educational overlay, and notifies the system service if rear display mode
     * should be cancelled or enabled.
     */
    private void closeOverlayAndNotifyService(boolean shouldCancelRequest) {
        mServiceNotified = true;
        mDeviceStateManagerGlobal.unregisterDeviceStateCallback(mDeviceStateManagerCallback);
        mDeviceStateManagerGlobal.onStateRequestOverlayDismissed(shouldCancelRequest);
        mDialogViewContainer = null;
    }

    /**
     * TestAPI to allow us to set the folded states array, instead of reading from resources.
     */
    @TestApi
    void setFoldedStates(int[] foldedStates) {
        mFoldedStates = foldedStates;
    }

    @TestApi
    void setDeviceStateManagerCallback(
            DeviceStateManager.DeviceStateCallback deviceStateManagerCallback) {
        mDeviceStateManagerCallback = deviceStateManagerCallback;
    }

    @TestApi
    void setAnimationRepeatCount(int repeatCount) {
        mAnimationRepeatCount = repeatCount;
    }

    private class DeviceStateManagerCallback implements DeviceStateManager.DeviceStateCallback {
        @Override
        public void onBaseStateChanged(int state) {
            if (mStartedFolded && !isFoldedState(state)) {
                // We've opened the device, we can close the overlay
                mRearDisplayEducationDialog.dismiss();
                closeOverlayAndNotifyService(false);
            } else if (!mStartedFolded && isFoldedState(state)) {
                // We've closed the device, finish activity
                mRearDisplayEducationDialog.dismiss();
                closeOverlayAndNotifyService(true);
            }
        }

        // We only care about physical device changes in this scenario
        @Override
        public void onStateChanged(int state) {}
    }
}

