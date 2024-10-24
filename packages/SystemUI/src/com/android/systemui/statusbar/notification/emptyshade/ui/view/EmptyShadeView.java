/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.emptyshade.ui.view;

import android.annotation.ColorInt;
import android.annotation.DrawableRes;
import android.annotation.StringRes;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.android.systemui.animation.LaunchableView;
import com.android.systemui.animation.LaunchableViewDelegate;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.notification.emptyshade.shared.ModesEmptyShadeFix;
import com.android.systemui.statusbar.notification.row.StackScrollerDecorView;
import com.android.systemui.statusbar.notification.stack.ExpandableViewState;

import kotlin.Unit;

import java.util.Objects;

public class EmptyShadeView extends StackScrollerDecorView implements LaunchableView {

    private TextView mEmptyText;
    private TextView mEmptyFooterText;

    private @StringRes int mTextId = R.string.empty_shade_text;
    private String mTextString;

    private @DrawableRes int mFooterIcon;
    private @StringRes int mFooterText;
    // This view is initially gone in the xml.
    private @Visibility int mFooterVisibility = View.GONE;
    private int mSize;

    private LaunchableViewDelegate mLaunchableViewDelegate = new LaunchableViewDelegate(this,
            visibility -> {
                super.setVisibility(visibility);
                return Unit.INSTANCE;
            });

    public EmptyShadeView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mSize = getResources().getDimensionPixelSize(
                R.dimen.notifications_unseen_footer_icon_size);
        if (ModesEmptyShadeFix.isEnabled()) {
            mTextString = getContext().getString(R.string.empty_shade_text);
        } else {
            // These will be set by the binder when appropriate if ModesEmptyShadeFix is on.
            mFooterIcon = R.drawable.ic_friction_lock_closed;
            mFooterText = R.string.unlock_to_see_notif_text;
        }
    }

    @Override
    public void setVisibility(int visibility) {
        mLaunchableViewDelegate.setVisibility(visibility);
    }

    @Override
    public void setShouldBlockVisibilityChanges(boolean block) {
        /* check if */ ModesEmptyShadeFix.isUnexpectedlyInLegacyMode();
        mLaunchableViewDelegate.setShouldBlockVisibilityChanges(block);
    }

    @Override
    public void onActivityLaunchAnimationEnd() {
        /* check if */ ModesEmptyShadeFix.isUnexpectedlyInLegacyMode();
    }

    @Override
    @NonNull
    public Rect getPaddingForLaunchAnimation() {
        /* check if */ ModesEmptyShadeFix.isUnexpectedlyInLegacyMode();
        return new Rect();
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mSize = getResources().getDimensionPixelSize(
                R.dimen.notifications_unseen_footer_icon_size);
        if (ModesEmptyShadeFix.isEnabled()) {
            mEmptyText.setText(mTextString);
        } else {
            mEmptyText.setText(mTextId);
        }
        mEmptyFooterText.setVisibility(mFooterVisibility);
        setFooterText(mFooterText);
        setFooterIcon(mFooterIcon);
    }

    @Override
    protected View findContentView() {
        return findViewById(R.id.no_notifications);
    }

    @Override
    protected View findSecondaryView() {
        return findViewById(R.id.no_notifications_footer);
    }

    /** Update view colors. */
    public void setTextColors(@ColorInt int onSurface, @ColorInt int onSurfaceVariant) {
        mEmptyText.setTextColor(onSurfaceVariant);
        mEmptyFooterText.setTextColor(onSurface);
        mEmptyFooterText.setCompoundDrawableTintList(ColorStateList.valueOf(onSurface));
    }

    /** Set the resource ID for the main text shown by the view. */
    public void setText(@StringRes int text) {
        ModesEmptyShadeFix.assertInLegacyMode();
        mTextId = text;
        mEmptyText.setText(mTextId);
    }

    /** Set the string for the main text shown by the view. */
    public void setText(String text) {
        if (ModesEmptyShadeFix.isUnexpectedlyInLegacyMode() || Objects.equals(mTextString, text)) {
            return;
        }
        mTextString = text;
        mEmptyText.setText(text);
    }

    /** Visibility for the footer (the additional icon+text shown below the main text). */
    public void setFooterVisibility(@Visibility int visibility) {
        if (ModesEmptyShadeFix.isEnabled() && mFooterVisibility == visibility) {
            return; // nothing to change
        }
        mFooterVisibility = visibility;
        setSecondaryVisible(/* visible = */ visibility == View.VISIBLE,
                /* animate = */false,
                /* onAnimationEnded = */ null);
    }

    /** Text resource ID for the footer (the additional icon+text shown below the main text). */
    public void setFooterText(@StringRes int text) {
        if (ModesEmptyShadeFix.isEnabled() && mFooterText == text) {
            return; // nothing to change
        }
        mFooterText = text;
        if (text != 0) {
            mEmptyFooterText.setText(mFooterText);
        } else {
            mEmptyFooterText.setText(null);
        }
    }

    /** Icon resource ID for the footer (the additional icon+text shown below the main text). */
    public void setFooterIcon(@DrawableRes int icon) {
        if (ModesEmptyShadeFix.isEnabled() && mFooterIcon == icon) {
            return; // nothing to change
        }
        mFooterIcon = icon;
        Drawable drawable;
        if (icon == 0) {
            drawable = null;
        } else {
            drawable = getResources().getDrawable(icon);
            drawable.setBounds(0, 0, mSize, mSize);
        }
        mEmptyFooterText.setCompoundDrawablesRelative(drawable, null, null, null);
    }

    /** Get resource ID for main text. */
    @StringRes
    public int getTextResource() {
        ModesEmptyShadeFix.assertInLegacyMode();
        return mTextId;
    }

    /** Get resource ID for footer text. */
    @StringRes
    public int getFooterTextResource() {
        ModesEmptyShadeFix.assertInLegacyMode();
        return mFooterText;
    }

    /** Get resource ID for footer icon. */
    @DrawableRes
    public int getFooterIconResource() {
        ModesEmptyShadeFix.assertInLegacyMode();
        return mFooterIcon;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mEmptyText = (TextView) findContentView();
        mEmptyFooterText = (TextView) findSecondaryView();
        mEmptyFooterText.setCompoundDrawableTintList(mEmptyFooterText.getTextColors());
    }

    @Override
    @NonNull
    public ExpandableViewState createExpandableViewState() {
        return new EmptyShadeViewState();
    }

    public class EmptyShadeViewState extends ExpandableViewState {
        @Override
        public void applyToView(View view) {
            super.applyToView(view);
            if (view instanceof EmptyShadeView) {
                EmptyShadeView emptyShadeView = (EmptyShadeView) view;
                boolean visible = this.clipTopAmount <= mEmptyText.getPaddingTop() * 0.6f;
                emptyShadeView.setContentVisibleAnimated(visible && emptyShadeView.isVisible());
            }
        }
    }
}
