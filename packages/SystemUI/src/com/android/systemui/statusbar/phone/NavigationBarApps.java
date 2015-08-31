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

package com.android.systemui.statusbar.phone;

import android.animation.LayoutTransition;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityManager.RecentTaskInfo;
import android.app.ActivityManagerNative;
import android.app.ActivityOptions;
import android.app.IActivityManager;
import android.app.ITaskStackListener;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.AttributeSet;
import android.util.Slog;
import android.view.DragEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.android.internal.content.PackageMonitor;
import com.android.systemui.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Container for application icons that appear in the navigation bar. Their appearance is similar
 * to the launcher hotseat. Clicking an icon launches or activates the associated activity. A long
 * click will trigger a drag to allow the icons to be reordered. As an icon is dragged the other
 * icons shift to make space for it to be dropped. These layout changes are animated.
 */
class NavigationBarApps extends LinearLayout {
    public final static boolean DEBUG = false;
    private final static String TAG = "NavigationBarApps";

    /**
     * Intent extra to store user serial number.
     */
    static final String EXTRA_PROFILE = "profile";

    // There are separate NavigationBarApps view instances for landscape vs. portrait, but they
    // share the data model.
    private static NavigationBarAppsModel sAppsModel;

    private final PackageManager mPackageManager;
    private final UserManager mUserManager;
    private final LayoutInflater mLayoutInflater;
    private final AppPackageMonitor mAppPackageMonitor;


