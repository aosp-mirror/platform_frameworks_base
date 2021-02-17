/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.wm.shell.sizecompatui;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;

import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.content.res.Configuration;
import android.os.IBinder;
import android.testing.AndroidTestingRunner;
import android.view.LayoutInflater;

import androidx.test.filters.SmallTest;

import com.android.wm.shell.R;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.ShellTestCase;
import com.android.wm.shell.common.DisplayLayout;
import com.android.wm.shell.common.SyncTransactionQueue;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link SizeCompatRestartButton}.
 *
 * Build/Install/Run:
 *  atest WMShellUnitTests:SizeCompatRestartButtonTest
 */
@RunWith(AndroidTestingRunner.class)
@SmallTest
public class SizeCompatRestartButtonTest extends ShellTestCase {

    @Mock private SyncTransactionQueue mSyncTransactionQueue;
    @Mock private IBinder mActivityToken;
    @Mock private ShellTaskOrganizer.TaskListener mTaskListener;
    @Mock private DisplayLayout mDisplayLayout;

    private SizeCompatUILayout mLayout;
    private SizeCompatRestartButton mButton;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        final int taskId = 1;
        mLayout = new SizeCompatUILayout(mSyncTransactionQueue, mContext, new Configuration(),
                taskId, mActivityToken, mTaskListener, mDisplayLayout, false /* hasShownHint*/);
        mButton = (SizeCompatRestartButton)
                LayoutInflater.from(mContext).inflate(R.layout.size_compat_ui, null);
        mButton.inject(mLayout);

        spyOn(mLayout);
        spyOn(mButton);
        doNothing().when(mButton).showHint();
    }

    @Test
    public void testOnClick() {
        doNothing().when(mLayout).onRestartButtonClicked();

        mButton.onClick(mButton);

        verify(mLayout).onRestartButtonClicked();
    }

    @Test
    public void testOnLongClick() {
        verify(mButton, never()).showHint();

        mButton.onLongClick(mButton);

        verify(mButton).showHint();
    }

    @Test
    public void testOnAttachedToWindow_showHint() {
        mLayout.mShouldShowHint = false;
        mButton.onAttachedToWindow();

        verify(mButton, never()).showHint();

        mLayout.mShouldShowHint = true;
        mButton.onAttachedToWindow();

        verify(mButton).showHint();
    }

    @Test
    public void testRemove() {
        mButton.remove();

        verify(mButton).dismissHint();
    }
}
