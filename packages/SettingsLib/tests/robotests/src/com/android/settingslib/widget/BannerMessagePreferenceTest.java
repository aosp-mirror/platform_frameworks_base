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

package com.android.settingslib.widget;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.robolectric.Robolectric.setupActivity;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Context;
import android.graphics.ColorFilter;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.AttributeSet;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.ColorRes;
import androidx.preference.PreferenceViewHolder;
import androidx.preference.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowDrawable;
import org.robolectric.shadows.ShadowTouchDelegate;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
public class BannerMessagePreferenceTest {

    private Context mContext;
    private View mRootView;
    private BannerMessagePreference mBannerPreference;
    private PreferenceViewHolder mHolder;

    private boolean mClickListenerCalled = false;
    private final View.OnClickListener mClickListener = v -> mClickListenerCalled = true;
    private final int mMinimumTargetSize =
            RuntimeEnvironment.application.getResources()
                    .getDimensionPixelSize(R.dimen.settingslib_preferred_minimum_touch_target);

    private static final int TEST_STRING_RES_ID =
            R.string.accessibility_banner_message_dismiss;

    @Before
    public void setUp() {
        mContext = RuntimeEnvironment.application;
        mClickListenerCalled = false;
        mBannerPreference = new BannerMessagePreference(mContext);
        setUpViewHolder();
    }

    @Test
    public void onBindViewHolder_whenTitleSet_shouldSetTitle() {
        mBannerPreference.setTitle("test");

        mBannerPreference.onBindViewHolder(mHolder);

        assertThat(((TextView) mRootView.findViewById(R.id.banner_title)).getText())
                .isEqualTo("test");
    }

    @Test
    public void onBindViewHolder_andOnLayoutView_dismissButtonTouchDelegate_isCorrectSize() {
        assumeAndroidS();
        mBannerPreference.setTitle("Title");
        mBannerPreference.setDismissButtonOnClickListener(mClickListener);

        mBannerPreference.onBindViewHolder(mHolder);
        setupActivity(Activity.class).setContentView(mRootView);

        assertThat(mRootView.getTouchDelegate()).isNotNull();
        ShadowTouchDelegate delegate = shadowOf(mRootView.getTouchDelegate());
        assertThat(delegate.getBounds().width()).isAtLeast(mMinimumTargetSize);
        assertThat(delegate.getBounds().height()).isAtLeast(mMinimumTargetSize);
        assertThat(delegate.getDelegateView())
                .isEqualTo(mRootView.findViewById(R.id.banner_dismiss_btn));
    }

    @Test
    public void onBindViewHolder_whenSummarySet_shouldSetSummary() {
        mBannerPreference.setSummary("test");

        mBannerPreference.onBindViewHolder(mHolder);

        assertThat(((TextView) mRootView.findViewById(R.id.banner_summary)).getText())
                .isEqualTo("test");
    }

    @Test
    public void onBindViewHolder_whenPreS_shouldBindView() {
        assumeAndroidR();
        mBannerPreference.setSummary("test");

        mBannerPreference.onBindViewHolder(mHolder);

        assertThat(((TextView) mRootView.findViewById(R.id.banner_summary)).getText())
                .isEqualTo("test");
    }

    @Test
    public void onBindViewHolder_whenAtLeastS_whenSubtitleSetByString_shouldSetSubtitle() {
        assumeAndroidS();
        mBannerPreference.setSubtitle("test");

        mBannerPreference.onBindViewHolder(mHolder);

        TextView mSubtitleView = mRootView.findViewById(R.id.banner_subtitle);
        assertThat(mSubtitleView.getText()).isEqualTo("test");
        assertThat(mSubtitleView.getVisibility()).isEqualTo(View.VISIBLE);
    }

    @Test
    public void onBindViewHolder_whenAtLeastS_whenSubtitleSetByResId_shouldSetSubtitle() {
        assumeAndroidS();
        mBannerPreference.setSubtitle(TEST_STRING_RES_ID);

        mBannerPreference.onBindViewHolder(mHolder);

        TextView mSubtitleView = mRootView.findViewById(R.id.banner_subtitle);
        assertThat(mSubtitleView.getText()).isEqualTo(mContext.getString(TEST_STRING_RES_ID));
        assertThat(mSubtitleView.getVisibility()).isEqualTo(View.VISIBLE);
    }

