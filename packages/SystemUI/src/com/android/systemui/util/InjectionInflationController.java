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

package com.android.systemui.util;

import android.content.Context;
import android.util.ArrayMap;
import android.util.AttributeSet;
import android.view.InflateException;
import android.view.LayoutInflater;
import android.view.View;

import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import javax.inject.Inject;
import javax.inject.Named;

import dagger.BindsInstance;
import dagger.Subcomponent;

/**
 * Manages inflation that requires dagger injection.
 * See docs/dagger.md for details.
 */
@SysUISingleton
public class InjectionInflationController {

    public static final String VIEW_CONTEXT = "view_context";
    private final ArrayMap<String, Method> mInjectionMap = new ArrayMap<>();
    private final LayoutInflater.Factory2 mFactory = new InjectionFactory();
    private final ViewInstanceCreator.Factory mViewInstanceCreatorFactory;

    @Inject
    public InjectionInflationController(ViewInstanceCreator.Factory viewInstanceCreatorFactory) {
        mViewInstanceCreatorFactory = viewInstanceCreatorFactory;
        initInjectionMap();
    }

    /**
     * Wraps a {@link LayoutInflater} to support creating dagger injected views.
     * See docs/dagger.md for details.
     */
    public LayoutInflater injectable(LayoutInflater inflater) {
        LayoutInflater ret = inflater.cloneInContext(inflater.getContext());
        ret.setPrivateFactory(mFactory);
        return ret;
    }

    private void initInjectionMap() {
        for (Method method : ViewInstanceCreator.class.getDeclaredMethods()) {
            if (View.class.isAssignableFrom(method.getReturnType())
                    && (method.getModifiers() & Modifier.PUBLIC) != 0) {
                mInjectionMap.put(method.getReturnType().getName(), method);
            }
        }
    }

    /**
     * Subcomponent that actually creates injected views.
     */
    @Subcomponent
    public interface ViewInstanceCreator {

        /** Factory for creating a ViewInstanceCreator. */
        @Subcomponent.Factory
        interface Factory {
            ViewInstanceCreator build(
                    @BindsInstance @Named(VIEW_CONTEXT) Context context,
                    @BindsInstance AttributeSet attributeSet);
        }

        /**
         * Creates the NotificationStackScrollLayout.
         */
        NotificationStackScrollLayout createNotificationStackScrollLayout();
    }


    private class InjectionFactory implements LayoutInflater.Factory2 {

        @Override
        public View onCreateView(String name, Context context, AttributeSet attrs) {
            Method creationMethod = mInjectionMap.get(name);
            if (creationMethod != null) {
                try {
                    return (View) creationMethod.invoke(
                            mViewInstanceCreatorFactory.build(context, attrs));
                } catch (IllegalAccessException e) {
                    throw new InflateException("Could not inflate " + name, e);
                } catch (InvocationTargetException e) {
                    throw new InflateException("Could not inflate " + name, e);
                }
            }
            return null;
        }

        @Override
        public View onCreateView(View parent, String name, Context context, AttributeSet attrs) {
            return onCreateView(name, context, attrs);
        }
    }
}
