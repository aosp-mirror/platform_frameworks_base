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
 * limitations under the License.
 */
package com.android.server.pm;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.IPinItemRequest;
import android.content.pm.LauncherApps;
import android.content.pm.LauncherApps.PinItemRequest;
import android.content.pm.ShortcutInfo;
import android.os.Bundle;
import android.os.UserHandle;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;

import java.util.Collections;
import java.util.List;

/**
 * Handles {@link android.content.pm.ShortcutManager#requestPinShortcut} related tasks.
 */
class ShortcutRequestPinProcessor {
    private static final String TAG = ShortcutService.TAG;
    private static final boolean DEBUG = ShortcutService.DEBUG;

    private final ShortcutService mService;
    private final Object mLock;

    /**
     * Internal for {@link android.content.pm.LauncherApps.PinItemRequest} which receives callbacks.
     */
    private abstract static class PinItemRequestInner extends IPinItemRequest.Stub {
        protected final ShortcutRequestPinProcessor mProcessor;
        private final IntentSender mResultIntent;
        private final int mLauncherUid;

        @GuardedBy("this")
        private boolean mAccepted;

        private PinItemRequestInner(ShortcutRequestPinProcessor processor,
                IntentSender resultIntent, int launcherUid) {
            mProcessor = processor;
            mResultIntent = resultIntent;
            mLauncherUid = launcherUid;
        }

        @Override
        public ShortcutInfo getShortcutInfo() {
            return null;
        }

        @Override
        public AppWidgetProviderInfo getAppWidgetProviderInfo() {
            return null;
        }

        @Override
        public Bundle getExtras() {
            return null;
        }

        /**
         * Returns true if the caller is same as the default launcher app when this request
         * object was created.
         */
        private boolean isCallerValid() {
            return mProcessor.isCallerUid(mLauncherUid);
        }

        @Override
        public boolean isValid() {
            if (!isCallerValid()) {
                return false;
            }
            // TODO When an app calls requestPinShortcut(), all pending requests should be
            // invalidated.
            synchronized (this) {
                return !mAccepted;
            }
        }

        /**
         * Called when the launcher calls {@link PinItemRequest#accept}.
         */
        @Override
        public boolean accept(Bundle options) {
            // Make sure the options are unparcellable by the FW. (e.g. not containing unknown
            // classes.)
            if (!isCallerValid()) {
                throw new SecurityException("Calling uid mismatch");
            }
            Intent extras = null;
            if (options != null) {
                try {
                    options.size();
                    extras = new Intent().putExtras(options);
                } catch (RuntimeException e) {
                    throw new IllegalArgumentException("options cannot be unparceled", e);
                }
            }
            synchronized (this) {
                if (mAccepted) {
                    throw new IllegalStateException("accept() called already");
                }
                mAccepted = true;
            }

            // Pin it and send the result intent.
            if (tryAccept()) {
                mProcessor.sendResultIntent(mResultIntent, extras);
                return true;
            } else {
                return false;
            }
        }

        protected boolean tryAccept() {
            return true;
        }
    }

    /**
     * Internal for {@link android.content.pm.LauncherApps.PinItemRequest} which receives callbacks.
     */
    private static class PinAppWidgetRequestInner extends PinItemRequestInner {
        final AppWidgetProviderInfo mAppWidgetProviderInfo;
        final Bundle mExtras;

        private PinAppWidgetRequestInner(ShortcutRequestPinProcessor processor,
                IntentSender resultIntent, int launcherUid,
                AppWidgetProviderInfo appWidgetProviderInfo, Bundle extras) {
            super(processor, resultIntent, launcherUid);

            mAppWidgetProviderInfo = appWidgetProviderInfo;
            mExtras = extras;
        }

        @Override
        public AppWidgetProviderInfo getAppWidgetProviderInfo() {
            return mAppWidgetProviderInfo;
        }

        @Override
        public Bundle getExtras() {
            return mExtras;
        }
    }

    /**
     * Internal for {@link android.content.pm.LauncherApps.PinItemRequest} which receives callbacks.
     */
    private static class PinShortcutRequestInner extends PinItemRequestInner {
        /** Original shortcut passed by the app. */
        public final ShortcutInfo shortcutOriginal;

        /**
         * Cloned shortcut that's passed to the launcher.  The notable difference from
         * {@link #shortcutOriginal} is it must not have the intent.
         */
        public final ShortcutInfo shortcutForLauncher;

        public final String launcherPackage;
        public final int launcherUserId;
        public final boolean preExisting;

