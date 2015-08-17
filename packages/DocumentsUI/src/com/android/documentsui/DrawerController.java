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

import static com.android.internal.util.Preconditions.checkArgument;

import android.app.Activity;
import android.support.v4.app.ActionBarDrawerToggle;
import android.support.v4.widget.DrawerLayout;
import android.support.v4.widget.DrawerLayout.DrawerListener;
import android.view.MenuItem;
import android.view.View;

/**
 * A facade over the various pieces comprising "roots fragment in a Drawer".
 *
 * @see DrawerController#create(DrawerLayout)
 */
abstract class DrawerController implements DrawerListener {

    abstract void setOpen(boolean open);
    abstract void lockOpen();
    abstract void lockClosed();
    abstract boolean isOpen();
    abstract boolean isUnlocked();
    abstract void syncState();
    abstract boolean onOptionsItemSelected(MenuItem item);

    /**
     * Returns a controller suitable for {@code Layout}.
     */
    static DrawerController create(Activity activity) {

        DrawerLayout layout = (DrawerLayout) activity.findViewById(R.id.drawer_layout);

        if (layout == null) {
            return new DummyDrawerController();
        }

        View drawer = activity.findViewById(R.id.drawer_roots);
        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
                activity,
                layout,
                R.drawable.ic_hamburger,
                R.string.drawer_open,
                R.string.drawer_close);

        return new RuntimeDrawerController(layout, drawer, toggle);
    }

    /**
     * Returns a controller suitable for {@code Layout}.
     */
    static DrawerController createDummy() {
        return new DummyDrawerController();
    }

    /**
     * Runtime controller that manages a real drawer.
     */
    private static final class RuntimeDrawerController extends DrawerController {

        private final ActionBarDrawerToggle mToggle;
        private DrawerLayout mLayout;
        private View mDrawer;

        public RuntimeDrawerController(
                DrawerLayout layout, View drawer, ActionBarDrawerToggle toggle) {
            checkArgument(layout != null);

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
        void syncState() {
            mToggle.syncState();
        }

        @Override
        boolean isUnlocked() {
            return mLayout.getDrawerLockMode(mDrawer) == DrawerLayout.LOCK_MODE_UNLOCKED;
        }

        @Override
        void lockOpen() {
            mLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_OPEN);
        }

        @Override
        void lockClosed() {
            mLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);
        }

        @Override
        boolean onOptionsItemSelected(MenuItem item) {
            return false;
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
        boolean isOpen() {
            return false;
        }

        @Override
        void syncState() {}

        @Override
        void lockOpen() {}

        @Override
        void lockClosed() {}

        @Override
        boolean isUnlocked() {
            return true;
        }

        @Override
        boolean onOptionsItemSelected(MenuItem item) {
            return false;
        }

        @Override
        public void onDrawerSlide(View drawerView, float slideOffset) {}

        @Override
        public void onDrawerOpened(View drawerView) {}

        @Override
        public void onDrawerClosed(View drawerView) {}

        @Override
        public void onDrawerStateChanged(int newState) {}

        @Override
        void setOpen(boolean open) {}
    }
}
