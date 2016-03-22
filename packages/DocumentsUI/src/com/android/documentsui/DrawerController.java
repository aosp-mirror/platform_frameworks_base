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

package com.android.documentsui;

import static com.android.documentsui.Shared.DEBUG;

import android.app.Activity;
import android.content.Context;
import android.support.v4.app.ActionBarDrawerToggle;
import android.support.v4.widget.DrawerLayout;
import android.support.v4.widget.DrawerLayout.DrawerListener;
import android.util.Log;
import android.view.View;
import android.widget.Toolbar;

/**
 * A facade over the various pieces comprising "roots fragment in a Drawer".
 *
 * @see DrawerController#create(DrawerLayout)
 */
abstract class DrawerController implements DrawerListener {

    public static final String TAG = "DrawerController";

    abstract void setOpen(boolean open);
    abstract boolean isPresent();
    abstract boolean isOpen();
    abstract void setTitle(String title);
    abstract void update();

    /**
     * Returns a controller suitable for {@code Layout}.
     */
    static DrawerController create(Activity activity) {

        DrawerLayout layout = (DrawerLayout) activity.findViewById(R.id.drawer_layout);

        if (layout == null) {
            return new DummyDrawerController();
        }

        View drawer = activity.findViewById(R.id.drawer_roots);
        Toolbar toolbar = (Toolbar) activity.findViewById(R.id.roots_toolbar);
        Display.adjustToolbar(toolbar, activity);
        drawer.getLayoutParams().width = calculateDrawerWidth(activity);

        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
                activity,
                layout,
                R.drawable.ic_hamburger,
                R.string.drawer_open,
                R.string.drawer_close);

        return new RuntimeDrawerController(layout, drawer, toggle, toolbar);
    }

    /**
     * Returns a controller suitable for {@code Layout}.
     */
    static DrawerController createDummy() {
        return new DummyDrawerController();
    }

    private static int calculateDrawerWidth(Activity activity) {
        // Material design specification for navigation drawer:
        // https://www.google.com/design/spec/patterns/navigation-drawer.html
        float width = Display.screenWidth(activity) - Display.actionBarHeight(activity);
        float maxWidth = activity.getResources().getDimension(R.dimen.max_drawer_width);
        int finalWidth = (int) ((width > maxWidth ? maxWidth : width));

        if (DEBUG)
            Log.d(TAG, "Calculated drawer width:" + (finalWidth / Display.density(activity)));

        return finalWidth;
    }

    /**
     * Runtime controller that manages a real drawer.
     */
    private static final class RuntimeDrawerController extends DrawerController {

        private final ActionBarDrawerToggle mToggle;
        private DrawerLayout mLayout;
        private View mDrawer;
        private Toolbar mToolbar;

        public RuntimeDrawerController(
                DrawerLayout layout, View drawer, ActionBarDrawerToggle toggle,
                Toolbar drawerToolbar) {
            mToolbar = drawerToolbar;
            assert(layout != null);

            mLayout = layout;
            mDrawer = drawer;
            mToggle = toggle;

            mLayout.setDrawerListener(this);
        }

        @Override
        void setOpen(boolean open) {
            if (open) {
                mLayout.openDrawer(mDrawer);
            } else {
                mLayout.closeDrawer(mDrawer);
            }
        }

        @Override
        boolean isOpen() {
            return mLayout.isDrawerOpen(mDrawer);
        }

        @Override
        boolean isPresent() {
            return true;
        }

        @Override
        void setTitle(String title) {
            mToolbar.setTitle(title);
        }

        @Override
        void update() {
            mToggle.syncState();
        }

        @Override
        public void onDrawerSlide(View drawerView, float slideOffset) {
            mToggle.onDrawerSlide(drawerView, slideOffset);
        }

        @Override
        public void onDrawerOpened(View drawerView) {
            mToggle.onDrawerOpened(drawerView);
        }

        @Override
        public void onDrawerClosed(View drawerView) {
            mToggle.onDrawerClosed(drawerView);
        }

        @Override
        public void onDrawerStateChanged(int newState) {
            mToggle.onDrawerStateChanged(newState);
        }
    }

    /*
     * Dummy controller useful with clients that don't host a real drawer.
     */
    private static final class DummyDrawerController extends DrawerController {

        @Override
        void setOpen(boolean open) {}


        @Override
        boolean isOpen() {
            return false;
        }

        @Override
        boolean isPresent() {
            return false;
        }

        @Override
        void setTitle(String title) {}

        @Override
        void update() {}

        @Override
        public void onDrawerSlide(View drawerView, float slideOffset) {}

        @Override
        public void onDrawerOpened(View drawerView) {}

        @Override
        public void onDrawerClosed(View drawerView) {}

        @Override
        public void onDrawerStateChanged(int newState) {}
    }
}
