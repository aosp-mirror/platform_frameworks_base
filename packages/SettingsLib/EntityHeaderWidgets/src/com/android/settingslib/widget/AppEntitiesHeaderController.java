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

import android.content.Context;
import android.content.res.Resources;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.annotation.VisibleForTesting;

import com.android.settingslib.widget.entityheader.R;

/**
 * This class is used to initialize view which was inflated
 * from {@link R.xml.app_entities_header.xml}.
 *
 * <p>How to use AppEntitiesHeaderController?
 *
 * <p>1. Add a {@link LayoutPreference} in layout XML file.
 * <pre>
 * &lt;com.android.settingslib.widget.LayoutPreference
 *        android:key="app_entities_header"
 *        android:layout="@layout/app_entities_header"/&gt;
 * </pre>
 *
 * <p>2. Use AppEntitiesHeaderController to call below methods, then you can initialize
 * view of <code>app_entities_header</code>.
 *
 * <pre>
 *
 * View headerView = ((LayoutPreference) screen.findPreference("app_entities_header"))
 *         .findViewById(R.id.app_entities_header);
 *
 * final AppEntityInfo appEntityInfo = new AppEntityInfo.Builder()
 *         .setIcon(icon)
 *         .setTitle(title)
 *         .setSummary(summary)
 *         .setOnClickListener(view -> doSomething())
 *         .build();
 *
 * AppEntitiesHeaderController.newInstance(context, headerView)
 *         .setHeaderTitleRes(R.string.xxxxx)
 *         .setHeaderDetailsRes(R.string.xxxxx)
 *         .setHeaderDetailsClickListener(onClickListener)
 *         .setAppEntity(0, appEntityInfo)
 *         .setAppEntity(1, appEntityInfo)
 *         .setAppEntity(2, appEntityInfo)
 *         .apply();
 * </pre>
 */
public class AppEntitiesHeaderController {

    private static final String TAG = "AppEntitiesHeaderCtl";

    @VisibleForTesting
    public static final int MAXIMUM_APPS = 3;

    private final Context mContext;
    private final TextView mHeaderTitleView;
    private final TextView mHeaderEmptyView;
    private final Button mHeaderDetailsView;

    private final AppEntityInfo[] mAppEntityInfos;
    private final View mAppViewsContainer;
    private final View[] mAppEntityViews;
    private final ImageView[] mAppIconViews;
    private final TextView[] mAppTitleViews;
    private final TextView[] mAppSummaryViews;

    private int mHeaderTitleRes;
    private int mHeaderDetailsRes;
    private int mHeaderEmptyRes;
    private CharSequence mHeaderDetails;
    private View.OnClickListener mDetailsOnClickListener;

    /**
     * Creates a new instance of the controller.
     *
     * @param context               the Context the view is running in
     * @param appEntitiesHeaderView view was inflated from <code>app_entities_header</code>
     */
    public static AppEntitiesHeaderController newInstance(@NonNull Context context,
            @NonNull View appEntitiesHeaderView) {
        return new AppEntitiesHeaderController(context, appEntitiesHeaderView);
    }

    private AppEntitiesHeaderController(Context context, View appEntitiesHeaderView) {
        mContext = context;
        mHeaderTitleView = appEntitiesHeaderView.findViewById(R.id.header_title);
        mHeaderDetailsView = appEntitiesHeaderView.findViewById(R.id.header_details);
        mHeaderEmptyView = appEntitiesHeaderView.findViewById(R.id.empty_view);
        mAppViewsContainer = appEntitiesHeaderView.findViewById(R.id.app_views_container);

        mAppEntityInfos = new AppEntityInfo[MAXIMUM_APPS];
        mAppIconViews = new ImageView[MAXIMUM_APPS];
        mAppTitleViews = new TextView[MAXIMUM_APPS];
        mAppSummaryViews = new TextView[MAXIMUM_APPS];

        mAppEntityViews = new View[]{
                appEntitiesHeaderView.findViewById(R.id.app1_view),
                appEntitiesHeaderView.findViewById(R.id.app2_view),
                appEntitiesHeaderView.findViewById(R.id.app3_view)
        };

        // Initialize view in advance, so we won't take too much time to do it when controller is
        // binding view.
        for (int index = 0; index < MAXIMUM_APPS; index++) {
            final View appView = mAppEntityViews[index];
            mAppIconViews[index] = (ImageView) appView.findViewById(R.id.app_icon);
            mAppTitleViews[index] = (TextView) appView.findViewById(R.id.app_title);
            mAppSummaryViews[index] = (TextView) appView.findViewById(R.id.app_summary);
        }
    }

    /**
     * Set the text resource for app entities header title.
     */
    public AppEntitiesHeaderController setHeaderTitleRes(@StringRes int titleRes) {
        mHeaderTitleRes = titleRes;
        return this;
    }

    /**
     * Set the text resource for app entities header details.
     */
    public AppEntitiesHeaderController setHeaderDetailsRes(@StringRes int detailsRes) {
        mHeaderDetailsRes = detailsRes;
        return this;
    }

