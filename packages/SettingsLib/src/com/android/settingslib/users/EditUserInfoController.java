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

package com.android.settingslib.users;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.UserHandle;
import android.os.UserManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.internal.util.UserIcons;
import com.android.settingslib.R;
import com.android.settingslib.RestrictedLockUtils;
import com.android.settingslib.RestrictedLockUtilsInternal;
import com.android.settingslib.drawable.CircleFramedDrawable;
import com.android.settingslib.utils.CustomDialogHelper;

import java.io.File;
import java.util.function.BiConsumer;

/**
 * This class encapsulates a Dialog for editing the user nickname and photo.
 */
public class EditUserInfoController {

    private static final String KEY_AWAITING_RESULT = "awaiting_result";
    private static final String KEY_SAVED_PHOTO = "pending_photo";

    private Dialog mEditUserInfoDialog;
    private Bitmap mSavedPhoto;
    private Drawable mSavedDrawable;
    private EditUserPhotoController mEditUserPhotoController;
    private boolean mWaitingForActivityResult = false;
    private final String mFileAuthority;

    public EditUserInfoController(String fileAuthority) {
        mFileAuthority = fileAuthority;
    }

    private void clear() {
        if (mEditUserPhotoController != null) {
            mEditUserPhotoController.removeNewUserPhotoBitmapFile();
        }
        mEditUserInfoDialog = null;
        mSavedPhoto = null;
        mSavedDrawable = null;
    }

    /**
     * This should be called when the container activity/fragment got re-initialized from a
     * previously saved state.
     */
    public void onRestoreInstanceState(Bundle icicle) {
        String pendingPhoto = icicle.getString(KEY_SAVED_PHOTO);
        if (pendingPhoto != null) {
            mSavedPhoto = EditUserPhotoController.loadNewUserPhotoBitmap(new File(pendingPhoto));
        }
        mWaitingForActivityResult = icicle.getBoolean(KEY_AWAITING_RESULT, false);
    }

    /**
     * Should be called from the container activity/fragment when it's onSaveInstanceState is
     * called.
     */
    public void onSaveInstanceState(Bundle outState) {
        if (mEditUserInfoDialog != null && mEditUserPhotoController != null) {
            // Bitmap cannot be stored into bundle because it may exceed parcel limit
            // Store it in a temporary file instead
            File file = mEditUserPhotoController.saveNewUserPhotoBitmap();
            if (file != null) {
                outState.putString(KEY_SAVED_PHOTO, file.getPath());
            }
        }
        outState.putBoolean(KEY_AWAITING_RESULT, mWaitingForActivityResult);
    }

    /**
     * Should be called from the container activity/fragment when an activity has started for
     * take/choose/crop photo actions.
     */
    public void startingActivityForResult() {
        mWaitingForActivityResult = true;
    }

    /**
     * Should be called from the container activity/fragment after it receives a result from
     * take/choose/crop photo activity.
     */
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        mWaitingForActivityResult = false;

