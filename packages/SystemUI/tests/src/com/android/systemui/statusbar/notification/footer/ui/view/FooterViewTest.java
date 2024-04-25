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

package com.android.systemui.statusbar.notification.footer.ui.view;

import static com.android.systemui.log.LogAssertKt.assertLogsWtf;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.FlagsParameterization;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.notification.footer.shared.FooterViewRefactor;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import platform.test.runner.parameterized.ParameterizedAndroidJunit4;
import platform.test.runner.parameterized.Parameters;

import java.util.List;

@SmallTest
@RunWith(ParameterizedAndroidJunit4.class)
public class FooterViewTest extends SysuiTestCase {

    @Parameters(name = "{0}")
    public static List<FlagsParameterization> getFlags() {
        return FlagsParameterization.allCombinationsOf(FooterViewRefactor.FLAG_NAME);
    }

    public FooterViewTest(FlagsParameterization flags) {
        mSetFlagsRule.setFlagsParameterization(flags);
    }

    FooterView mView;

    Context mSpyContext = spy(mContext);

    @Before
    public void setUp() {
        mView = (FooterView) LayoutInflater.from(mSpyContext).inflate(
                R.layout.status_bar_notification_footer, null, false);
        mView.setAnimationDuration(0);
    }

    @Test
    public void testViewsNotNull() {
        assertNotNull(mView.findContentView());
        assertNotNull(mView.findSecondaryView());
    }

    @Test
    public void setDismissOnClick() {
        mView.setClearAllButtonClickListener(mock(View.OnClickListener.class));
        assertTrue(mView.findSecondaryView().hasOnClickListeners());
    }

    @Test
    public void setManageOnClick() {
        mView.setManageButtonClickListener(mock(View.OnClickListener.class));
        assertTrue(mView.findViewById(R.id.manage_text).hasOnClickListeners());
    }

    @Test
    @DisableFlags(FooterViewRefactor.FLAG_NAME)
    public void setHistoryShown() {
        mView.showHistory(true);
        assertTrue(mView.isHistoryShown());
        assertTrue(((TextView) mView.findViewById(R.id.manage_text))
                .getText().toString().contains("History"));
    }

    @Test
    @DisableFlags(FooterViewRefactor.FLAG_NAME)
    public void setHistoryNotShown() {
        mView.showHistory(false);
        assertFalse(mView.isHistoryShown());
        assertTrue(((TextView) mView.findViewById(R.id.manage_text))
                .getText().toString().contains("Manage"));
    }

    @Test
    public void testPerformVisibilityAnimation() {
        mView.setVisible(false /* visible */, false /* animate */);
        assertFalse(mView.isVisible());

        mView.setVisible(true /* visible */, true /* animate */);
    }

    @Test
    public void testPerformSecondaryVisibilityAnimation() {
        mView.setClearAllButtonVisible(false /* visible */, false /* animate */);
        assertFalse(mView.isClearAllButtonVisible());

        mView.setClearAllButtonVisible(true /* visible */, true /* animate */);
    }

    @Test
    @EnableFlags(FooterViewRefactor.FLAG_NAME)
    public void testSetManageOrHistoryButtonText_resourceOnlyFetchedOnce() {
        int resId = R.string.manage_notifications_history_text;
        mView.setManageOrHistoryButtonText(resId);
        verify(mSpyContext).getString(eq(resId));

        clearInvocations(mSpyContext);

        assertThat(((TextView) mView.findViewById(R.id.manage_text))
                .getText().toString()).contains("History");

        // Set it a few more times, it shouldn't lead to the resource being fetched again
        mView.setManageOrHistoryButtonText(resId);
        mView.setManageOrHistoryButtonText(resId);

        verify(mSpyContext, never()).getString(anyInt());
    }

    @Test
    @DisableFlags(FooterViewRefactor.FLAG_NAME)
    public void testSetManageOrHistoryButtonText_expectsFlagEnabled() {
        clearInvocations(mSpyContext);
        int resId = R.string.manage_notifications_history_text;
        assertLogsWtf(() -> mView.setManageOrHistoryButtonText(resId));
        verify(mSpyContext, never()).getString(anyInt());
    }

    @Test
    @EnableFlags(FooterViewRefactor.FLAG_NAME)
    public void testSetManageOrHistoryButtonDescription_resourceOnlyFetchedOnce() {
        int resId = R.string.manage_notifications_history_text;
        mView.setManageOrHistoryButtonDescription(resId);
        verify(mSpyContext).getString(eq(resId));

        clearInvocations(mSpyContext);

        assertThat(((TextView) mView.findViewById(R.id.manage_text))
                .getContentDescription().toString()).contains("History");

        // Set it a few more times, it shouldn't lead to the resource being fetched again
        mView.setManageOrHistoryButtonDescription(resId);
        mView.setManageOrHistoryButtonDescription(resId);

        verify(mSpyContext, never()).getString(anyInt());
    }