    @Test
    public void onBindViewHolder_whenAtLeastS_whenSubtitleXmlAttribute_shouldSetSubtitle() {
        assumeAndroidS();
        AttributeSet mAttributeSet = Robolectric.buildAttributeSet()
                .addAttribute(R.attr.subtitle, "Test")
                .build();
        mBannerPreference = new BannerMessagePreference(mContext, mAttributeSet);

        mBannerPreference.onBindViewHolder(mHolder);

        TextView mSubtitleView = mRootView.findViewById(R.id.banner_subtitle);
        assertThat(mSubtitleView.getText()).isEqualTo("Test");
        assertThat(mSubtitleView.getVisibility()).isEqualTo(View.VISIBLE);
    }

    @Test
    public void onBindViewHolder_whenAtLeastS_shouldNotShowSubtitleIfUnset() {
        assumeAndroidS();
        mBannerPreference.onBindViewHolder(mHolder);

        TextView mSubtitleView = mRootView.findViewById(R.id.banner_subtitle);
        assertThat(mSubtitleView.getVisibility()).isEqualTo(View.GONE);
    }

    @Test
    public void onBindViewHolder_whenAtLeastS_whenIconSet_shouldSetIcon() {
        assumeAndroidS();
        mBannerPreference.setIcon(R.drawable.settingslib_ic_cross);

        mBannerPreference.onBindViewHolder(mHolder);

        ImageView mIcon = mRootView.findViewById(R.id.banner_icon);
        ShadowDrawable shadowDrawable = shadowOf(mIcon.getDrawable());
        assertThat(shadowDrawable.getCreatedFromResId())
                .isEqualTo(R.drawable.settingslib_ic_cross);
    }

    @Test
    public void onBindViewHolder_whenAtLeastS_whenNoIconSet_shouldSetIconToDefault() {
        assumeAndroidS();
        mBannerPreference.onBindViewHolder(mHolder);

        ImageView mIcon = mRootView.findViewById(R.id.banner_icon);
        ShadowDrawable shadowDrawable = shadowOf(mIcon.getDrawable());
        assertThat(shadowDrawable.getCreatedFromResId()).isEqualTo(R.drawable.ic_warning);
    }

    @Test
    public void setPositiveButtonText_shouldShowPositiveButton() {
        mBannerPreference.setPositiveButtonText(TEST_STRING_RES_ID);

        mBannerPreference.onBindViewHolder(mHolder);

        Button mPositiveButton = mRootView.findViewById(R.id.banner_positive_btn);
        assertThat(mPositiveButton.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mPositiveButton.getText()).isEqualTo(mContext.getString(TEST_STRING_RES_ID));
    }

    @Test
    public void setNegativeButtonText_shouldShowNegativeButton() {
        mBannerPreference.setNegativeButtonText(TEST_STRING_RES_ID);

        mBannerPreference.onBindViewHolder(mHolder);

        Button mNegativeButton = mRootView.findViewById(R.id.banner_negative_btn);
        assertThat(mNegativeButton.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mNegativeButton.getText()).isEqualTo(mContext.getString(TEST_STRING_RES_ID));
    }

    @Test
    public void withoutSetPositiveButtonText_shouldHidePositiveButton() {
        mBannerPreference.onBindViewHolder(mHolder);

        assertThat(mRootView.findViewById(R.id.banner_positive_btn).getVisibility())
                .isEqualTo(View.GONE);
    }

    @Test
    public void withoutSetNegativeButtonText_shouldHideNegativeButton() {
        mBannerPreference.onBindViewHolder(mHolder);

        assertThat(mRootView.findViewById(R.id.banner_negative_btn).getVisibility())
                .isEqualTo(View.GONE);
    }

    @Test
    public void setPositiveButtonVisible_withTrue_shouldShowPositiveButton() {
        mBannerPreference.setPositiveButtonText(TEST_STRING_RES_ID);

        mBannerPreference.setPositiveButtonVisible(true);
        mBannerPreference.onBindViewHolder(mHolder);

        assertThat((mRootView.findViewById(R.id.banner_positive_btn)).getVisibility())
                .isEqualTo(View.VISIBLE);
    }

    @Test
    public void setPositiveButtonVisible_withFalse_shouldHidePositiveButton() {
        mBannerPreference.setPositiveButtonText(TEST_STRING_RES_ID);

        mBannerPreference.setPositiveButtonVisible(false);
        mBannerPreference.onBindViewHolder(mHolder);

        assertThat(mRootView.findViewById(R.id.banner_positive_btn).getVisibility())
                .isEqualTo(View.GONE);
    }

