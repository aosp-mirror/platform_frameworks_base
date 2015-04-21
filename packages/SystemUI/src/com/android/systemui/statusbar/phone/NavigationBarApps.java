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
import android.app.ActivityOptions;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.UserHandle;
import android.util.AttributeSet;
import android.util.Log;
import android.view.DragEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.android.systemui.R;

/**
 * Container for application icons that appear in the navigation bar. Their appearance is similar
 * to the launcher hotseat. Clicking an icon launches the associated activity. A long click will
 * trigger a drag to allow the icons to be reordered. As an icon is dragged the other icons shift
 * to make space for it to be dropped. These layout changes are animated.
 */
class NavigationBarApps extends LinearLayout {
    private final static boolean DEBUG = false;
    private final static String TAG = "NavigationBarApps";

    private final NavigationBarAppsModel mAppsModel;
    private final LauncherApps mLauncherApps;
    private final PackageManager mPackageManager;
    private final LayoutInflater mLayoutInflater;

    // The view being dragged, or null if the user is not dragging. This may be a newly created
    // placeholder view if the drag is coming from outside the apps list.
    private View mDragView;

    public NavigationBarApps(Context context, AttributeSet attrs) {
        super(context, attrs);
        mAppsModel = new NavigationBarAppsModel(context);
        mLauncherApps = (LauncherApps) context.getSystemService("launcherapps");
        mPackageManager = context.getPackageManager();
        mLayoutInflater = LayoutInflater.from(context);

        // Dragging an icon removes and adds back the dragged icon. Use the layout transitions to
        // trigger animation. By default all transitions animate, so turn off the unneeded ones.
        LayoutTransition transition = new LayoutTransition();
        // Don't trigger on disappear. Adding the view will trigger the layout animation.
        transition.disableTransitionType(LayoutTransition.DISAPPEARING);
        transition.disableTransitionType(LayoutTransition.CHANGE_DISAPPEARING);
        // Don't animate the dragged icon itself.
        transition.disableTransitionType(LayoutTransition.APPEARING);
        setLayoutTransition(transition);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        createAppButtons();
    }

    /** Creates an ImageView for each pinned app. */
    private void createAppButtons() {
        // Load the saved icons, if any.
        mAppsModel.initialize();

        int appCount = mAppsModel.getAppCount();
        for (int i = 0; i < appCount; i++) {
            ImageView button = createAppButton();
            addView(button);

            // Load the icon asynchronously.
            ComponentName activityName = mAppsModel.getApp(i);
            new GetActivityIconTask(mPackageManager, button).execute(activityName);
        }
    }

    /**
     * Creates a new ImageView for a launcher activity, inflated from
     * R.layout.navigation_bar_app_item.
     */
    private ImageView createAppButton() {
        ImageView button = (ImageView) mLayoutInflater.inflate(
                R.layout.navigation_bar_app_item, this, false /* attachToRoot */);
        button.setOnClickListener(new AppClickListener());
        // TODO: Ripple effect. Use either KeyButtonRipple or the default ripple background.
        button.setOnLongClickListener(new AppLongClickListener());
        button.setOnDragListener(new AppIconDragListener());
        return button;
    }

    private class AppLongClickListener implements View.OnLongClickListener {
        @Override
        public boolean onLongClick(View v) {
            mDragView = v;
            ComponentName activityName = mAppsModel.getApp(indexOfChild(v));
            // The drag data is an Intent to launch the activity.
            Intent mainIntent = Intent.makeMainActivity(activityName);
            ClipData dragData = ClipData.newIntent("", mainIntent);
            // Use the ImageView to create the shadow.
            View.DragShadowBuilder shadow = new AppIconDragShadowBuilder((ImageView) v);
            v.startDrag(dragData, shadow, null /* myLocalState */, 0 /* flags */);
            return true;
        }
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
        }