    @Test
    @DisableFlags(FooterViewRefactor.FLAG_NAME)
    public void testSetManageOrHistoryButtonDescription_expectsFlagEnabled() {
        clearInvocations(mSpyContext);
        int resId = R.string.accessibility_clear_all;
        assertLogsWtf(() -> mView.setManageOrHistoryButtonDescription(resId));
        verify(mSpyContext, never()).getString(anyInt());
    }

    @Test
    @EnableFlags(FooterViewRefactor.FLAG_NAME)
    public void testSetClearAllButtonText_resourceOnlyFetchedOnce() {
        int resId = R.string.clear_all_notifications_text;
        mView.setClearAllButtonText(resId);
        verify(mSpyContext).getString(eq(resId));

        clearInvocations(mSpyContext);

        assertThat(((TextView) mView.findViewById(R.id.dismiss_text))
                .getText().toString()).contains("Clear all");

        // Set it a few more times, it shouldn't lead to the resource being fetched again
        mView.setClearAllButtonText(resId);
        mView.setClearAllButtonText(resId);

        verify(mSpyContext, never()).getString(anyInt());
    }

    @Test
    @DisableFlags(FooterViewRefactor.FLAG_NAME)
    public void testSetClearAllButtonText_expectsFlagEnabled() {
        clearInvocations(mSpyContext);
        int resId = R.string.clear_all_notifications_text;
        assertLogsWtf(() -> mView.setClearAllButtonText(resId));
        verify(mSpyContext, never()).getString(anyInt());
    }

    @Test
    @EnableFlags(FooterViewRefactor.FLAG_NAME)
    public void testSetClearAllButtonDescription_resourceOnlyFetchedOnce() {
        int resId = R.string.accessibility_clear_all;
        mView.setClearAllButtonDescription(resId);
        verify(mSpyContext).getString(eq(resId));

        clearInvocations(mSpyContext);

        assertThat(((TextView) mView.findViewById(R.id.dismiss_text))
                .getContentDescription().toString()).contains("Clear all notifications");

        // Set it a few more times, it shouldn't lead to the resource being fetched again
        mView.setClearAllButtonDescription(resId);
        mView.setClearAllButtonDescription(resId);

        verify(mSpyContext, never()).getString(anyInt());
    }

    @Test
    @DisableFlags(FooterViewRefactor.FLAG_NAME)
    public void testSetClearAllButtonDescription_expectsFlagEnabled() {
        clearInvocations(mSpyContext);
        int resId = R.string.accessibility_clear_all;
        assertLogsWtf(() -> mView.setClearAllButtonDescription(resId));
        verify(mSpyContext, never()).getString(anyInt());
    }

    @Test
    @EnableFlags(FooterViewRefactor.FLAG_NAME)
    public void testSetMessageString_resourceOnlyFetchedOnce() {
        int resId = R.string.unlock_to_see_notif_text;
        mView.setMessageString(resId);
        verify(mSpyContext).getString(eq(resId));

        clearInvocations(mSpyContext);

        assertThat(((TextView) mView.findViewById(R.id.unlock_prompt_footer))
                .getText().toString()).contains("Unlock");

        // Set it a few more times, it shouldn't lead to the resource being fetched again
        mView.setMessageString(resId);
        mView.setMessageString(resId);

        verify(mSpyContext, never()).getString(anyInt());
    }

    @Test
    @DisableFlags(FooterViewRefactor.FLAG_NAME)
    public void testSetMessageString_expectsFlagEnabled() {
        clearInvocations(mSpyContext);
        int resId = R.string.unlock_to_see_notif_text;
        assertLogsWtf(() -> mView.setMessageString(resId));
        verify(mSpyContext, never()).getString(anyInt());
    }

    @Test
    @EnableFlags(FooterViewRefactor.FLAG_NAME)
    public void testSetMessageIcon_resourceOnlyFetchedOnce() {
        int resId = R.drawable.ic_friction_lock_closed;
        mView.setMessageIcon(resId);
        verify(mSpyContext).getDrawable(eq(resId));

        clearInvocations(mSpyContext);

        // Set it a few more times, it shouldn't lead to the resource being fetched again
        mView.setMessageIcon(resId);
        mView.setMessageIcon(resId);

        verify(mSpyContext, never()).getDrawable(anyInt());
    }

    @Test
    @DisableFlags(FooterViewRefactor.FLAG_NAME)
    public void testSetMessageIcon_expectsFlagEnabled() {
        clearInvocations(mSpyContext);
        int resId = R.drawable.ic_friction_lock_closed;
        assertLogsWtf(() -> mView.setMessageIcon(resId));
        verify(mSpyContext, never()).getDrawable(anyInt());
    }

    @Test
    public void testSetFooterLabelVisible() {
        mView.setFooterLabelVisible(true);
        assertThat(mView.findViewById(R.id.unlock_prompt_footer).getVisibility())
                .isEqualTo(View.VISIBLE);
    }

    @Test
    public void testSetFooterLabelInvisible() {
        mView.setFooterLabelVisible(false);
        assertThat(mView.findViewById(R.id.unlock_prompt_footer).getVisibility())
                .isEqualTo(View.GONE);
    }
}