    @Test
    public void setNegativeButtonVisible_withTrue_shouldShowNegativeButton() {
        mBannerPreference.setNegativeButtonText(TEST_STRING_RES_ID);

        mBannerPreference.setNegativeButtonVisible(true);
        mBannerPreference.onBindViewHolder(mHolder);

        assertThat(mRootView.findViewById(R.id.banner_negative_btn).getVisibility())
                .isEqualTo(View.VISIBLE);
    }

    @Test
    public void setNegativeButtonVisible_withFalse_shouldHideNegativeButton() {
        mBannerPreference.setNegativeButtonText(TEST_STRING_RES_ID);

        mBannerPreference.setNegativeButtonVisible(false);
        mBannerPreference.onBindViewHolder(mHolder);

        assertThat(mRootView.findViewById(R.id.banner_negative_btn).getVisibility())
                .isEqualTo(View.GONE);
    }

    @Test
    public void setPositiveButtonOnClickListener_setsClickListener() {
        mBannerPreference.setPositiveButtonOnClickListener(mClickListener);

        mBannerPreference.onBindViewHolder(mHolder);
        mRootView.findViewById(R.id.banner_positive_btn).callOnClick();

        assertThat(mClickListenerCalled).isTrue();
    }

    @Test
    public void setNegativeButtonOnClickListener_setsClickListener() {
        mBannerPreference.setNegativeButtonOnClickListener(mClickListener);

        mBannerPreference.onBindViewHolder(mHolder);
        mRootView.findViewById(R.id.banner_negative_btn).callOnClick();

        assertThat(mClickListenerCalled).isTrue();
    }

    @Test
    public void setDismissButtonOnClickListener_whenAtLeastS_setsClickListener() {
        assumeAndroidS();
        mBannerPreference.setDismissButtonOnClickListener(mClickListener);

        mBannerPreference.onBindViewHolder(mHolder);
        mRootView.findViewById(R.id.banner_dismiss_btn).callOnClick();

        assertThat(mClickListenerCalled).isTrue();
    }

    @Test
    public void onBindViewHolder_whenAtLeastS_withDismissOnClickListener_dismissIsVisible() {
        assumeAndroidS();
        mBannerPreference.setDismissButtonOnClickListener(mClickListener);

        mBannerPreference.onBindViewHolder(mHolder);

        assertThat((mRootView.findViewById(R.id.banner_dismiss_btn)).getVisibility())
                .isEqualTo(View.VISIBLE);
    }

    @Test
    public void onBindViewHolder_whenAtLeastS_withNoDismissClickListener_dimissButtonIsGone() {
        assumeAndroidS();

        mBannerPreference.onBindViewHolder(mHolder);

        assertThat((mRootView.findViewById(R.id.banner_dismiss_btn)).getVisibility())
                .isEqualTo(View.GONE);
    }

    @Test
    public void onBindViewHolder_whenAtLeastS_withNoClickListenerAndVisible_dimissButtonIsGone() {
        assumeAndroidS();
        mBannerPreference.setDismissButtonVisible(true);

        mBannerPreference.onBindViewHolder(mHolder);

        assertThat(mRootView.findViewById(R.id.banner_dismiss_btn).getVisibility())
                .isEqualTo(View.GONE);
    }

    @Test
    public void onBindViewHolder_whenAtLeastS_withClickListenerAndNotVisible_dimissButtonIsGone() {
        assumeAndroidS();
        mBannerPreference.setDismissButtonOnClickListener(mClickListener);
        mBannerPreference.setDismissButtonVisible(false);

        mBannerPreference.onBindViewHolder(mHolder);

        ImageButton mDismissButton = mRootView.findViewById(R.id.banner_dismiss_btn);
        assertThat(mDismissButton.getVisibility()).isEqualTo(View.GONE);
    }

