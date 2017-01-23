/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.plugins.qs;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;

import com.android.systemui.plugins.FragmentBase;

/**
 * Fragment that contains QS in the notification shade.  Most of the interface is for
 * handling the expand/collapsing of the view interaction.
 */
public interface QS extends FragmentBase {

    public static final String ACTION = "com.android.systemui.action.PLUGIN_QS";

    // This should be incremented any time this class or ActivityStarter or BaseStatusBarHeader
    // change in incompatible ways.
    public static final int VERSION = 5;

    String TAG = "QS";

    public abstract void setPanelView(HeightListener notificationPanelView);
    public abstract BaseStatusBarHeader getHeader();

    public abstract void hideImmediately();
    public abstract int getQsMinExpansionHeight();
    public abstract int getDesiredHeight();
    public abstract void setHeightOverride(int desiredHeight);
    public abstract void setHeaderClickable(boolean qsExpansionEnabled);
    public abstract boolean isCustomizing();
    public abstract void setOverscrolling(boolean overscrolling);
    public abstract void setExpanded(boolean qsExpanded);
    public abstract void setListening(boolean listening);
    public abstract boolean isShowingDetail();
    public abstract void closeDetail();
    public abstract void setKeyguardShowing(boolean keyguardShowing);
    public abstract void animateHeaderSlidingIn(long delay);
    public abstract void animateHeaderSlidingOut();
    public abstract void setQsExpansion(float qsExpansionFraction, float headerTranslation);
    public abstract void setHeaderListening(boolean listening);
    public abstract void notifyCustomizeChanged();

    public abstract void setContainer(ViewGroup container);

    public interface HeightListener {
        void onQsHeightChanged();
    }

    public interface Callback {
        void onShowingDetail(DetailAdapter detail, int x, int y);
        void onToggleStateChanged(boolean state);
        void onScanStateChanged(boolean state);
    }

    public interface DetailAdapter {
        CharSequence getTitle();
        Boolean getToggleState();
        default boolean getToggleEnabled() {
            return true;
        }
        View createDetailView(Context context, View convertView, ViewGroup parent);
        Intent getSettingsIntent();
        void setToggleState(boolean state);
        int getMetricsCategory();

        /**
         * Indicates whether the detail view wants to have its header (back button, title and
         * toggle) shown.
         */
        default boolean hasHeader() { return true; }
    }

    public abstract static class BaseStatusBarHeader extends RelativeLayout {

        public BaseStatusBarHeader(Context context, AttributeSet attrs) {
            super(context, attrs);
        }

        public abstract int getCollapsedHeight();
        public abstract int getExpandedHeight();

        public abstract void setExpanded(boolean b);
        public abstract void setExpansion(float headerExpansionFraction);
        public abstract void setListening(boolean listening);
        public abstract void updateEverything();
        public abstract void setCallback(Callback qsPanelCallback);
        public abstract View getExpandView();
    }

}