        private PinShortcutRequestInner(ShortcutRequestPinProcessor processor,
                ShortcutInfo shortcutOriginal, ShortcutInfo shortcutForLauncher,
                IntentSender resultIntent,
                String launcherPackage, int launcherUserId, int launcherUid, boolean preExisting) {
            super(processor, resultIntent, launcherUid);
            this.shortcutOriginal = shortcutOriginal;
            this.shortcutForLauncher = shortcutForLauncher;
            this.launcherPackage = launcherPackage;
            this.launcherUserId = launcherUserId;
            this.preExisting = preExisting;
        }

        @Override
        public ShortcutInfo getShortcutInfo() {
            return shortcutForLauncher;
        }

        @Override
        protected boolean tryAccept() {
            if (DEBUG) {
                Slog.d(TAG, "Launcher accepted shortcut. ID=" + shortcutOriginal.getId()
                    + " package=" + shortcutOriginal.getPackage());
            }
            return mProcessor.directPinShortcut(this);
        }
    }

    public ShortcutRequestPinProcessor(ShortcutService service, Object lock) {
        mService = service;
        mLock = lock;
    }

    public boolean isRequestPinItemSupported(int callingUserId, int requestType) {
        return getRequestPinConfirmationActivity(callingUserId, requestType) != null;
    }

    /**
     * Handle {@link android.content.pm.ShortcutManager#requestPinShortcut)} and
     * {@link android.appwidget.AppWidgetManager#requestPinAppWidget}.
     * In this flow the PinItemRequest is delivered directly to the default launcher app.
     * One of {@param inShortcut} and {@param inAppWidget} is always non-null and the other is
     * always null.
     */
    public boolean requestPinItemLocked(ShortcutInfo inShortcut, AppWidgetProviderInfo inAppWidget,
        Bundle extras, int userId, IntentSender resultIntent) {

        // First, make sure the launcher supports it.

        // Find the confirmation activity in the default launcher.
        final int requestType = inShortcut != null ?
                PinItemRequest.REQUEST_TYPE_SHORTCUT : PinItemRequest.REQUEST_TYPE_APPWIDGET;
        final Pair<ComponentName, Integer> confirmActivity =
                getRequestPinConfirmationActivity(userId, requestType);

        // If the launcher doesn't support it, just return a rejected result and finish.
        if (confirmActivity == null) {
            Log.w(TAG, "Launcher doesn't support requestPinnedShortcut(). Shortcut not created.");
            return false;
        }

        final int launcherUserId = confirmActivity.second;

        // Make sure the launcher user is unlocked. (it's always the parent profile, so should
        // really be unlocked here though.)
        mService.throwIfUserLockedL(launcherUserId);

        // Next, validate the incoming shortcut, etc.
        final PinItemRequest request;
        if (inShortcut != null) {
            request = requestPinShortcutLocked(inShortcut, resultIntent, confirmActivity);
        } else {
            int launcherUid = mService.injectGetPackageUid(
                    confirmActivity.first.getPackageName(), launcherUserId);
            request = new PinItemRequest(
                    new PinAppWidgetRequestInner(this, resultIntent, launcherUid, inAppWidget,
                            extras),
                    PinItemRequest.REQUEST_TYPE_APPWIDGET);
        }
        return startRequestConfirmActivity(confirmActivity.first, launcherUserId, request,
                requestType);
    }

    /**
     * Handle {@link android.content.pm.ShortcutManager#createShortcutResultIntent(ShortcutInfo)}.
     * In this flow the PinItemRequest is delivered to the caller app. Its the app's responsibility
     * to send it to the Launcher app (via {@link android.app.Activity#setResult(int, Intent)}).
     */
    public Intent createShortcutResultIntent(@NonNull ShortcutInfo inShortcut, int userId) {
        // Find the default launcher activity
        final int launcherUserId = mService.getParentOrSelfUserId(userId);
        final ComponentName defaultLauncher = mService.getDefaultLauncher(launcherUserId);
        if (defaultLauncher == null) {
            Log.e(TAG, "Default launcher not found.");
            return null;
        }

        // Make sure the launcher user is unlocked. (it's always the parent profile, so should
        // really be unlocked here though.)
        mService.throwIfUserLockedL(launcherUserId);

        // Next, validate the incoming shortcut, etc.
        final PinItemRequest request = requestPinShortcutLocked(inShortcut, null,
                Pair.create(defaultLauncher, launcherUserId));
        return new Intent().putExtra(LauncherApps.EXTRA_PIN_ITEM_REQUEST, request);
    }