    @Test
    public void onBindViewHolder_whenAtLeastS_whenAttentionUnset_setsHighTheme() {
        assumeAndroidS();
        Drawable mCardBackgroundSpy = spy(mRootView.getBackground());
        mRootView.setBackground(mCardBackgroundSpy);

        mBannerPreference.onBindViewHolder(mHolder);

        assertThat(((ImageView) mHolder.findViewById(R.id.banner_icon)).getColorFilter())
                .isEqualTo(getColorFilter(R.color.banner_accent_attention_high));
        assertThat(getButtonColor(R.id.banner_positive_btn))
                .isEqualTo(getColorId(R.color.banner_accent_attention_high));
        assertThat(getButtonColor(R.id.banner_negative_btn))
                .isEqualTo(getColorId(R.color.banner_accent_attention_high));
        verify(mCardBackgroundSpy).setTint(getColorId(R.color.banner_background_attention_high));
    }

    @Test
    public void onBindViewHolder_whenAtLeastS_whenAttentionHighByXML_setsHighTheme() {
        assumeAndroidS();
        Drawable mCardBackgroundSpy = spy(mRootView.getBackground());
        mRootView.setBackground(mCardBackgroundSpy);
        AttributeSet mAttributeSet = Robolectric.buildAttributeSet()
                .addAttribute(R.attr.attentionLevel, "high")
                .build();
        mBannerPreference = new BannerMessagePreference(mContext, mAttributeSet);

        mBannerPreference.onBindViewHolder(mHolder);

        assertThat(((ImageView) mHolder.findViewById(R.id.banner_icon)).getColorFilter())
                .isEqualTo(getColorFilter(R.color.banner_accent_attention_high));
        assertThat(getButtonColor(R.id.banner_positive_btn))
                .isEqualTo(getColorId(R.color.banner_accent_attention_high));
        assertThat(getButtonColor(R.id.banner_negative_btn))
                .isEqualTo(getColorId(R.color.banner_accent_attention_high));
        verify(mCardBackgroundSpy).setTint(getColorId(R.color.banner_background_attention_high));
    }

    @Test
    public void onBindViewHolder_whenAtLeastS_whenAttentionMediumByXML_setsMediumTheme() {
        assumeAndroidS();
        Drawable mCardBackgroundSpy = spy(mRootView.getBackground());
        mRootView.setBackground(mCardBackgroundSpy);
        AttributeSet mAttributeSet = Robolectric.buildAttributeSet()
                .addAttribute(R.attr.attentionLevel, "medium")
                .build();
        mBannerPreference = new BannerMessagePreference(mContext, mAttributeSet);

        mBannerPreference.onBindViewHolder(mHolder);

        assertThat(((ImageView) mHolder.findViewById(R.id.banner_icon)).getColorFilter())
                .isEqualTo(getColorFilter(R.color.banner_accent_attention_medium));
        assertThat(getButtonColor(R.id.banner_positive_btn))
                .isEqualTo(getColorId(R.color.banner_accent_attention_medium));
        assertThat(getButtonColor(R.id.banner_negative_btn))
                .isEqualTo(getColorId(R.color.banner_accent_attention_medium));
        verify(mCardBackgroundSpy).setTint(getColorId(R.color.banner_background_attention_medium));
    }

    @Test
    public void onBindViewHolder_whenAtLeastS_whenAttentionLowByXML_setsLowTheme() {
        assumeAndroidS();
        Drawable mCardBackgroundSpy = spy(mRootView.getBackground());
        mRootView.setBackground(mCardBackgroundSpy);
        AttributeSet mAttributeSet = Robolectric.buildAttributeSet()
                .addAttribute(R.attr.attentionLevel, "low")
                .build();
        mBannerPreference = new BannerMessagePreference(mContext, mAttributeSet);

        mBannerPreference.onBindViewHolder(mHolder);

        assertThat(((ImageView) mHolder.findViewById(R.id.banner_icon)).getColorFilter())
                .isEqualTo(getColorFilter(R.color.banner_accent_attention_low));
        assertThat(getButtonColor(R.id.banner_positive_btn))
                .isEqualTo(getColorId(R.color.banner_accent_attention_low));
        assertThat(getButtonColor(R.id.banner_negative_btn))
                .isEqualTo(getColorId(R.color.banner_accent_attention_low));
        verify(mCardBackgroundSpy).setTint(getColorId(R.color.banner_background_attention_low));
    }

