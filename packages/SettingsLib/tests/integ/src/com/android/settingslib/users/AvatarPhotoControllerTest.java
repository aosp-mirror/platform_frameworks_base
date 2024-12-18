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

import static com.android.settingslib.avatarpicker.AvatarPhotoController.REQUEST_CODE_CHOOSE_PHOTO;
import static com.android.settingslib.avatarpicker.AvatarPhotoController.REQUEST_CODE_CROP_PHOTO;
import static com.android.settingslib.avatarpicker.AvatarPhotoController.REQUEST_CODE_TAKE_PHOTO;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.MediaStore;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

@RunWith(AndroidJUnit4.class)
public class AvatarPhotoControllerTest {

    private static final long TIMEOUT_MILLIS = 5000;
    private static final int PHOTO_SIZE = 200;

    @Mock AvatarPhotoController.AvatarUi mMockAvatarUi;

    private File mImagesDir;
    private AvatarPhotoController mController;
    private Uri mTakePhotoUri = Uri.parse(
            "content://com.android.settingslib.test/my_cache/multi_user/TakeEditUserPhoto.jpg");
    private Uri mCropPhotoUri = Uri.parse(
            "content://com.android.settingslib.test/my_cache/multi_user/CropEditUserPhoto.jpg");
    private Context mContext = InstrumentationRegistry.getTargetContext();

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mMockAvatarUi.getPhotoSize()).thenReturn(PHOTO_SIZE);
        when(mMockAvatarUi.startSystemActivityForResult(any(), anyInt())).thenReturn(true);

        mImagesDir = new File(
                InstrumentationRegistry.getTargetContext().getCacheDir(), "multi_user");
        mImagesDir.mkdir();

        AvatarPhotoController.ContextInjector contextInjector =
                new AvatarPhotoController.ContextInjectorImpl(
                        InstrumentationRegistry.getTargetContext(), "com.android.settingslib.test");
        mController = new AvatarPhotoController(mMockAvatarUi, contextInjector, false);
    }

    @After
    public void tearDown() {
        String[] entries = mImagesDir.list();
        for (String entry : entries) {
            new File(mImagesDir, entry).delete();
        }
        mImagesDir.delete();
    }

    @Test
    public void takePhotoHasCorrectIntentAndResultCode() {
        mController.takePhoto();

        verifyStartActivityForResult(
                MediaStore.ACTION_IMAGE_CAPTURE_SECURE, REQUEST_CODE_TAKE_PHOTO);
    }

    @Test
    public void choosePhotoHasCorrectIntentAndResultCode() {
        mController.choosePhoto();

        verifyStartActivityForResult(
                MediaStore.ACTION_PICK_IMAGES, REQUEST_CODE_CHOOSE_PHOTO);
    }

    @Test
    public void takePhotoIsFollowedByCrop() throws IOException {
        new File(mImagesDir, "file.txt").createNewFile();

        Intent intent = new Intent();
        intent.setData(Uri.parse(
                "content://com.android.settingslib.test/my_cache/multi_user/file.txt"));
        mController.onActivityResult(
                REQUEST_CODE_TAKE_PHOTO, Activity.RESULT_OK, intent);

        verifyStartSystemActivityForResult(
                "com.android.camera.action.CROP", REQUEST_CODE_CROP_PHOTO);
    }

    @Test
    public void takePhotoIsNotFollowedByCropWhenResultCodeNotOk() throws IOException {
        new File(mImagesDir, "file.txt").createNewFile();

        Intent intent = new Intent();
        intent.setData(Uri.parse(
                "content://com.android.settingslib.test/my_cache/multi_user/file.txt"));
        mController.onActivityResult(
                REQUEST_CODE_TAKE_PHOTO, Activity.RESULT_CANCELED, intent);

        verify(mMockAvatarUi, never()).startActivityForResult(any(), anyInt());
        verify(mMockAvatarUi, never()).startSystemActivityForResult(any(), anyInt());
    }

    @Test
    public void takePhotoIsFollowedByCropWhenTakePhotoUriReturned() throws IOException {
        new File(mImagesDir, "TakeEditUserPhoto.jpg").createNewFile();

        Intent intent = new Intent();
        intent.setData(mTakePhotoUri);
        mController.onActivityResult(
                REQUEST_CODE_TAKE_PHOTO, Activity.RESULT_OK, intent);

        verifyStartSystemActivityForResult(
                "com.android.camera.action.CROP", REQUEST_CODE_CROP_PHOTO);
    }

    @Test
    public void choosePhotoIsFollowedByCrop() throws IOException {
        new File(mImagesDir, "file.txt").createNewFile();

        Intent intent = new Intent();
        intent.setData(Uri.parse(
                "content://com.android.settingslib.test/my_cache/multi_user/file.txt"));
        mController.onActivityResult(
                REQUEST_CODE_CHOOSE_PHOTO, Activity.RESULT_OK, intent);

        verifyStartSystemActivityForResult(
                "com.android.camera.action.CROP", REQUEST_CODE_CROP_PHOTO);
    }

    @Test
    public void choosePhotoIsNotFollowedByCropWhenResultCodeNotOk() throws IOException {
        new File(mImagesDir, "file.txt").createNewFile();

        Intent intent = new Intent();
        intent.setData(Uri.parse(
                "content://com.android.settingslib.test/my_cache/multi_user/file.txt"));
        mController.onActivityResult(
                REQUEST_CODE_CHOOSE_PHOTO, Activity.RESULT_CANCELED, intent);

        verify(mMockAvatarUi, never()).startActivityForResult(any(), anyInt());
        verify(mMockAvatarUi, never()).startSystemActivityForResult(any(), anyInt());
    }

    @Test
    public void choosePhotoIsFollowedByCropWhenTakePhotoUriReturned() throws IOException {
        new File(mImagesDir, "TakeEditUserPhoto.jpg").createNewFile();

        Intent intent = new Intent();
        intent.setData(mTakePhotoUri);
        mController.onActivityResult(
                REQUEST_CODE_CHOOSE_PHOTO, Activity.RESULT_OK, intent);

        verifyStartSystemActivityForResult(
                "com.android.camera.action.CROP", REQUEST_CODE_CROP_PHOTO);
    }

    @Test
    public void cropPhotoResultIsReturnedIfResultOkAndContent() {
        Intent intent = new Intent();
        intent.setData(mCropPhotoUri);
        mController.onActivityResult(REQUEST_CODE_CROP_PHOTO, Activity.RESULT_OK, intent);
        verify(mMockAvatarUi, timeout(TIMEOUT_MILLIS)).returnUriResult(mCropPhotoUri);
    }

    @Test
    public void cropPhotoResultIsNotReturnedIfResultCancel() {
        Intent intent = new Intent();
        intent.setData(mCropPhotoUri);
        mController.onActivityResult(REQUEST_CODE_CROP_PHOTO, Activity.RESULT_CANCELED, intent);
        verify(mMockAvatarUi, timeout(TIMEOUT_MILLIS).times(0)).returnUriResult(mCropPhotoUri);
    }

    @Test
    public void cropPhotoResultIsNotReturnedIfResultNotContent() {
        Intent intent = new Intent();
        intent.setData(Uri.parse("file://test"));
        mController.onActivityResult(REQUEST_CODE_CROP_PHOTO, Activity.RESULT_OK, intent);
        verify(mMockAvatarUi, timeout(TIMEOUT_MILLIS).times(0)).returnUriResult(mCropPhotoUri);
    }

    @Test
    public void cropDoesNotUseTakePhotoUri() throws IOException {
        new File(mImagesDir, "file.txt").createNewFile();

        Intent intent = new Intent();
        intent.setData(Uri.parse(
                "content://com.android.settingslib.test/my_cache/multi_user/file.txt"));
        mController.onActivityResult(
                REQUEST_CODE_TAKE_PHOTO, Activity.RESULT_OK, intent);

        Intent startIntent = verifyStartSystemActivityForResult(
                "com.android.camera.action.CROP", REQUEST_CODE_CROP_PHOTO);
        assertThat(startIntent.getData()).isNotEqualTo(mTakePhotoUri);
    }

    @Test
    public void internalCropUsedIfNoSystemCropperFound() throws IOException {
        when(mMockAvatarUi.startSystemActivityForResult(any(), anyInt())).thenReturn(false);

        File file = new File(mImagesDir, "file.txt");
        saveBitmapToFile(file);

        Intent intent = new Intent();
        intent.setData(Uri.parse(
                "content://com.android.settingslib.test/my_cache/multi_user/file.txt"));
        mController.onActivityResult(
                REQUEST_CODE_TAKE_PHOTO, Activity.RESULT_OK, intent);

        verify(mMockAvatarUi, timeout(TIMEOUT_MILLIS)).returnUriResult(mCropPhotoUri);

        InputStream imageStream = mContext.getContentResolver().openInputStream(mCropPhotoUri);
        Bitmap bitmap = BitmapFactory.decodeStream(imageStream);
        assertThat(bitmap.getWidth()).isEqualTo(PHOTO_SIZE);
        assertThat(bitmap.getHeight()).isEqualTo(PHOTO_SIZE);
    }

    private Intent verifyStartActivityForResult(String action, int resultCode) {
        ArgumentCaptor<Intent> captor = ArgumentCaptor.forClass(Intent.class);
        verify(mMockAvatarUi, timeout(TIMEOUT_MILLIS))
                .startActivityForResult(captor.capture(), eq(resultCode));
        Intent intent = captor.getValue();
        assertThat(intent.getAction()).isEqualTo(action);
        return intent;
    }

    private Intent verifyStartSystemActivityForResult(String action, int resultCode) {
        ArgumentCaptor<Intent> captor = ArgumentCaptor.forClass(Intent.class);
        verify(mMockAvatarUi, timeout(TIMEOUT_MILLIS))
                .startSystemActivityForResult(captor.capture(), eq(resultCode));
        Intent intent = captor.getValue();
        assertThat(intent.getAction()).isEqualTo(action);
        return intent;
    }

    private void saveBitmapToFile(File file) throws IOException {
        Bitmap bitmap = Bitmap.createBitmap(500, 500, Bitmap.Config.ARGB_8888);
        OutputStream os = new FileOutputStream(file);
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, os);
        os.flush();
        os.close();
    }

}