        return handled || childHandled;
    }

    /** Returns true if a drag should be handled. */
    private static boolean canAcceptDrag(DragEvent event) {
        // The event must contain an intent.
        return event.getClipDescription().hasMimeType(ClipDescription.MIMETYPE_TEXT_INTENT);
    }

    /**
     * Sets up for a drag. Runs once per drag operation. Returns true if the data represents
     * an app shortcut and will be accepted for a drop.
     */
    private boolean onDragStarted(DragEvent event) {
        if (DEBUG) Log.d(TAG, "onDragStarted");

        // Ensure that an app shortcut is being dragged.
        if (!canAcceptDrag(event)) {
            return false;
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
     * Creates a blank icon-sized View to create an empty space during a drag. Also creates a data
     * model entry so the rest of the code can assume it is reordering existing entries.
     */
    private ImageView createPlaceholderAppButton(int index) {
        ImageView button = createAppButton();
        addView(button, index);
        mAppsModel.addApp(index, null /* name */);
        return button;
    }

    /**
     * Handles a drag entering an existing icon. Not implemented in the drag listener because it
     * needs to use LinearLayout/ViewGroup methods.
     */
    private void onDragEnteredIcon(View target) {
        if (DEBUG) Log.d(TAG, "onDragEntered " + indexOfChild(target));

        // If the drag didn't start from an existing icon, add an invisible placeholder to create
        // empty space for the user to drag into.
        if (mDragView == null) {
            int placeholderIndex = indexOfChild(target);
            mDragView = createPlaceholderAppButton(placeholderIndex);
            return;
        }

        // If the user is dragging on top of the original icon location, do nothing.
        if (target == mDragView) {
            return;
        }

        // "Move" the dragged app by removing it and adding it back at the target location.
        int dragViewIndex = indexOfChild(mDragView);
        int targetIndex = indexOfChild(target);
        // This works, but is subtle:
        // * If dragViewIndex > targetIndex then the dragged app is moving from right to left and
        //   the dragged app will be added in front of the target.
        // * If dragViewIndex < targetIndex then the dragged app is moving from left to right.
        //   Removing the drag view will shift the later views one position to the left. Adding
        //   the view at targetIndex will therefore place the app *after* the target.
        removeView(mDragView);
        addView(mDragView, targetIndex);

        // Update the data model.
        ComponentName app = mAppsModel.removeApp(dragViewIndex);
        mAppsModel.addApp(targetIndex, app);
    }

    private boolean onDrop(DragEvent event) {
        if (DEBUG) Log.d(TAG, "onDrop");

        int dragViewIndex = indexOfChild(mDragView);
        if (mAppsModel.getApp(dragViewIndex) == null) {
            // The drag view was a placeholder. Unpack the drop.
            ComponentName activityName = getActivityNameFromDragEvent(event);
            if (activityName != null) {
                // The drop had valid data. Update the placeholder with a real activity and icon.
                updateAppAt(dragViewIndex, activityName);
            } else {
                // This wasn't a valid drop. Clean up the placeholder and model.
                removeAppAt(dragViewIndex);
                mDragView = null;
            }
        }

        // The drag is complete. If the drag view still exists ensure it is visible.
        if (mDragView != null) {
            mDragView.setVisibility(View.VISIBLE);
            mDragView = null;
        }

        // Persist the state of the reordered icons.
        mAppsModel.savePrefs();
        return true;
    }

    /** Returns an app launch Intent from a DragEvent, or null if the data wasn't valid. */
    private ComponentName getActivityNameFromDragEvent(DragEvent event) {
        ClipData data = event.getClipData();
        if (data == null) {
            return null;
        }
        if (data.getItemCount() != 1) {
            return null;
        }
        Intent intent = data.getItemAt(0).getIntent();
        if (intent == null) {
            return null;
        }
        return intent.getComponent();
    }

    /** Updates the app at a given view index. */
    private void updateAppAt(int index, ComponentName activityName) {
        mAppsModel.setApp(index, activityName);
        ImageView button = (ImageView) getChildAt(index);
        new GetActivityIconTask(mPackageManager, button).execute(activityName);
    }

    /** Removes the app at a given view index from both the UI and data model. */
    private void removeAppAt(int index) {
        removeViewAt(index);
        mAppsModel.removeApp(index);
    }

    /** Cleans up at the end of the drag. */
    private boolean onDragEnded() {
        if (DEBUG) Log.d(TAG, "onDragEnded");

        if (mDragView != null) {
            // The icon wasn't dropped into the app list. Remove the placeholder.
            removeAppAt(indexOfChild(mDragView));
            mAppsModel.savePrefs();
            mDragView = null;
        }

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
        @Override
        public void onClick(View v) {
            ComponentName activityName = mAppsModel.getApp(indexOfChild(v));

            // TODO: Support apps from multiple user profiles. The profile will need to be stored in
            // the data model for each app shortcut.
            UserHandle user = UserHandle.OWNER;

            // Play a scale-up animation while launching the activity.
            // TODO: Consider playing a different animation, or no animation, if the activity is
            // already open in a visible window. In that case we should move the task to front
            // with minimal animation, perhaps using ActivityManager.moveTaskToFront().
            Rect sourceBounds = new Rect();
            v.getBoundsOnScreen(sourceBounds);
            ActivityOptions opts =
                    ActivityOptions.makeScaleUpAnimation(v, 0, 0, v.getWidth(), v.getHeight());
            Bundle optsBundle = opts.toBundle();

            // Launch the activity.
            mLauncherApps.startMainActivity(activityName, user, sourceBounds, optsBundle);
        }
    }
}