    @Test
    public void setAttentionLevel_whenAtLeastS_whenHighAttention_setsHighTheme() {
        assumeAndroidS();
        Drawable mCardBackgroundSpy = spy(mRootView.getBackground());
        mRootView.setBackground(mCardBackgroundSpy);
        mBannerPreference.setAttentionLevel(BannerMessagePreference.AttentionLevel.HIGH);

        mBannerPreference.onBindViewHolder(mHolder);

        assertThat(((ImageView) mHolder.findViewById(R.id.banner_icon)).getColorFilter())
                .isEqualTo(getColorFilter(R.color.banner_accent_attention_high));
        assertThat(getButtonColor(R.id.banner_positive_btn))
                .isEqualTo(getColorId(R.color.banner_accent_attention_high));
        assertThat(getButtonColor(R.id.banner_negative_btn))
                .isEqualTo(getColorId(R.color.banner_accent_attention_high));
        verify(mCardBackgroundSpy).setTint(getColorId(R.color.banner_background_attention_high));
    }

    @Test
    public void setAttentionLevel_whenAtLeastS_whenMedAttention_setsMediumTheme() {
        assumeAndroidS();
        Drawable mCardBackgroundSpy = spy(mRootView.getBackground());
        mRootView.setBackground(mCardBackgroundSpy);
        mBannerPreference.setAttentionLevel(BannerMessagePreference.AttentionLevel.MEDIUM);

        mBannerPreference.onBindViewHolder(mHolder);

        assertThat(((ImageView) mHolder.findViewById(R.id.banner_icon)).getColorFilter())
                .isEqualTo(getColorFilter(R.color.banner_accent_attention_medium));
        assertThat(getButtonColor(R.id.banner_positive_btn))
                .isEqualTo(getColorId(R.color.banner_accent_attention_medium));
        assertThat(getButtonColor(R.id.banner_negative_btn))
                .isEqualTo(getColorId(R.color.banner_accent_attention_medium));
        verify(mCardBackgroundSpy).setTint(getColorId(R.color.banner_background_attention_medium));
    }

    @Test
    public void setAttentionLevel_whenAtLeastS_whenLowAttention_setsLowTheme() {
        assumeAndroidS();
        Drawable mCardBackgroundSpy = spy(mRootView.getBackground());
        mRootView.setBackground(mCardBackgroundSpy);
        mBannerPreference.setAttentionLevel(BannerMessagePreference.AttentionLevel.LOW);

        mBannerPreference.onBindViewHolder(mHolder);

        assertThat(((ImageView) mHolder.findViewById(R.id.banner_icon)).getColorFilter())
                .isEqualTo(getColorFilter(R.color.banner_accent_attention_low));
        assertThat(getButtonColor(R.id.banner_positive_btn))
                .isEqualTo(getColorId(R.color.banner_accent_attention_low));
        assertThat(getButtonColor(R.id.banner_negative_btn))
                .isEqualTo(getColorId(R.color.banner_accent_attention_low));
        verify(mCardBackgroundSpy).setTint(getColorId(R.color.banner_background_attention_low));
    }

    private int getButtonColor(int buttonResId) {
        Button mButton = mRootView.findViewById(buttonResId);
        return mButton.getTextColors().getDefaultColor();
    }

    private ColorFilter getColorFilter(@ColorRes int colorResId) {
        return new PorterDuffColorFilter(getColorId(colorResId), PorterDuff.Mode.SRC_IN);
    }

    private int getColorId(@ColorRes int colorResId) {
        return mContext.getResources().getColor(colorResId, mContext.getTheme());
    }

    private void assumeAndroidR() {
        ReflectionHelpers.setStaticField(Build.VERSION.class, "SDK_INT", 30);
        ReflectionHelpers.setStaticField(Build.VERSION.class, "CODENAME", "R");
        ReflectionHelpers.setStaticField(BannerMessagePreference.class, "IS_AT_LEAST_S", false);
        // Reset view holder to use correct layout.
    }

    private void assumeAndroidS() {
        ReflectionHelpers.setStaticField(Build.VERSION.class, "SDK_INT", 31);
        ReflectionHelpers.setStaticField(Build.VERSION.class, "CODENAME", "S");
        ReflectionHelpers.setStaticField(BannerMessagePreference.class, "IS_AT_LEAST_S", true);
        // Re-inflate view to update layout.
        setUpViewHolder();
    }

    private void setUpViewHolder() {
        mRootView =
                View.inflate(mContext, mBannerPreference.getLayoutResource(), null /* parent */);
        mHolder = PreferenceViewHolder.createInstanceForTests(mRootView);
    }
}