    /**
     * Handle {@link android.content.pm.ShortcutManager#requestPinShortcut)}.
     */
    @NonNull
    private PinItemRequest requestPinShortcutLocked(ShortcutInfo inShortcut,
            IntentSender resultIntentOriginal, Pair<ComponentName, Integer> confirmActivity) {
        final ShortcutPackage ps = mService.getPackageShortcutsForPublisherLocked(
                inShortcut.getPackage(), inShortcut.getUserId());

        final ShortcutInfo existing = ps.findShortcutById(inShortcut.getId());
        final boolean existsAlready = existing != null;
        final boolean existingIsVisible = existsAlready && existing.isVisibleToPublisher();

        if (DEBUG) {
            Slog.d(TAG, "requestPinnedShortcut: package=" + inShortcut.getPackage()
                    + " existsAlready=" + existsAlready
                    + " existingIsVisible=" + existingIsVisible
                    + " shortcut=" + inShortcut.toInsecureString());
        }

        // This is the shortcut that'll be sent to the launcher.
        final ShortcutInfo shortcutForLauncher;
        final String launcherPackage = confirmActivity.first.getPackageName();
        final int launcherUserId = confirmActivity.second;

        IntentSender resultIntentToSend = resultIntentOriginal;

        if (existsAlready) {
            validateExistingShortcut(existing);

            final boolean isAlreadyPinned = mService.getLauncherShortcutsLocked(
                    launcherPackage, existing.getUserId(), launcherUserId).hasPinned(existing);
            if (isAlreadyPinned) {
                // When the shortcut is already pinned by this launcher, the request will always
                // succeed, so just send the result at this point.
                sendResultIntent(resultIntentOriginal, null);

                // So, do not send the intent again.
                resultIntentToSend = null;
            }

            // Pass a clone, not the original.
            // Note this will remove the intent and icons.
            shortcutForLauncher = existing.clone(ShortcutInfo.CLONE_REMOVE_FOR_LAUNCHER);

            if (!isAlreadyPinned) {
                // FLAG_PINNED may still be set, if it's pinned by other launchers.
                shortcutForLauncher.clearFlags(ShortcutInfo.FLAG_PINNED);
            }
        } else {
            // If the shortcut has no default activity, try to set the main activity.
            // But in the request-pin case, it's optional, so it's okay even if the caller
            // has no default activity.
            if (inShortcut.getActivity() == null) {
                inShortcut.setActivity(mService.injectGetDefaultMainActivity(
                        inShortcut.getPackage(), inShortcut.getUserId()));
            }

            // It doesn't exist, so it must have all mandatory fields.
            mService.validateShortcutForPinRequest(inShortcut);

            // Initialize the ShortcutInfo for pending approval.
            inShortcut.resolveResourceStrings(mService.injectGetResourcesForApplicationAsUser(
                    inShortcut.getPackage(), inShortcut.getUserId()));
            if (DEBUG) {
                Slog.d(TAG, "Resolved shortcut=" + inShortcut.toInsecureString());
            }
            // We should strip out the intent, but should preserve the icon.
            shortcutForLauncher = inShortcut.clone(
                    ShortcutInfo.CLONE_REMOVE_FOR_LAUNCHER_APPROVAL);
        }
        if (DEBUG) {
            Slog.d(TAG, "Sending to launcher=" + shortcutForLauncher.toInsecureString());
        }

        // Create a request object.
        final PinShortcutRequestInner inner =
                new PinShortcutRequestInner(this, inShortcut, shortcutForLauncher,
                        resultIntentToSend, launcherPackage, launcherUserId,
                        mService.injectGetPackageUid(launcherPackage, launcherUserId),
                        existsAlready);

        return new PinItemRequest(inner, PinItemRequest.REQUEST_TYPE_SHORTCUT);
    }

    private void validateExistingShortcut(ShortcutInfo shortcutInfo) {
        // Make sure it's enabled.
        // (Because we can't always force enable it automatically as it may be a stale
        // manifest shortcut.)
        Preconditions.checkArgument(shortcutInfo.isEnabled(),
                "Shortcut ID=" + shortcutInfo + " already exists but disabled.");
    }

    private boolean startRequestConfirmActivity(ComponentName activity, int launcherUserId,
            PinItemRequest request, int requestType) {
        final String action = requestType == LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT ?
                LauncherApps.ACTION_CONFIRM_PIN_SHORTCUT :
                LauncherApps.ACTION_CONFIRM_PIN_APPWIDGET;

        // Start the activity.
        final Intent confirmIntent = new Intent(action);
        confirmIntent.setComponent(activity);
        confirmIntent.putExtra(LauncherApps.EXTRA_PIN_ITEM_REQUEST, request);
        confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);

