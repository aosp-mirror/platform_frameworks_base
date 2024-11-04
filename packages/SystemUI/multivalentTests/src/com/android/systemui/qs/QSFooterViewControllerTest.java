/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.qs;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.testing.TestableLooper;
import android.view.View;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.res.R;
import com.android.systemui.retail.data.repository.FakeRetailModeRepository;
import com.android.systemui.retail.domain.interactor.RetailModeInteractorImpl;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.utils.leaks.LeakCheckedTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
@TestableLooper.RunWithLooper
@SmallTest
public class QSFooterViewControllerTest extends LeakCheckedTest {

    @Mock
    private QSFooterView mView;
    @Mock
    private UserTracker mUserTracker;
    @Mock
    private QSPanelController mQSPanelController;
    @Mock
    private ClipboardManager mClipboardManager;
    @Mock
    private TextView mBuildText;
    @Mock
    private FalsingManager mFalsingManager;
    @Mock
    private ActivityStarter mActivityStarter;

    private FakeRetailModeRepository mRetailModeRepository;

    private QSFooterViewController mController;
    private View mEditButton;

    @Before
    public void setup() throws Exception {
        MockitoAnnotations.initMocks(this);

        mRetailModeRepository = new FakeRetailModeRepository();
        mRetailModeRepository.setRetailMode(false);

        mEditButton = new View(mContext);

        injectLeakCheckedDependencies(ALL_SUPPORTED_CLASSES);

        mContext.addMockSystemService(ClipboardManager.class, mClipboardManager);

        when(mView.getContext()).thenReturn(mContext);
        when(mView.getResources()).thenReturn(mContext.getResources());
        when(mUserTracker.getUserContext()).thenReturn(mContext);

        when(mView.isAttachedToWindow()).thenReturn(true);
        when(mView.findViewById(R.id.build)).thenReturn(mBuildText);
        when(mView.findViewById(android.R.id.edit)).thenReturn(mEditButton);

        mController = new QSFooterViewController(mView, mUserTracker, mFalsingManager,
                mActivityStarter, mQSPanelController,
                new RetailModeInteractorImpl(mRetailModeRepository));

        mController.init();
    }

    @Test
    public void testBuildTextCopy() {
        String text = "TEST";
        ArgumentCaptor<View.OnLongClickListener> onLongClickCaptor =
                ArgumentCaptor.forClass(View.OnLongClickListener.class);

        verify(mBuildText).setOnLongClickListener(onLongClickCaptor.capture());

        when(mBuildText.getText()).thenReturn(text);
        onLongClickCaptor.getValue().onLongClick(mBuildText);

        ArgumentCaptor<ClipData> captor = ArgumentCaptor.forClass(ClipData.class);
        verify(mClipboardManager).setPrimaryClip(captor.capture());
        assertThat(captor.getValue().getItemAt(0).getText()).isEqualTo(text);
    }

    @Test
    public void testEditButton_falseTap() {
        when(mFalsingManager.isFalseTap(anyInt())).thenReturn(true);

        mEditButton.performClick();

        verify(mQSPanelController, never()).showEdit(any());
        verifyNoMoreInteractions(mActivityStarter);
    }

    @Test
    public void testEditButton_realTap() {
        when(mFalsingManager.isFalseTap(anyInt())).thenReturn(false);

        mEditButton.performClick();

        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);

        verify(mActivityStarter).postQSRunnableDismissingKeyguard(captor.capture());
        captor.getValue().run();
        verify(mQSPanelController).showEdit(mEditButton);
    }

    @Test
    public void testEditButton_notRetailMode_visible() {
        mRetailModeRepository.setRetailMode(false);

        mController.setVisibility(View.VISIBLE);
        assertThat(mEditButton.getVisibility()).isEqualTo(View.VISIBLE);
    }

    @Test
    public void testEditButton_retailMode_notVisible() {
        mRetailModeRepository.setRetailMode(true);

        mController.setVisibility(View.VISIBLE);
        assertThat(mEditButton.getVisibility()).isEqualTo(View.GONE);
    }
}
