/*
 * Copyright (C) 2013 The Android Open Source Project
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
package com.android.keyguard;

import android.annotation.NonNull;
import android.app.Presentation;
import android.content.Context;
import android.hardware.devicestate.DeviceState;
import android.hardware.devicestate.DeviceStateManager;
import android.hardware.display.DisplayManager;
import android.media.MediaRouter;
import android.media.MediaRouter.RouteInfo;
import android.os.Trace;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseArray;
import android.view.Display;
import android.view.DisplayAddress;
import android.view.DisplayInfo;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.Nullable;

import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dagger.qualifiers.UiBackground;
import com.android.systemui.navigationbar.NavigationBarController;
import com.android.systemui.navigationbar.views.NavigationBarView;
import com.android.systemui.settings.DisplayTracker;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import dagger.Lazy;

import java.util.concurrent.Executor;

import javax.inject.Inject;

@SysUISingleton
public class KeyguardDisplayManager {
    protected static final String TAG = "KeyguardDisplayManager";
    private static final boolean DEBUG = KeyguardConstants.DEBUG;

    private MediaRouter mMediaRouter = null;
    private final DisplayManager mDisplayService;
    private final DisplayTracker mDisplayTracker;
    private final Lazy<NavigationBarController> mNavigationBarControllerLazy;
    private final ConnectedDisplayKeyguardPresentation.Factory
            mConnectedDisplayKeyguardPresentationFactory;
    private final Context mContext;

    private boolean mShowing;
    private final DisplayInfo mTmpDisplayInfo = new DisplayInfo();

    private final DeviceStateHelper mDeviceStateHelper;
    private final KeyguardStateController mKeyguardStateController;

    private final SparseArray<Presentation> mPresentations = new SparseArray<>();

    private final DisplayTracker.Callback mDisplayCallback =
            new DisplayTracker.Callback() {
                @Override
                public void onDisplayAdded(int displayId) {
                    Trace.beginSection(
                            "KeyguardDisplayManager#onDisplayAdded(displayId=" + displayId + ")");
                    final Display display = mDisplayService.getDisplay(displayId);
                    if (mShowing) {
                        updateNavigationBarVisibility(displayId, false /* navBarVisible */);
                        showPresentation(display);
                    }
                    Trace.endSection();
                }

                @Override
                public void onDisplayRemoved(int displayId) {
                    Trace.beginSection(
                            "KeyguardDisplayManager#onDisplayRemoved(displayId=" + displayId + ")");
                    hidePresentation(displayId);
                    Trace.endSection();
                }
            };

    @Inject
    public KeyguardDisplayManager(Context context,
            Lazy<NavigationBarController> navigationBarControllerLazy,
            DisplayTracker displayTracker,
            @Main Executor mainExecutor,
            @UiBackground Executor uiBgExecutor,
            DeviceStateHelper deviceStateHelper,
            KeyguardStateController keyguardStateController,
            ConnectedDisplayKeyguardPresentation.Factory
                    connectedDisplayKeyguardPresentationFactory) {
        mContext = context;
        mNavigationBarControllerLazy = navigationBarControllerLazy;
        uiBgExecutor.execute(() -> mMediaRouter = mContext.getSystemService(MediaRouter.class));
        mDisplayService = mContext.getSystemService(DisplayManager.class);
        mDisplayTracker = displayTracker;
        mDisplayTracker.addDisplayChangeCallback(mDisplayCallback, mainExecutor);
        mDeviceStateHelper = deviceStateHelper;
        mKeyguardStateController = keyguardStateController;
        mConnectedDisplayKeyguardPresentationFactory = connectedDisplayKeyguardPresentationFactory;
    }

    private boolean isKeyguardShowable(Display display) {
        if (display == null) {
            if (DEBUG) Log.i(TAG, "Cannot show Keyguard on null display");
            return false;
        }
        if (display.getDisplayId() == mDisplayTracker.getDefaultDisplayId()) {
            if (DEBUG) Log.i(TAG, "Do not show KeyguardPresentation on the default display");
            return false;
        }
        display.getDisplayInfo(mTmpDisplayInfo);
        if ((mTmpDisplayInfo.flags & Display.FLAG_PRIVATE) != 0) {
            if (DEBUG) Log.i(TAG, "Do not show KeyguardPresentation on a private display");
            return false;
        }
        if ((mTmpDisplayInfo.flags & Display.FLAG_ALWAYS_UNLOCKED) != 0) {
            if (DEBUG) {
                Log.i(TAG, "Do not show KeyguardPresentation on an unlocked display");
            }
            return false;
        }
        if (mKeyguardStateController.isOccluded()
                && mDeviceStateHelper.isConcurrentDisplayActive(display)) {
            if (DEBUG) {
                // When activities with FLAG_SHOW_WHEN_LOCKED are shown on top of Keyguard, the
                // Keyguard state becomes "occluded". In this case, we should not show the
                // KeyguardPresentation, since the activity is presenting content onto the
                // non-default display.
                Log.i(TAG, "Do not show KeyguardPresentation when occluded and concurrent"
                        + " display is active");
            }
            return false;
        }

        return true;
    }
    /**
     * @param display The display to show the presentation on.
     * @return {@code true} if a presentation was added.
     *         {@code false} if the presentation cannot be added on that display or the presentation
     *         was already there.
     */
    private boolean showPresentation(Display display) {
        if (!isKeyguardShowable(display)) return false;
        if (DEBUG) Log.i(TAG, "Keyguard enabled on display: " + display);
        final int displayId = display.getDisplayId();
        Presentation presentation = mPresentations.get(displayId);
        if (presentation == null) {
            final Presentation newPresentation = createPresentation(display);
            newPresentation.setOnDismissListener(dialog -> {
                if (newPresentation.equals(mPresentations.get(displayId))) {
                    mPresentations.remove(displayId);
                }
            });
            presentation = newPresentation;
            try {
                presentation.show();
            } catch (WindowManager.InvalidDisplayException ex) {
                Log.w(TAG, "Invalid display:", ex);
                presentation = null;
            }
            if (presentation != null) {
                mPresentations.append(displayId, presentation);
                return true;
            }
        }
        return false;
    }

    Presentation createPresentation(Display display) {
        return mConnectedDisplayKeyguardPresentationFactory.create(display);
    }

    /**
     * @param displayId The id of the display to hide the presentation off.
     */
    private void hidePresentation(int displayId) {
        final Presentation presentation = mPresentations.get(displayId);
        if (presentation != null) {
            presentation.dismiss();
            mPresentations.remove(displayId);
        }
    }

    public void show() {
        if (!mShowing) {
            if (DEBUG) Log.v(TAG, "show");
            if (mMediaRouter != null) {
                mMediaRouter.addCallback(MediaRouter.ROUTE_TYPE_REMOTE_DISPLAY,
                        mMediaRouterCallback, MediaRouter.CALLBACK_FLAG_PASSIVE_DISCOVERY);
            } else {
                Log.w(TAG, "MediaRouter not yet initialized");
            }
            updateDisplays(true /* showing */);
        }
        mShowing = true;
    }

    public void hide() {
        if (mShowing) {
            if (DEBUG) Log.v(TAG, "hide");
            if (mMediaRouter != null) {
                mMediaRouter.removeCallback(mMediaRouterCallback);
            }
            updateDisplays(false /* showing */);
        }
        mShowing = false;
    }

    private final MediaRouter.SimpleCallback mMediaRouterCallback =
            new MediaRouter.SimpleCallback() {
        @Override
        public void onRouteSelected(MediaRouter router, int type, RouteInfo info) {
            if (DEBUG) Log.d(TAG, "onRouteSelected: type=" + type + ", info=" + info);
            updateDisplays(mShowing);
        }

        @Override
        public void onRouteUnselected(MediaRouter router, int type, RouteInfo info) {
            if (DEBUG) Log.d(TAG, "onRouteUnselected: type=" + type + ", info=" + info);
            updateDisplays(mShowing);
        }

        @Override
        public void onRoutePresentationDisplayChanged(MediaRouter router, RouteInfo info) {
            if (DEBUG) Log.d(TAG, "onRoutePresentationDisplayChanged: info=" + info);
            updateDisplays(mShowing);
        }
    };

    protected boolean updateDisplays(boolean showing) {
        boolean changed = false;
        if (showing) {
            final Display[] displays = mDisplayTracker.getAllDisplays();
            for (Display display : displays) {
                int displayId = display.getDisplayId();
                updateNavigationBarVisibility(displayId, false /* navBarVisible */);
                changed |= showPresentation(display);
            }
        } else {
            changed = mPresentations.size() > 0;
            for (int i = mPresentations.size() - 1; i >= 0; i--) {
                int displayId = mPresentations.keyAt(i);
                updateNavigationBarVisibility(displayId, true /* navBarVisible */);
                mPresentations.valueAt(i).dismiss();
            }
            mPresentations.clear();
        }
        return changed;
    }

    // TODO(b/127878649): this logic is from
    //  {@link StatusBarKeyguardViewManager#updateNavigationBarVisibility}. Try to revisit a long
    //  term solution in R.
    private void updateNavigationBarVisibility(int displayId, boolean navBarVisible) {
        // Leave this task to {@link StatusBarKeyguardViewManager}
        if (displayId == mDisplayTracker.getDefaultDisplayId()) return;

        NavigationBarView navBarView = mNavigationBarControllerLazy.get()
                .getNavigationBarView(displayId);
        // We may not have nav bar on a display.
        if (navBarView == null) return;

        if (navBarVisible) {
            navBarView.getRootView().setVisibility(View.VISIBLE);
        } else {
            navBarView.getRootView().setVisibility(View.GONE);
        }

    }

    /**
     * Helper used to receive device state info from {@link DeviceStateManager}.
     */
    public static class DeviceStateHelper implements DeviceStateManager.DeviceStateCallback {

        @Nullable
        private final DisplayAddress.Physical mRearDisplayPhysicalAddress;

        // TODO(b/271317597): These device states should be defined in DeviceStateManager
        private final int mConcurrentState;
        private boolean mIsInConcurrentDisplayState;

        @Inject
        DeviceStateHelper(Context context,
                DeviceStateManager deviceStateManager,
                @Main Executor mainExecutor) {

            final String rearDisplayPhysicalAddress = context.getResources().getString(
                    com.android.internal.R.string.config_rearDisplayPhysicalAddress);
            if (TextUtils.isEmpty(rearDisplayPhysicalAddress)) {
                mRearDisplayPhysicalAddress = null;
            } else {
                mRearDisplayPhysicalAddress = DisplayAddress
                        .fromPhysicalDisplayId(Long.parseLong(rearDisplayPhysicalAddress));
            }

            mConcurrentState = context.getResources().getInteger(
                    com.android.internal.R.integer.config_deviceStateConcurrentRearDisplay);
            deviceStateManager.registerCallback(mainExecutor, this);
        }

        @Override
        public void onDeviceStateChanged(@NonNull DeviceState state) {
            // When concurrent state ends, the display also turns off. This is enforced in various
            // ExtensionRearDisplayPresentationTest CTS tests. So, we don't need to invoke
            // hide() since that will happen through the onDisplayRemoved callback.
            mIsInConcurrentDisplayState = state.getIdentifier() == mConcurrentState;
        }

        boolean isConcurrentDisplayActive(Display display) {
            return mIsInConcurrentDisplayState
                    && mRearDisplayPhysicalAddress != null
                    && mRearDisplayPhysicalAddress.equals(display.getAddress());
        }
    }
}
