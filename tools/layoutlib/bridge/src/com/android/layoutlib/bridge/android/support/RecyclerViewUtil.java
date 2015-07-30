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
import com.android.layoutlib.bridge.Bridge;
import com.android.layoutlib.bridge.android.BridgeContext;
import com.android.layoutlib.bridge.android.RenderParamsFlags;
import com.android.layoutlib.bridge.util.ReflectionUtils.ReflectionException;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.view.View;

import static com.android.layoutlib.bridge.util.ReflectionUtils.getCause;
import static com.android.layoutlib.bridge.util.ReflectionUtils.getMethod;
import static com.android.layoutlib.bridge.util.ReflectionUtils.invoke;

/**
 * Utility class for working with android.support.v7.widget.RecyclerView
 */
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
            @NonNull LayoutlibCallback layoutlibCallback, int adapterLayout) {
        try {
            setLayoutManager(recyclerView, context, layoutlibCallback);
            Object adapter = createAdapter(layoutlibCallback);
            if (adapter != null) {
                setProperty(recyclerView, CN_ADAPTER, adapter, "setAdapter");
                setProperty(adapter, int.class, adapterLayout, "setLayoutId");
            }
        } catch (ReflectionException e) {
            Throwable cause = getCause(e);
            Bridge.getLog().error(LayoutLog.TAG_BROKEN,
                    "Error occurred while trying to setup RecyclerView.", cause, null);
        }
    }

    private static void setLayoutManager(@NonNull View recyclerView, @NonNull BridgeContext context,
            @NonNull LayoutlibCallback callback) throws ReflectionException {
        if (getLayoutManager(recyclerView) == null) {
            // Only set the layout manager if not already set by the recycler view.
            Object layoutManager = createLayoutManager(context, callback);
            if (layoutManager != null) {
                setProperty(recyclerView, CN_LAYOUT_MANAGER, layoutManager, "setLayoutManager");
            }
        }
    }

    /** Creates a LinearLayoutManager using the provided context. */
    @Nullable
    private static Object createLayoutManager(@NonNull Context context,
            @NonNull LayoutlibCallback callback)
            throws ReflectionException {
        try {
            return callback.loadView(CN_LINEAR_LAYOUT_MANAGER, LLM_CONSTRUCTOR_SIGNATURE,
                    new Object[]{context});
        } catch (Exception e) {
            throw new ReflectionException(e);
        }
    }

    @Nullable
    private static Object getLayoutManager(View recyclerView) throws ReflectionException {
        return invoke(getMethod(recyclerView.getClass(), "getLayoutManager"), recyclerView);
    }

    @Nullable
    private static Object createAdapter(@NonNull LayoutlibCallback layoutlibCallback)
            throws ReflectionException {
        Boolean ideSupport =
                layoutlibCallback.getFlag(RenderParamsFlags.FLAG_KEY_RECYCLER_VIEW_SUPPORT);
        if (ideSupport != Boolean.TRUE) {
            return null;
        }
        try {
            return layoutlibCallback.loadClass(CN_ADAPTER, new Class[0], new Object[0]);
        } catch (Exception e) {
            throw new ReflectionException(e);
        }
    }

    private static void setProperty(@NonNull Object object, @NonNull String propertyClassName,
      @NonNull Object propertyValue, @NonNull String propertySetter)
            throws ReflectionException {
        Class<?> propertyClass = getClassInstance(propertyValue, propertyClassName);
        setProperty(object, propertyClass, propertyValue, propertySetter);
    }

    private static void setProperty(@NonNull Object object, @NonNull Class<?> propertyClass,
            @Nullable Object propertyValue, @NonNull String propertySetter)
            throws ReflectionException {
        invoke(getMethod(object.getClass(), propertySetter, propertyClass), object, propertyValue);
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
