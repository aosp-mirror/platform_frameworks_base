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
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.multiuser.Flags;
import android.net.Uri;
import android.util.Log;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.util.UserIcons;
import com.android.settingslib.drawable.CircleFramedDrawable;
import com.android.settingslib.utils.ThreadUtils;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * This class contains logic for starting activities to take/choose/crop photo, reads and transforms
 * the result image.
 */
public class EditUserPhotoController {
    private static final String TAG = "EditUserPhotoController";

    // It seems that this class generates custom request codes and they may
    // collide with ours, these values are very unlikely to have a conflict.
    private static final int REQUEST_CODE_PICK_AVATAR = 1004;

    private static final String IMAGES_DIR = "multi_user";
    private static final String NEW_USER_PHOTO_FILE_NAME = "NewUserPhoto.png";

    private static final String AVATAR_PICKER_ACTION = "com.android.avatarpicker"
            + ".FULL_SCREEN_ACTIVITY";
    static final String EXTRA_IS_USER_NEW = "is_user_new";

    private final Activity mActivity;
    private final ActivityStarter mActivityStarter;
    private final ImageView mImageView;
    private final String mFileAuthority;
    private final ListeningExecutorService mExecutorService;
    private final File mImagesDir;
    private Bitmap mNewUserPhotoBitmap;
    private Drawable mNewUserPhotoDrawable;
    private String mCachedDrawablePath;
    public EditUserPhotoController(Activity activity, ActivityStarter activityStarter,
            ImageView view, Bitmap savedBitmap, Drawable savedDrawable, String fileAuthority) {
        this(activity, activityStarter, view, savedBitmap, savedDrawable, fileAuthority, true);
    }
    public EditUserPhotoController(Activity activity, ActivityStarter activityStarter,
            ImageView view, Bitmap savedBitmap, Drawable savedDrawable, String fileAuthority,
            boolean isUserNew) {
        mActivity = activity;
        mActivityStarter = activityStarter;
        mFileAuthority = fileAuthority;

        mImagesDir = new File(activity.getCacheDir(), IMAGES_DIR);
        mImagesDir.mkdir();
        mImageView = view;
        mImageView.setOnClickListener(v -> showAvatarPicker(isUserNew));

        mNewUserPhotoBitmap = savedBitmap;
        mNewUserPhotoDrawable = savedDrawable;
        mExecutorService = ThreadUtils.getBackgroundExecutor();
    }

    /**
     * Handles activity result from containing activity/fragment after a take/choose/crop photo
     * action result is received.
     */
    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK) {
            return false;
        }

        if (requestCode == REQUEST_CODE_PICK_AVATAR) {
            if (data.hasExtra(AvatarPickerActivity.EXTRA_DEFAULT_ICON_TINT_COLOR)) {
                int tintColor =
                        data.getIntExtra(AvatarPickerActivity.EXTRA_DEFAULT_ICON_TINT_COLOR, -1);
                onDefaultIconSelected(tintColor);
                return true;
            }
            if (data.getData() != null) {
                onPhotoCropped(data.getData());
                return true;
            }
        }
        return false;
    }

    public Drawable getNewUserPhotoDrawable() {
        return mNewUserPhotoDrawable;
    }

    private void showAvatarPicker(boolean isUserNew) {
        Intent intent;
        if (Flags.avatarSync()) {
            intent = new Intent(AVATAR_PICKER_ACTION);
            intent.addCategory(Intent.CATEGORY_DEFAULT);
            intent.putExtra(EXTRA_IS_USER_NEW, isUserNew);
        } else {
            intent = new Intent(mImageView.getContext(), AvatarPickerActivity.class);
        }
        intent.putExtra(AvatarPickerActivity.EXTRA_FILE_AUTHORITY, mFileAuthority);
        mActivityStarter.startActivityForResult(intent, REQUEST_CODE_PICK_AVATAR);
    }

    private void onDefaultIconSelected(int tintColor) {
        ListenableFuture<Bitmap> future = mExecutorService.submit(() -> {
            Resources res = mActivity.getResources();
            Drawable drawable =
                    UserIcons.getDefaultUserIconInColor(res, tintColor);
            return UserIcons.convertToBitmapAtUserIconSize(res, drawable);
        });
        Futures.addCallback(future, new FutureCallback<>() {
            @Override
            public void onSuccess(@NonNull Bitmap result) {
                onPhotoProcessed(result);
            }

            @Override
            public void onFailure(Throwable t) {
                Log.e(TAG, "Error processing default icon", t);
            }
        }, mImageView.getContext().getMainExecutor());
    }

    private void onPhotoCropped(final Uri data) {
        ListenableFuture<Bitmap> future = mExecutorService.submit(() -> {
            InputStream imageStream = null;
            Bitmap bitmap = null;
            try {
                imageStream = mActivity.getContentResolver()
                        .openInputStream(data);
                bitmap = BitmapFactory.decodeStream(imageStream);
            } catch (FileNotFoundException fe) {
                Log.w(TAG, "Cannot find image file", fe);
            } finally {
                if (imageStream != null) {
                    try {
                        imageStream.close();
                    } catch (IOException ioe) {
                        Log.w(TAG, "Cannot close image stream", ioe);
                    }
                }
            }
            return bitmap;
        });
        Futures.addCallback(future, new FutureCallback<>() {
            @Override
            public void onSuccess(@Nullable Bitmap result) {
                onPhotoProcessed(result);
            }

            @Override
            public void onFailure(Throwable t) {}
        }, mImageView.getContext().getMainExecutor());
    }

    private void onPhotoProcessed(@Nullable Bitmap bitmap) {
        if (bitmap != null) {
            mNewUserPhotoBitmap = bitmap;
            var unused = mExecutorService.submit(() -> {
                mCachedDrawablePath = saveNewUserPhotoBitmap().getPath();
            });
            mNewUserPhotoDrawable = CircleFramedDrawable
                    .getInstance(mImageView.getContext(), mNewUserPhotoBitmap);
            mImageView.setImageDrawable(mNewUserPhotoDrawable);
        }
    }

    File saveNewUserPhotoBitmap() {
        if (mNewUserPhotoBitmap == null) {
            return null;
        }
        try {
            File file = new File(mImagesDir, NEW_USER_PHOTO_FILE_NAME);
            OutputStream os = new FileOutputStream(file);
            mNewUserPhotoBitmap.compress(Bitmap.CompressFormat.PNG, 100, os);
            os.flush();
            os.close();
            return file;
        } catch (IOException e) {
            Log.e(TAG, "Cannot create temp file", e);
        }
        return null;
    }

    static Bitmap loadNewUserPhotoBitmap(File file) {
        return BitmapFactory.decodeFile(file.getAbsolutePath());
    }

    void removeNewUserPhotoBitmapFile() {
        new File(mImagesDir, NEW_USER_PHOTO_FILE_NAME).delete();
    }

    String getCachedDrawablePath() {
        return mCachedDrawablePath;
    }
}
