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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.RadioButton;

import androidx.fragment.app.FragmentActivity;

import com.android.settingslib.R;
import com.android.settingslib.RestrictedLockUtils;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;

@RunWith(RobolectricTestRunner.class)
public class CreateUserDialogControllerTest {

    @Mock
    private ActivityStarter mActivityStarter;

    private boolean mPhotoRestrictedByBase;
    private Activity mActivity;
    private TestCreateUserDialogController mUnderTest;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mActivity = spy(ActivityController.of(new FragmentActivity()).get());
        mActivity.setTheme(androidx.appcompat.R.style.Theme_AppCompat_DayNight);
        mUnderTest = new TestCreateUserDialogController();
        mPhotoRestrictedByBase = false;
    }

    @Test
    public void positiveButton_grantAdminStage_noValue_OkButtonShouldBeDisabled() {
        Runnable cancelCallback = mock(Runnable.class);

        final AlertDialog dialog = (AlertDialog) mUnderTest.createDialog(mActivity,
                mActivityStarter, true, null,
                cancelCallback);
        dialog.show();
        assertThat(dialog.findViewById(R.id.button_ok).isEnabled()).isEqualTo(true);
        Button next = dialog.findViewById(R.id.button_ok);
        next.performClick();
        assertThat(dialog.findViewById(R.id.button_ok).isEnabled()).isEqualTo(false);
        ((RadioButton) dialog.findViewById(R.id.grant_admin_yes)).setChecked(true);
        assertThat(dialog.findViewById(R.id.button_ok).isEnabled()).isEqualTo(true);
        dialog.dismiss();
    }

    @Test
    public void positiveButton_MultipleAdminDisabled_shouldSkipGrantAdminStage() {
        Runnable cancelCallback = mock(Runnable.class);

        final AlertDialog dialog = (AlertDialog) mUnderTest.createDialog(mActivity,
                mActivityStarter, false, null,
                cancelCallback);
        dialog.show();
        assertThat(dialog.findViewById(R.id.grant_admin_view).getVisibility()).isEqualTo(View.GONE);
        assertThat(dialog.findViewById(R.id.button_ok).isEnabled()).isEqualTo(true);
        Button next = dialog.findViewById(R.id.button_ok);
        next.performClick();
        assertThat(dialog.findViewById(R.id.grant_admin_view).getVisibility()).isEqualTo(View.GONE);
        assertThat(dialog.findViewById(R.id.button_ok).isEnabled()).isEqualTo(true);
        Button back = dialog.findViewById(R.id.button_cancel);
        back.performClick();
        assertThat(dialog.findViewById(R.id.grant_admin_view).getVisibility()).isEqualTo(View.GONE);
        dialog.dismiss();
    }

    @Test
    public void editUserInfoController_shouldOnlyBeVisibleOnLastStage() {
        Runnable cancelCallback = mock(Runnable.class);
        final AlertDialog dialog = (AlertDialog) mUnderTest.createDialog(mActivity,
                mActivityStarter, true, null,
                cancelCallback);
        dialog.show();
        assertThat(dialog.findViewById(R.id.user_info_editor).getVisibility()).isEqualTo(View.GONE);
        Button next = dialog.findViewById(R.id.button_ok);
        next.performClick();
        ((RadioButton) dialog.findViewById(R.id.grant_admin_yes)).setChecked(true);
        assertThat(dialog.findViewById(R.id.user_info_editor).getVisibility()).isEqualTo(View.GONE);
        next.performClick();
        assertThat(dialog.findViewById(R.id.user_info_editor).getVisibility())
                .isEqualTo(View.VISIBLE);
        dialog.dismiss();
    }

    @Test
    public void positiveButton_MultipleAdminEnabled_shouldShowGrantAdminStage() {
        Runnable cancelCallback = mock(Runnable.class);

        final AlertDialog dialog = (AlertDialog) mUnderTest.createDialog(mActivity,
                mActivityStarter, true, null,
                cancelCallback);
        dialog.show();
        assertThat(dialog.findViewById(R.id.grant_admin_view).getVisibility()).isEqualTo(View.GONE);
        assertThat(dialog.findViewById(R.id.button_ok).isEnabled()).isEqualTo(true);
        Button next = dialog.findViewById(R.id.button_ok);
        next.performClick();
        assertThat(dialog.findViewById(R.id.grant_admin_view).getVisibility())
                .isEqualTo(View.VISIBLE);
        ((RadioButton) dialog.findViewById(R.id.grant_admin_yes)).setChecked(true);
        next.performClick();
        assertThat(dialog.findViewById(R.id.grant_admin_view).getVisibility()).isEqualTo(View.GONE);
        dialog.dismiss();
    }

    @Test
    public void cancelCallback_isCalled_whenCancelled() {
        NewUserData successCallback = mock(NewUserData.class);
        Runnable cancelCallback = mock(Runnable.class);

        AlertDialog dialog = (AlertDialog) mUnderTest.createDialog(mActivity,
                mActivityStarter, true, successCallback,
                cancelCallback);
        dialog.show();
        dialog.cancel();
        verifyNoInteractions(successCallback);
        verify(cancelCallback, times(1))
                .run();
    }

    @Test
    public void cancelCallback_isCalled_whenNegativeButtonClickedOnFirstStage() {
        NewUserData successCallback = mock(NewUserData.class);
        Runnable cancelCallback = mock(Runnable.class);

        AlertDialog dialog = (AlertDialog) mUnderTest.createDialog(mActivity,
                mActivityStarter, true, successCallback,
                cancelCallback);
        dialog.show();
        Button back = dialog.findViewById(R.id.button_cancel);
        back.performClick();
        verifyNoInteractions(successCallback);
        verify(cancelCallback, times(1))
                .run();
    }

    @Test
    public void cancelCallback_isNotCalled_whenNegativeButtonClickedOnSecondStage() {
        NewUserData successCallback = mock(NewUserData.class);
        Runnable cancelCallback = mock(Runnable.class);

        AlertDialog dialog = (AlertDialog) mUnderTest.createDialog(mActivity,
                mActivityStarter, true, successCallback,
                cancelCallback);
        dialog.show();
        Button next = dialog.findViewById(R.id.button_ok);
        next.performClick();
        Button back = dialog.findViewById(R.id.button_cancel);
        back.performClick();
        verifyNoInteractions(successCallback);
        verifyNoInteractions(cancelCallback);
        dialog.dismiss();
    }

    @Test
    public void successCallback_isCalled_setNameAndAdminStatus() {
        NewUserData successCallback = mock(NewUserData.class);
        Runnable cancelCallback = mock(Runnable.class);

        AlertDialog dialog = (AlertDialog) mUnderTest.createDialog(mActivity,
                mActivityStarter, true, successCallback,
                cancelCallback);
        // No photo chosen
        when(mUnderTest.getPhotoController().getNewUserPhotoDrawable()).thenReturn(null);
        dialog.show();
        Button next = dialog.findViewById(R.id.button_ok);
        next.performClick();
        ((RadioButton) dialog.findViewById(R.id.grant_admin_yes)).setChecked(true);
        next.performClick();
        String expectedNewName = "Test";
        EditText editText = dialog.findViewById(R.id.user_name);
        editText.setText(expectedNewName);
        next.performClick();
        verify(successCallback, times(1))
                .onSuccess(expectedNewName, null, true);
        verifyNoInteractions(cancelCallback);
    }

    @Test
    public void successCallback_isCalled_setName_MultipleAdminDisabled() {
        NewUserData successCallback = mock(NewUserData.class);
        Runnable cancelCallback = mock(Runnable.class);

        AlertDialog dialog = (AlertDialog) mUnderTest.createDialog(mActivity,
                mActivityStarter, false, successCallback,
                cancelCallback);
        // No photo chosen
        when(mUnderTest.getPhotoController().getNewUserPhotoDrawable()).thenReturn(null);
        dialog.show();
        Button next = dialog.findViewById(R.id.button_ok);
        next.performClick();
        String expectedNewName = "Test";
        EditText editText = dialog.findViewById(R.id.user_name);
        editText.setText(expectedNewName);
        next.performClick();
        verify(successCallback, times(1))
                .onSuccess(expectedNewName, null, false);
        verifyNoInteractions(cancelCallback);
    }

    private class TestCreateUserDialogController extends CreateUserDialogController {
        private EditUserPhotoController mPhotoController;

        TestCreateUserDialogController() {
            super("file_authority");
        }

        private EditUserPhotoController getPhotoController() {
            return mPhotoController;
        }

        @Override
        EditUserPhotoController createEditUserPhotoController(ImageView userPhotoView) {
            mPhotoController = mock(EditUserPhotoController.class, Answers.RETURNS_DEEP_STUBS);
            return mPhotoController;
        }
        @Override
        RestrictedLockUtils.EnforcedAdmin getChangePhotoAdminRestriction(Context context) {
            return null;
        }

        @Override
        boolean isChangePhotoRestrictedByBase(Context context) {
            return mPhotoRestrictedByBase;
        }
    }
}
