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
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.UserHandle;
import android.util.AttributeSet;
import android.util.Log;
import android.view.DragEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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

    // The view being dragged, or null if the user is not dragging.
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
            ImageView button = createAppButton(mAppsModel.getApp(i));
            // TODO: remove padding from leftmost button.
            addView(button);
        }
    }

    /**
     * Creates a new ImageView for a launcher activity, inflated from
     * R.layout.navigation_bar_app_item.
     */
    private ImageView createAppButton(ComponentName activityName) {
        ImageView button = (ImageView) mLayoutInflater.inflate(
                R.layout.navigation_bar_app_item, this, false /* attachToRoot */);
        button.setOnClickListener(new AppClickListener(activityName));
        // TODO: Ripple effect. Use either KeyButtonRipple or the default ripple background.
        button.setOnLongClickListener(new AppLongClickListener());
        button.setOnDragListener(new AppDragListener());
        // Load the icon asynchronously.
        new GetActivityIconTask(mPackageManager, button).execute(activityName);
        return button;
    }

    /** Starts a drag on long-click. */
    private class AppLongClickListener implements View.OnLongClickListener {
        @Override
        public boolean onLongClick(View v) {
            mDragView = v;
            // TODO: Use real metadata for the drop, perhaps a launch intent.
            ClipData dragData = ClipData.newPlainText("label", "text");
            // Use the ImageView to create the shadow.
            View.DragShadowBuilder shadow = new IconDragShadowBuilder((ImageView) v);
            v.startDrag(dragData, shadow, null /* myLocalState */, 0 /* flags */);
            return true;
        }
    }

    /** Creates a scaled-up version of an ImageView's Drawable for dragging. */
    private static class IconDragShadowBuilder extends View.DragShadowBuilder {
        private final static int ICON_SCALE = 3;
        final Drawable mDrawable;
        final int mWidth;
        final int mHeight;

        public IconDragShadowBuilder(ImageView icon) {
            mDrawable = icon.getDrawable();
            // The Drawable may not be the same size as the ImageView, so use the ImageView size.
            mWidth = icon.getWidth() * ICON_SCALE;
            mHeight = icon.getHeight() * ICON_SCALE;
        }

        @Override
        public void onProvideShadowMetrics(Point size, Point touch) {
            size.set(mWidth, mHeight);
            touch.set(mWidth / 2, mHeight / 2);
        }

        @Override
        public void onDrawShadow(Canvas canvas) {
            // The Drawable's native bounds may be different than the source ImageView. Force it
            // to the correct size.
            Rect oldBounds = mDrawable.copyBounds();
            mDrawable.setBounds(0, 0, mWidth, mHeight);
            mDrawable.draw(canvas);
            mDrawable.setBounds(oldBounds);
        }
    }

    /**
     * Handles a drag entering an existing icon. Not implemented in the drag listener because it
     * needs to use LinearLayout/ViewGroup methods.
     */
    private void onDragEntered(View target) {
        if (DEBUG) Log.d(TAG, "onDragEntered " + indexOfChild(target));

        if (target == mDragView) {
            // Nothing to do, the user is dragging on top of the original location.
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

    private void onDrop() {
        // Persist the state of the reordered icons.
        mAppsModel.savePrefs();
    }

    /** Drag listener for app icons. */
    private class AppDragListener implements View.OnDragListener {
        @Override
        public boolean onDrag(View v, DragEvent event) {
            switch (event.getAction()) {
                case DragEvent.ACTION_DRAG_STARTED: {
                    if (DEBUG) Log.d(TAG, "onStarted " + viewIndexInParent(v));
                    // Hide the icon being dragged. The drag shadow will continue to draw.
                    if (v == mDragView) {
                        v.setVisibility(View.INVISIBLE);
                    }
                    // Every button listens for drag events in order to detect enter/exit.
                    return true;
                }
                case DragEvent.ACTION_DRAG_ENTERED: {
                    // Forward to NavigationBarApps.
                    onDragEntered(v);
                    return false;
                }
                case DragEvent.ACTION_DRAG_LOCATION: {
                    // Nothing to do.
                    return false;
                }
                case DragEvent.ACTION_DRAG_EXITED: {
                    // Nothing to do.
                    return false;
                }
                case DragEvent.ACTION_DROP: {
                    onDrop();
                    return false;
                }
                case DragEvent.ACTION_DRAG_ENDED: {
                    if (DEBUG) Log.d(TAG, "onDragEnded " + viewIndexInParent(v));
                    // Ensure the dragged app becomes visible again.
                    if (v == mDragView) {
                        v.setVisibility(View.VISIBLE);
                        mDragView = null;
                    }
                    return true;
                }
            }
            return false;
        }

        /** Returns a View's index in its ViewGroup parent. */
        private int viewIndexInParent(View v) {
            return ((ViewGroup) v.getParent()).indexOfChild(v);
        }
    }

    /**
     * A click listener that launches an activity.
     */
    private class AppClickListener implements View.OnClickListener {
        private final ComponentName mActivityName;

        public AppClickListener(ComponentName activityName) {
            mActivityName = activityName;
        }

        @Override
        public void onClick(View v) {
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
            mLauncherApps.startMainActivity(mActivityName, user, sourceBounds, optsBundle);
        }
    }
}
