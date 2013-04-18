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
package android.view.transition;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.ViewGroup;

/**
 * A scene represents the collection of values that various properties in the
 * View hierarchy will have when the scene is applied. A Scene can be
 * configured to automatically run a Transition when it is applied, which will
 * animate the various property changes that take place during the
 * scene change.
 */
public final class Scene {

    private Context mContext;
    private int mLayoutId = -1;
    private ViewGroup mSceneRoot;
    private ViewGroup mLayout; // alternative to layoutId
    Runnable mEnterAction, mExitAction;

    /**
     * Constructs a Scene with no information about how values will change
     * when this scene is applied. This constructor might be used when
     * a Scene is created with the intention of being dynamically configured,
     * through setting {@link #setEnterAction(Runnable)} and possibly
     * {@link #setExitAction(Runnable)}.
     *
     * @param sceneRoot The root of the hierarchy in which scene changes
     * and transitions will take place.
     */
    public Scene(ViewGroup sceneRoot) {
        mSceneRoot = sceneRoot;
    }

    /**
     * Constructs a Scene which, when entered, will remove any
     * children from the sceneRoot container and will inflate and add
     * the hierarchy specified by the layoutId resource file.
     *
     * @param sceneRoot The root of the hierarchy in which scene changes
     * and transitions will take place.
     * @param layoutId The id of a resource file that defines the view
     * hierarchy of this scene.
     * @param context The context used in the process of inflating
     * the layout resource.
     */
    public Scene(ViewGroup sceneRoot, int layoutId, Context context) {
        mContext = context;
        mSceneRoot = sceneRoot;
        mLayoutId = layoutId;
    }

    /**
     * Constructs a Scene which, when entered, will remove any
     * children from the sceneRoot container and add the layout
     * object as a new child of that container.
     *
     * @param sceneRoot The root of the hierarchy in which scene changes
     * and transitions will take place.
     * @param layout The view hiearrchy of this scene, added as a child
     * of sceneRoot when this scene is entered.
     */
    public Scene(ViewGroup sceneRoot, ViewGroup layout) {
        mSceneRoot = sceneRoot;
        mLayout = layout;
    }

    /**
     * Gets the root of the scene, which is the root of the view hierarchy
     * affected by changes due to this scene, and which will be animated
     * when this scene is entered.
     *
     * @return The root of the view hierarchy affected by this scene.
     */
    public ViewGroup getSceneRoot() {
        return mSceneRoot;
    }

    /**
     * Exits this scene, if it is the {@link ViewGroup#getCurrentScene()
     * currentScene} on the scene's {@link #getSceneRoot() scene root}.
     * Exiting a scene involves removing the layout added if the scene
     * has either a layoutId or layout view group (set at construction
     * time) and running the {@link #setExitAction(Runnable) exit action}
     * if there is one.
     */
    public void exit() {
        if (mSceneRoot.getCurrentScene() == this) {
            if (mLayoutId >= 0 || mLayout != null) {
                // Undo layout change caused by entering this scene
                getSceneRoot().removeAllViews();
            }
            if (mExitAction != null) {
                mExitAction.run();
            }
        }
    }

    /**
     * Enters this scene, which entails changing all values that
     * are specified by this scene. These may be values associated
     * with a layout view group or layout resource file which will
     * now be added to the scene root, or it may be values changed by
     * an {@link #setEnterAction(Runnable)} enter action}, or a
     * combination of the these. No transition will be run when the
     * scene is entered. To get transition behavior in scene changes,
     * use one of the methods in {@link TransitionManager} instead.
     */
    public void enter() {

        // Apply layout change, if any
        if (mLayoutId >= 0 || mLayout != null) {
            // redundant with exit() action of previous scene, but must
            // empty out that parent container before adding to it
            getSceneRoot().removeAllViews();

            if (mLayoutId >= 0) {
                LayoutInflater.from(mContext).inflate(mLayoutId, mSceneRoot);
            } else {
                mSceneRoot.addView(mLayout);
            }
        }

        // Notify next scene that it is entering. Subclasses may override to configure scene.
        if (mEnterAction != null) {
            mEnterAction.run();
        }

        mSceneRoot.setCurrentScene(this );
    }

    /**
     * Scenes that are not defined with layout resources or
     * hierarchies, or which need to perform additional steps
     * after those hierarchies are changed to, should set an enter
     * action, and possibly an exit action as well. An enter action
     * will cause Scene to call back into application code to do
     * anything else the application needs after transitions have
     * captured pre-change values and after any other scene changes
     * have been applied, such as the layout (if any) being added to
     * the view hierarchy. After this method is called, Transitions will
     * be played.
     *
     * @param action The runnable whose {@link Runnable#run() run()} method will
     * be called when this scene is entered
     * @see #setExitAction(Runnable)
     * @see Scene#Scene(ViewGroup, int, Context)
     * @see Scene#Scene(ViewGroup, ViewGroup)
     */
    public void setEnterAction(Runnable action) {
        mEnterAction = action;
    }

    /**
     * Scenes that are not defined with layout resources or
     * hierarchies, or which need to perform additional steps
     * after those hierarchies are changed to, should set an enter
     * action, and possibly an exit action as well. An exit action
     * will cause Scene to call back into application code to do
     * anything the application needs to do after applicable transitions have
     * captured pre-change values, but before any other scene changes
     * have been applied, such as the new layout (if any) being added to
     * the view hierarchy. After this method is called, the next scene
     * will be entered, including a call to {@link #setEnterAction(Runnable)}
     * if an enter action is set.
     *
     * @see #setEnterAction(Runnable)
     * @see Scene#Scene(ViewGroup, int, Context)
     * @see Scene#Scene(ViewGroup, ViewGroup)
     */
    public void setExitAction(Runnable action) {
        mExitAction = action;
    }

}