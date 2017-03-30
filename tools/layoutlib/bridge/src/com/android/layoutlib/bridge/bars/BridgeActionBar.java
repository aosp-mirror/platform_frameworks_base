/*
 * Copyright (C) 2015 The Android Open Source Project
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
import com.android.layoutlib.bridge.MockView;
import com.android.layoutlib.bridge.android.BridgeContext;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;

/**
 * An abstraction over two implementations of the ActionBar - framework and appcompat.
 */
public abstract class BridgeActionBar {
    // Store a reference to the context so that we don't have to cast it repeatedly.
    @NonNull protected final BridgeContext mBridgeContext;
    @NonNull protected final SessionParams mParams;
    // A Layout that contains the inflated action bar. The menu popup is added to this layout.
    @Nullable protected final ViewGroup mEnclosingLayout;

    private final View mDecorContent;
    private final ActionBarCallback mCallback;

    @SuppressWarnings("NullableProblems")  // Should be initialized by subclasses.
    @NonNull private FrameLayout mContentRoot;

    public BridgeActionBar(@NonNull BridgeContext context, @NonNull SessionParams params) {
        mBridgeContext = context;
        mParams = params;
        mCallback = params.getLayoutlibCallback().getActionBarCallback();
        ResourceValue layoutName = getLayoutResource(context);

        int layoutId = 0;
        if (layoutName == null) {
            assert false : "Unable to find the layout for Action Bar.";
        }
        else {
            if (layoutName.isFramework()) {
                layoutId = context.getFrameworkResourceValue(layoutName.getResourceType(),
                        layoutName.getName(), 0);
            } else {
                layoutId = context.getProjectResourceValue(layoutName.getResourceType(),
                        layoutName.getName(), 0);

            }
        }
        if (layoutId == 0) {
            assert false : String.format("Unable to resolve attribute \"%1$s\" of type \"%2$s\"",
                    layoutName.getName(), layoutName.getResourceType());
            mDecorContent = new MockView(context);
            mEnclosingLayout = null;
        }
        else {
            if (mCallback.isOverflowPopupNeeded()) {
                // Create a RelativeLayout around the action bar, to which the overflow popup may be
                // added.
                mEnclosingLayout = new RelativeLayout(mBridgeContext);
                setMatchParent(mEnclosingLayout);
            } else {
                mEnclosingLayout = null;
            }

            // Inflate action bar layout.
            mDecorContent = getInflater(context).inflate(layoutId, mEnclosingLayout,
                    mEnclosingLayout != null);
        }
    }

    /**
     * Returns the Layout Resource that should be used to inflate the action bar. This layout
     * should cover the complete screen, and have a FrameLayout included, where the content will
     * be inflated.
     */
    protected abstract ResourceValue getLayoutResource(BridgeContext context);

    protected LayoutInflater getInflater(BridgeContext context) {
        return LayoutInflater.from(context);
    }

    protected void setContentRoot(@NonNull FrameLayout contentRoot) {
        mContentRoot = contentRoot;
    }

    @NonNull
    public FrameLayout getContentRoot() {
        return mContentRoot;
    }

    /**
     * Returns the view inflated. This should contain both the ActionBar and the app content in it.
     */
    protected View getDecorContent() {
        return mDecorContent;
    }

    /** Setup things like the title, subtitle, icon etc. */
    protected void setupActionBar() {
        setTitle();
        setSutTitle();
        setIcon();
        setHomeAsUp(mCallback.getHomeButtonStyle() == HomeButtonStyle.SHOW_HOME_AS_UP);
    }

    protected abstract void setTitle(CharSequence title);
    protected abstract void setSubtitle(CharSequence subtitle);
    protected abstract void setIcon(String icon);
    protected abstract void setHomeAsUp(boolean homeAsUp);

    private void setTitle() {
        RenderResources res = mBridgeContext.getRenderResources();

        String title = mParams.getAppLabel();
        ResourceValue titleValue = res.findResValue(title, false);
        if (titleValue != null && titleValue.getValue() != null) {
            setTitle(titleValue.getValue());
        } else {
            setTitle(title);
        }
    }

    private void setSutTitle() {
        String subTitle = mCallback.getSubTitle();
        if (subTitle != null) {
            setSubtitle(subTitle);
        }
    }

    private void setIcon() {
        String appIcon = mParams.getAppIcon();
        if (appIcon != null) {
            setIcon(appIcon);
        }
    }

    public abstract void createMenuPopup();

    /**
     * The root view that represents the action bar and possibly the content included in it.
     */
    public View getRootView() {
        return mEnclosingLayout == null ? mDecorContent : mEnclosingLayout;
    }

    public ActionBarCallback getCallBack() {
        return mCallback;
    }

    protected static void setMatchParent(View view) {
        view.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT));
    }
}
