/*
 * Copyright (C) 2023 The Android Open Source Project
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

import android.annotation.IntDef;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.UserHandle;
import android.os.UserManager;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.RadioButton;
import android.widget.RadioGroup;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.android.internal.util.UserIcons;
import com.android.settingslib.R;
import com.android.settingslib.RestrictedLockUtils;
import com.android.settingslib.RestrictedLockUtilsInternal;
import com.android.settingslib.drawable.CircleFramedDrawable;
import com.android.settingslib.utils.CustomDialogHelper;
import com.android.settingslib.utils.ThreadUtils;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * This class encapsulates a Dialog for editing the user nickname and photo.
 */
public class CreateUserDialogController {

    private static final String KEY_AWAITING_RESULT = "awaiting_result";
    private static final String KEY_CURRENT_STATE = "current_state";
    private static final String KEY_SAVED_PHOTO = "pending_photo";
    private static final String KEY_SAVED_NAME = "saved_name";
    private static final String KEY_IS_ADMIN = "admin_status";
    private static final String KEY_ADD_USER_LONG_MESSAGE_DISPLAYED =
            "key_add_user_long_message_displayed";
    public static final int MESSAGE_PADDING = 10;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({EXIT_DIALOG, INITIAL_DIALOG, GRANT_ADMIN_DIALOG,
            EDIT_NAME_DIALOG, CREATE_USER_AND_CLOSE})
    public @interface AddUserState {}

    private static final int EXIT_DIALOG = -1;
    private static final int INITIAL_DIALOG = 0;
    private static final int GRANT_ADMIN_DIALOG = 1;
    private static final int EDIT_NAME_DIALOG = 2;
    private static final int CREATE_USER_AND_CLOSE = 3;

    private @AddUserState int mCurrentState;

    private CustomDialogHelper mCustomDialogHelper;

    private EditUserPhotoController mEditUserPhotoController;
    private Bitmap mSavedPhoto;
    private String mSavedName;
    private Drawable mSavedDrawable;
    private String mCachedDrawablePath;
    private String mUserName;
    private Drawable mNewUserIcon;
    private Boolean mIsAdmin;
    private Dialog mUserCreationDialog;
    private View mGrantAdminView;
    private View mEditUserInfoView;
    private EditText mUserNameView;
    private Activity mActivity;
    private ActivityStarter mActivityStarter;
    private boolean mWaitingForActivityResult;
    private NewUserData mSuccessCallback;
    private Runnable mCancelCallback;

    private final String mFileAuthority;

    public CreateUserDialogController(String fileAuthority) {
        mFileAuthority = fileAuthority;
    }

    /**
     * Resets saved values.
     */
    public void clear() {
        mUserCreationDialog = null;
        mCustomDialogHelper = null;
        mEditUserPhotoController = null;
        mSavedPhoto = null;
        mSavedName = null;
        mSavedDrawable = null;
        mIsAdmin = null;
        mActivity = null;
        mActivityStarter = null;
        mGrantAdminView = null;
        mEditUserInfoView = null;
        mUserNameView = null;
        mSuccessCallback = null;
        mCancelCallback = null;
        mCachedDrawablePath = null;
        mCurrentState = INITIAL_DIALOG;
    }

    /**
     * Notifies that the containing activity or fragment was reinitialized.
     */
    public void onRestoreInstanceState(Bundle savedInstanceState) {
        mCachedDrawablePath = savedInstanceState.getString(KEY_SAVED_PHOTO);
        mCurrentState = savedInstanceState.getInt(KEY_CURRENT_STATE);
        if (savedInstanceState.containsKey(KEY_IS_ADMIN)) {
            mIsAdmin = savedInstanceState.getBoolean(KEY_IS_ADMIN);
        }
        mSavedName = savedInstanceState.getString(KEY_SAVED_NAME);
        mWaitingForActivityResult = savedInstanceState.getBoolean(KEY_AWAITING_RESULT, false);
    }

    /**
     * Notifies that the containing activity or fragment is saving its state for later use.
     */
    public void onSaveInstanceState(Bundle savedInstanceState) {
        if (mUserCreationDialog != null && mEditUserPhotoController != null
                && mCachedDrawablePath == null) {
            mCachedDrawablePath = mEditUserPhotoController.getCachedDrawablePath();
        }
        if (mCachedDrawablePath != null) {
            savedInstanceState.putString(KEY_SAVED_PHOTO, mCachedDrawablePath);
        }
        if (mIsAdmin != null) {
            savedInstanceState.putBoolean(KEY_IS_ADMIN, Boolean.TRUE.equals(mIsAdmin));
        }
        savedInstanceState.putString(KEY_SAVED_NAME, mUserNameView.getText().toString().trim());
        savedInstanceState.putInt(KEY_CURRENT_STATE, mCurrentState);
        savedInstanceState.putBoolean(KEY_AWAITING_RESULT, mWaitingForActivityResult);
    }

