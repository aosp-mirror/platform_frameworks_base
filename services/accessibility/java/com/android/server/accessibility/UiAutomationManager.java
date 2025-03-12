/*
 ** Copyright 2017, The Android Open Source Project
 **
 ** Licensed under the Apache License, Version 2.0 (the "License");
 ** you may not use this file except in compliance with the License.
 ** You may obtain a copy of the License at
 **
 **     http://www.apache.org/licenses/LICENSE-2.0
 **
 ** Unless required by applicable law or agreed to in writing, software
 ** distributed under the License is distributed on an "AS IS" BASIS,
 ** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 ** See the License for the specific language governing permissions and
 ** limitations under the License.
 */

package com.android.server.accessibility;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.AccessibilityTrace;
import android.accessibilityservice.IAccessibilityServiceClient;
import android.annotation.Nullable;
import android.annotation.PermissionManuallyEnforced;
import android.annotation.RequiresNoPermission;
import android.app.UiAutomation;
import android.content.ComponentName;
import android.content.Context;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.IBinder.DeathRecipient;
import android.os.Looper;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.util.Slog;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;

import com.android.internal.util.DumpUtils;
import com.android.internal.util.Preconditions;
import com.android.server.utils.Slogf;
import com.android.server.wm.WindowManagerInternal;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * Class to manage UiAutomation.
 */
class UiAutomationManager {
    private static final ComponentName COMPONENT_NAME =
            new ComponentName("com.android.server.accessibility", "UiAutomation");
    private static final String LOG_TAG = "UiAutomationManager";

    private final Object mLock;

    private UiAutomationService mUiAutomationService;

    private AbstractAccessibilityServiceConnection.SystemSupport mSystemSupport;

    private int mUiAutomationFlags;

    UiAutomationManager(Object lock) {
        mLock = lock;
    }

    private IBinder mUiAutomationServiceOwner;
    private final DeathRecipient mUiAutomationServiceOwnerDeathRecipient =
            new DeathRecipient() {
                @Override
                public void binderDied() {
                    mUiAutomationServiceOwner.unlinkToDeath(this, 0);
                    mUiAutomationServiceOwner = null;
                    destroyUiAutomationService();
                    Slog.v(LOG_TAG, "UiAutomation service owner died");
                }
            };

    /**
     * Register a UiAutomation if it uses the accessibility subsystem. Only one may be registered
     * at a time.
     *
     * @param owner A binder object owned by the process that owns the UiAutomation to be
     *              registered.
     * @param serviceClient The UiAutomation's service interface.
     * @param accessibilityServiceInfo The UiAutomation's service info
     * @param flags The UiAutomation's flags
     * @param id The id for the service connection
     * @see UiAutomation#FLAG_DONT_USE_ACCESSIBILITY
     */
    void registerUiTestAutomationServiceLocked(IBinder owner,
            IAccessibilityServiceClient serviceClient,
            Context context, AccessibilityServiceInfo accessibilityServiceInfo,
            int id, Handler mainHandler,
            AccessibilitySecurityPolicy securityPolicy,
            AbstractAccessibilityServiceConnection.SystemSupport systemSupport,
            AccessibilityTrace trace,
            WindowManagerInternal windowManagerInternal,
            SystemActionPerformer systemActionPerformer,
            AccessibilityWindowManager awm, int flags) {
        accessibilityServiceInfo.setComponentName(COMPONENT_NAME);
        Slogf.i(LOG_TAG, "Registering UiTestAutomationService (id=%s, flags=0x%x) when"
                        + " called by user %d",
                accessibilityServiceInfo.getId(), flags,
                Binder.getCallingUserHandle().getIdentifier());
        if (mUiAutomationService != null) {
            throw new IllegalStateException(
                    "UiAutomationService " + mUiAutomationService.mClient + "already registered!");
        }

        try {
            owner.linkToDeath(mUiAutomationServiceOwnerDeathRecipient, 0);
        } catch (RemoteException re) {
            Slog.e(LOG_TAG, "Couldn't register for the death of a UiTestAutomationService!",
                    re);
            return;
        }

        mUiAutomationFlags = flags;
        mSystemSupport = systemSupport;
        // Ignore registering UiAutomation if it is not allowed to use the accessibility
        // subsystem.
        if (!useAccessibility()) {
            return;
        }
        mUiAutomationService = new UiAutomationService(context, accessibilityServiceInfo, id,
                mainHandler, mLock, securityPolicy, systemSupport, trace, windowManagerInternal,
                systemActionPerformer, awm);
        mUiAutomationServiceOwner = owner;
        mUiAutomationService.mClient = serviceClient;
        try {
            mUiAutomationService.mClient.asBinder().linkToDeath(mUiAutomationService, 0);
        } catch (RemoteException re) {
            Slog.e(LOG_TAG, "Failed registering death link: " + re);
            destroyUiAutomationService();
            return;
        }

        // UiAutomationService#connectServiceUnknownThread posts to a handler
        // so this call should return immediately.
        mUiAutomationService.connectServiceUnknownThread();
    }

