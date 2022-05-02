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

package com.android.systemui.media.dialog;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewStub;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.graphics.drawable.IconCompat;

import com.android.settingslib.qrcode.QrCodeGenerator;
import com.android.systemui.R;
import com.android.systemui.broadcast.BroadcastSender;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.statusbar.phone.SystemUIDialog;

import com.google.zxing.WriterException;

/**
 * Dialog for media output broadcast.
 */
@SysUISingleton
public class MediaOutputBroadcastDialog extends MediaOutputBaseDialog {
    private static final String TAG = "BroadcastDialog";

    private ViewStub mBroadcastInfoArea;
    private ImageView mBroadcastQrCodeView;
    private ImageView mBroadcastNotify;
    private TextView mBroadcastName;
    private ImageView mBroadcastNameEdit;
    private TextView mBroadcastCode;
    private ImageView mBroadcastCodeEye;
    private Boolean mIsPasswordHide = true;
    private ImageView mBroadcastCodeEdit;
    private AlertDialog mAlertDialog;
    private TextView mBroadcastErrorMessage;

    static final int METADATA_BROADCAST_NAME = 0;
    static final int METADATA_BROADCAST_CODE = 1;

    private static final int MAX_BROADCAST_INFO_UPDATE = 3;

    MediaOutputBroadcastDialog(Context context, boolean aboveStatusbar,
            BroadcastSender broadcastSender, MediaOutputController mediaOutputController) {
        super(context, broadcastSender, mediaOutputController);
        mAdapter = new MediaOutputGroupAdapter(mMediaOutputController);
        // TODO(b/226710953): Move the part to MediaOutputBaseDialog for every class
        //  that extends MediaOutputBaseDialog
        if (!aboveStatusbar) {
            getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        initBtQrCodeUI();
    }

    @Override
    int getHeaderIconRes() {
        return 0;
    }

    @Override
    IconCompat getHeaderIcon() {
        return mMediaOutputController.getHeaderIcon();
    }

    @Override
    int getHeaderIconSize() {
        return mContext.getResources().getDimensionPixelSize(
                R.dimen.media_output_dialog_header_album_icon_size);
    }

    @Override
    CharSequence getHeaderText() {
        return mMediaOutputController.getHeaderTitle();
    }

    @Override
    CharSequence getHeaderSubtitle() {
        return mMediaOutputController.getHeaderSubTitle();
    }

    @Override
    Drawable getAppSourceIcon() {
        return mMediaOutputController.getAppSourceIcon();
    }

    @Override
    int getStopButtonVisibility() {
        return View.VISIBLE;
    }

    @Override
    public void onStopButtonClick() {
        mMediaOutputController.stopBluetoothLeBroadcast();
        dismiss();
    }

    private String getBroadcastMetadataInfo(int metadata) {
        switch (metadata) {
            case METADATA_BROADCAST_NAME:
                return mMediaOutputController.getBroadcastName();
            case METADATA_BROADCAST_CODE:
                return mMediaOutputController.getBroadcastCode();
            default:
                return "";
        }
    }

    private void initBtQrCodeUI() {
        //add the view to xml
        inflateBroadcastInfoArea();

        //init UI component
        mBroadcastQrCodeView = getDialogView().requireViewById(R.id.qrcode_view);
        //Set the QR code view
        setQrCodeView();

        mBroadcastNotify = getDialogView().requireViewById(R.id.broadcast_info);
        mBroadcastNotify.setOnClickListener(v -> {
            mMediaOutputController.launchLeBroadcastNotifyDialog(
                    /* view= */ null,
                    /* broadcastSender= */ null,
                    MediaOutputController.BroadcastNotifyDialog.ACTION_BROADCAST_INFO_ICON,
                    /* onClickListener= */ null);
        });
        mBroadcastName = getDialogView().requireViewById(R.id.broadcast_name_summary);
        mBroadcastNameEdit = getDialogView().requireViewById(R.id.broadcast_name_edit);
        mBroadcastNameEdit.setOnClickListener(v -> {
            launchBroadcastUpdatedDialog(false, mBroadcastName.getText().toString());
        });
        mBroadcastCode = getDialogView().requireViewById(R.id.broadcast_code_summary);
        mBroadcastCode.setTransformationMethod(PasswordTransformationMethod.getInstance());
        mBroadcastCodeEye = getDialogView().requireViewById(R.id.broadcast_code_eye);
        mBroadcastCodeEye.setOnClickListener(v -> {
            updateBroadcastCodeVisibility();
        });
        mBroadcastCodeEdit = getDialogView().requireViewById(R.id.broadcast_code_edit);
        mBroadcastCodeEdit.setOnClickListener(v -> {
            launchBroadcastUpdatedDialog(true, mBroadcastCode.getText().toString());
        });

        mBroadcastName.setText(getBroadcastMetadataInfo(METADATA_BROADCAST_NAME));
        mBroadcastCode.setText(getBroadcastMetadataInfo(METADATA_BROADCAST_CODE));
    }

    private void inflateBroadcastInfoArea() {
        mBroadcastInfoArea = getDialogView().requireViewById(R.id.broadcast_qrcode);
        mBroadcastInfoArea.inflate();
    }

    private void setQrCodeView() {
        //get the Metadata, and convert to BT QR code format.
        String broadcastMetadata = getBroadcastMetadata();
        if (broadcastMetadata.isEmpty()) {
            //TDOD(b/226708424) Error handling for unable to generate the QR code bitmap
            return;
        }
        try {
            final int qrcodeSize = getContext().getResources().getDimensionPixelSize(
                    R.dimen.media_output_qrcode_size);
            final Bitmap bmp = QrCodeGenerator.encodeQrCode(broadcastMetadata, qrcodeSize);
            mBroadcastQrCodeView.setImageBitmap(bmp);
        } catch (WriterException e) {
            //TDOD(b/226708424) Error handling for unable to generate the QR code bitmap
            Log.e(TAG, "Error generatirng QR code bitmap " + e);
        }
    }

    private void updateBroadcastCodeVisibility() {
        mBroadcastCode.setTransformationMethod(
                mIsPasswordHide ? HideReturnsTransformationMethod.getInstance()
                        : PasswordTransformationMethod.getInstance());
        mIsPasswordHide = !mIsPasswordHide;
    }

    private void launchBroadcastUpdatedDialog(boolean isBroadcastCode, String editString) {
        final View layout = LayoutInflater.from(mContext).inflate(
                R.layout.media_output_broadcast_update_dialog, null);
        final EditText editText = layout.requireViewById(R.id.broadcast_edit_text);
        editText.setText(editString);
        mBroadcastErrorMessage = layout.requireViewById(R.id.broadcast_error_message);
        mAlertDialog = new Builder(mContext)
                .setTitle(isBroadcastCode ? R.string.media_output_broadcast_code
                    : R.string.media_output_broadcast_name)
                .setView(layout)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.media_output_broadcast_dialog_save,
                        (d, w) -> {
                            updateBroadcastInfo(isBroadcastCode, editText.getText().toString());
                        })
                .create();

        mAlertDialog.getWindow().setType(WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);
        SystemUIDialog.setShowForAllUsers(mAlertDialog, true);
        SystemUIDialog.registerDismissListener(mAlertDialog);
        mAlertDialog.show();
    }