        if (mEditUserPhotoController != null && mEditUserInfoDialog != null) {
            mEditUserPhotoController.onActivityResult(requestCode, resultCode, data);
        }
    }

    /**
     * Creates a user edit dialog with option to change the user's name and photo.
     *
     * @param activityStarter - ActivityStarter is called with appropriate intents and request
     *                        codes to take photo/choose photo/crop photo.
     */
    public @NonNull Dialog createDialog(@NonNull Activity activity,
            @NonNull ActivityStarter activityStarter, @Nullable Drawable oldUserIcon,
            @Nullable String defaultUserName,
            @Nullable BiConsumer<String, Drawable> successCallback,
            @Nullable Runnable cancelCallback) {
        LayoutInflater inflater = LayoutInflater.from(activity);
        View content = inflater.inflate(R.layout.edit_user_info_dialog_content, null);

        EditText userNameView = content.findViewById(R.id.user_name);
        userNameView.setText(defaultUserName);

        ImageView userPhotoView = content.findViewById(R.id.user_photo);

        // if oldUserIcon param is null then we use a default gray user icon
        Drawable defaultUserIcon = oldUserIcon != null ? oldUserIcon : UserIcons.getDefaultUserIcon(
                activity.getResources(), UserHandle.USER_NULL, false);
        // in case a new photo was selected and the activity got recreated we have to load the image
        Drawable userIcon = getUserIcon(activity, defaultUserIcon);
        userPhotoView.setImageDrawable(userIcon);

        if (isChangePhotoRestrictedByBase(activity)) {
            // some users can't change their photos so we need to remove the suggestive icon
            content.findViewById(R.id.add_a_photo_icon).setVisibility(View.GONE);
        } else {
            RestrictedLockUtils.EnforcedAdmin adminRestriction =
                    getChangePhotoAdminRestriction(activity);
            if (adminRestriction != null) {
                userPhotoView.setOnClickListener(view ->
                        RestrictedLockUtils.sendShowAdminSupportDetailsIntent(
                                activity, adminRestriction));
            } else {
                mEditUserPhotoController = createEditUserPhotoController(activity, activityStarter,
                        userPhotoView);
            }
        }
        mEditUserInfoDialog = buildDialog(activity, content, userNameView, oldUserIcon,
                defaultUserName, successCallback, cancelCallback);

        // Make sure the IME is up.
        mEditUserInfoDialog.getWindow()
                .setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);

        return mEditUserInfoDialog;
    }

    private Drawable getUserIcon(Activity activity, Drawable defaultUserIcon) {
        if (mSavedPhoto != null) {
            mSavedDrawable = CircleFramedDrawable.getInstance(activity, mSavedPhoto);
            return mSavedDrawable;
        }
        return defaultUserIcon;
    }

    private Dialog buildDialog(Activity activity, View content, EditText userNameView,
            @Nullable Drawable oldUserIcon, String defaultUserName,
            BiConsumer<String, Drawable> successCallback, Runnable cancelCallback) {
        CustomDialogHelper dialogHelper = new CustomDialogHelper(activity);
        dialogHelper
                .setTitle(R.string.user_info_settings_title)
                .addCustomView(content)
                .setPositiveButton(R.string.okay, view -> {
                    Drawable newUserIcon = mEditUserPhotoController != null
                            ? mEditUserPhotoController.getNewUserPhotoDrawable()
                            : null;
                    Drawable userIcon = newUserIcon != null
                            ? newUserIcon
                            : oldUserIcon;

                    String newName = userNameView.getText().toString().trim();
                    String userName = !newName.isEmpty() ? newName : defaultUserName;

                    clear();
                    if (successCallback != null) {
                        successCallback.accept(userName, userIcon);
                    }
                    dialogHelper.getDialog().dismiss();
                })
                .setBackButton(R.string.cancel, view -> {
                    clear();
                    if (cancelCallback != null) {
                        cancelCallback.run();
                    }
                    dialogHelper.getDialog().dismiss();
                });
        dialogHelper.getDialog().setOnCancelListener(dialog -> {
            clear();
            if (cancelCallback != null) {
                cancelCallback.run();
            }
            dialogHelper.getDialog().dismiss();
        });
        return dialogHelper.getDialog();
    }

    @VisibleForTesting
    boolean isChangePhotoRestrictedByBase(Context context) {
        return RestrictedLockUtilsInternal.hasBaseUserRestriction(
                context, UserManager.DISALLOW_SET_USER_ICON, UserHandle.myUserId());
    }

    @VisibleForTesting
    RestrictedLockUtils.EnforcedAdmin getChangePhotoAdminRestriction(Context context) {
        return RestrictedLockUtilsInternal.checkIfRestrictionEnforced(
                context, UserManager.DISALLOW_SET_USER_ICON, UserHandle.myUserId());
    }

    @VisibleForTesting
    EditUserPhotoController createEditUserPhotoController(Activity activity,
            ActivityStarter activityStarter, ImageView userPhotoView) {
        return new EditUserPhotoController(activity, activityStarter, userPhotoView,
                mSavedPhoto, mSavedDrawable, mFileAuthority, false);
    }
}
