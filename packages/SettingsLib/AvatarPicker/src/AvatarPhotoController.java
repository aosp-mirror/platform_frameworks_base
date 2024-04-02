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

package com.android.settingslib.avatarpicker;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.StrictMode;
import android.provider.MediaStore;
import android.util.EventLog;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import libcore.io.Streams;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

class AvatarPhotoController {

    interface AvatarUi {
        boolean isFinishing();

        void returnUriResult(Uri uri);

        void startActivityForResult(Intent intent, int resultCode);

        boolean startSystemActivityForResult(Intent intent, int resultCode);

        int getPhotoSize();
    }

    interface ContextInjector {
        File getCacheDir();

        Uri createTempImageUri(File parentDir, String fileName, boolean purge);

        ContentResolver getContentResolver();

        Context getContext();
    }

    private static final String TAG = "AvatarPhotoController";

    static final int REQUEST_CODE_CHOOSE_PHOTO = 1001;
    static final int REQUEST_CODE_TAKE_PHOTO = 1002;
    static final int REQUEST_CODE_CROP_PHOTO = 1003;

    /**
     * Delay to allow the photo picker exit animation to complete before the crop activity opens.
     */
    private static final long DELAY_BEFORE_CROP_MILLIS = 150;

    private static final String IMAGES_DIR = "multi_user";
    private static final String PRE_CROP_PICTURE_FILE_NAME = "PreCropEditUserPhoto.jpg";
    private static final String CROP_PICTURE_FILE_NAME = "CropEditUserPhoto.jpg";
    private static final String TAKE_PICTURE_FILE_NAME = "TakeEditUserPhoto.jpg";

    private final int mPhotoSize;

    private final AvatarUi mAvatarUi;
    private final ContextInjector mContextInjector;

    private final File mImagesDir;
    private final Uri mPreCropPictureUri;
    private final Uri mCropPictureUri;
    private final Uri mTakePictureUri;

    AvatarPhotoController(AvatarUi avatarUi, ContextInjector contextInjector, boolean waiting) {
        mAvatarUi = avatarUi;
        mContextInjector = contextInjector;

        mImagesDir = new File(mContextInjector.getCacheDir(), IMAGES_DIR);
        mImagesDir.mkdir();
        mPreCropPictureUri = mContextInjector
                .createTempImageUri(mImagesDir, PRE_CROP_PICTURE_FILE_NAME, !waiting);
        mCropPictureUri =
                mContextInjector.createTempImageUri(mImagesDir, CROP_PICTURE_FILE_NAME, !waiting);
        mTakePictureUri =
                mContextInjector.createTempImageUri(mImagesDir, TAKE_PICTURE_FILE_NAME, !waiting);
        mPhotoSize = mAvatarUi.getPhotoSize();
    }

