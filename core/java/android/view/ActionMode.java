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

package android.view;


import android.annotation.StringRes;
import android.graphics.Rect;

/**
 * Represents a contextual mode of the user interface. Action modes can be used to provide
 * alternative interaction modes and replace parts of the normal UI until finished.
 * Examples of good action modes include text selection and contextual actions.
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>For information about how to provide contextual actions with {@code ActionMode},
 * read the <a href="{@docRoot}guide/topics/ui/menus.html#context-menu">Menus</a>
 * developer guide.</p>
 * </div>
 */
public abstract class ActionMode {

    /**
     * The action mode is treated as a Primary mode. This is the default.
     * Use with {@link #setType}.
     */
    public static final int TYPE_PRIMARY = 0;
    /**
     * The action mode is treated as a Floating Toolbar.
     * Use with {@link #setType}.
     */
    public static final int TYPE_FLOATING = 1;

    /**
     * Default value to hide the action mode for
     * {@link ViewConfiguration#getDefaultActionModeHideDuration()}.
     */
    public static final int DEFAULT_HIDE_DURATION = -1;

    private Object mTag;
    private boolean mTitleOptionalHint;
    private int mType = TYPE_PRIMARY;

    /**
     * Set a tag object associated with this ActionMode.
     *
     * <p>Like the tag available to views, this allows applications to associate arbitrary
     * data with an ActionMode for later reference.
     *
     * @param tag Tag to associate with this ActionMode
     *
     * @see #getTag()
     */
    public void setTag(Object tag) {
        mTag = tag;
    }

    /**
     * Retrieve the tag object associated with this ActionMode.
     *
     * <p>Like the tag available to views, this allows applications to associate arbitrary
     * data with an ActionMode for later reference.
     *
     * @return Tag associated with this ActionMode
     *
     * @see #setTag(Object)
     */
    public Object getTag() {
        return mTag;
    }

    /**
     * Set the title of the action mode. This method will have no visible effect if
     * a custom view has been set.
     *
     * @param title Title string to set
     *
     * @see #setTitle(int)
     * @see #setCustomView(View)
     */
    public abstract void setTitle(CharSequence title);

    /**
     * Set the title of the action mode. This method will have no visible effect if
     * a custom view has been set.
     *
     * @param resId Resource ID of a string to set as the title
     *
     * @see #setTitle(CharSequence)
     * @see #setCustomView(View)
     */
    public abstract void setTitle(@StringRes int resId);

    /**
     * Set the subtitle of the action mode. This method will have no visible effect if
     * a custom view has been set.
     *
     * @param subtitle Subtitle string to set
     *
     * @see #setSubtitle(int)
     * @see #setCustomView(View)
     */
    public abstract void setSubtitle(CharSequence subtitle);

    /**
     * Set the subtitle of the action mode. This method will have no visible effect if
     * a custom view has been set.
     *
     * @param resId Resource ID of a string to set as the subtitle
     *
     * @see #setSubtitle(CharSequence)
     * @see #setCustomView(View)
     */
    public abstract void setSubtitle(@StringRes int resId);

    /**
     * Set whether or not the title/subtitle display for this action mode
     * is optional.
     *
     * <p>In many cases the supplied title for an action mode is merely
     * meant to add context and is not strictly required for the action
     * mode to be useful. If the title is optional, the system may choose
     * to hide the title entirely rather than truncate it due to a lack
     * of available space.</p>
     *
     * <p>Note that this is merely a hint; the underlying implementation
     * may choose to ignore this setting under some circumstances.</p>
     *
     * @param titleOptional true if the title only presents optional information.
     */
    public void setTitleOptionalHint(boolean titleOptional) {
        mTitleOptionalHint = titleOptional;
    }

    /**
     * @return true if this action mode has been given a hint to consider the
     *         title/subtitle display to be optional.
     *
     * @see #setTitleOptionalHint(boolean)
     * @see #isTitleOptional()
     */
    public boolean getTitleOptionalHint() {
        return mTitleOptionalHint;
    }

    /**
     * @return true if this action mode considers the title and subtitle fields
     *         as optional. Optional titles may not be displayed to the user.
     */
    public boolean isTitleOptional() {
        return false;
    }

    /**
     * Set a custom view for this action mode. The custom view will take the place of
     * the title and subtitle. Useful for things like search boxes.
     *
     * @param view Custom view to use in place of the title/subtitle.
     *
     * @see #setTitle(CharSequence)
     * @see #setSubtitle(CharSequence)
     */
    public abstract void setCustomView(View view);

    /**
     * Set a type for this action mode. This will affect how the system renders the action mode if
     * it has to.
     *
     * @param type One of {@link #TYPE_PRIMARY} or {@link #TYPE_FLOATING}.
     */
    public void setType(int type) {
        mType = type;
    }

    /**
     * Returns the type for this action mode.
     *
     * @return One of {@link #TYPE_PRIMARY} or {@link #TYPE_FLOATING}.
     */
    public int getType() {
        return mType;
    }

    /**
     * Invalidate the action mode and refresh menu content. The mode's
     * {@link ActionMode.Callback} will have its
     * {@link Callback#onPrepareActionMode(ActionMode, Menu)} method called.
     * If it returns true the menu will be scanned for updated content and any relevant changes
     * will be reflected to the user.
     */
    public abstract void invalidate();

