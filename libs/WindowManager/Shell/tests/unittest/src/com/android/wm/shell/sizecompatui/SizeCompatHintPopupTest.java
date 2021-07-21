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
import static org.mockito.Mockito.verify;

import android.content.res.Configuration;
import android.testing.AndroidTestingRunner;
import android.view.LayoutInflater;
import android.widget.Button;

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
 * Tests for {@link SizeCompatHintPopup}.
 *
 * Build/Install/Run:
 *  atest WMShellUnitTests:SizeCompatHintPopupTest
 */
@RunWith(AndroidTestingRunner.class)
@SmallTest
public class SizeCompatHintPopupTest extends ShellTestCase {

    @Mock private SyncTransactionQueue mSyncTransactionQueue;
    @Mock private SizeCompatUIController.SizeCompatUICallback mCallback;
    @Mock private ShellTaskOrganizer.TaskListener mTaskListener;
    @Mock private DisplayLayout mDisplayLayout;

    private SizeCompatUILayout mLayout;
    private SizeCompatHintPopup mHint;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        final int taskId = 1;
        mLayout = new SizeCompatUILayout(mSyncTransactionQueue, mCallback, mContext,
                new Configuration(), taskId, mTaskListener, mDisplayLayout,
                false /* hasShownHint */);
        mHint = (SizeCompatHintPopup)
                LayoutInflater.from(mContext).inflate(R.layout.size_compat_mode_hint, null);
        mHint.inject(mLayout);

        spyOn(mLayout);
    }

    @Test
    public void testOnClick() {
        doNothing().when(mLayout).dismissHint();

        final Button button = mHint.findViewById(R.id.got_it);
        button.performClick();

        verify(mLayout).dismissHint();
    }
}
