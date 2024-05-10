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

package com.android.settingslib.widget;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.Button;

import androidx.preference.PreferenceViewHolder;

import com.android.settingslib.widget.preference.actionbuttons.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class ActionButtonsPreferenceTest {

    private Context mContext;
    private View mRootView;
    private ActionButtonsPreference mPref;
    private PreferenceViewHolder mHolder;

    @Before
    public void setUp() {
        mContext = RuntimeEnvironment.application;
        mRootView = View.inflate(mContext, R.layout.settingslib_action_buttons, null /* parent */);
        mHolder = PreferenceViewHolder.createInstanceForTests(mRootView);
        mPref = new ActionButtonsPreference(mContext);
    }

    @Test
    public void onBindViewHolder_setTitle_shouldShowButtonByDefault() {
        mPref.setButton1Text(com.android.settingslib.R.string.install_other_apps);
        mPref.setButton2Text(com.android.settingslib.R.string.install_other_apps);
        mPref.setButton3Text(com.android.settingslib.R.string.install_other_apps);
        mPref.setButton4Text(com.android.settingslib.R.string.install_other_apps);

        mPref.onBindViewHolder(mHolder);

        assertThat(mRootView.findViewById(R.id.button1).getVisibility())
                .isEqualTo(View.VISIBLE);
        assertThat(mRootView.findViewById(R.id.button2).getVisibility())
                .isEqualTo(View.VISIBLE);
        assertThat(mRootView.findViewById(R.id.button3).getVisibility())
                .isEqualTo(View.VISIBLE);
        assertThat(mRootView.findViewById(R.id.button4).getVisibility())
                .isEqualTo(View.VISIBLE);
    }

    @Test
    public void onBindViewHolder_setIcon_shouldShowButtonByDefault() {
        mPref.setButton1Icon(com.android.internal.R.drawable.ic_plus);
        mPref.setButton2Icon(com.android.internal.R.drawable.ic_plus);
        mPref.setButton3Icon(com.android.internal.R.drawable.ic_plus);
        mPref.setButton4Icon(com.android.internal.R.drawable.ic_plus);

        mPref.onBindViewHolder(mHolder);

        assertThat(mRootView.findViewById(R.id.button1).getVisibility())
                .isEqualTo(View.VISIBLE);
        assertThat(mRootView.findViewById(R.id.button2).getVisibility())
                .isEqualTo(View.VISIBLE);
        assertThat(mRootView.findViewById(R.id.button3).getVisibility())
                .isEqualTo(View.VISIBLE);
        assertThat(mRootView.findViewById(R.id.button4).getVisibility())
                .isEqualTo(View.VISIBLE);
    }

    @Test
    public void onBindViewHolder_notSetTitleOrIcon_shouldNotShowButtonByDefault() {
        mPref.onBindViewHolder(mHolder);

        assertThat(mRootView.findViewById(R.id.button1).getVisibility())
                .isEqualTo(View.GONE);
        assertThat(mRootView.findViewById(R.id.button2).getVisibility())
                .isEqualTo(View.GONE);
        assertThat(mRootView.findViewById(R.id.button3).getVisibility())
                .isEqualTo(View.GONE);
        assertThat(mRootView.findViewById(R.id.button4).getVisibility())
                .isEqualTo(View.GONE);
    }

    @Test
    public void onBindViewHolder_setVisibleIsGoneAndSetTitle_shouldNotShowButton() {
        mPref.setButton1Text(com.android.settingslib.R.string.install_other_apps).setButton1Visible(false);
        mPref.setButton2Text(com.android.settingslib.R.string.install_other_apps).setButton2Visible(false);
        mPref.setButton3Text(com.android.settingslib.R.string.install_other_apps).setButton3Visible(false);
        mPref.setButton4Text(com.android.settingslib.R.string.install_other_apps).setButton4Visible(false);

        mPref.onBindViewHolder(mHolder);

        assertThat(mRootView.findViewById(R.id.button1).getVisibility())
                .isEqualTo(View.GONE);
        assertThat(mRootView.findViewById(R.id.button2).getVisibility())
                .isEqualTo(View.GONE);
        assertThat(mRootView.findViewById(R.id.button3).getVisibility())
                .isEqualTo(View.GONE);
        assertThat(mRootView.findViewById(R.id.button4).getVisibility())
                .isEqualTo(View.GONE);
    }

    @Test
    public void onBindViewHolder_setVisibleIsGoneAndSetIcon_shouldNotShowButton() {
        mPref.setButton1Icon(com.android.internal.R.drawable.ic_plus).setButton1Visible(false);
        mPref.setButton2Icon(com.android.internal.R.drawable.ic_plus).setButton2Visible(false);
        mPref.setButton3Icon(com.android.internal.R.drawable.ic_plus).setButton3Visible(false);
        mPref.setButton4Icon(com.android.internal.R.drawable.ic_plus).setButton4Visible(false);

        mPref.onBindViewHolder(mHolder);

        assertThat(mRootView.findViewById(R.id.button1).getVisibility())
                .isEqualTo(View.GONE);
        assertThat(mRootView.findViewById(R.id.button2).getVisibility())
                .isEqualTo(View.GONE);
        assertThat(mRootView.findViewById(R.id.button3).getVisibility())
                .isEqualTo(View.GONE);
        assertThat(mRootView.findViewById(R.id.button4).getVisibility())
                .isEqualTo(View.GONE);
    }

    @Test
    public void onBindViewHolder_setVisibility_shouldUpdateButtonVisibility() {
        mPref.setButton1Text(com.android.settingslib.R.string.install_other_apps).setButton1Visible(false);
        mPref.setButton2Text(com.android.settingslib.R.string.install_other_apps).setButton2Visible(false);
        mPref.setButton3Text(com.android.settingslib.R.string.install_other_apps).setButton3Visible(false);
        mPref.setButton4Text(com.android.settingslib.R.string.install_other_apps).setButton4Visible(false);

        mPref.onBindViewHolder(mHolder);

        assertThat(mRootView.findViewById(R.id.button1).getVisibility())
                .isEqualTo(View.GONE);
        assertThat(mRootView.findViewById(R.id.button2).getVisibility())
                .isEqualTo(View.GONE);
        assertThat(mRootView.findViewById(R.id.button3).getVisibility())
                .isEqualTo(View.GONE);
        assertThat(mRootView.findViewById(R.id.button4).getVisibility())
                .isEqualTo(View.GONE);

        mPref.setButton1Visible(true);
        mPref.setButton2Visible(true);
        mPref.setButton3Visible(true);
        mPref.setButton4Visible(true);

        mPref.onBindViewHolder(mHolder);

        assertThat(mRootView.findViewById(R.id.button1).getVisibility())
                .isEqualTo(View.VISIBLE);
        assertThat(mRootView.findViewById(R.id.button2).getVisibility())
                .isEqualTo(View.VISIBLE);
        assertThat(mRootView.findViewById(R.id.button3).getVisibility())
                .isEqualTo(View.VISIBLE);
        assertThat(mRootView.findViewById(R.id.button4).getVisibility())
                .isEqualTo(View.VISIBLE);
    }

    @Test
    public void onBindViewHolder_setEnabled_shouldEnableButton() {
        mPref.setButton1Enabled(true);
        mPref.setButton2Enabled(false);
        mPref.setButton3Enabled(true);
        mPref.setButton4Enabled(false);

        mPref.onBindViewHolder(mHolder);

        assertThat(mRootView.findViewById(R.id.button1).isEnabled()).isTrue();
        assertThat(mRootView.findViewById(R.id.button2).isEnabled()).isFalse();
        assertThat(mRootView.findViewById(R.id.button3).isEnabled()).isTrue();
        assertThat(mRootView.findViewById(R.id.button4).isEnabled()).isFalse();
    }

    @Test
    public void onBindViewHolder_setText_shouldShowSameText() {
        mPref.setButton1Text(com.android.settingslib.R.string.install_other_apps);
        mPref.setButton2Text(com.android.settingslib.R.string.install_other_apps);
        mPref.setButton3Text(com.android.settingslib.R.string.install_other_apps);
        mPref.setButton4Text(com.android.settingslib.R.string.install_other_apps);

        mPref.onBindViewHolder(mHolder);

        assertThat(((Button) mRootView.findViewById(R.id.button1)).getText())
                .isEqualTo(mContext.getText(com.android.settingslib.R.string.install_other_apps));
        assertThat(((Button) mRootView.findViewById(R.id.button2)).getText())
                .isEqualTo(mContext.getText(com.android.settingslib.R.string.install_other_apps));
        assertThat(((Button) mRootView.findViewById(R.id.button3)).getText())
                .isEqualTo(mContext.getText(com.android.settingslib.R.string.install_other_apps));
        assertThat(((Button) mRootView.findViewById(R.id.button4)).getText())
                .isEqualTo(mContext.getText(com.android.settingslib.R.string.install_other_apps));
    }

    @Test
    public void onBindViewHolder_setButtonIcon_iconMustDisplayAboveText() {
        mPref.setButton1Text(com.android.settingslib.R.string.install_other_apps);
        mPref.setButton1Icon(com.android.internal.R.drawable.ic_plus);

        mPref.onBindViewHolder(mHolder);
        final Drawable[] drawablesAroundText =
                ((Button) mRootView.findViewById(R.id.button1))
                        .getCompoundDrawables();

        assertThat(drawablesAroundText[1 /* top */]).isNotNull();
    }

    @Test
    public void setButtonIcon_iconResourceIdIsZero_shouldNotDisplayIcon() {
        mPref.setButton1Text(com.android.settingslib.R.string.install_other_apps);
        mPref.setButton1Icon(0);

        mPref.onBindViewHolder(mHolder);
        final Drawable[] drawablesAroundText =
                ((Button) mRootView.findViewById(R.id.button1))
                        .getCompoundDrawables();

        assertThat(drawablesAroundText[1 /* top */]).isNull();
    }

    @Test
    public void setButtonIcon_iconResourceIdNotExisting_shouldNotDisplayIconAndCrash() {
        mPref.setButton1Text(com.android.settingslib.R.string.install_other_apps);
        mPref.setButton1Icon(999999999 /* not existing id */);
        // Should not crash here
        mPref.onBindViewHolder(mHolder);
        final Drawable[] drawablesAroundText =
                ((Button) mRootView.findViewById(R.id.button1))
                        .getCompoundDrawables();

        assertThat(drawablesAroundText[1 /* top */]).isNull();
    }

    @Test
    public void onBindViewHolder_setAllButton_shouldShowAllDivider() {
        mPref.setButton1Text(com.android.settingslib.R.string.install_other_apps);
        mPref.setButton2Text(com.android.settingslib.R.string.install_other_apps);
        mPref.setButton3Text(com.android.settingslib.R.string.install_other_apps);
        mPref.setButton4Text(com.android.settingslib.R.string.install_other_apps);

        mPref.onBindViewHolder(mHolder);

        assertThat(mRootView.findViewById(R.id.divider1).getVisibility())
                .isEqualTo(View.VISIBLE);
        assertThat(mRootView.findViewById(R.id.divider2).getVisibility())
                .isEqualTo(View.VISIBLE);
        assertThat(mRootView.findViewById(R.id.divider3).getVisibility())
                .isEqualTo(View.VISIBLE);
    }

    @Test
    public void onBindViewHolder_setAllButtonWithoutButton2_shouldHideDivider1() {
        mPref.setButton1Text(com.android.settingslib.R.string.install_other_apps);
        mPref.setButton3Text(com.android.settingslib.R.string.install_other_apps);
        mPref.setButton4Text(com.android.settingslib.R.string.install_other_apps);

        mPref.onBindViewHolder(mHolder);

        assertThat(mRootView.findViewById(R.id.divider1).getVisibility())
                .isEqualTo(View.GONE);
        assertThat(mRootView.findViewById(R.id.divider2).getVisibility())
                .isEqualTo(View.VISIBLE);
        assertThat(mRootView.findViewById(R.id.divider3).getVisibility())
                .isEqualTo(View.VISIBLE);
    }

    @Test
    public void onBindViewHolder_setAllButtonWithoutButton3_shouldHideDivider2() {
        mPref.setButton1Text(com.android.settingslib.R.string.install_other_apps);
        mPref.setButton2Text(com.android.settingslib.R.string.install_other_apps);
        mPref.setButton4Text(com.android.settingslib.R.string.install_other_apps);

        mPref.onBindViewHolder(mHolder);

        assertThat(mRootView.findViewById(R.id.divider1).getVisibility())
                .isEqualTo(View.VISIBLE);
        assertThat(mRootView.findViewById(R.id.divider2).getVisibility())
                .isEqualTo(View.GONE);
        assertThat(mRootView.findViewById(R.id.divider3).getVisibility())
                .isEqualTo(View.VISIBLE);
    }

    @Test
    public void onBindViewHolder_setButton1And4_shouldShowDivider3Only() {
        mPref.setButton1Text(com.android.settingslib.R.string.install_other_apps);
        mPref.setButton4Text(com.android.settingslib.R.string.install_other_apps);

        mPref.onBindViewHolder(mHolder);

        assertThat(mRootView.findViewById(R.id.divider1).getVisibility())
                .isEqualTo(View.GONE);
        assertThat(mRootView.findViewById(R.id.divider2).getVisibility())
                .isEqualTo(View.GONE);
        assertThat(mRootView.findViewById(R.id.divider3).getVisibility())
                .isEqualTo(View.VISIBLE);
    }

    @Test
    public void onBindViewHolder_setOneButtonOnly_noDivider() {
        mPref.setButton4Text(com.android.settingslib.R.string.install_other_apps);

        mPref.onBindViewHolder(mHolder);

        assertThat(mRootView.findViewById(R.id.divider1).getVisibility())
                .isEqualTo(View.GONE);
        assertThat(mRootView.findViewById(R.id.divider2).getVisibility())
                .isEqualTo(View.GONE);
        assertThat(mRootView.findViewById(R.id.divider3).getVisibility())
                .isEqualTo(View.GONE);
    }

    @Test
    public void onBindViewHolder_setButton1And2_shouldShowDivider1Only() {
        mPref.setButton1Text(com.android.settingslib.R.string.install_other_apps);
        mPref.setButton2Text(com.android.settingslib.R.string.install_other_apps);

        mPref.onBindViewHolder(mHolder);

        assertThat(mRootView.findViewById(R.id.divider1).getVisibility())
                .isEqualTo(View.VISIBLE);
        assertThat(mRootView.findViewById(R.id.divider2).getVisibility())
                .isEqualTo(View.GONE);
        assertThat(mRootView.findViewById(R.id.divider3).getVisibility())
                .isEqualTo(View.GONE);
    }

    @Test
    public void onBindViewHolder_setButton1And3_shouldShowDivider2Only() {
        mPref.setButton1Text(com.android.settingslib.R.string.install_other_apps);
        mPref.setButton3Text(com.android.settingslib.R.string.install_other_apps);

        mPref.onBindViewHolder(mHolder);

        assertThat(mRootView.findViewById(R.id.divider1).getVisibility())
                .isEqualTo(View.GONE);
        assertThat(mRootView.findViewById(R.id.divider2).getVisibility())
                .isEqualTo(View.VISIBLE);
        assertThat(mRootView.findViewById(R.id.divider3).getVisibility())
                .isEqualTo(View.GONE);
    }

    public static ActionButtonsPreference createMock() {
        final ActionButtonsPreference pref = mock(ActionButtonsPreference.class);
        when(pref.setButton1Text(anyInt())).thenReturn(pref);
        when(pref.setButton1Icon(anyInt())).thenReturn(pref);
        when(pref.setButton1Enabled(anyBoolean())).thenReturn(pref);
        when(pref.setButton1Visible(anyBoolean())).thenReturn(pref);
        when(pref.setButton1OnClickListener(any(View.OnClickListener.class))).thenReturn(pref);

        when(pref.setButton2Text(anyInt())).thenReturn(pref);
        when(pref.setButton2Icon(anyInt())).thenReturn(pref);
        when(pref.setButton2Enabled(anyBoolean())).thenReturn(pref);
        when(pref.setButton2Visible(anyBoolean())).thenReturn(pref);
        when(pref.setButton2OnClickListener(any(View.OnClickListener.class))).thenReturn(pref);

        when(pref.setButton3Text(anyInt())).thenReturn(pref);
        when(pref.setButton3Icon(anyInt())).thenReturn(pref);
        when(pref.setButton3Enabled(anyBoolean())).thenReturn(pref);
        when(pref.setButton3Visible(anyBoolean())).thenReturn(pref);
        when(pref.setButton3OnClickListener(any(View.OnClickListener.class))).thenReturn(pref);

        when(pref.setButton4Text(anyInt())).thenReturn(pref);
        when(pref.setButton4Icon(anyInt())).thenReturn(pref);
        when(pref.setButton4Enabled(anyBoolean())).thenReturn(pref);
        when(pref.setButton4Visible(anyBoolean())).thenReturn(pref);
        when(pref.setButton4OnClickListener(any(View.OnClickListener.class))).thenReturn(pref);
        return pref;
    }
}
