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

package com.android.settingslib.users;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.StrictMode;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import android.util.EventLog;
import android.util.Log;

import androidx.core.content.FileProvider;

import libcore.io.Streams;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

class AvatarPhotoController {
    private static final String TAG = "AvatarPhotoController";

    private static final int REQUEST_CODE_CHOOSE_PHOTO = 1001;
    private static final int REQUEST_CODE_TAKE_PHOTO = 1002;
    private static final int REQUEST_CODE_CROP_PHOTO = 1003;
    // in rare cases we get a null Cursor when querying for DisplayPhoto.CONTENT_MAX_DIMENSIONS_URI
    // so we need a default photo size
    private static final int DEFAULT_PHOTO_SIZE = 500;

    private static final String IMAGES_DIR = "multi_user";
    private static final String CROP_PICTURE_FILE_NAME = "CropEditUserPhoto.jpg";
    private static final String TAKE_PICTURE_FILE_NAME = "TakeEditUserPhoto.jpg";

    private final int mPhotoSize;

    private final AvatarPickerActivity mActivity;
    private final String mFileAuthority;

    private final File mImagesDir;
    private final Uri mCropPictureUri;
    private final Uri mTakePictureUri;

    AvatarPhotoController(AvatarPickerActivity activity, boolean waiting, String fileAuthority) {
        mActivity = activity;
        mFileAuthority = fileAuthority;

        mImagesDir = new File(activity.getCacheDir(), IMAGES_DIR);
        mImagesDir.mkdir();
        mCropPictureUri = createTempImageUri(activity, CROP_PICTURE_FILE_NAME, !waiting);
        mTakePictureUri = createTempImageUri(activity, TAKE_PICTURE_FILE_NAME, !waiting);
        mPhotoSize = getPhotoSize(activity);
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
                mActivity.returnUriResult(pictureUri);
                return true;
            case REQUEST_CODE_TAKE_PHOTO:
            case REQUEST_CODE_CHOOSE_PHOTO:
                if (mTakePictureUri.equals(pictureUri)) {
                    if (PhotoCapabilityUtils.canCropPhoto(mActivity)) {
                        cropPhoto();
                    } else {
                        onPhotoNotCropped(pictureUri);
                    }
                } else {
                    copyAndCropPhoto(pictureUri);
                }
                return true;
        }
        return false;
    }

    void takePhoto() {
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE_SECURE);
        appendOutputExtra(intent, mTakePictureUri);
        mActivity.startActivityForResult(intent, REQUEST_CODE_TAKE_PHOTO);
    }

    void choosePhoto() {
        Intent intent = new Intent(MediaStore.ACTION_PICK_IMAGES, null);
        intent.setType("image/*");
        mActivity.startActivityForResult(intent, REQUEST_CODE_CHOOSE_PHOTO);
    }

    private void copyAndCropPhoto(final Uri pictureUri) {
        // TODO: Replace AsyncTask
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                final ContentResolver cr = mActivity.getContentResolver();
                try (InputStream in = cr.openInputStream(pictureUri);
                     OutputStream out = cr.openOutputStream(mTakePictureUri)) {
                    Streams.copy(in, out);
                } catch (IOException e) {
                    Log.w(TAG, "Failed to copy photo", e);
                }
                return null;
            }

            @Override
            protected void onPostExecute(Void result) {
                if (!mActivity.isFinishing() && !mActivity.isDestroyed()) {
                    cropPhoto();
                }
            }
        }.execute();
    }

    private void cropPhoto() {
        // TODO: Use a public intent, when there is one.
        Intent intent = new Intent("com.android.camera.action.CROP");
        intent.setDataAndType(mTakePictureUri, "image/*");
        appendOutputExtra(intent, mCropPictureUri);
        appendCropExtras(intent);
        if (intent.resolveActivity(mActivity.getPackageManager()) != null) {
            try {
                StrictMode.disableDeathOnFileUriExposure();
                mActivity.startActivityForResult(intent, REQUEST_CODE_CROP_PHOTO);
            } finally {
                StrictMode.enableDeathOnFileUriExposure();
            }
        } else {
            onPhotoNotCropped(mTakePictureUri);
        }
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
        // TODO: Replace AsyncTask to avoid possible memory leaks and handle configuration change
        new AsyncTask<Void, Void, Bitmap>() {
            @Override
            protected Bitmap doInBackground(Void... params) {
                // Scale and crop to a square aspect ratio
                Bitmap croppedImage = Bitmap.createBitmap(mPhotoSize, mPhotoSize,
                        Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(croppedImage);
                Bitmap fullImage;
                try {
                    InputStream imageStream = mActivity.getContentResolver()
                            .openInputStream(data);
                    fullImage = BitmapFactory.decodeStream(imageStream);
                } catch (FileNotFoundException fe) {
                    return null;
                }
                if (fullImage != null) {
                    int rotation = getRotation(mActivity, data);
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
                    return croppedImage;
                } else {
                    // Bah! Got nothin.
                    return null;
                }
            }

            @Override
            protected void onPostExecute(Bitmap bitmap) {
                saveBitmapToFile(bitmap, new File(mImagesDir, CROP_PICTURE_FILE_NAME));
                mActivity.returnUriResult(mCropPictureUri);
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, (Void[]) null);
    }

    /**
     * Reads the image's exif data and determines the rotation degree needed to display the image
     * in portrait mode.
     */
    private int getRotation(Context context, Uri selectedImage) {
        int rotation = -1;
        try {
            InputStream imageStream = context.getContentResolver().openInputStream(selectedImage);
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

    private static int getPhotoSize(Context context) {
        try (Cursor cursor = context.getContentResolver().query(
                ContactsContract.DisplayPhoto.CONTENT_MAX_DIMENSIONS_URI,
                new String[]{ContactsContract.DisplayPhoto.DISPLAY_MAX_DIM}, null, null, null)) {
            if (cursor != null) {
                cursor.moveToFirst();
                return cursor.getInt(0);
            } else {
                return DEFAULT_PHOTO_SIZE;
            }
        }
    }

    private Uri createTempImageUri(Context context, String fileName, boolean purge) {
        final File fullPath = new File(mImagesDir, fileName);
        if (purge) {
            fullPath.delete();
        }
        return FileProvider.getUriForFile(context, mFileAuthority, fullPath);
    }
}
