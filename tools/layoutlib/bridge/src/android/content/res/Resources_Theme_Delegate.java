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
        BridgeTypedArray ta = RenderSessionImpl.getCurrentContext().obtainStyledAttributes(attrs);
        ta.setTheme(thisTheme);
        restoreResources(changed);
        return ta;
    }

    @LayoutlibDelegate
    /*package*/ static TypedArray obtainStyledAttributes(
            Resources thisResources, Theme thisTheme,
            int resid, int[] attrs)
            throws NotFoundException {
        boolean changed = setupResources(thisTheme);
        BridgeTypedArray ta = RenderSessionImpl.getCurrentContext().obtainStyledAttributes(resid,
                attrs);
        ta.setTheme(thisTheme);
        restoreResources(changed);
        return ta;
    }

    @LayoutlibDelegate
    /*package*/ static TypedArray obtainStyledAttributes(
            Resources thisResources, Theme thisTheme,
            AttributeSet set, int[] attrs, int defStyleAttr, int defStyleRes) {
        boolean changed = setupResources(thisTheme);
        BridgeTypedArray ta = RenderSessionImpl.getCurrentContext().obtainStyledAttributes(set,
                attrs, defStyleAttr, defStyleRes);
        ta.setTheme(thisTheme);
        restoreResources(changed);
        return ta;
    }

    @LayoutlibDelegate
    /*package*/ static boolean resolveAttribute(
            Resources thisResources, Theme thisTheme,
            int resid, TypedValue outValue,
            boolean resolveRefs) {
        boolean changed = setupResources(thisTheme);
        boolean found =  RenderSessionImpl.getCurrentContext().resolveThemeAttribute(resid,
                outValue, resolveRefs);
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
        // Key is a space-separated list of theme ids applied that have been merged into the
        // BridgeContext's theme to make thisTheme.
        String[] appliedStyles = thisTheme.getKey().split(" ");
        boolean changed = false;
        for (String s : appliedStyles) {
            if (s.isEmpty()) {
                continue;
            }
            // See the definition of force parameter in Theme.applyStyle().
            boolean force = false;
            if (s.charAt(s.length() - 1) == '!') {
                force = true;
                s = s.substring(0, s.length() - 1);
            }
            int styleId = Integer.parseInt(s, 16);
            StyleResourceValue style = resolveStyle(styleId);
            if (style != null) {
                RenderSessionImpl.getCurrentContext().getRenderResources().applyStyle(style, force);
                changed = true;
            }

        }
        return changed;
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