    void unregisterUiTestAutomationServiceLocked(IAccessibilityServiceClient serviceClient) {
        synchronized (mLock) {
            if (useAccessibility()
                    && ((mUiAutomationService == null)
                            || (serviceClient == null)
                            || (mUiAutomationService.mClient == null)
                            || (serviceClient.asBinder()
                                    != mUiAutomationService.mClient.asBinder()))) {
                throw new IllegalStateException("UiAutomationService " + serviceClient
                        + " not registered!");
            }
            destroyUiAutomationService();
        }
    }

    void sendAccessibilityEventLocked(AccessibilityEvent event) {
        if (mUiAutomationService != null) {
            mUiAutomationService.notifyAccessibilityEvent(event);
        }
    }

    boolean isUiAutomationRunningLocked() {
        return (mUiAutomationService != null || !useAccessibility());
    }

    boolean suppressingAccessibilityServicesLocked() {
        return (mUiAutomationService != null || !useAccessibility())
                && ((mUiAutomationFlags
                & UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES) == 0);
    }

    boolean useAccessibility() {
        return ((mUiAutomationFlags & UiAutomation.FLAG_DONT_USE_ACCESSIBILITY) == 0);
    }

    boolean canIntrospect() {
        return mUiAutomationService != null;
    }

    boolean isTouchExplorationEnabledLocked() {
        return (mUiAutomationService != null)
                && mUiAutomationService.mRequestTouchExplorationMode;
    }

    boolean canRetrieveInteractiveWindowsLocked() {
        return (mUiAutomationService != null) && mUiAutomationService.mRetrieveInteractiveWindows;
    }

    int getRequestedEventMaskLocked() {
        if (mUiAutomationService == null) return 0;
        return mUiAutomationService.mEventTypes;
    }

    int getRelevantEventTypes() {
        UiAutomationService uiAutomationService;
        synchronized (mLock) {
            uiAutomationService = mUiAutomationService;
        }
        if (uiAutomationService == null) return 0;
        return uiAutomationService.getRelevantEventTypes();
    }

    @Nullable
    AccessibilityServiceInfo getServiceInfo() {
        UiAutomationService uiAutomationService;
        synchronized (mLock) {
            uiAutomationService = mUiAutomationService;
        }
        if (uiAutomationService == null) return null;
        return uiAutomationService.getServiceInfo();
    }

    void dumpUiAutomationService(FileDescriptor fd, final PrintWriter pw, String[] args) {
        UiAutomationService uiAutomationService;
        synchronized (mLock) {
            uiAutomationService = mUiAutomationService;
        }
        if (uiAutomationService != null) {
            uiAutomationService.dump(fd, pw, args);
        }
    }

    private void destroyUiAutomationService() {
        synchronized (mLock) {
            if (mUiAutomationService != null) {
                mUiAutomationService.mClient.asBinder().unlinkToDeath(mUiAutomationService, 0);
                mUiAutomationService.onRemoved();
                mUiAutomationService.resetLocked();
                mUiAutomationService = null;
                if (mUiAutomationServiceOwner != null) {
                    mUiAutomationServiceOwner.unlinkToDeath(
                            mUiAutomationServiceOwnerDeathRecipient, 0);
                    mUiAutomationServiceOwner = null;
                }
            }
            mUiAutomationFlags = 0;
            mSystemSupport.onClientChangeLocked(false);
        }
    }

    private class UiAutomationService extends AbstractAccessibilityServiceConnection {
        private final Handler mMainHandler;

