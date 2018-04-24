/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui;

import android.app.AlarmManager;
import android.content.Context;
import android.util.ArrayMap;
import android.util.Log;
import android.view.ViewGroup;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.util.function.TriConsumer;
import com.android.internal.widget.LockPatternUtils;
import com.android.internal.colorextraction.ColorExtractor.GradientColors;
import com.android.keyguard.ViewMediatorCallback;
import com.android.systemui.Dependency.DependencyProvider;
import com.android.systemui.classifier.FalsingManager;
import com.android.systemui.keyguard.DismissCallbackRegistry;
import com.android.systemui.qs.QSTileHost;
import com.android.systemui.statusbar.KeyguardIndicationController;
import com.android.systemui.statusbar.NotificationBlockingHelperManager;
import com.android.systemui.statusbar.NotificationEntryManager;
import com.android.systemui.statusbar.NotificationGutsManager;
import com.android.systemui.statusbar.NotificationListener;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationLogger;
import com.android.systemui.statusbar.NotificationMediaManager;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.NotificationViewHierarchyManager;
import com.android.systemui.statusbar.ScrimView;
import com.android.systemui.statusbar.SmartReplyController;
import com.android.systemui.statusbar.notification.VisualStabilityManager;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.statusbar.phone.KeyguardBouncer;
import com.android.systemui.statusbar.phone.KeyguardDismissUtil;
import com.android.systemui.statusbar.phone.LockIcon;
import com.android.systemui.statusbar.phone.LockscreenWallpaper;
import com.android.systemui.statusbar.phone.NotificationGroupManager;
import com.android.systemui.statusbar.phone.NotificationIconAreaController;
import com.android.systemui.statusbar.phone.ScrimController;
import com.android.systemui.statusbar.phone.ScrimState;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.statusbar.phone.StatusBarIconController;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.systemui.statusbar.policy.SmartReplyConstants;

import java.util.function.Consumer;

/**
 * Class factory to provide customizable SystemUI components.
 */
public class SystemUIFactory {
    private static final String TAG = "SystemUIFactory";

    static SystemUIFactory mFactory;

    public static SystemUIFactory getInstance() {
        return mFactory;
    }

    public static void createFromConfig(Context context) {
        final String clsName = context.getString(R.string.config_systemUIFactoryComponent);
        if (clsName == null || clsName.length() == 0) {
            throw new RuntimeException("No SystemUIFactory component configured");
        }

        try {
            Class<?> cls = null;
            cls = context.getClassLoader().loadClass(clsName);
            mFactory = (SystemUIFactory) cls.newInstance();
        } catch (Throwable t) {
            Log.w(TAG, "Error creating SystemUIFactory component: " + clsName, t);
            throw new RuntimeException(t);
        }
    }

    public SystemUIFactory() {}

    public StatusBarKeyguardViewManager createStatusBarKeyguardViewManager(Context context,
            ViewMediatorCallback viewMediatorCallback, LockPatternUtils lockPatternUtils) {
        return new StatusBarKeyguardViewManager(context, viewMediatorCallback, lockPatternUtils);
    }

    public KeyguardBouncer createKeyguardBouncer(Context context, ViewMediatorCallback callback,
            LockPatternUtils lockPatternUtils,  ViewGroup container,
            DismissCallbackRegistry dismissCallbackRegistry,
            KeyguardBouncer.BouncerExpansionCallback expansionCallback) {
        return new KeyguardBouncer(context, callback, lockPatternUtils, container,
                dismissCallbackRegistry, FalsingManager.getInstance(context), expansionCallback);
    }

    public ScrimController createScrimController(ScrimView scrimBehind, ScrimView scrimInFront,
            LockscreenWallpaper lockscreenWallpaper,
            TriConsumer<ScrimState, Float, GradientColors> scrimStateListener,
            Consumer<Integer> scrimVisibleListener, DozeParameters dozeParameters,
            AlarmManager alarmManager) {
        return new ScrimController(scrimBehind, scrimInFront, scrimStateListener,
                scrimVisibleListener, dozeParameters, alarmManager);
    }

    public NotificationIconAreaController createNotificationIconAreaController(Context context,
            StatusBar statusBar) {
        return new NotificationIconAreaController(context, statusBar);
    }

    public KeyguardIndicationController createKeyguardIndicationController(Context context,
            ViewGroup indicationArea, LockIcon lockIcon) {
        return new KeyguardIndicationController(context, indicationArea, lockIcon);
    }

    public QSTileHost createQSTileHost(Context context, StatusBar statusBar,
            StatusBarIconController iconController) {
        return new QSTileHost(context, statusBar, iconController);
    }

    public void injectDependencies(ArrayMap<Object, DependencyProvider> providers,
            Context context) {
        providers.put(NotificationLockscreenUserManager.class,
                () -> new NotificationLockscreenUserManager(context));
        providers.put(VisualStabilityManager.class, VisualStabilityManager::new);
        providers.put(NotificationGroupManager.class, NotificationGroupManager::new);
        providers.put(NotificationMediaManager.class, () -> new NotificationMediaManager(context));
        providers.put(NotificationGutsManager.class, () -> new NotificationGutsManager(context));
        providers.put(NotificationBlockingHelperManager.class,
                () -> new NotificationBlockingHelperManager(context));
        providers.put(NotificationRemoteInputManager.class,
                () -> new NotificationRemoteInputManager(context));
        providers.put(SmartReplyConstants.class,
                () -> new SmartReplyConstants(Dependency.get(Dependency.MAIN_HANDLER), context));
        providers.put(NotificationListener.class, () -> new NotificationListener(context));
        providers.put(NotificationLogger.class, NotificationLogger::new);
        providers.put(NotificationViewHierarchyManager.class,
                () -> new NotificationViewHierarchyManager(context));
        providers.put(NotificationEntryManager.class, () -> new NotificationEntryManager(context));
        providers.put(KeyguardDismissUtil.class, KeyguardDismissUtil::new);
        providers.put(SmartReplyController.class, () -> new SmartReplyController());
    }
}