    /**
     * Set the text for app entities header details.
     */
    public AppEntitiesHeaderController setHeaderDetails(CharSequence detailsText) {
        mHeaderDetails = detailsText;
        return this;
    }

    /**
     * Register a callback to be invoked when header details view is clicked.
     */
    public AppEntitiesHeaderController setHeaderDetailsClickListener(
            @Nullable View.OnClickListener clickListener) {
        mDetailsOnClickListener = clickListener;
        return this;
    }

    /**
     * Sets the string resource id for the empty text.
     */
    public AppEntitiesHeaderController setHeaderEmptyRes(@StringRes int emptyRes) {
        mHeaderEmptyRes = emptyRes;
        return this;
    }

    /**
     * Set an app entity at a specified position view.
     *
     * @param index         the index at which the specified view is to be inserted
     * @param appEntityInfo the information of an app entity
     * @return this {@code AppEntitiesHeaderController} object
     */
    public AppEntitiesHeaderController setAppEntity(int index,
            @NonNull AppEntityInfo appEntityInfo) {
        mAppEntityInfos[index] = appEntityInfo;
        return this;
    }

    /**
     * Remove an app entity at a specified position view.
     *
     * @param index the index at which the specified view is to be removed
     * @return this {@code AppEntitiesHeaderController} object
     */
    public AppEntitiesHeaderController removeAppEntity(int index) {
        mAppEntityInfos[index] = null;
        return this;
    }

    /**
     * Clear all app entities in app entities header.
     *
     * @return this {@code AppEntitiesHeaderController} object
     */
    public AppEntitiesHeaderController clearAllAppEntities() {
        for (int index = 0; index < MAXIMUM_APPS; index++) {
            removeAppEntity(index);
        }
        return this;
    }

    /**
     * Done mutating app entities header, rebinds everything.
     */
    public void apply() {
        bindHeaderTitleView();

        if (isAppEntityInfosEmpty()) {
            setEmptyViewVisible(true);
            return;
        }
        setEmptyViewVisible(false);
        bindHeaderDetailsView();

        // Rebind all apps view
        for (int index = 0; index < MAXIMUM_APPS; index++) {
            bindAppEntityView(index);
        }
    }

    private void bindHeaderTitleView() {
        CharSequence titleText = "";
        try {
            titleText = mContext.getText(mHeaderTitleRes);
        } catch (Resources.NotFoundException e) {
            Log.e(TAG, "Resource of header title can't not be found!", e);
        }
        mHeaderTitleView.setText(titleText);
        mHeaderTitleView.setVisibility(
                TextUtils.isEmpty(titleText) ? View.GONE : View.VISIBLE);
    }

    private void bindHeaderDetailsView() {
        CharSequence detailsText = mHeaderDetails;
        if (TextUtils.isEmpty(detailsText)) {
            try {
                detailsText = mContext.getText(mHeaderDetailsRes);
            } catch (Resources.NotFoundException e) {
                Log.e(TAG, "Resource of header details can't not be found!", e);
            }
        }
        mHeaderDetailsView.setText(detailsText);
        mHeaderDetailsView.setVisibility(
                TextUtils.isEmpty(detailsText) ? View.GONE : View.VISIBLE);
        mHeaderDetailsView.setOnClickListener(mDetailsOnClickListener);
    }

    private void bindAppEntityView(int index) {
        final AppEntityInfo appEntityInfo = mAppEntityInfos[index];
        mAppEntityViews[index].setVisibility(appEntityInfo != null ? View.VISIBLE : View.GONE);

        if (appEntityInfo != null) {
            mAppEntityViews[index].setOnClickListener(appEntityInfo.getClickListener());

            mAppIconViews[index].setImageDrawable(appEntityInfo.getIcon());

            final CharSequence title = appEntityInfo.getTitle();
            mAppTitleViews[index].setVisibility(
                    TextUtils.isEmpty(title) ? View.INVISIBLE : View.VISIBLE);
            mAppTitleViews[index].setText(title);

            final CharSequence summary = appEntityInfo.getSummary();
            mAppSummaryViews[index].setVisibility(
                    TextUtils.isEmpty(summary) ? View.INVISIBLE : View.VISIBLE);
            mAppSummaryViews[index].setText(summary);
        }
    }

    private void setEmptyViewVisible(boolean visible) {
        if (mHeaderEmptyRes != 0) {
            mHeaderEmptyView.setText(mHeaderEmptyRes);
        }
        mHeaderEmptyView.setVisibility(visible ? View.VISIBLE : View.GONE);
        mHeaderDetailsView.setVisibility(visible ? View.GONE : View.VISIBLE);
        mAppViewsContainer.setVisibility(visible ? View.GONE : View.VISIBLE);
    }

    private boolean isAppEntityInfosEmpty() {
        for (AppEntityInfo info : mAppEntityInfos) {
            if (info != null) {
                return false;
            }
        }
        return true;
    }
}
