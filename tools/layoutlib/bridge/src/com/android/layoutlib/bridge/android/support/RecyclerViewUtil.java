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

package com.android.layoutlib.bridge.android.support;

import com.android.ide.common.rendering.api.LayoutLog;
import com.android.ide.common.rendering.api.LayoutlibCallback;
import com.android.ide.common.rendering.api.SessionParams;
import com.android.layoutlib.bridge.Bridge;
import com.android.layoutlib.bridge.android.BridgeContext;
import com.android.layoutlib.bridge.android.RenderParamsFlags;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.view.View;

import java.lang.reflect.Method;

import static com.android.layoutlib.bridge.util.ReflectionUtils.ReflectionException;
import static com.android.layoutlib.bridge.util.ReflectionUtils.getMethod;
import static com.android.layoutlib.bridge.util.ReflectionUtils.invoke;

/**
 * Utility class for working with android.support.v7.widget.RecyclerView
 */
@SuppressWarnings("SpellCheckingInspection")  // for "recycler".
public class RecyclerViewUtil {

    private static final String RV_PKG_PREFIX = "android.support.v7.widget.";
    public static final String CN_RECYCLER_VIEW = RV_PKG_PREFIX + "RecyclerView";
    private static final String CN_LAYOUT_MANAGER = CN_RECYCLER_VIEW + "$LayoutManager";
    private static final String CN_ADAPTER = CN_RECYCLER_VIEW + "$Adapter";

    // LinearLayoutManager related constants.
    private static final String CN_LINEAR_LAYOUT_MANAGER = RV_PKG_PREFIX + "LinearLayoutManager";
    private static final Class<?>[] LLM_CONSTRUCTOR_SIGNATURE = new Class<?>[]{Context.class};

    /**
     * Tries to create an Adapter ({@code android.support.v7.widget.RecyclerView.Adapter} and a
     * LayoutManager {@code RecyclerView.LayoutManager} and assign these to the {@code RecyclerView}
     * that is passed.
     * <p/>
     * Any exceptions thrown during the process are logged in {@link Bridge#getLog()}
     */
    public static void setAdapter(@NonNull View recyclerView, @NonNull BridgeContext context,
            @NonNull SessionParams params) {
        try {
            setLayoutManager(recyclerView, context, params.getLayoutlibCallback());
            Object adapter = createAdapter(params);
            setProperty(recyclerView, CN_ADAPTER, adapter, "setAdapter");
        } catch (ReflectionException e) {
            Bridge.getLog().error(LayoutLog.TAG_BROKEN,
                    "Error occured while trying to setup RecyclerView.", e, null);
        }
    }

    private static void setLayoutManager(@NonNull View recyclerView, @NonNull BridgeContext context,
            @NonNull LayoutlibCallback callback) throws ReflectionException {
        if (getLayoutManager(recyclerView) == null) {
            // Only set the layout manager if not already set by the recycler view.
            Object layoutManager = createLayoutManager(context, callback);
            setProperty(recyclerView, CN_LAYOUT_MANAGER, layoutManager, "setLayoutManager");
        }
    }

    /** Creates a LinearLayoutManager using the provided context. */
    @Nullable
    private static Object createLayoutManager(@NonNull Context context,
            @NonNull LayoutlibCallback callback)
            throws ReflectionException {
        try {
            return callback.loadView(CN_LINEAR_LAYOUT_MANAGER, LLM_CONSTRUCTOR_SIGNATURE,
                    new Object[]{ context});
        } catch (Exception e) {
            throw new ReflectionException(e);
        }
    }

    @Nullable
    private static Object getLayoutManager(View recyclerview) throws ReflectionException {
        Method getLayoutManager = getMethod(recyclerview.getClass(), "getLayoutManager");
        return getLayoutManager != null ? invoke(getLayoutManager, recyclerview) : null;
    }

    @Nullable
    private static Object createAdapter(@NonNull SessionParams params) throws ReflectionException {
        Boolean ideSupport = params.getFlag(RenderParamsFlags.FLAG_KEY_RECYCLER_VIEW_SUPPORT);
        if (ideSupport != Boolean.TRUE) {
            return null;
        }
        try {
            return params.getLayoutlibCallback().loadView(CN_ADAPTER, new Class[0], new Object[0]);
        } catch (Exception e) {
            throw new ReflectionException(e);
        }
    }

    private static void setProperty(@NonNull View recyclerView, @NonNull String propertyClassName,
            @Nullable Object propertyValue, @NonNull String propertySetter)
            throws ReflectionException {
        if (propertyValue != null) {
            Class<?> layoutManagerClass = getClassInstance(propertyValue, propertyClassName);
            Method setLayoutManager = getMethod(recyclerView.getClass(),
                    propertySetter, layoutManagerClass);
            if (setLayoutManager != null) {
                invoke(setLayoutManager, recyclerView, propertyValue);
            }
        }
    }

    /**
     * Looks through the class hierarchy of {@code object} at runtime and returns the class matching
     * the name {@code className}.
     * <p/>
     * This is used when we cannot use Class.forName() since the class we want was loaded from a
     * different ClassLoader.
     */
    @NonNull
    private static Class<?> getClassInstance(@NonNull Object object, @NonNull String className) {
        Class<?> superClass = object.getClass();
        while (superClass != null) {
            if (className.equals(superClass.getName())) {
                return superClass;
            }
            superClass = superClass.getSuperclass();
        }
        throw new RuntimeException("invalid object/classname combination.");
    }
}
