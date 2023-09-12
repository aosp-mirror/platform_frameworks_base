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

package com.android.settingslib;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.admin.DevicePolicyManager;
import android.app.admin.DevicePolicyResourcesManager;
import android.content.Context;
import android.view.View;
import android.widget.TextView;

import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class RestrictedPreferenceHelperTest {

    @Mock
    private Context mContext;
    @Mock
    private Preference mPreference;
    @Mock
    private DevicePolicyManager mDevicePolicyManager;
    @Mock
    private DevicePolicyResourcesManager mDevicePolicyResourcesManager;
    @Mock
    private RestrictedTopLevelPreference mRestrictedTopLevelPreference;

    private PreferenceViewHolder mViewHolder;
    private RestrictedPreferenceHelper mHelper;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        doReturn(mDevicePolicyResourcesManager).when(mDevicePolicyManager)
                .getResources();
        doReturn(mDevicePolicyManager).when(mContext)
                .getSystemService(DevicePolicyManager.class);
        mViewHolder = PreferenceViewHolder.createInstanceForTests(mock(View.class));
        mHelper = new RestrictedPreferenceHelper(mContext, mPreference, null);
    }

    @Test
    public void bindPreference_disabled_shouldDisplayDisabledSummary() {
        final TextView summaryView = mock(TextView.class, RETURNS_DEEP_STUBS);
        when(mViewHolder.itemView.findViewById(android.R.id.summary))
                .thenReturn(summaryView);
        when(summaryView.getContext().getText(R.string.disabled_by_admin_summary_text))
                .thenReturn("test");
        when(mDevicePolicyResourcesManager.getString(any(), any())).thenReturn("test");

        mHelper.useAdminDisabledSummary(true);
        mHelper.setDisabledByAdmin(new RestrictedLockUtils.EnforcedAdmin());
        mHelper.onBindViewHolder(mViewHolder);

        verify(summaryView).setText("test");
        verify(summaryView, never()).setVisibility(View.GONE);
    }

    @Test
    public void bindPreference_notDisabled_shouldNotHideSummary() {
        final TextView summaryView = mock(TextView.class, RETURNS_DEEP_STUBS);
        when(mViewHolder.itemView.findViewById(android.R.id.summary))
                .thenReturn(summaryView);
        when(summaryView.getContext().getText(R.string.disabled_by_admin_summary_text))
                .thenReturn("test");
        when(mDevicePolicyResourcesManager.getString(any(), any())).thenReturn("test");
        when(summaryView.getText()).thenReturn("test");

        mHelper.useAdminDisabledSummary(true);
        mHelper.setDisabledByAdmin(null);
        mHelper.onBindViewHolder(mViewHolder);

        verify(summaryView).setText(null);
        verify(summaryView, never()).setVisibility(View.GONE);
    }

    @Test
    public void setDisabledByAdmin_RestrictedPreference_shouldDisablePreference() {
        mHelper.setDisabledByAdmin(new RestrictedLockUtils.EnforcedAdmin());

        verify(mPreference).setEnabled(false);
    }

    @Test
    public void setDisabledByAdmin_TopLevelRestrictedPreference_shouldNotDisablePreference() {
        mHelper = new RestrictedPreferenceHelper(mContext,
                mRestrictedTopLevelPreference, /* attrs= */ null);

        mHelper.setDisabledByAdmin(new RestrictedLockUtils.EnforcedAdmin());

        verify(mRestrictedTopLevelPreference, never()).setEnabled(false);
    }

    /**
     * Tests if the instance of {@link RestrictedLockUtils.EnforcedAdmin} is received by
     * {@link RestrictedPreferenceHelper#setDisabledByAdmin(RestrictedLockUtils.EnforcedAdmin)} as a
     * copy or as a reference.
     */
    @Test
    public void setDisabledByAdmin_disablePreference_receivedEnforcedAdminIsNotAReference() {
        RestrictedLockUtils.EnforcedAdmin enforcedAdmin =
                new RestrictedLockUtils.EnforcedAdmin(/* component */ null,
                        /* enforcedRestriction */ "some_restriction",
                        /* userHandle */ null);

        mHelper.setDisabledByAdmin(enforcedAdmin);

        // If `setDisabledByAdmin` stored `enforcedAdmin` as a reference, then the following
        // assignment would be propagated.
        enforcedAdmin.enforcedRestriction = null;
        assertThat(mHelper.mEnforcedAdmin.enforcedRestriction).isEqualTo("some_restriction");

        assertThat(mHelper.isDisabledByAdmin()).isTrue();
    }
}
