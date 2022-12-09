/**
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.bluetooth;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;

import androidx.test.filters.SmallTest;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.android.internal.logging.UiEventLogger;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.media.dialog.MediaOutputDialogFactory;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class BroadcastDialogTest extends SysuiTestCase {

    private static final String SWITCH_APP = "Music";
    private static final String TEST_PACKAGE = "com.google.android.apps.nbu.files";
    private BroadcastDialog mBroadcastDialog;
    private View mDialogView;
    private TextView mSubTitle;
    private Button mChangeOutputButton;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mBroadcastDialog = new BroadcastDialog(mContext, mock(MediaOutputDialogFactory.class),
                SWITCH_APP, TEST_PACKAGE, mock(UiEventLogger.class));
        mBroadcastDialog.show();
        mDialogView = mBroadcastDialog.mDialogView;
    }

    @After
    public void tearDown() {
        mBroadcastDialog.dismiss();
    }

    @Test
    public void onCreate_withCurrentApp_checkSwitchAppContent() {
        mSubTitle = mDialogView.requireViewById(R.id.dialog_subtitle);

        assertThat(mSubTitle.getText()).isEqualTo(
                mContext.getString(R.string.bt_le_audio_broadcast_dialog_sub_title, SWITCH_APP));
    }

    @Test
    public void onClick_withChangeOutput_dismissBroadcastDialog() {
        mChangeOutputButton = mDialogView.requireViewById(R.id.change_output);
        mChangeOutputButton.performClick();

        assertThat(mBroadcastDialog.isShowing()).isFalse();
    }
}
