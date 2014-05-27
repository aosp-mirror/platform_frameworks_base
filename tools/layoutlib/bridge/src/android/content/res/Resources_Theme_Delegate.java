/*
 * Copyright (C) 2011 The Android Open Source Project
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

package android.content.res;

import com.android.annotations.Nullable;
import com.android.ide.common.rendering.api.ResourceReference;
import com.android.ide.common.rendering.api.StyleResourceValue;
import com.android.layoutlib.bridge.android.BridgeContext;
import com.android.layoutlib.bridge.impl.DelegateManager;
import com.android.layoutlib.bridge.impl.RenderSessionImpl;
import com.android.resources.ResourceType;
import com.android.tools.layoutlib.annotations.LayoutlibDelegate;

import android.content.res.Resources.NotFoundException;
import android.content.res.Resources.Theme;
import android.util.AttributeSet;
import android.util.TypedValue;

/**
 * Delegate used to provide new implementation of a select few methods of {@link Resources.Theme}
 *
 * Through the layoutlib_create tool, the original  methods of Theme have been replaced
 * by calls to methods of the same name in this delegate class.
 *
 */
public class Resources_Theme_Delegate {

    // Whether to use the Theme.mThemeResId as primary theme.
    boolean force;

    // ---- delegate manager ----

    private static final DelegateManager<Resources_Theme_Delegate> sManager =
            new DelegateManager<Resources_Theme_Delegate>(Resources_Theme_Delegate.class);

    public static DelegateManager<Resources_Theme_Delegate> getDelegateManager() {
        return sManager;
    }

    // ---- delegate methods. ----

    @LayoutlibDelegate
    /*package*/ static TypedArray obtainStyledAttributes(
            Resources thisResources, Theme thisTheme,
            int[] attrs) {
        boolean changed = setupResources(thisTheme);
        TypedArray ta = RenderSessionImpl.getCurrentContext().obtainStyledAttributes(attrs);
        restoreResources(changed);
        return ta;
    }

    @LayoutlibDelegate
    /*package*/ static TypedArray obtainStyledAttributes(
            Resources thisResources, Theme thisTheme,
            int resid, int[] attrs)
            throws NotFoundException {
        boolean changed = setupResources(thisTheme);
        TypedArray ta = RenderSessionImpl.getCurrentContext().obtainStyledAttributes(resid, attrs);
        restoreResources(changed);
        return ta;
    }

    @LayoutlibDelegate
    /*package*/ static TypedArray obtainStyledAttributes(
            Resources thisResources, Theme thisTheme,
            AttributeSet set, int[] attrs, int defStyleAttr, int defStyleRes) {
        boolean changed = setupResources(thisTheme);
        TypedArray ta = RenderSessionImpl.getCurrentContext().obtainStyledAttributes(set, attrs,
                defStyleAttr, defStyleRes);
        restoreResources(changed);
        return ta;
    }

    @LayoutlibDelegate
    /*package*/ static boolean resolveAttribute(
            Resources thisResources, Theme thisTheme,
            int resid, TypedValue outValue,
            boolean resolveRefs) {
        boolean changed = setupResources(thisTheme);
        boolean found =  RenderSessionImpl.getCurrentContext().resolveThemeAttribute(
                resid, outValue, resolveRefs);
        restoreResources(changed);
        return found;
    }

    @LayoutlibDelegate
    /*package*/ static TypedArray resolveAttributes(Resources thisResources, Theme thisTheme,
            int[] values, int[] attrs) {
        // FIXME
        return null;
    }

    // ---- private helper methods ----

    private static boolean setupResources(Theme thisTheme) {
        Resources_Theme_Delegate themeDelegate = sManager.getDelegate(thisTheme.getNativeTheme());
        StyleResourceValue style = resolveStyle(thisTheme.getAppliedStyleResId());
        if (style != null) {
            RenderSessionImpl.getCurrentContext().getRenderResources()
                    .applyStyle(style, themeDelegate.force);
            return true;
        }
        return false;
    }

    private static void restoreResources(boolean changed) {
        if (changed) {
            RenderSessionImpl.getCurrentContext().getRenderResources().clearStyles();
        }
    }

    @Nullable
    private static StyleResourceValue resolveStyle(int nativeResid) {
        if (nativeResid == 0) {
            return null;
        }
        BridgeContext context = RenderSessionImpl.getCurrentContext();
        ResourceReference theme = context.resolveId(nativeResid);
        if (theme.isFramework()) {
            return (StyleResourceValue) context.getRenderResources()
                    .getFrameworkResource(ResourceType.STYLE, theme.getName());
        } else {
            return (StyleResourceValue) context.getRenderResources()
                    .getProjectResource(ResourceType.STYLE, theme.getName());
        }
    }
}