        final long token = mService.injectClearCallingIdentity();
        try {
            mService.mContext.startActivityAsUser(
                    confirmIntent, UserHandle.of(launcherUserId));
        } catch (RuntimeException e) { // ActivityNotFoundException, etc.
            Log.e(TAG, "Unable to start activity " + activity, e);
            return false;
        } finally {
            mService.injectRestoreCallingIdentity(token);
        }
        return true;
    }

    /**
     * Find the activity that handles {@link LauncherApps#ACTION_CONFIRM_PIN_SHORTCUT} in the
     * default launcher.
     */
    @Nullable
    @VisibleForTesting
    Pair<ComponentName, Integer> getRequestPinConfirmationActivity(
            int callingUserId, int requestType) {
        // Find the default launcher.
        final int launcherUserId = mService.getParentOrSelfUserId(callingUserId);
        final ComponentName defaultLauncher = mService.getDefaultLauncher(launcherUserId);

        if (defaultLauncher == null) {
            Log.e(TAG, "Default launcher not found.");
            return null;
        }
        final ComponentName activity = mService.injectGetPinConfirmationActivity(
                defaultLauncher.getPackageName(), launcherUserId, requestType);
        return (activity == null) ? null : Pair.create(activity, launcherUserId);
    }

    public void sendResultIntent(@Nullable IntentSender intent, @Nullable Intent extras) {
        if (DEBUG) {
            Slog.d(TAG, "Sending result intent.");
        }
        mService.injectSendIntentSender(intent, extras);
    }

    public boolean isCallerUid(int uid) {
        return uid == mService.injectBinderCallingUid();
    }

    /**
     * The last step of the "request pin shortcut" flow.  Called when the launcher accepted a
     * request.
     */
    public boolean directPinShortcut(PinShortcutRequestInner request) {

        final ShortcutInfo original = request.shortcutOriginal;
        final int appUserId = original.getUserId();
        final String appPackageName = original.getPackage();
        final int launcherUserId = request.launcherUserId;
        final String launcherPackage = request.launcherPackage;
        final String shortcutId = original.getId();

        List<ShortcutInfo> changedShortcuts = null;

        synchronized (mLock) {
            if (!(mService.isUserUnlockedL(appUserId)
                    && mService.isUserUnlockedL(request.launcherUserId))) {
                Log.w(TAG, "User is locked now.");
                return false;
            }

            final ShortcutLauncher launcher = mService.getLauncherShortcutsLocked(
                    launcherPackage, appUserId, launcherUserId);
            launcher.attemptToRestoreIfNeededAndSave();
            if (launcher.hasPinned(original)) {
                if (DEBUG) {
                    Slog.d(TAG, "Shortcut " + original + " already pinned.");                       // This too.
                }
                return true;
            }

            final ShortcutPackage ps = mService.getPackageShortcutsForPublisherLocked(
                    appPackageName, appUserId);
            final ShortcutInfo current = ps.findShortcutById(shortcutId);

            // The shortcut might have been changed, so we need to do the same validation again.
            try {
                if (current == null) {
                    // It doesn't exist, so it must have all necessary fields.
                    mService.validateShortcutForPinRequest(original);
                } else {
                    validateExistingShortcut(current);
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "Unable to pin shortcut: " + e.getMessage());
                return false;
            }

            // If the shortcut doesn't exist, need to create it.
            // First, create it as a dynamic shortcut.
            if (current == null) {
                if (DEBUG) {
                    Slog.d(TAG, "Temporarily adding " + shortcutId + " as dynamic");
                }
                // Add as a dynamic shortcut.  In order for a shortcut to be dynamic, it must
                // have a target activity, so we set a dummy here.  It's later removed
                // in deleteDynamicWithId().
                if (original.getActivity() == null) {
                    original.setActivity(mService.getDummyMainActivity(appPackageName));
                }
                ps.addOrReplaceDynamicShortcut(original);
            }

            // Pin the shortcut.
            if (DEBUG) {
                Slog.d(TAG, "Pinning " + shortcutId);
            }


            launcher.addPinnedShortcut(appPackageName, appUserId, shortcutId,
                    /*forPinRequest=*/ true);

            if (current == null) {
                if (DEBUG) {
                    Slog.d(TAG, "Removing " + shortcutId + " as dynamic");
                }
                ps.deleteDynamicWithId(shortcutId, /*ignoreInvisible=*/ false);
            }

            ps.adjustRanks(); // Shouldn't be needed, but just in case.

            changedShortcuts = Collections.singletonList(ps.findShortcutById(shortcutId));
        }

        mService.verifyStates();
        mService.packageShortcutsChanged(appPackageName, appUserId, changedShortcuts, null);

        return true;
    }
}