    /**
     * Handles activity result from containing activity/fragment after a take/choose/crop photo
     * action result is received.
     */
    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK) {
            return false;
        }
        final Uri pictureUri = data != null && data.getData() != null
                ? data.getData() : mTakePictureUri;

        // Check if the result is a content uri
        if (!ContentResolver.SCHEME_CONTENT.equals(pictureUri.getScheme())) {
            Log.e(TAG, "Invalid pictureUri scheme: " + pictureUri.getScheme());
            EventLog.writeEvent(0x534e4554, "172939189", -1, pictureUri.getPath());
            return false;
        }

        switch (requestCode) {
            case REQUEST_CODE_CROP_PHOTO:
                mAvatarUi.returnUriResult(pictureUri);
                return true;
            case REQUEST_CODE_TAKE_PHOTO:
                if (mTakePictureUri.equals(pictureUri)) {
                    cropPhoto(pictureUri);
                } else {
                    copyAndCropPhoto(pictureUri, false);
                }
                return true;
            case REQUEST_CODE_CHOOSE_PHOTO:
                copyAndCropPhoto(pictureUri, true);
                return true;
        }
        return false;
    }

    void takePhoto() {
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE_SECURE);
        appendOutputExtra(intent, mTakePictureUri);
        mAvatarUi.startActivityForResult(intent, REQUEST_CODE_TAKE_PHOTO);
    }

    void choosePhoto() {
        Intent intent = new Intent(MediaStore.ACTION_PICK_IMAGES, null);
        intent.setType("image/*");
        mAvatarUi.startActivityForResult(intent, REQUEST_CODE_CHOOSE_PHOTO);
    }

    private void copyAndCropPhoto(final Uri pictureUri, boolean delayBeforeCrop) {
        ListenableFuture<Uri> future = ThreadUtils.getBackgroundExecutor().submit(() -> {
            final ContentResolver cr = mContextInjector.getContentResolver();
            try {
                InputStream in = cr.openInputStream(pictureUri);
                OutputStream out = cr.openOutputStream(mPreCropPictureUri);
                Streams.copy(in, out);
                return mPreCropPictureUri;
            } catch (IOException e) {
                Log.w(TAG, "Failed to copy photo", e);
                return null;
            }
        });
        Futures.addCallback(future, new FutureCallback<>() {
            @Override
            public void onSuccess(@Nullable Uri result) {
                if (result == null) {
                    return;
                }
                Runnable cropRunnable = () -> {
                    if (!mAvatarUi.isFinishing()) {
                        cropPhoto(mPreCropPictureUri);
                    }
                };
                if (delayBeforeCrop) {
                    mContextInjector.getContext().getMainThreadHandler()
                            .postDelayed(cropRunnable, DELAY_BEFORE_CROP_MILLIS);
                } else {
                    cropRunnable.run();
                }
            }

            @Override
            public void onFailure(Throwable t) {
                Log.e(TAG, "Error performing copy-and-crop", t);
            }
        }, mContextInjector.getContext().getMainExecutor());
    }

    private void cropPhoto(final Uri pictureUri) {
        // TODO: Use a public intent, when there is one.
        Intent intent = new Intent("com.android.camera.action.CROP");
        intent.setDataAndType(pictureUri, "image/*");
        appendOutputExtra(intent, mCropPictureUri);
        appendCropExtras(intent);
        try {
            StrictMode.disableDeathOnFileUriExposure();
            if (mAvatarUi.startSystemActivityForResult(intent, REQUEST_CODE_CROP_PHOTO)) {
                return;
            }
        } finally {
            StrictMode.enableDeathOnFileUriExposure();
        }
        onPhotoNotCropped(pictureUri);
    }

    private void appendOutputExtra(Intent intent, Uri pictureUri) {
        intent.putExtra(MediaStore.EXTRA_OUTPUT, pictureUri);
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(ClipData.newRawUri(MediaStore.EXTRA_OUTPUT, pictureUri));
    }

    private void appendCropExtras(Intent intent) {
        intent.putExtra("crop", "true");
        intent.putExtra("scale", true);
        intent.putExtra("scaleUpIfNeeded", true);
        intent.putExtra("aspectX", 1);
        intent.putExtra("aspectY", 1);
        intent.putExtra("outputX", mPhotoSize);
        intent.putExtra("outputY", mPhotoSize);
    }

    private void onPhotoNotCropped(final Uri data) {
        ListenableFuture<Bitmap> future = ThreadUtils.getBackgroundExecutor().submit(() -> {
            // Scale and crop to a square aspect ratio
            Bitmap croppedImage = Bitmap.createBitmap(mPhotoSize, mPhotoSize,
                    Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(croppedImage);
            Bitmap fullImage;
            try (InputStream imageStream = mContextInjector.getContentResolver()
                    .openInputStream(data)) {
                fullImage = BitmapFactory.decodeStream(imageStream);
            }
            if (fullImage == null) {
                Log.e(TAG, "Image data could not be decoded");
                return null;
            }
            int rotation = getRotation(data);
            final int squareSize = Math.min(fullImage.getWidth(),
                    fullImage.getHeight());
            final int left = (fullImage.getWidth() - squareSize) / 2;
            final int top = (fullImage.getHeight() - squareSize) / 2;

            Matrix matrix = new Matrix();
            RectF rectSource = new RectF(left, top,
                    left + squareSize, top + squareSize);
            RectF rectDest = new RectF(0, 0, mPhotoSize, mPhotoSize);
            matrix.setRectToRect(rectSource, rectDest, Matrix.ScaleToFit.CENTER);
            matrix.postRotate(rotation, mPhotoSize / 2f, mPhotoSize / 2f);
            canvas.drawBitmap(fullImage, matrix, new Paint());
            saveBitmapToFile(croppedImage, new File(mImagesDir, CROP_PICTURE_FILE_NAME));
            return croppedImage;
        });
        Futures.addCallback(future, new FutureCallback<>() {
            @Override
            public void onSuccess(@Nullable Bitmap result) {
                if (result != null) {
                    mAvatarUi.returnUriResult(mCropPictureUri);
                }
            }

            @Override
            public void onFailure(Throwable t) {
                Log.e(TAG, "Error performing internal crop", t);
            }
        }, mContextInjector.getContext().getMainExecutor());
    }

    /**
     * Reads the image's exif data and determines the rotation degree needed to display the image
     * in portrait mode.
     */
    private int getRotation(Uri selectedImage) {
        int rotation = -1;
        try {
            InputStream imageStream =
                    mContextInjector.getContentResolver().openInputStream(selectedImage);
            ExifInterface exif = new ExifInterface(imageStream);
            rotation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, -1);
        } catch (IOException exception) {
            Log.e(TAG, "Error while getting rotation", exception);
        }

        switch (rotation) {
            case ExifInterface.ORIENTATION_ROTATE_90:
                return 90;
            case ExifInterface.ORIENTATION_ROTATE_180:
                return 180;
            case ExifInterface.ORIENTATION_ROTATE_270:
                return 270;
            default:
                return 0;
        }
    }

    private void saveBitmapToFile(Bitmap bitmap, File file) {
        try {
            OutputStream os = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, os);
            os.flush();
            os.close();
        } catch (IOException e) {
            Log.e(TAG, "Cannot create temp file", e);
        }
    }

    static class AvatarUiImpl implements AvatarUi {
        private final AvatarPickerActivity mActivity;

        AvatarUiImpl(AvatarPickerActivity activity) {
            mActivity = activity;
        }

        @Override
        public boolean isFinishing() {
            return mActivity.isFinishing() || mActivity.isDestroyed();
        }

        @Override
        public void returnUriResult(Uri uri) {
            mActivity.returnUriResult(uri);
        }

        @Override
        public void startActivityForResult(Intent intent, int resultCode) {
            mActivity.startActivityForResult(intent, resultCode);
        }

        @Override
        public boolean startSystemActivityForResult(Intent intent, int code) {
            List<ResolveInfo> resolveInfos = mActivity.getPackageManager()
                    .queryIntentActivities(intent, PackageManager.MATCH_SYSTEM_ONLY);
            if (resolveInfos.isEmpty()) {
                Log.w(TAG, "No system package activity could be found for code " + code);
                return false;
            }
            intent.setPackage(resolveInfos.get(0).activityInfo.packageName);
            mActivity.startActivityForResult(intent, code);
            return true;
        }

        @Override
        public int getPhotoSize() {
            return mActivity.getResources()
                    .getDimensionPixelSize(com.android.internal.R.dimen.user_icon_size);
        }
    }

    static class ContextInjectorImpl implements ContextInjector {
        private final Context mContext;
        private final String mFileAuthority;

        ContextInjectorImpl(Context context, String fileAuthority) {
            mContext = context;
            mFileAuthority = fileAuthority;
        }

        @Override
        public File getCacheDir() {
            return mContext.getCacheDir();
        }

        @Override
        public Uri createTempImageUri(File parentDir, String fileName, boolean purge) {
            final File fullPath = new File(parentDir, fileName);
            if (purge) {
                fullPath.delete();
            }
            return FileProvider.getUriForFile(mContext, mFileAuthority, fullPath);
        }

        @Override
        public ContentResolver getContentResolver() {
            return mContext.getContentResolver();
        }

        @Override
        public Context getContext() {
            return mContext;
        }
    }
}
