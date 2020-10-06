/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.widget.EditText;
import android.widget.ImageView;

import androidx.fragment.app.FragmentActivity;

import com.android.settingslib.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@RunWith(RobolectricTestRunner.class)
public class EditUserInfoControllerTest {
    private static final int MAX_USER_NAME_LENGTH = 100;

    @Mock
    private Drawable mCurrentIcon;
    @Mock
    private ActivityStarter mActivityStarter;

    private boolean mCanChangePhoto;
    private Activity mActivity;
    private TestEditUserInfoController mController;

    public class TestEditUserInfoController extends EditUserInfoController {
        private EditUserPhotoController mPhotoController;

        TestEditUserInfoController() {
            super("file_authority");
        }

        private EditUserPhotoController getPhotoController() {
            return mPhotoController;
        }

        @Override
        EditUserPhotoController createEditUserPhotoController(Activity activity,
                ActivityStarter activityStarter, ImageView userPhotoView) {
            mPhotoController = mock(EditUserPhotoController.class, Answers.RETURNS_DEEP_STUBS);
            return mPhotoController;
        }

        @Override
        boolean canChangePhoto(Context context) {
            return mCanChangePhoto;
        }
    }

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mActivity = spy(ActivityController.of(new FragmentActivity()).get());
        mActivity.setTheme(R.style.Theme_AppCompat_DayNight);
        mController = new TestEditUserInfoController();
        mCanChangePhoto = true;
    }

    @Test
    public void photoControllerOnActivityResult_whenWaiting_isCalled() {
        mController.createDialog(mActivity, mActivityStarter, mCurrentIcon, "test user",
                "title", null, null);
        mController.startingActivityForResult();
        Intent resultData = new Intent();
        mController.onActivityResult(0, 0, resultData);
        EditUserPhotoController photoController = mController.getPhotoController();

        assertThat(photoController).isNotNull();
        verify(photoController).onActivityResult(0, 0, resultData);
    }

    @Test
    @Config(shadows = ShadowDialog.class)
    public void userNameView_inputLongName_shouldBeConstrained() {
        // generate a string of 200 'A's
        final String longName = Stream.generate(
                () -> String.valueOf('A')).limit(200).collect(Collectors.joining());

        final AlertDialog dialog = (AlertDialog) mController.createDialog(mActivity,
                mActivityStarter, mCurrentIcon,
                "test user", "title", null,
                null);
        dialog.show();
        final EditText userNameEditText = dialog.findViewById(R.id.user_name);
        userNameEditText.setText(longName);

        assertThat(userNameEditText.getText().length()).isEqualTo(MAX_USER_NAME_LENGTH);
    }

    @Test
    public void cancelCallback_isCalled_whenCancelled() {
        BiConsumer<String, Drawable> successCallback = mock(BiConsumer.class);
        Runnable cancelCallback = mock(Runnable.class);

        AlertDialog dialog = (AlertDialog) mController.createDialog(
                mActivity, mActivityStarter, mCurrentIcon, "test",
                "title", successCallback, cancelCallback);
        dialog.show();
        dialog.cancel();

        verifyZeroInteractions(successCallback);
        verify(cancelCallback, times(1))
                .run();
    }

    @Test
    public void cancelCallback_isCalled_whenNegativeClicked() {
        BiConsumer<String, Drawable> successCallback = mock(BiConsumer.class);
        Runnable cancelCallback = mock(Runnable.class);

        AlertDialog dialog = (AlertDialog) mController.createDialog(
                mActivity, mActivityStarter, mCurrentIcon, "test",
                "title", successCallback, cancelCallback);
        dialog.show();
        dialog.getButton(Dialog.BUTTON_NEGATIVE).performClick();

        verifyZeroInteractions(successCallback);
        verify(cancelCallback, times(1))
                .run();
    }

    @Test
    public void successCallback_isCalled_whenNothingChanged() {
        BiConsumer<String, Drawable> successCallback = mock(BiConsumer.class);
        Runnable cancelCallback = mock(Runnable.class);

        Drawable oldUserIcon = mCurrentIcon;
        AlertDialog dialog = (AlertDialog) mController.createDialog(
                mActivity, mActivityStarter, oldUserIcon, "test",
                "title", successCallback, cancelCallback);
        // No change to the photo.
        when(mController.getPhotoController().getNewUserPhotoDrawable()).thenReturn(null);
        dialog.show();
        dialog.getButton(Dialog.BUTTON_POSITIVE).performClick();

        verify(successCallback, times(1))
                .accept("test", oldUserIcon);
        verifyZeroInteractions(cancelCallback);
    }

    @Test
    public void successCallback_calledWithNullIcon_whenOldIconIsNullAndNothingChanged() {
        BiConsumer<String, Drawable> successCallback = mock(BiConsumer.class);
        Runnable cancelCallback = mock(Runnable.class);

        AlertDialog dialog = (AlertDialog) mController.createDialog(
                mActivity, mActivityStarter, null, "test",
                "title", successCallback, cancelCallback);
        // No change to the photo.
        when(mController.getPhotoController().getNewUserPhotoDrawable()).thenReturn(null);
        dialog.show();
        dialog.getButton(Dialog.BUTTON_POSITIVE).performClick();

        verify(successCallback, times(1))
                .accept("test", null);
        verifyZeroInteractions(cancelCallback);
    }

    @Test
    public void successCallback_isCalled_whenLabelChanges() {
        BiConsumer<String, Drawable> successCallback = mock(BiConsumer.class);
        Runnable cancelCallback = mock(Runnable.class);

        AlertDialog dialog = (AlertDialog) mController.createDialog(
                mActivity, mActivityStarter, mCurrentIcon, "test",
                "title", successCallback, cancelCallback);
        // No change to the photo.
        when(mController.getPhotoController().getNewUserPhotoDrawable()).thenReturn(null);
        dialog.show();
        String expectedNewName = "new test user";
        EditText editText = (EditText) dialog.findViewById(R.id.user_name);
        editText.setText(expectedNewName);
        dialog.getButton(Dialog.BUTTON_POSITIVE).performClick();

        verify(successCallback, times(1))
                .accept(expectedNewName, mCurrentIcon);
        verifyZeroInteractions(cancelCallback);
    }

    @Test
    public void successCallback_isCalled_whenPhotoChanges() {
        BiConsumer<String, Drawable> successCallback = mock(BiConsumer.class);
        Runnable cancelCallback = mock(Runnable.class);

        AlertDialog dialog = (AlertDialog) mController.createDialog(
                mActivity, mActivityStarter, mCurrentIcon, "test",
                "title", successCallback, cancelCallback);
        // A different drawable.
        Drawable newPhoto = mock(Drawable.class);
        when(mController.getPhotoController().getNewUserPhotoDrawable()).thenReturn(newPhoto);
        dialog.show();
        dialog.getButton(Dialog.BUTTON_POSITIVE).performClick();

        verify(successCallback, times(1))
                .accept("test", newPhoto);
        verifyZeroInteractions(cancelCallback);
    }

    @Test
    public void successCallback_isCalledWithChangedPhoto_whenOldIconIsNullAndPhotoChanges() {
        BiConsumer<String, Drawable> successCallback = mock(BiConsumer.class);
        Runnable cancelCallback = mock(Runnable.class);

        AlertDialog dialog = (AlertDialog) mController.createDialog(
                mActivity, mActivityStarter, null, "test",
                "title", successCallback, cancelCallback);
        // A different drawable.
        Drawable newPhoto = mock(Drawable.class);
        when(mController.getPhotoController().getNewUserPhotoDrawable()).thenReturn(newPhoto);
        dialog.show();
        dialog.getButton(Dialog.BUTTON_POSITIVE).performClick();

        verify(successCallback, times(1))
                .accept("test", newPhoto);
        verifyZeroInteractions(cancelCallback);
    }

    @Test
    public void createDialog_canNotChangePhoto_nullPhotoController() {
        mCanChangePhoto = false;

        mController.createDialog(mActivity, mActivityStarter, mCurrentIcon,
                "test", "title", null, null);

        assertThat(mController.mPhotoController).isNull();
    }
}
