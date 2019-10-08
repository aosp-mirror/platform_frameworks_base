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

import com.android.keyguard.KeyguardClockSwitch;
import com.android.keyguard.KeyguardMessageArea;
import com.android.keyguard.KeyguardSliceView;
import com.android.systemui.SystemUIFactory;
import com.android.systemui.qs.QSCarrierGroup;
import com.android.systemui.qs.QSFooterImpl;
import com.android.systemui.qs.QSPanel;
import com.android.systemui.qs.QuickQSPanel;
import com.android.systemui.qs.QuickStatusBarHeader;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout;
import com.android.systemui.statusbar.phone.LockIcon;
import com.android.systemui.statusbar.phone.NotificationPanelView;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;
import dagger.Subcomponent;

/**
 * Manages inflation that requires dagger injection.
 * See docs/dagger.md for details.
 */
@Singleton
public class InjectionInflationController {

    public static final String VIEW_CONTEXT = "view_context";
    private final ViewCreator mViewCreator;
    private final ArrayMap<String, Method> mInjectionMap = new ArrayMap<>();
    private final LayoutInflater.Factory2 mFactory = new InjectionFactory();

    @Inject
    public InjectionInflationController(SystemUIFactory.SystemUIRootComponent rootComponent) {
        mViewCreator = rootComponent.createViewCreator();
        initInjectionMap();
    }

    ArrayMap<String, Method> getInjectionMap() {
        return mInjectionMap;
    }

    ViewCreator getFragmentCreator() {
        return mViewCreator;
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
     * The subcomponent of dagger that holds all views that need injection.
     */
    @Subcomponent
    public interface ViewCreator {
        /**
         * Creates another subcomponent to actually generate the view.
         */
        ViewInstanceCreator createInstanceCreator(ViewAttributeProvider attributeProvider);
    }

    /**
     * Secondary sub-component that actually creates the views.
     *
     * Having two subcomponents lets us hide the complexity of providing the named context
     * and AttributeSet from the SystemUIRootComponent, instead we have one subcomponent that
     * creates a new ViewInstanceCreator any time we need to inflate a view.
     */
    @Subcomponent(modules = ViewAttributeProvider.class)
    public interface ViewInstanceCreator {
        /**
         * Creates the QuickStatusBarHeader.
         */
        QuickStatusBarHeader createQsHeader();
        /**
         * Creates the QSFooterImpl.
         */
        QSFooterImpl createQsFooter();

        /**
         * Creates the NotificationStackScrollLayout.
         */
        NotificationStackScrollLayout createNotificationStackScrollLayout();

        /**
         * Creates the NotificationPanelView.
         */
        NotificationPanelView createPanelView();

        /**
         * Creates the QSCarrierGroup
         */
        QSCarrierGroup createQSCarrierGroup();

        /**
         * Creates the KeyguardClockSwitch.
         */
        KeyguardClockSwitch createKeyguardClockSwitch();

        /**
         * Creates the KeyguardSliceView.
         */
        KeyguardSliceView createKeyguardSliceView();

        /**
         * Creates the KeyguardMessageArea.
         */
        KeyguardMessageArea createKeyguardMessageArea();

        /**
         * Creates the keyguard LockIcon.
         */
        LockIcon createLockIcon();

        /**
         * Creates the QSPanel.
         */
        QSPanel createQSPanel();

        /**
         * Creates the QuickQSPanel.
         */
        QuickQSPanel createQuickQSPanel();
    }

    /**
     * Module for providing view-specific constructor objects.
     */
    @Module
    public class ViewAttributeProvider {
        private final Context mContext;
        private final AttributeSet mAttrs;

        private ViewAttributeProvider(Context context, AttributeSet attrs) {
            mContext = context;
            mAttrs = attrs;
        }

        /**
         * Provides the view-themed context (as opposed to the global sysui application context).
         */
        @Provides
        @Named(VIEW_CONTEXT)
        public Context provideContext() {
            return mContext;
        }

        /**
         * Provides the AttributeSet for the current view being inflated.
         */
        @Provides
        public AttributeSet provideAttributeSet() {
            return mAttrs;
        }
    }

    private class InjectionFactory implements LayoutInflater.Factory2 {

        @Override
        public View onCreateView(String name, Context context, AttributeSet attrs) {
            Method creationMethod = mInjectionMap.get(name);
            if (creationMethod != null) {
                ViewAttributeProvider provider = new ViewAttributeProvider(context, attrs);
                try {
                    return (View) creationMethod.invoke(
                            mViewCreator.createInstanceCreator(provider));
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
