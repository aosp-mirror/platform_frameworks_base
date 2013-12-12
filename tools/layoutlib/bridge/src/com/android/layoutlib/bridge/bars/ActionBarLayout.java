/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.layoutlib.bridge.bars;

import com.android.ide.common.rendering.api.ActionBarCallback;
import com.android.ide.common.rendering.api.ActionBarCallback.HomeButtonStyle;
import com.android.ide.common.rendering.api.RenderResources;
import com.android.ide.common.rendering.api.ResourceValue;
import com.android.ide.common.rendering.api.SessionParams;
import com.android.internal.app.ActionBarImpl;
import com.android.internal.view.menu.MenuBuilder;
import com.android.internal.widget.ActionBarContainer;
import com.android.internal.widget.ActionBarView;
import com.android.layoutlib.bridge.Bridge;
import com.android.layoutlib.bridge.android.BridgeContext;
import com.android.layoutlib.bridge.android.BridgeXmlBlockParser;
import com.android.layoutlib.bridge.impl.ParserFactory;
import com.android.layoutlib.bridge.impl.ResourceHelper;
import com.android.resources.ResourceType;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import android.app.ActionBar;
import android.app.ActionBar.Tab;
import android.app.ActionBar.TabListener;
import android.app.FragmentTransaction;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MenuInflater;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;

public class ActionBarLayout extends LinearLayout {

    // Store another reference to the context so that we don't have to cast it repeatedly.
    @SuppressWarnings("hiding")
    private final BridgeContext mContext;
    private final ActionBar mActionBar;
    private final String mIcon;
    private final String mTitle;
    private final String mSubTitle;
    private final boolean mSplit;
    private final boolean mShowHomeAsUp;
    private final List<Integer> mMenuIds;
    private final MenuBuilder mMenuBuilder;
    private final int mNavMode;

    public ActionBarLayout(BridgeContext context, SessionParams params)
            throws XmlPullParserException {
        super(context);
        mIcon = params.getAppIcon();
        mTitle = params.getAppLabel();
        mContext = context;
        mMenuIds = new ArrayList<Integer>();

        ActionBarCallback callback = params.getProjectCallback().getActionBarCallback();
        mSplit = callback.getSplitActionBarWhenNarrow() &
                context.getResources().getBoolean(
                        com.android.internal.R.bool.split_action_bar_is_narrow);
        mNavMode = callback.getNavigationMode();
        // TODO: Support Navigation Drawer Indicator.
        mShowHomeAsUp = callback.getHomeButtonStyle() == HomeButtonStyle.SHOW_HOME_AS_UP;
        mSubTitle = callback.getSubTitle();

        fillMenuIds(callback.getMenuIdNames());
        mMenuBuilder = new MenuBuilder(mContext);

        setOrientation(LinearLayout.HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);

        LayoutInflater inflater = (LayoutInflater) context.getSystemService(
                Context.LAYOUT_INFLATER_SERVICE);
        XmlPullParser parser = ParserFactory.create(
                getClass().getResourceAsStream("/bars/action_bar.xml"), "action_bar.xml");
        BridgeXmlBlockParser bridgeParser = new BridgeXmlBlockParser(
                parser, context, false /*platformFile*/);
        try {
            inflater.inflate(bridgeParser, this, true /*attachToRoot*/);
        } finally {
            bridgeParser.ensurePopped();
        }
        mActionBar = new ActionBarImpl(this);

        setUpActionBar();
    }

    private void fillMenuIds(List<String> menuIdNames) {
        for (String name : menuIdNames) {
            int id = mContext.getProjectResourceValue(ResourceType.MENU, name, -1);
            if (id > -1) {
                mMenuIds.add(id);
            }
        }
    }

    private void setUpActionBar() {
        RenderResources res = mContext.getRenderResources();
        Drawable iconDrawable = getDrawable(res, mIcon, false /*isFramework*/);
        if (iconDrawable != null) {
            mActionBar.setIcon(iconDrawable);
        }
        ResourceValue titleValue = res.findResValue(mTitle, false /*isFramework*/);
        if (titleValue != null && titleValue.getValue() != null) {
            mActionBar.setTitle(titleValue.getValue());
        } else {
            mActionBar.setTitle(mTitle);
        }
        if (mSubTitle != null) {
            mActionBar.setSubtitle(mSubTitle);
        }
        ActionBarView actionBarView = (ActionBarView) findViewById(
            Bridge.getResourceId(ResourceType.ID, "action_bar"));
        actionBarView.setSplitView((ActionBarContainer) findViewById(
            Bridge.getResourceId(ResourceType.ID, "split_action_bar")));
        actionBarView.setSplitActionBar(mSplit);
        if (mShowHomeAsUp) {
            mActionBar.setDisplayOptions(0xFF, ActionBar.DISPLAY_HOME_AS_UP);
        }
        setUpActionMenus();

        mActionBar.setNavigationMode(mNavMode);
        if (mNavMode == ActionBar.NAVIGATION_MODE_TABS) {
            setUpTabs(3);
        }
    }

    private void setUpActionMenus() {
        if (mMenuIds == null) {
            return;
        }
        ActionBarView actionBarView = (ActionBarView) findViewById(Bridge.getResourceId(
                ResourceType.ID, "action_bar"));
        final MenuInflater inflater = new MenuInflater(mActionBar.getThemedContext());
        for (int id : mMenuIds) {
            inflater.inflate(id, mMenuBuilder);
        }
        actionBarView.setMenu(mMenuBuilder, null /*callback*/);
    }

    // TODO: Use an adapter, like List View to set up tabs.
    private void setUpTabs(int num) {
        for (int i = 1; i <= num; i++) {
            Tab tab = mActionBar.newTab()
                    .setText("Tab" + i)
                    .setTabListener(new TabListener() {
                @Override
                public void onTabUnselected(Tab t, FragmentTransaction ft) {
                    // pass
                }
                @Override
                public void onTabSelected(Tab t, FragmentTransaction ft) {
                    // pass
                }
                @Override
                public void onTabReselected(Tab t, FragmentTransaction ft) {
                    // pass
                }
            });
            mActionBar.addTab(tab);
        }
    }

    private Drawable getDrawable(RenderResources res, String name, boolean isFramework) {
        ResourceValue value = res.findResValue(name, isFramework);
        value = res.resolveResValue(value);
        if (value != null) {
            return ResourceHelper.getDrawable(value, mContext);
        }
        return null;
    }
}
