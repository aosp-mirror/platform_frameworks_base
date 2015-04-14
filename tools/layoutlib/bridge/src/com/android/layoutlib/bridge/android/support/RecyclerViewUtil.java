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

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.ide.common.rendering.api.LayoutLog;
import com.android.ide.common.rendering.api.LayoutlibCallback;
import com.android.ide.common.rendering.api.SessionParams;
import com.android.layoutlib.bridge.Bridge;
import com.android.layoutlib.bridge.android.BridgeContext;
import com.android.layoutlib.bridge.android.SessionParamsFlags;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;

import java.lang.reflect.Method;

import static com.android.layoutlib.bridge.util.ReflectionUtils.*;

/**
 * Utility class for working with android.support.v7.widget.RecyclerView
 */
@SuppressWarnings("SpellCheckingInspection")  // for "recycler".
public class RecyclerViewUtil {

    /**
     * Used by {@link LayoutManagerType}.
     * <p/>
     * Not declared inside the enum, since it needs to be accessible in the constructor.
     */
    private static final Object CONTEXT = new Object();

    public static final String CN_RECYCLER_VIEW = "android.support.v7.widget.RecyclerView";
    private static final String CN_LAYOUT_MANAGER = CN_RECYCLER_VIEW + "$LayoutManager";
    private static final String CN_ADAPTER = CN_RECYCLER_VIEW + "$Adapter";

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
        Object cookie = context.getCookie(recyclerView);
        assert cookie == null || cookie instanceof LayoutManagerType || cookie instanceof String;
        if (!(cookie instanceof LayoutManagerType)) {
            if (cookie != null) {
                // TODO: When layoutlib API is updated, try to load the class with a null
                // constructor or a constructor taking one argument - the context.
                Bridge.getLog().warning(LayoutLog.TAG_UNSUPPORTED,
                        "LayoutManager (" + cookie + ") not found, falling back to " +
                                "LinearLayoutManager", null);
            }
            cookie = LayoutManagerType.getDefault();
        }
        Object layoutManager = createLayoutManager((LayoutManagerType) cookie, context, callback);
        setProperty(recyclerView, CN_LAYOUT_MANAGER, layoutManager, "setLayoutManager");
    }

    @Nullable
    private static Object createLayoutManager(@Nullable LayoutManagerType type,
            @NonNull Context context, @NonNull LayoutlibCallback callback)
            throws ReflectionException {
        if (type == null) {
            type = LayoutManagerType.getDefault();
        }
        try {
            return callback.loadView(type.getClassName(), type.getSignature(), type.getArgs(context));
        } catch (Exception e) {
            throw new ReflectionException(e);
        }
    }

    @Nullable
    private static Object createAdapter(@NonNull SessionParams params) throws ReflectionException {
        Boolean ideSupport = params.getFlag(SessionParamsFlags.FLAG_KEY_RECYCLER_VIEW_SUPPORT);
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

    /** Supported LayoutManagers. */
    public enum LayoutManagerType {
        LINEAR_LAYOUT_MANGER("Linear",
                "android.support.v7.widget.LinearLayoutManager",
                new Class[]{Context.class}, new Object[]{CONTEXT}),
        GRID_LAYOUT_MANAGER("Grid",
                "android.support.v7.widget.GridLayoutManager",
                new Class[]{Context.class, int.class}, new Object[]{CONTEXT, 2}),
        STAGGERED_GRID_LAYOUT_MANAGER("StaggeredGrid",
                "android.support.v7.widget.StaggeredGridLayoutManager",
                new Class[]{int.class, int.class}, new Object[]{2, LinearLayout.VERTICAL});

        private String mLogicalName;
        private String mClassName;
        private Class[] mSignature;
        private Object[] mArgs;

        LayoutManagerType(String logicalName, String className, Class[] signature, Object[] args) {
            mLogicalName = logicalName;
            mClassName = className;
            mSignature = signature;
            mArgs = args;
        }

        String getClassName() {
            return mClassName;
        }

        Class[] getSignature() {
            return mSignature;
        }

        @NonNull
        Object[] getArgs(Context context) {
            Object[] args = new Object[mArgs.length];
            System.arraycopy(mArgs, 0, args, 0, mArgs.length);
            for (int i = 0; i < args.length; i++) {
                if (args[i] == CONTEXT) {
                    args[i] = context;
                }
            }
            return args;
        }

        @NonNull
        public static LayoutManagerType getDefault() {
            return LINEAR_LAYOUT_MANGER;
        }

        @Nullable
        public static LayoutManagerType getByLogicalName(@NonNull String logicalName) {
            for (LayoutManagerType type : values()) {
                if (logicalName.equals(type.mLogicalName)) {
                    return type;
                }
            }
            return null;
        }

        @Nullable
        public static LayoutManagerType getByClassName(@NonNull String className) {
            for (LayoutManagerType type : values()) {
                if (className.equals(type.mClassName)) {
                    return type;
                }
            }
            return null;
        }
    }
}
