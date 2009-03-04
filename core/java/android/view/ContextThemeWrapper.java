/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.view;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources;

/**
 * A ContextWrapper that allows you to modify the theme from what is in the 
 * wrapped context. 
 */
public class ContextThemeWrapper extends ContextWrapper {
    private Context mBase;
    private int mThemeResource;
    private Resources.Theme mTheme;
    private LayoutInflater mInflater;

    public ContextThemeWrapper() {
        super(null);
    }
    
    public ContextThemeWrapper(Context base, int themeres) {
        super(base);
        mBase = base;
        mThemeResource = themeres;
    }

    @Override protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(newBase);
        mBase = newBase;
    }
    
    @Override public void setTheme(int resid) {
        mThemeResource = resid;
        initializeTheme();
    }
    
    @Override public Resources.Theme getTheme() {
        if (mTheme != null) {
            return mTheme;
        }

        if (mThemeResource == 0) {
            mThemeResource = com.android.internal.R.style.Theme;
        }
        initializeTheme();

        return mTheme;
    }

    @Override public Object getSystemService(String name) {
        if (LAYOUT_INFLATER_SERVICE.equals(name)) {
            if (mInflater == null) {
                mInflater = LayoutInflater.from(mBase).cloneInContext(this);
            }
            return mInflater;
        }
        return mBase.getSystemService(name);
    }
    
    /**
     * Called by {@link #setTheme} and {@link #getTheme} to apply a theme
     * resource to the current Theme object.  Can override to change the
     * default (simple) behavior.  This method will not be called in multiple
     * threads simultaneously.
     *
     * @param theme The Theme object being modified.
     * @param resid The theme style resource being applied to <var>theme</var>.
     * @param first Set to true if this is the first time a style is being
     *              applied to <var>theme</var>.
     */
    protected void onApplyThemeResource(Resources.Theme theme, int resid, boolean first) {
        theme.applyStyle(resid, true);
    }

    private void initializeTheme() {
        final boolean first = mTheme == null;
        if (first) {
            mTheme = getResources().newTheme();
            Resources.Theme theme = mBase.getTheme();
            if (theme != null) {
                mTheme.setTo(theme);
            }
        }
        onApplyThemeResource(mTheme, mThemeResource, first);
    }
}

