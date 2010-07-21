/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.app;

import android.content.Context;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

/**
 * Represents a contextual mode of the user interface. Contextual modes can be used for
 * modal interactions with content and replace parts of the normal UI until finished.
 * Examples of good contextual modes include selection modes, search, content editing, etc.
 */
public abstract class ContextualMode {
    /**
     * Set the title of the contextual mode. This method will have no visible effect if
     * a custom view has been set.
     *
     * @param title Title string to set
     *
     * @see #setCustomView(View)
     */
    public abstract void setTitle(CharSequence title);

    /**
     * Set the subtitle of the contextual mode. This method will have no visible effect if
     * a custom view has been set.
     *
     * @param subtitle Subtitle string to set
     *
     * @see #setCustomView(View)
     */
    public abstract void setSubtitle(CharSequence subtitle);

    /**
     * Set a custom view for this contextual mode. The custom view will take the place of
     * the title and subtitle. Useful for things like search boxes.
     *
     * @param view Custom view to use in place of the title/subtitle.
     *
     * @see #setTitle(CharSequence)
     * @see #setSubtitle(CharSequence)
     */
    public abstract void setCustomView(View view);

    /**
     * Invalidate the contextual mode and refresh menu content. The contextual mode's
     * {@link ContextualMode.Callback} will have its
     * {@link Callback#onPrepareContextMode(ContextualMode, Menu)} method called.
     * If it returns true the menu will be scanned for updated content and any relevant changes
     * will be reflected to the user.
     */
    public abstract void invalidate();

    /**
     * Finish and close this context mode. The context mode's {@link ContextualMode.Callback} will
     * have its {@link Callback#onDestroyContextMode(ContextualMode)} method called.
     */
    public abstract void finish();

    /**
     * Returns the menu of actions that this contextual mode presents.
     * @return The contextual mode's menu.
     */
    public abstract Menu getMenu();

    /**
     * Returns the current title of this contextual mode.
     * @return Title text
     */
    public abstract CharSequence getTitle();

    /**
     * Returns the current subtitle of this contextual mode.
     * @return Subtitle text
     */
    public abstract CharSequence getSubtitle();

    /**
     * Returns the current custom view for this contextual mode.
     * @return The current custom view
     */
    public abstract View getCustomView();

    /**
     * Callback interface for contextual modes. Supplied to
     * {@link Context#startContextMode(Callback)}, a ContextModeCallback
     * configures and handles events raised by a user's interaction with a context mode.
     *
     * <p>A context mode's lifecycle is as follows:
     * <ul>
     * <li>{@link Callback#onCreateContextMode(ActionBar.ContextualMode, Menu)} once on initial
     * creation</li>
     * <li>{@link Callback#onPrepareContextMode(ActionBar.ContextualMode, Menu)} after creation
     * and any time the {@link ContextualMode} is invalidated</li>
     * <li>{@link Callback#onContextItemClicked(ActionBar.ContextualMode, MenuItem)} any time a
     * contextual action button is clicked</li>
     * <li>{@link Callback#onDestroyContextMode(ActionBar.ContextualMode)} when the context mode
     * is closed</li>
     * </ul>
     */
    public interface Callback {
        /**
         * Called when a context mode is first created. The menu supplied will be used to generate
         * action buttons for the context mode.
         *
         * @param mode ContextMode being created
         * @param menu Menu used to populate contextual action buttons
         * @return true if the context mode should be created, false if entering this context mode
         *          should be aborted.
         */
        public boolean onCreateContextMode(ContextualMode mode, Menu menu);

        /**
         * Called to refresh a context mode's action menu whenever it is invalidated.
         *
         * @param mode ContextMode being prepared
         * @param menu Menu used to populate contextual action buttons
         * @return true if the menu or context mode was updated, false otherwise.
         */
        public boolean onPrepareContextMode(ContextualMode mode, Menu menu);

        /**
         * Called to report a user click on a contextual action button.
         *
         * @param mode The current ContextMode
         * @param item The item that was clicked
         * @return true if this callback handled the event, false if the standard MenuItem
         *          invocation should continue.
         */
        public boolean onContextItemClicked(ContextualMode mode, MenuItem item);

        /**
         * Called when a context mode is about to be exited and destroyed.
         *
         * @param mode The current ContextMode being destroyed
         */
        public void onDestroyContextMode(ContextualMode mode);
    }
}