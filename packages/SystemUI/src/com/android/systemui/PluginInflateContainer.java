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
package com.android.systemui;

import android.annotation.Nullable;
import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

import com.android.systemui.res.R;
import com.android.systemui.plugins.PluginListener;
import com.android.systemui.plugins.PluginManager;
import com.android.systemui.plugins.ViewProvider;

/**
 * Define an interface or abstract class as follows that includes the
 * version and action.
 *
 * public interface MyInterface {
 *     public static final String ACTION =
 *             "com.android.systemui.action.PLUGIN_MYINTERFACE";
 *
 *     public static final int VERSION = 1;
 *
 *     void myImportantInterface();
 * }
 *
 * Then put in a PluginInflateContainer to use and specify the interface
 * or class that will be implemented as viewType.  The layout specified
 * will be used by default and whenever a plugin is not present.
 *
 * <com.android.systemui.PluginInflateContainer
 *     android:id="@+id/some_id"
 *     android:layout_width="match_parent"
 *     android:layout_height="match_parent"
 *     android:layout="@layout/my_default_component"
 *     systemui:viewType="com.android.systemui.plugins.MyInterface" />
 */
public class PluginInflateContainer extends AutoReinflateContainer
        implements PluginListener<ViewProvider> {

    private static final String TAG = "PluginInflateContainer";

    private Class<ViewProvider> mClass;
    private View mPluginView;

    public PluginInflateContainer(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.PluginInflateContainer);
        String viewType = a.getString(R.styleable.PluginInflateContainer_viewType);
        a.recycle();
        try {
            mClass = (Class<ViewProvider>) Class.forName(viewType);
        } catch (Exception e) {
            Log.d(TAG, "Problem getting class info " + viewType, e);
            mClass = null;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (mClass != null) {
            Dependency.get(PluginManager.class).addPluginListener(this, mClass);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mClass != null) {
            Dependency.get(PluginManager.class).removePluginListener(this);
        }
    }

    @Override
    protected void inflateLayoutImpl() {
        if (mPluginView != null) {
            addView(mPluginView);
        } else {
            super.inflateLayoutImpl();
        }
    }

    @Override
    public void onPluginConnected(ViewProvider plugin, Context context) {
        mPluginView = plugin.getView();
        inflateLayout();
    }

    @Override
    public void onPluginDisconnected(ViewProvider plugin) {
        mPluginView = null;
        inflateLayout();
    }
}