        UiAutomationService(Context context, AccessibilityServiceInfo accessibilityServiceInfo,
                int id, Handler mainHandler, Object lock,
                AccessibilitySecurityPolicy securityPolicy,
                SystemSupport systemSupport, AccessibilityTrace trace,
                WindowManagerInternal windowManagerInternal,
                SystemActionPerformer systemActionPerformer, AccessibilityWindowManager awm) {
            super(context, COMPONENT_NAME, accessibilityServiceInfo, id, mainHandler, lock,
                    securityPolicy, systemSupport, trace, windowManagerInternal,
                    systemActionPerformer, awm);
            final boolean isMainHandler = mainHandler.getLooper() == Looper.getMainLooper();
            final String errorMessage = "UiAutomationService must use the main handler";
            if (Build.IS_USERDEBUG || Build.IS_ENG) {
                Preconditions.checkArgument(isMainHandler, errorMessage);
            } else if (!isMainHandler) {
                Slog.e(LOG_TAG, errorMessage);
            }
            mMainHandler = mainHandler;
            setDisplayTypes(DISPLAY_TYPE_DEFAULT | DISPLAY_TYPE_PROXY);
        }

        void connectServiceUnknownThread() {
            // This needs to be done on the main thread
            mMainHandler.post(
                    () -> {
                        try {
                            final IAccessibilityServiceClient client;
                            final UiAutomationService uiAutomationService;
                            synchronized (mLock) {
                                client = mClient;
                                uiAutomationService = mUiAutomationService;
                                if (client == null) {
                                    mClientBinder = null;
                                } else {
                                    mClientBinder = mClient.asBinder();
                                    mClientBinder.linkToDeath(this, 0);
                                }
                            }
                            // If the client is null, the UiAutomation has been shut down on
                            // another thread.
                            if (client != null && uiAutomationService != null) {
                                uiAutomationService.addWindowTokensForAllDisplays();
                                if (mTrace.isA11yTracingEnabledForTypes(
                                        AccessibilityTrace.FLAGS_ACCESSIBILITY_SERVICE_CLIENT)) {
                                    mTrace.logTrace(
                                            "UiAutomationService.connectServiceUnknownThread",
                                            AccessibilityTrace.FLAGS_ACCESSIBILITY_SERVICE_CLIENT,
                                            "serviceConnection="
                                                    + this
                                                    + ";connectionId="
                                                    + mId
                                                    + "windowToken="
                                                    + mOverlayWindowTokens.get(
                                                            Display.DEFAULT_DISPLAY));
                                }
                                client.init(
                                        this,
                                        mId,
                                        mOverlayWindowTokens.get(Display.DEFAULT_DISPLAY));
                            }
                        } catch (RemoteException re) {
                            Slog.w(LOG_TAG, "Error initializing connection", re);
                            destroyUiAutomationService();
                        }
                    });
        }

        @Override
        public void binderDied() {
            destroyUiAutomationService();
        }

        @Override
        protected boolean hasRightsToCurrentUserLocked() {
            // Allow UiAutomation to work for any user
            return true;
        }

        @Override
        protected boolean supportsFlagForNotImportantViews(AccessibilityServiceInfo info) {
            return true;
        }

        @PermissionManuallyEnforced
        @Override
        public void dump(FileDescriptor fd, final PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpPermission(mContext, LOG_TAG, pw)) return;
            synchronized (mLock) {
                pw.append("Ui Automation[eventTypes="
                        + AccessibilityEvent.eventTypeToString(mEventTypes));
                pw.append(", notificationTimeout=" + mNotificationTimeout);
                pw.append("]");
            }
        }

        // Since this isn't really an accessibility service, several methods are just stubbed here.
        @RequiresNoPermission
        @Override
        public boolean setSoftKeyboardShowMode(int mode) {
            return false;
        }

        @RequiresNoPermission
        @Override
        public int getSoftKeyboardShowMode() {
            return 0;
        }

        @RequiresNoPermission
        @Override
        public boolean switchToInputMethod(String imeId) {
            return false;
        }

        @RequiresNoPermission
        @Override
        public int setInputMethodEnabled(String imeId, boolean enabled) {
            return AccessibilityService.SoftKeyboardController.ENABLE_IME_FAIL_UNKNOWN;
        }

        @RequiresNoPermission
        @Override
        public boolean isAccessibilityButtonAvailable() {
            return false;
        }

        @RequiresNoPermission
        @Override
        public void disableSelf() {}

        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service) {}

        @Override
        public void onServiceDisconnected(ComponentName componentName) {}

        @Override
        public boolean isCapturingFingerprintGestures() {
            return false;
        }

        @Override
        public void onFingerprintGestureDetectionActiveChanged(boolean active) {}

        @Override
        public void onFingerprintGesture(int gesture) {}

        @RequiresNoPermission
        @Override
        public void takeScreenshot(int displayId, RemoteCallback callback) {}
    }
}
