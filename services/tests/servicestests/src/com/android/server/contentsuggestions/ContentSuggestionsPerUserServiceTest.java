/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.contentsuggestions;

import static androidx.test.InstrumentationRegistry.getContext;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.app.contentsuggestions.ContentSuggestionsManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.UserManagerInternal;
import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.server.LocalServices;
import com.android.server.wm.ActivityTaskManagerInternal;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class ContentSuggestionsPerUserServiceTest {
    private int mUserId;
    private ContentSuggestionsPerUserService mPerUserService;
    private ActivityTaskManagerInternal mActivityTaskManagerInternal;

    @Before
    public void setup() {
        UserManagerInternal umi = mock(UserManagerInternal.class);
        LocalServices.removeServiceForTest(UserManagerInternal.class);
        LocalServices.addService(UserManagerInternal.class, umi);

        mActivityTaskManagerInternal = mock(ActivityTaskManagerInternal.class);
        LocalServices.removeServiceForTest(ActivityTaskManagerInternal.class);
        LocalServices.addService(ActivityTaskManagerInternal.class, mActivityTaskManagerInternal);

        ContentSuggestionsManagerService contentSuggestionsManagerService =
                new ContentSuggestionsManagerService(getContext());
        mUserId = 1;
        mPerUserService = new ContentSuggestionsPerUserService(contentSuggestionsManagerService,
                new Object(),
                mUserId);
    }

    // Tests TaskSnapshot is taken when the key ContentSuggestionsManager.EXTRA_BITMAP is missing
    // from imageContextRequestExtras provided.
    @Test
    public void testProvideContextImageLocked_noBitmapInBundle() {
        Bundle imageContextRequestExtras = Bundle.EMPTY;
        mPerUserService.provideContextImageLocked(mUserId, imageContextRequestExtras);
        verify(mActivityTaskManagerInternal, times(1)).getTaskSnapshotNoRestore(anyInt(),
                anyBoolean());
    }

    // Tests TaskSnapshot is not taken when the key ContentSuggestionsManager.EXTRA_BITMAP is
    // provided in imageContextRequestExtras.
    @Test
    public void testProvideContextImageLocked_bitmapInBundle() {
        Bundle imageContextRequestExtras = new Bundle();
        imageContextRequestExtras.putParcelable(ContentSuggestionsManager.EXTRA_BITMAP,
                Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888));
        mPerUserService.provideContextImageLocked(mUserId, imageContextRequestExtras);
        verify(mActivityTaskManagerInternal, times(0))
                .getTaskSnapshotNoRestore(anyInt(), anyBoolean());
    }
}