    private String getBroadcastMetadata() {
        return mMediaOutputController.getBroadcastMetadata();
    }

    private void updateBroadcastInfo(boolean isBroadcastCode, String updatedString) {
        Button positiveBtn = mAlertDialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (positiveBtn != null) {
            positiveBtn.setEnabled(false);
        }

        if (isBroadcastCode) {
            handleBroadcastCodeUpdated(updatedString);
        } else {
            handleBroadcastNameUpdated(updatedString);
        }
    }

    private void handleBroadcastNameUpdated(String name) {
        // TODO(b/230473995) Add the retry mechanism and error handling when update fails
        String currentName = mMediaOutputController.getBroadcastName();
        int retryCount = MAX_BROADCAST_INFO_UPDATE;
        mMediaOutputController.setBroadcastName(name);
        if (!mMediaOutputController.updateBluetoothLeBroadcast()) {
            mMediaOutputController.setBroadcastName(currentName);
            handleLeUpdateBroadcastFailed(retryCount);
        }
    }

    private void handleBroadcastCodeUpdated(String newPassword) {
        // TODO(b/230473995) Add the retry mechanism and error handling when update fails
        String currentPassword = mMediaOutputController.getBroadcastCode();
        int retryCount = MAX_BROADCAST_INFO_UPDATE;
        if (!mMediaOutputController.stopBluetoothLeBroadcast()) {
            mMediaOutputController.setBroadcastCode(currentPassword);
            handleLeUpdateBroadcastFailed(retryCount);
            return;
        }

        mMediaOutputController.setBroadcastCode(newPassword);
        if (!mMediaOutputController.startBluetoothLeBroadcast()) {
            mMediaOutputController.setBroadcastCode(currentPassword);
            handleLeUpdateBroadcastFailed(retryCount);
            return;
        }

        mAlertDialog.dismiss();
    }

    private void handleLeUpdateBroadcastFailed(int retryCount) {
        final Button positiveBtn = mAlertDialog.getButton(AlertDialog.BUTTON_POSITIVE);
        mBroadcastErrorMessage.setVisibility(View.VISIBLE);
        if (retryCount < MAX_BROADCAST_INFO_UPDATE) {
            if (positiveBtn != null) {
                positiveBtn.setEnabled(true);
            }
            mBroadcastErrorMessage.setText(R.string.media_output_broadcast_update_error);
        } else {
            mBroadcastErrorMessage.setText(R.string.media_output_broadcast_last_update_error);
        }
    }
}