    /**
     * Notifies that an activity has started.
     */
    public void startingActivityForResult() {
        mWaitingForActivityResult = true;
    }

    /**
     * Notifies that the result from activity has been received.
     */
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        mWaitingForActivityResult = false;
        if (mEditUserPhotoController != null) {
            mEditUserPhotoController.onActivityResult(requestCode, resultCode, data);
        }
    }

    /**
     * Creates an add user dialog with option to set the user's name and photo and choose their
     * admin status.
     */
    public Dialog createDialog(Activity activity,
            ActivityStarter activityStarter, boolean isMultipleAdminEnabled,
            NewUserData successCallback, Runnable cancelCallback) {
        mActivity = activity;
        mCustomDialogHelper = new CustomDialogHelper(activity);
        mSuccessCallback = successCallback;
        mCancelCallback = cancelCallback;
        mActivityStarter = activityStarter;
        addCustomViews(isMultipleAdminEnabled);
        mUserCreationDialog = mCustomDialogHelper.getDialog();
        updateLayout();
        mUserCreationDialog.setOnDismissListener(view -> finish());
        mCustomDialogHelper.setMessagePadding(MESSAGE_PADDING);
        mUserCreationDialog.setCanceledOnTouchOutside(true);
        return mUserCreationDialog;
    }

    private void addCustomViews(boolean isMultipleAdminEnabled) {
        addGrantAdminView();
        addUserInfoEditView();
        mCustomDialogHelper.setPositiveButton(R.string.next, view -> {
            mCurrentState++;
            if (mCurrentState == GRANT_ADMIN_DIALOG && !isMultipleAdminEnabled) {
                mCurrentState++;
            }
            updateLayout();
        });
        mCustomDialogHelper.setNegativeButton(R.string.back, view -> {
            mCurrentState--;
            if (mCurrentState == GRANT_ADMIN_DIALOG && !isMultipleAdminEnabled) {
                mCurrentState--;
            }
            updateLayout();
        });
    }

    private void updateLayout() {
        switch (mCurrentState) {
            case INITIAL_DIALOG:
                mEditUserInfoView.setVisibility(View.GONE);
                mGrantAdminView.setVisibility(View.GONE);
                final SharedPreferences preferences = mActivity.getPreferences(
                        Context.MODE_PRIVATE);
                final boolean longMessageDisplayed = preferences.getBoolean(
                        KEY_ADD_USER_LONG_MESSAGE_DISPLAYED, false);
                final int messageResId = longMessageDisplayed
                        ? R.string.user_add_user_message_short
                        : R.string.user_add_user_message_long;
                if (!longMessageDisplayed) {
                    preferences.edit().putBoolean(
                            KEY_ADD_USER_LONG_MESSAGE_DISPLAYED,
                            true).apply();
                }
                Drawable icon = mActivity.getDrawable(R.drawable.ic_person_add);
                mCustomDialogHelper.setVisibility(mCustomDialogHelper.ICON, true)
                        .setVisibility(mCustomDialogHelper.MESSAGE, true)
                        .setIcon(icon)
                        .setButtonEnabled(true)
                        .setTitle(R.string.user_add_user_title)
                        .setMessage(messageResId)
                        .setNegativeButtonText(R.string.cancel)
                        .setPositiveButtonText(R.string.next);
                mCustomDialogHelper.requestFocusOnTitle();
                break;
            case GRANT_ADMIN_DIALOG:
                mEditUserInfoView.setVisibility(View.GONE);
                mGrantAdminView.setVisibility(View.VISIBLE);
                mCustomDialogHelper
                        .setVisibility(mCustomDialogHelper.ICON, true)
                        .setVisibility(mCustomDialogHelper.MESSAGE, true)
                        .setIcon(mActivity.getDrawable(R.drawable.ic_admin_panel_settings))
                        .setTitle(R.string.user_grant_admin_title)
                        .setMessage(R.string.user_grant_admin_message)
                        .setNegativeButtonText(R.string.back)
                        .setPositiveButtonText(R.string.next);
                mCustomDialogHelper.requestFocusOnTitle();
                if (mIsAdmin == null) {
                    mCustomDialogHelper.setButtonEnabled(false);
                }
                break;
            case EDIT_NAME_DIALOG:
                mCustomDialogHelper
                        .setVisibility(mCustomDialogHelper.ICON, false)
                        .setVisibility(mCustomDialogHelper.MESSAGE, false)
                        .setTitle(R.string.user_info_settings_title)
                        .setNegativeButtonText(R.string.back)
                        .setPositiveButtonText(R.string.done);
                mCustomDialogHelper.requestFocusOnTitle();
                mEditUserInfoView.setVisibility(View.VISIBLE);
                mGrantAdminView.setVisibility(View.GONE);
                break;
            case CREATE_USER_AND_CLOSE:
                mNewUserIcon = (mEditUserPhotoController != null
                        && mEditUserPhotoController.getNewUserPhotoDrawable() != null)
                        ? mEditUserPhotoController.getNewUserPhotoDrawable()
                        : mSavedDrawable;
                String newName = mUserNameView.getText().toString().trim();
                String defaultName = mActivity.getString(R.string.user_new_user_name);
                mUserName = !newName.isEmpty() ? newName : defaultName;
                mCustomDialogHelper.getDialog().dismiss();
                break;
            case EXIT_DIALOG:
                mCustomDialogHelper.getDialog().dismiss();
                break;
            default:
                if (mCurrentState < EXIT_DIALOG) {
                    mCurrentState = EXIT_DIALOG;
                    updateLayout();
                } else {
                    mCurrentState = CREATE_USER_AND_CLOSE;
                    updateLayout();
                }
                break;
        }
    }

    private void setUserIcon(Drawable defaultUserIcon, ImageView userPhotoView) {
        if (mCachedDrawablePath != null) {
            ListenableFuture<Drawable> future = ThreadUtils.getBackgroundExecutor()
                    .submit(() -> {
                        mSavedPhoto = EditUserPhotoController.loadNewUserPhotoBitmap(
                                new File(mCachedDrawablePath));
                        mSavedDrawable = CircleFramedDrawable.getInstance(mActivity, mSavedPhoto);
                        return mSavedDrawable;
                    });
            Futures.addCallback(future, new FutureCallback<>() {
                @Override
                public void onSuccess(@NonNull Drawable result) {
                    userPhotoView.setImageDrawable(result);
                }

                @Override
                public void onFailure(Throwable t) {}
            }, mActivity.getMainExecutor());
        } else {
            userPhotoView.setImageDrawable(defaultUserIcon);
        }
    }

    private void addUserInfoEditView() {
        mEditUserInfoView = View.inflate(mActivity, R.layout.edit_user_info_dialog_content, null);
        mCustomDialogHelper.addCustomView(mEditUserInfoView);
        setUserName();
        ImageView userPhotoView = mEditUserInfoView.findViewById(R.id.user_photo);

        // if oldUserIcon param is null then we use a default gray user icon
        Drawable defaultUserIcon = UserIcons.getDefaultUserIcon(
                mActivity.getResources(), UserHandle.USER_NULL, false);
        setUserIcon(defaultUserIcon, userPhotoView);
        if (isChangePhotoRestrictedByBase(mActivity)) {
            // some users can't change their photos so we need to remove the suggestive icon
            mEditUserInfoView.findViewById(R.id.add_a_photo_icon).setVisibility(View.GONE);
        } else {
            RestrictedLockUtils.EnforcedAdmin adminRestriction =
                    getChangePhotoAdminRestriction(mActivity);
            if (adminRestriction != null) {
                userPhotoView.setOnClickListener(view ->
                        RestrictedLockUtils.sendShowAdminSupportDetailsIntent(
                                mActivity, adminRestriction));
            } else {
                mEditUserPhotoController = createEditUserPhotoController(userPhotoView);
            }
        }
    }

    private void setUserName() {
        mUserNameView = mEditUserInfoView.findViewById(R.id.user_name);
        if (mSavedName == null) {
            mUserNameView.setText(R.string.user_new_user_name);
        } else {
            mUserNameView.setText(mSavedName);
        }
    }

    private void addGrantAdminView() {
        mGrantAdminView = View.inflate(mActivity, R.layout.grant_admin_dialog_content, null);
        mCustomDialogHelper.addCustomView(mGrantAdminView);
        RadioGroup radioGroup = mGrantAdminView.findViewById(R.id.choose_admin);
        radioGroup.setOnCheckedChangeListener((group, checkedId) -> {
                    mCustomDialogHelper.setButtonEnabled(true);
                    mIsAdmin = checkedId == R.id.grant_admin_yes;
                }
        );
        if (Boolean.TRUE.equals(mIsAdmin)) {
            RadioButton button = radioGroup.findViewById(R.id.grant_admin_yes);
            button.setChecked(true);
        } else if (Boolean.FALSE.equals(mIsAdmin)) {
            RadioButton button = radioGroup.findViewById(R.id.grant_admin_no);
            button.setChecked(true);
        }
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
    EditUserPhotoController createEditUserPhotoController(ImageView userPhotoView) {
        return new EditUserPhotoController(mActivity, mActivityStarter, userPhotoView,
                mSavedPhoto, mSavedDrawable, mFileAuthority);
    }

    public boolean isActive() {
        return mCustomDialogHelper != null && mCustomDialogHelper.getDialog() != null;
    }

    /**
     * Runs callback and clears saved values after dialog is dismissed.
     */
    public void finish() {
        if (mCurrentState == CREATE_USER_AND_CLOSE) {
            if (mSuccessCallback != null) {
                mSuccessCallback.onSuccess(mUserName, mNewUserIcon, Boolean.TRUE.equals(mIsAdmin));
            }
        } else {
            if (mCancelCallback != null) {
                mCancelCallback.run();
            }
        }
        clear();
    }
}
