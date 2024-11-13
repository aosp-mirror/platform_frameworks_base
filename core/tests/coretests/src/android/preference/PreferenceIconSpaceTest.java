/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.preference;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.test.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class PreferenceIconSpaceTest {

    private TestPreference mPreference;

    @Mock
    private ViewGroup mViewGroup;
    @Mock
    private ImageView mIconView;
    @Mock
    private View mImageFrame;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mViewGroup.findViewById(com.android.internal.R.id.icon_frame))
                .thenReturn(mImageFrame);
        when(mViewGroup.findViewById(com.android.internal.R.id.icon))
                .thenReturn(mIconView);

        mPreference = new TestPreference(InstrumentationRegistry.getTargetContext());
    }

    @Test
    public void bindView_iconSpaceReserved_shouldReserveIconSpace() {
        mPreference.setIconSpaceReserved(true);
        mPreference.bindView(mViewGroup);

        verify(mIconView).setVisibility(View.INVISIBLE);
        verify(mImageFrame).setVisibility(View.INVISIBLE);
    }

    @Test
    public void bindView_iconSpaceNotReserved_shouldNotReserveIconSpace() {
        mPreference.setIconSpaceReserved(false);
        mPreference.bindView(mViewGroup);

        verify(mIconView).setVisibility(View.GONE);
        verify(mImageFrame).setVisibility(View.GONE);
    }

    @Test
    public void bindView_hasIcon_shouldDisplayIcon() {
        mPreference.setIcon(new ColorDrawable(Color.BLACK));
        mPreference.bindView(mViewGroup);

        verify(mIconView).setVisibility(View.VISIBLE);
        verify(mImageFrame).setVisibility(View.VISIBLE);
    }

    private static class TestPreference extends Preference {

        public TestPreference(Context context) {
            super(context);
        }

        public void bindView(View view) {
            onBindView(view);
        }
    }
}