    // This view has two roles:
    // 1) If the drag started outside the pinned apps list, it is a placeholder icon with a null
    // tag.
    // 2) If the drag started inside the pinned apps list, it is the icon for the app being dragged
    // with the associated AppInfo tag.
    // The icon is set invisible for the duration of the drag, creating a visual space for a drop.
    // When the user is not dragging this member is null.
    private ImageView mDragView;

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_USER_SWITCHED.equals(action)) {
                int currentUserId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
                onUserSwitched(currentUserId);
            } else if (Intent.ACTION_MANAGED_PROFILE_REMOVED.equals(action)) {
                UserHandle removedProfile = intent.getParcelableExtra(Intent.EXTRA_USER);
                onManagedProfileRemoved(removedProfile);
            }
        }
    };

    public NavigationBarApps(Context context, AttributeSet attrs) {
        super(context, attrs);
        sAppsModel = new NavigationBarAppsModel(context);
        mPackageManager = context.getPackageManager();
        mUserManager = (UserManager) getContext().getSystemService(Context.USER_SERVICE);
        mLayoutInflater = LayoutInflater.from(context);
        mAppPackageMonitor = new AppPackageMonitor();

        // Dragging an icon removes and adds back the dragged icon. Use the layout transitions to
        // trigger animation. By default all transitions animate, so turn off the unneeded ones.
        LayoutTransition transition = new LayoutTransition();
        // Don't trigger on disappear. Adding the view will trigger the layout animation.
        transition.disableTransitionType(LayoutTransition.DISAPPEARING);
        // Don't animate the dragged icon itself.
        transition.disableTransitionType(LayoutTransition.APPEARING);
        // When an icon is dragged off the shelf, start sliding the other icons over immediately
        // to match the parent view's animation.
        transition.setStartDelay(LayoutTransition.CHANGE_DISAPPEARING, 0);
        transition.setStagger(LayoutTransition.CHANGE_DISAPPEARING, 0);
        setLayoutTransition(transition);

        TaskStackListener taskStackListener = new TaskStackListener();
        IActivityManager iam = ActivityManagerNative.getDefault();
        try {
            iam.registerTaskStackListener(taskStackListener);
        } catch (RemoteException e) {
            Slog.e(TAG, "registerTaskStackListener failed", e);
        }
    }

    // Monitor that catches events like "app uninstalled".
    private class AppPackageMonitor extends PackageMonitor {
        @Override
        public void onPackageRemoved(String packageName, int uid) {
            postUnpinIfUnlauncheable(packageName, new UserHandle(getChangingUserId()));
            super.onPackageRemoved(packageName, uid);
        }

        @Override
        public void onPackageModified(String packageName) {
            postUnpinIfUnlauncheable(packageName, new UserHandle(getChangingUserId()));
            super.onPackageModified(packageName);
        }

        @Override
        public void onPackagesAvailable(String[] packages) {
            if (isReplacing()) {
                UserHandle user = new UserHandle(getChangingUserId());

                for (String packageName : packages) {
                    postUnpinIfUnlauncheable(packageName, user);
                }
            }
            super.onPackagesAvailable(packages);
        }

        @Override
        public void onPackagesUnavailable(String[] packages) {
            if (!isReplacing()) {
                UserHandle user = new UserHandle(getChangingUserId());

                for (String packageName : packages) {
                    postUnpinIfUnlauncheable(packageName, user);
                }
            }
            super.onPackagesUnavailable(packages);
        }
    }

    private void postUnpinIfUnlauncheable(final String packageName, final UserHandle user) {
        // This method doesn't necessarily get called in the main thread. Redirect the call into
        // the main thread.
        post(new Runnable() {
            @Override
            public void run() {
                if (!isAttachedToWindow()) return;
                unpinIfUnlauncheable(packageName, user);
            }
        });
    }

    private void unpinIfUnlauncheable(String packageName, UserHandle user) {
        // Unpin icons for all apps that match a package that perhaps became unlauncheable.
        boolean appsWereUnpinned = false;
        for(int i = getChildCount() - 1; i >= 0; --i) {
            View child = getChildAt(i);
            AppButtonData appButtonData = (AppButtonData)child.getTag();
            if (appButtonData == null) continue;  // Skip the drag placeholder.

            if (!appButtonData.pinned) continue;

            AppInfo appInfo = appButtonData.appInfo;
            if (!appInfo.getUser().equals(user)) continue;

            ComponentName appComponentName = appInfo.getComponentName();
            if (!appComponentName.getPackageName().equals(packageName)) continue;

            if (sAppsModel.buildAppLaunchIntent(appInfo) != null) {
                continue;
            }

            appButtonData.pinned = false;
            appsWereUnpinned = true;

            if (appButtonData.isEmpty()) {
                removeViewAt(i);
            }
        }
        if (appsWereUnpinned) {
            savePinnedApps();
        }
    }

    @Override
    protected void onAttachedToWindow() {
      super.onAttachedToWindow();
        // When an icon is dragged out of the pinned area this view's width changes, which causes
        // the parent container's layout to change and the divider and recents icons to shift left.
        // Animate the parent's CHANGING transition.
        ViewGroup parent = (ViewGroup) getParent();
        LayoutTransition transition = new LayoutTransition();
        transition.disableTransitionType(LayoutTransition.APPEARING);
        transition.disableTransitionType(LayoutTransition.DISAPPEARING);
        transition.disableTransitionType(LayoutTransition.CHANGE_APPEARING);
        transition.disableTransitionType(LayoutTransition.CHANGE_DISAPPEARING);
        transition.enableTransitionType(LayoutTransition.CHANGING);
        parent.setLayoutTransition(transition);

        sAppsModel.setCurrentUser(ActivityManager.getCurrentUser());
        recreatePinnedAppButtons();
        updateRecentApps();

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        filter.addAction(Intent.ACTION_MANAGED_PROFILE_REMOVED);
        mContext.registerReceiver(mBroadcastReceiver, filter);

        mAppPackageMonitor.register(mContext, null, UserHandle.ALL, true);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mContext.unregisterReceiver(mBroadcastReceiver);
        mAppPackageMonitor.unregister();
    }

    private void addAppButton(AppButtonData appButtonData) {
        ImageView button = createAppButton(appButtonData);
        addView(button);

        AppInfo app = appButtonData.appInfo;
        CharSequence appLabel = getAppLabel(mPackageManager, app.getComponentName());
        button.setContentDescription(appLabel);

        // Load the icon asynchronously.
        new GetActivityIconTask(mPackageManager, button).execute(appButtonData);
    }

    /**
     * Creates an ImageView icon for each pinned app. Removes any existing icons. May be called
     * to synchronize the current view with the shared data mode.
     */
    private void recreatePinnedAppButtons() {
        // Remove any existing icon buttons.
        removeAllViews();

        List<AppInfo> apps = sAppsModel.getApps();
        int appCount = apps.size();
        for (int i = 0; i < appCount; i++) {
            AppInfo app = apps.get(i);
            addAppButton(new AppButtonData(app, true /* pinned */));
        }
    }

    /**
     * Saves pinned apps stored in app icons into the data model.
     */
    private void savePinnedApps() {
        List<AppInfo> apps = new ArrayList<AppInfo>();
        int childCount = getChildCount();
        for (int i = 0; i != childCount; ++i) {
            View child = getChildAt(i);
            AppButtonData appButtonData = (AppButtonData)child.getTag();
            if (appButtonData == null) continue;  // Skip the drag placeholder.
            if(!appButtonData.pinned) continue;
            apps.add(appButtonData.appInfo);
        }
        sAppsModel.setApps(apps);
    }

    /**
     * Creates a new ImageView for an app, inflated from R.layout.navigation_bar_app_item.
     */
    private ImageView createAppButton(AppButtonData appButtonData) {
        ImageView button = (ImageView) mLayoutInflater.inflate(
                R.layout.navigation_bar_app_item, this, false /* attachToRoot */);
        button.setOnClickListener(new AppClickListener());
        // TODO: Ripple effect. Use either KeyButtonRipple or the default ripple background.
        button.setOnLongClickListener(new AppLongClickListener());
        button.setOnDragListener(new AppIconDragListener());
        button.setTag(appButtonData);
        return button;
    }

    private class AppLongClickListener implements View.OnLongClickListener {
        @Override
        public boolean onLongClick(View v) {
            mDragView = (ImageView) v;
            AppButtonData appButtonData = (AppButtonData) v.getTag();
            startAppDrag(mDragView, appButtonData.appInfo);
            return true;
        }
    }

    /**
     * Returns the human-readable name for an activity's package or null.
     * TODO: Cache the labels, perhaps in an LruCache.
     */
    @Nullable
    static CharSequence getAppLabel(PackageManager packageManager,
                                    ComponentName activityName) {
        String packageName = activityName.getPackageName();
        ApplicationInfo info;
        try {
            info = packageManager.getApplicationInfo(packageName, 0x0 /* flags */);
        } catch (PackageManager.NameNotFoundException e) {
            Slog.w(TAG, "Package not found " + packageName);
            return null;
        }
        return packageManager.getApplicationLabel(info);
    }

    /** Helper function to start dragging an app icon (either pinned or recent). */
    static void startAppDrag(ImageView icon, AppInfo appInfo) {
        // The drag data is an Intent to launch the activity.
        Intent mainIntent = Intent.makeMainActivity(appInfo.getComponentName());
        UserManager userManager =
                (UserManager) icon.getContext().getSystemService(Context.USER_SERVICE);
        long userSerialNumber = userManager.getSerialNumberForUser(appInfo.getUser());
        mainIntent.putExtra(EXTRA_PROFILE, userSerialNumber);
        ClipData dragData = ClipData.newIntent("", mainIntent);
        // Use the ImageView to create the shadow.
        View.DragShadowBuilder shadow = new AppIconDragShadowBuilder(icon);
        // Use a global drag because the icon might be dragged into the launcher.
        icon.startDrag(dragData, shadow, null /* myLocalState */, View.DRAG_FLAG_GLOBAL);
    }

    @Override
    public boolean dispatchDragEvent(DragEvent event) {
        // ACTION_DRAG_ENTERED is handled by each individual app icon drag listener.
        boolean childHandled = super.dispatchDragEvent(event);

        // Other drag types are handled once per drag by this view. This is handled explicitly
        // because attaching a DragListener to this ViewGroup does not work -- the DragListener in
        // the children consumes the drag events.
        boolean handled = false;
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED:
                handled = onDragStarted(event);
                break;
            case DragEvent.ACTION_DRAG_ENDED:
                handled = onDragEnded();
                break;
            case DragEvent.ACTION_DROP:
                handled = onDrop(event);
                break;
            case DragEvent.ACTION_DRAG_EXITED:
                handled = onDragExited();
                break;
        }

        return handled || childHandled;
    }

    /** Returns true if a drag should be handled. */
    private static boolean canAcceptDrag(DragEvent event) {
        // Poorly behaved apps might not provide a clip description.
        if (event.getClipDescription() == null) {
            return false;
        }
        // The event must contain an intent.
        return event.getClipDescription().hasMimeType(ClipDescription.MIMETYPE_TEXT_INTENT);
    }

    /**
     * Sets up for a drag. Runs once per drag operation. Returns true if the data represents
     * an app shortcut and will be accepted for a drop.
     */
    private boolean onDragStarted(DragEvent event) {
        if (DEBUG) Slog.d(TAG, "onDragStarted");

        // Ensure that an app shortcut is being dragged.
        if (!canAcceptDrag(event)) {
            return false;
        }

        // If there are no pinned apps this view will be collapsed, but the user still needs some
        // empty space to use as a drag target.
        if (getChildCount() == 0) {
            mDragView = createPlaceholderDragView(0);
        }

        // If this is an existing icon being reordered, hide the app icon. The drag shadow will
        // continue to draw.
        if (mDragView != null) {
            mDragView.setVisibility(View.INVISIBLE);
        }

        // Listen for the drag end event.
        return true;
    }

    /**
     * Creates a blank icon-sized View to create an empty space during a drag.
     */
    private ImageView createPlaceholderDragView(int index) {
        ImageView button = createAppButton(null);
        addView(button, index);
        return button;
    }

    /**
     * Handles a drag entering an existing icon. Not implemented in the drag listener because it
     * needs to use LinearLayout/ViewGroup methods.
     */
    private void onDragEnteredIcon(View target) {
        if (DEBUG) Slog.d(TAG, "onDragEntered " + indexOfChild(target));

        // If the drag didn't start from an existing icon, add an invisible placeholder to create
        // empty space for the user to drag into.
        if (mDragView == null) {
            int placeholderIndex = indexOfChild(target);
            mDragView = createPlaceholderDragView(placeholderIndex);
            return;
        }

        // If the user is dragging on top of the original icon location, do nothing.
        if (target == mDragView) {
            return;
        }

        // "Move" the dragged app by removing it and adding it back at the target location.
        int targetIndex = indexOfChild(target);
        // This works, but is subtle:
        // * If dragViewIndex > targetIndex then the dragged app is moving from right to left and
        //   the dragged app will be added in front of the target.
        // * If dragViewIndex < targetIndex then the dragged app is moving from left to right.
        //   Removing the drag view will shift the later views one position to the left. Adding
        //   the view at targetIndex will therefore place the app *after* the target.
        removeView(mDragView);
        addView(mDragView, targetIndex);
    }

    private boolean onDrop(DragEvent event) {
        if (DEBUG) Slog.d(TAG, "onDrop");

        // An earlier drag event might have canceled the drag. If so, there is nothing to do.
        if (mDragView == null) {
            return true;
        }

        boolean dragResult = true;
        AppInfo appInfo = getAppFromDragEvent(event);
        if (appInfo == null) {
            // This wasn't a valid drop. Clean up the placeholder.
            removePlaceholderDragViewIfNeeded();
            dragResult = false;
        } else if (mDragView.getTag() == null) {
            // This is a drag that adds a new app. Convert the placeholder to a real icon.
            updateApp(mDragView, new AppButtonData(appInfo, true /* pinned */));
        }
        endDrag();
        return dragResult;
    }

    /** Cleans up at the end of a drag. */
    private void endDrag() {
        // An earlier drag event might have canceled the drag. If so, there is nothing to do.
        if (mDragView == null) return;

        mDragView.setVisibility(View.VISIBLE);
        mDragView = null;
        savePinnedApps();
        // Add recent tasks to the info of the potentially added app.
        updateRecentApps();
    }

    /** Returns an app info from a DragEvent, or null if the data wasn't valid. */
    private AppInfo getAppFromDragEvent(DragEvent event) {
        ClipData data = event.getClipData();
        if (data == null) {
            return null;
        }
        if (data.getItemCount() != 1) {
            return null;
        }
        ClipData.Item item = data.getItemAt(0);
        if (item == null) {
            return null;
        }
        Intent intent = item.getIntent();
        if (intent == null) {
            return null;
        }
        long userSerialNumber = intent.getLongExtra(EXTRA_PROFILE, -1);
        if (userSerialNumber == -1) {
            return null;
        }
        UserHandle appUser = mUserManager.getUserForSerialNumber(userSerialNumber);
        if (appUser == null) {
            return null;
        }
        ComponentName componentName = intent.getComponent();
        if (componentName == null) {
            return null;
        }
        AppInfo appInfo = new AppInfo(componentName, appUser);
        if (sAppsModel.buildAppLaunchIntent(appInfo) == null) {
            return null;
        }
        return appInfo;
    }

    /** Updates the app at a given view index. */
    private void updateApp(ImageView button, AppButtonData appButtonData) {
        button.setTag(appButtonData);
        new GetActivityIconTask(mPackageManager, button).execute(appButtonData);
    }

    /** Removes the empty placeholder view. */
    private void removePlaceholderDragViewIfNeeded() {
        // If the drag has ended already there is nothing to do.
        if (mDragView == null) {
            return;
        }
        removeView(mDragView);
    }

    /** Cleans up at the end of the drag. */
    private boolean onDragEnded() {
        if (DEBUG) Slog.d(TAG, "onDragEnded");
        // If the icon wasn't already dropped into the app list then remove the placeholder.
        removePlaceholderDragViewIfNeeded();
        endDrag();
        return true;
    }

    /** Handles the dragged icon exiting the bounds of this view during the drag. */
    private boolean onDragExited() {
        if (DEBUG) Slog.d(TAG, "onDragExited");
        // Remove the placeholder. It will be added again if the user drags the icon back over
        // the shelf.
        removePlaceholderDragViewIfNeeded();
        return true;
    }

    /** Drag listener for individual app icons. */
    private class AppIconDragListener implements View.OnDragListener {
        @Override
        public boolean onDrag(View v, DragEvent event) {
            switch (event.getAction()) {
                case DragEvent.ACTION_DRAG_STARTED: {
                    // Every button listens for drag events in order to detect enter/exit.
                    return canAcceptDrag(event);
                }
                case DragEvent.ACTION_DRAG_ENTERED: {
                    // Forward to NavigationBarApps.
                    onDragEnteredIcon(v);
                    return false;
                }
            }
            return false;
        }
    }

    /**
     * A click listener that launches an activity.
     */
    private class AppClickListener implements View.OnClickListener {
        private void launchApp(AppInfo appInfo, View anchor) {
            Intent launchIntent = sAppsModel.buildAppLaunchIntent(appInfo);
            if (launchIntent == null) {
                Toast.makeText(
                        getContext(), R.string.activity_not_found, Toast.LENGTH_SHORT).show();
                return;
            }

            // Play a scale-up animation while launching the activity.
            // TODO: Consider playing a different animation, or no animation, if the activity is
            // already open in a visible window. In that case we should move the task to front
            // with minimal animation, perhaps using ActivityManager.moveTaskToFront().
            Rect sourceBounds = new Rect();
            anchor.getBoundsOnScreen(sourceBounds);
            ActivityOptions opts =
                    ActivityOptions.makeScaleUpAnimation(
                            anchor, 0, 0, anchor.getWidth(), anchor.getHeight());
            Bundle optsBundle = opts.toBundle();
            launchIntent.setSourceBounds(sourceBounds);

            mContext.startActivityAsUser(launchIntent, optsBundle, appInfo.getUser());
        }

        private void activateLatestTask(List<RecentTaskInfo> tasks) {
            // 'tasks' is guaranteed to be non-empty.
            int latestTaskPersistentId = tasks.get(0).persistentId;
            // Launch or bring the activity to front.
            IActivityManager manager = ActivityManagerNative.getDefault();
            try {
                manager.startActivityFromRecents(latestTaskPersistentId, null /* options */);
            } catch (RemoteException e) {
                Slog.e(TAG, "Exception when activating a recent task", e);
            } catch (IllegalArgumentException e) {
                Slog.e(TAG, "Exception when activating a recent task", e);
            }
        }

        @Override
        public void onClick(View v) {
            AppButtonData appButtonData = (AppButtonData)v.getTag();

            if (appButtonData.tasks == null || appButtonData.tasks.size() == 0) {
                launchApp(appButtonData.appInfo, v);
            } else {
                activateLatestTask(appButtonData.tasks);
            }
        }
    }

    private void onUserSwitched(int currentUserId) {
        sAppsModel.setCurrentUser(currentUserId);
        recreatePinnedAppButtons();
    }

    private void onManagedProfileRemoved(UserHandle removedProfile) {
        // Unpin apps from the removed profile.
        boolean itemsWereUnpinned = false;
        for(int i = getChildCount() - 1; i >= 0; --i) {
            View view = getChildAt(i);
            AppButtonData appButtonData = (AppButtonData)view.getTag();
            if (appButtonData == null) return;  // Skip the drag placeholder.
            if (!appButtonData.pinned) continue;
            if (!appButtonData.appInfo.getUser().equals(removedProfile)) continue;

            appButtonData.pinned = false;
            itemsWereUnpinned = true;
            if (appButtonData.isEmpty()) {
                removeViewAt(i);
            }
        }
        if (itemsWereUnpinned) {
            savePinnedApps();
        }
    }

    /**
     * Returns app data for a button that matches the provided app info, if it exists, or null
     * otherwise.
     */
    private AppButtonData findAppButtonData(AppInfo appInfo) {
        int size = getChildCount();
        for (int i = 0; i < size; ++i) {
            View view = getChildAt(i);
            AppButtonData appButtonData = (AppButtonData)view.getTag();
            if (appButtonData == null) continue;  // Skip the drag placeholder.
            if (appButtonData.appInfo.equals(appInfo)) {
                return appButtonData;
            }
        }
        return null;
    }

    private void updateTasks(List<RecentTaskInfo> tasks) {
        // Remove tasks from all app buttons.
        for (int i = getChildCount() - 1; i >= 0; --i) {
            View view = getChildAt(i);
            AppButtonData appButtonData = (AppButtonData)view.getTag();
            if (appButtonData == null) return;  // Skip the drag placeholder.
            appButtonData.clearTasks();
        }

        // Re-add tasks to app buttons, adding new buttons if needed.
        int size = tasks.size();
        for (int i = 0; i != size; ++i) {
            RecentTaskInfo task = tasks.get(i);
            AppInfo taskAppInfo = taskToAppInfo(task);
            if (taskAppInfo == null) continue;
            AppButtonData appButtonData = findAppButtonData(taskAppInfo);
            if (appButtonData == null) {
                appButtonData = new AppButtonData(taskAppInfo, false);
                addAppButton(appButtonData);
            }
            appButtonData.addTask(task);
        }

        // Remove unpinned apps that now have no tasks.
        for (int i = getChildCount() - 1; i >= 0; --i) {
            View view = getChildAt(i);
            AppButtonData appButtonData = (AppButtonData)view.getTag();
            if (appButtonData == null) return;  // Skip the drag placeholder.
            if (appButtonData.isEmpty()) {
                removeViewAt(i);
            }
        }

        if (DEBUG) {
            for (int i = getChildCount() - 1; i >= 0; --i) {
                View view = getChildAt(i);
                AppButtonData appButtonData = (AppButtonData)view.getTag();
                if (appButtonData == null) return;  // Skip the drag placeholder.
                new GetActivityIconTask(mPackageManager, (ImageView )view).execute(appButtonData);

            }
        }
    }

    private void updateRecentApps() {
        ActivityManager activityManager =
                (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
        // TODO: Should this be getRunningTasks?
        List<RecentTaskInfo> recentTasks = activityManager.getRecentTasksForUser(
                ActivityManager.getMaxAppRecentsLimitStatic(),
                ActivityManager.RECENT_IGNORE_HOME_STACK_TASKS |
                        ActivityManager.RECENT_IGNORE_UNAVAILABLE |
                        ActivityManager.RECENT_INCLUDE_PROFILES,
                UserHandle.USER_CURRENT);
        if (DEBUG) Slog.d(TAG, "Got recents " + recentTasks.size());
        updateTasks(recentTasks);
    }

    private static ComponentName getActivityForTask(RecentTaskInfo task) {
        // If the task was started from an alias, return the actual activity component that was
        // initially started.
        if (task.origActivity != null) {
            return task.origActivity;
        }
        // Prefer the first activity of the task.
        if (task.baseActivity != null) {
            return task.baseActivity;
        }
        // Then goes the activity that started the task.
        if (task.realActivity != null) {
            return task.realActivity;
        }
        // This should not happen, but fall back to the base intent's activity component name.
        return task.baseIntent.getComponent();
    }

    private ComponentName getLaunchComponentForPackage(String packageName, int userId) {
        // This code is based on ApplicationPackageManager.getLaunchIntentForPackage.
        PackageManager packageManager = mContext.getPackageManager();

        // First see if the package has an INFO activity; the existence of
        // such an activity is implied to be the desired front-door for the
        // overall package (such as if it has multiple launcher entries).
        Intent intentToResolve = new Intent(Intent.ACTION_MAIN);
        intentToResolve.addCategory(Intent.CATEGORY_INFO);
        intentToResolve.setPackage(packageName);
        List<ResolveInfo> ris = packageManager.queryIntentActivitiesAsUser(
                intentToResolve, 0, userId);

        // Otherwise, try to find a main launcher activity.
        if (ris == null || ris.size() <= 0) {
            // reuse the intent instance
            intentToResolve.removeCategory(Intent.CATEGORY_INFO);
            intentToResolve.addCategory(Intent.CATEGORY_LAUNCHER);
            intentToResolve.setPackage(packageName);
            ris = packageManager.queryIntentActivitiesAsUser(intentToResolve, 0, userId);
        }
        if (ris == null || ris.size() <= 0) {
            Slog.e(TAG, "Failed to build intent for " + packageName);
            return null;
        }
        return new ComponentName(ris.get(0).activityInfo.packageName,
                ris.get(0).activityInfo.name);
    }

    private AppInfo taskToAppInfo(RecentTaskInfo task) {
        ComponentName componentName = getActivityForTask(task);
        UserHandle taskUser = new UserHandle(task.userId);
        AppInfo appInfo = new AppInfo(componentName, taskUser);

        if (sAppsModel.buildAppLaunchIntent(appInfo) == null) {
            // If task's activity is not launcheable, fall back to a launch component of the
            // task's package.
            ComponentName component = getLaunchComponentForPackage(
                    componentName.getPackageName(), task.userId);

            if (component == null) {
                return null;
            }

            appInfo = new AppInfo(component, taskUser);
        }

        return appInfo;
    }

    /**
     * A listener that updates the app buttons whenever the recents task stack changes.
     */
    private class TaskStackListener extends ITaskStackListener.Stub {
        @Override
        public void onTaskStackChanged() throws RemoteException {
            // Post the message back to the UI thread.
            post(new Runnable() {
                @Override
                public void run() {
                    if (isAttachedToWindow()) {
                        updateRecentApps();
                    }
                }
            });
        }
    }
}