    /**
     * Invalidate the content rect associated to this ActionMode. This only makes sense for
     * action modes that support dynamic positioning on the screen, and provides a more efficient
     * way to reposition it without invalidating the whole action mode.
     *
     * @see Callback2#onGetContentRect(ActionMode, View, Rect) .
     */
    public void invalidateContentRect() {}

    /**
     * Hide the action mode view from obstructing the content below for a short duration.
     * This only makes sense for action modes that support dynamic positioning on the screen.
     * If this method is called again before the hide duration expires, the later hide call will
     * cancel the former and then take effect.
     * NOTE that there is an internal limit to how long the mode can be hidden for. It's typically
     * about a few seconds.
     *
     * @param duration The number of milliseconds to hide for.
     * @see #DEFAULT_HIDE_DURATION
     */
    public void hide(long duration) {}

    /**
     * Finish and close this action mode. The action mode's {@link ActionMode.Callback} will
     * have its {@link Callback#onDestroyActionMode(ActionMode)} method called.
     */
    public abstract void finish();

    /**
     * Returns the menu of actions that this action mode presents.
     * @return The action mode's menu.
     */
    public abstract Menu getMenu();

    /**
     * Returns the current title of this action mode.
     * @return Title text
     */
    public abstract CharSequence getTitle();

    /**
     * Returns the current subtitle of this action mode.
     * @return Subtitle text
     */
    public abstract CharSequence getSubtitle();

    /**
     * Returns the current custom view for this action mode.
     * @return The current custom view
     */
    public abstract View getCustomView();

    /**
     * Returns a {@link MenuInflater} with the ActionMode's context.
     */
    public abstract MenuInflater getMenuInflater();

    /**
     * Called when the window containing the view that started this action mode gains or loses
     * focus.
     *
     * @param hasWindowFocus True if the window containing the view that started this action mode
     *        now has focus, false otherwise.
     *
     */
    public void onWindowFocusChanged(boolean hasWindowFocus) {}

    /**
     * Returns whether the UI presenting this action mode can take focus or not.
     * This is used by internal components within the framework that would otherwise
     * present an action mode UI that requires focus, such as an EditText as a custom view.
     *
     * @return true if the UI used to show this action mode can take focus
     * @hide Internal use only
     */
    public boolean isUiFocusable() {
        return true;
    }

    /**
     * Callback interface for action modes. Supplied to
     * {@link View#startActionMode(Callback)}, a Callback
     * configures and handles events raised by a user's interaction with an action mode.
     *
     * <p>An action mode's lifecycle is as follows:
     * <ul>
     * <li>{@link Callback#onCreateActionMode(ActionMode, Menu)} once on initial
     * creation</li>
     * <li>{@link Callback#onPrepareActionMode(ActionMode, Menu)} after creation
     * and any time the {@link ActionMode} is invalidated</li>
     * <li>{@link Callback#onActionItemClicked(ActionMode, MenuItem)} any time a
     * contextual action button is clicked</li>
     * <li>{@link Callback#onDestroyActionMode(ActionMode)} when the action mode
     * is closed</li>
     * </ul>
     */
    public interface Callback {
        /**
         * Called when action mode is first created. The menu supplied will be used to
         * generate action buttons for the action mode.
         *
         * @param mode ActionMode being created
         * @param menu Menu used to populate action buttons
         * @return true if the action mode should be created, false if entering this
         *              mode should be aborted.
         */
        public boolean onCreateActionMode(ActionMode mode, Menu menu);

        /**
         * Called to refresh an action mode's action menu whenever it is invalidated.
         *
         * @param mode ActionMode being prepared
         * @param menu Menu used to populate action buttons
         * @return true if the menu or action mode was updated, false otherwise.
         */
        public boolean onPrepareActionMode(ActionMode mode, Menu menu);

        /**
         * Called to report a user click on an action button.
         *
         * @param mode The current ActionMode
         * @param item The item that was clicked
         * @return true if this callback handled the event, false if the standard MenuItem
         *          invocation should continue.
         */
        public boolean onActionItemClicked(ActionMode mode, MenuItem item);

        /**
         * Called when an action mode is about to be exited and destroyed.
         *
         * @param mode The current ActionMode being destroyed
         */
        public void onDestroyActionMode(ActionMode mode);
    }

    /**
     * Extension of {@link ActionMode.Callback} to provide content rect information. This is
     * required for ActionModes with dynamic positioning such as the ones with type
     * {@link ActionMode#TYPE_FLOATING} to ensure the positioning doesn't obscure app content. If
     * an app fails to provide a subclass of this class, a default implementation will be used.
     */
    public static abstract class Callback2 implements ActionMode.Callback {

        /**
         * Called when an ActionMode needs to be positioned on screen, potentially occluding view
         * content. Note this may be called on a per-frame basis.
         *
         * @param mode The ActionMode that requires positioning.
         * @param view The View that originated the ActionMode, in whose coordinates the Rect should
         *          be provided.
         * @param outRect The Rect to be populated with the content position. Use this to specify
         *          where the content in your app lives within the given view. This will be used
         *          to avoid occluding the given content Rect with the created ActionMode.
         */
        public void onGetContentRect(ActionMode mode, View view, Rect outRect) {
            if (view != null) {
                outRect.set(0, 0, view.getWidth(), view.getHeight());
            } else {
                outRect.set(0, 0, 0, 0);
            }
        }

    }
}
