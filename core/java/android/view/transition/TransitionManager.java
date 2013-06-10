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

import android.util.ArrayMap;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import java.util.ArrayList;

/**
 * This class manages the set of transitions that fire when there is a
 * change of {@link Scene}. To use the manager, add scenes along with
 * transition objects with calls to {@link #setTransition(Scene, Transition)}
 * or {@link #setTransition(Scene, Scene, Transition)}. Setting specific
 * transitions for scene changes is not required; by default, a Scene change
 * will use {@link AutoTransition} to do something reasonable for most
 * situations. Specifying other transitions for particular scene changes is
 * only necessary if the application wants different transition behavior
 * in these situations.
 */
public class TransitionManager {
    // TODO: how to handle enter/exit?

    private static final Transition sDefaultTransition = new AutoTransition();
    private Transition mDefaultTransition = new AutoTransition();

    ArrayMap<Scene, Transition> mSceneTransitions = new ArrayMap<Scene, Transition>();
    ArrayMap<Scene, ArrayMap<Scene, Transition>> mScenePairTransitions =
            new ArrayMap<Scene, ArrayMap<Scene, Transition>>();
    static ArrayMap<ViewGroup, Transition> sRunningTransitions =
            new ArrayMap<ViewGroup, Transition>();
    private static ArrayList<ViewGroup> sPendingTransitions = new ArrayList<ViewGroup>();


    /**
     * Sets the transition to be used for any scene change for which no
     * other transition is explicitly set. The initial value is
     * an {@link AutoTransition} instance.
     *
     * @param transition The default transition to be used for scene changes.
     */
    public void setDefaultTransition(Transition transition) {
        mDefaultTransition = transition;
    }

    /**
     * Gets the current default transition. The initial value is an {@link
     * AutoTransition} instance.
     *
     * @return The current default transition.
     * @see #setDefaultTransition(Transition)
     */
    public Transition getDefaultTransition() {
        return mDefaultTransition;
    }

    /**
     * Sets a specific transition to occur when the given scene is entered.
     *
     * @param scene The scene which, when applied, will cause the given
     * transition to run.
     * @param transition The transition that will play when the given scene is
     * entered. A value of null will result in the default behavior of
     * using {@link AutoTransition}.
     */
    public void setTransition(Scene scene, Transition transition) {
        mSceneTransitions.put(scene, transition);
    }

    /**
     * Sets a specific transition to occur when the given pair of scenes is
     * exited/entered.
     *
     * @param fromScene The scene being exited when the given transition will
     * be run
     * @param toScene The scene being entered when the given transition will
     * be run
     * @param transition The transition that will play when the given scene is
     * entered. A value of null will result in the default behavior of
     * using {@link AutoTransition}.
     */
    public void setTransition(Scene fromScene, Scene toScene, Transition transition) {
        ArrayMap<Scene, Transition> sceneTransitionMap = mScenePairTransitions.get(toScene);
        if (sceneTransitionMap == null) {
            sceneTransitionMap = new ArrayMap<Scene, Transition>();
            mScenePairTransitions.put(toScene, sceneTransitionMap);
        }
        sceneTransitionMap.put(fromScene, transition);
    }

    /**
     * Returns the Transition for the given scene being entered. The result
     * depends not only on the given scene, but also the scene which the
     * {@link Scene#getSceneRoot() sceneRoot} of the Scene is currently in.
     *
     * @param scene The scene being entered
     * @return The Transition to be used for the given scene change. If no
     * Transition was specified for this scene change, {@link AutoTransition}
     * will be used instead.
     */
    private Transition getTransition(Scene scene) {
        Transition transition = null;
        ViewGroup sceneRoot = scene.getSceneRoot();
        if (sceneRoot != null) {
            // TODO: cached in Scene instead? long-term, cache in View itself
            Scene currScene = sceneRoot.getCurrentScene();
            if (currScene != null) {
                ArrayMap<Scene, Transition> sceneTransitionMap = mScenePairTransitions.get(scene);
                if (sceneTransitionMap != null) {
                    transition = sceneTransitionMap.get(currScene);
                    if (transition != null) {
                        return transition;
                    }
                }
            }
        }
        transition = mSceneTransitions.get(scene);
        return (transition != null) ? transition : new AutoTransition();
    }

    /**
     * This is where all of the work of a transition/scene-change is
     * orchestrated. This method captures the start values for the given
     * transition, exits the current Scene, enters the new scene, captures
     * the end values for the transition, and finally plays the
     * resulting values-populated transition.
     *
     * @param scene The scene being entered
     * @param transition The transition to play for this scene change
     */
    private static void changeScene(Scene scene, final Transition transition) {

        final ViewGroup sceneRoot = scene.getSceneRoot();

        sceneChangeSetup(sceneRoot, transition);

        scene.enter();

        sceneChangeRunTransition(sceneRoot, transition);
    }

    private static void sceneChangeRunTransition(final ViewGroup sceneRoot,
            final Transition transition) {
        if (transition != null) {
            final ViewTreeObserver observer = sceneRoot.getViewTreeObserver();
            observer.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                public boolean onPreDraw() {
                    sceneRoot.getViewTreeObserver().removeOnPreDrawListener(this);
                    // Add to running list, handle end to remove it
                    sRunningTransitions.put(sceneRoot, transition);
                    transition.addListener(new Transition.TransitionListenerAdapter() {
                        @Override
                        public void onTransitionEnd(Transition transition) {
                            sRunningTransitions.remove(sceneRoot);
                        }
                    });
                    transition.captureValues(sceneRoot, false);
                    transition.play(sceneRoot);
                    return true;
                }
            });
        }
    }

    private static void sceneChangeSetup(ViewGroup sceneRoot, Transition transition) {

        Transition runningTransition = sRunningTransitions.get(sceneRoot);
        if (runningTransition != null) {
            runningTransition.cancelTransition();
        }

        // Capture current values
        if (transition != null) {
            transition.captureValues(sceneRoot, true);
        }

        // Notify previous scene that it is being exited
        Scene previousScene = sceneRoot.getCurrentScene();
        if (previousScene != null) {
            previousScene.exit();
        }
    }

    /**
     * Change to the given scene, using the
     * appropriate transition for this particular scene change
     * (as specified to the TransitionManager, or the default
     * if no such transition exists).
     *
     * @param scene The Scene to change to
     */
    public void transitionTo(Scene scene) {
        // Auto transition if there is no transition declared for the Scene, but there is
        // a root or parent view
        changeScene(scene, getTransition(scene));

    }

    /**
     * Static utility method to simply change to the given scene using
     * the default transition for TransitionManager.
     *
     * @param scene The Scene to change to
     */
    public static void go(Scene scene) {
        changeScene(scene, sDefaultTransition);
    }

    /**
     * Static utility method to simply change to the given scene using
     * the given transition.
     *
     * <p>Passing in <code>null</code> for the transition parameter will
     * result in the scene changing without any transition running, and is
     * equivalent to calling {@link Scene#exit()} on the scene root's
     * {@link ViewGroup#getCurrentScene() current scene}, followed by
     * {@link Scene#enter()} on the scene specified by the <code>scene</code>
     * parameter.</p>
     *
     * @param scene The Scene to change to
     * @param transition The transition to use for this scene change. A
     * value of null causes the scene change to happen with no transition.
     */
    public static void go(Scene scene, Transition transition) {
        changeScene(scene, transition);
    }

    /**
     * Static utility method to simply change to a scene defined by the
     * code in the given runnable, which will be executed after
     * the current values have been captured for the transition.
     * This is equivalent to creating a Scene and calling {@link
     * Scene#setEnterAction(Runnable)} with the runnable, then calling
     * {@link #go(Scene, Transition)}. The transition used will be the
     * default provided by TransitionManager.
     *
     * @param sceneRoot The root of the View hierarchy used when this scene
     * runs a transition automatically.
     * @param action The runnable whose {@link Runnable#run() run()} method will
     * be called.
     */
    public static void go(ViewGroup sceneRoot, Runnable action) {
        Scene scene = new Scene(sceneRoot);
        scene.setEnterAction(action);
        changeScene(scene, sDefaultTransition);
    }

    /**
     * Static utility method to simply change to a scene defined by the
     * code in the given runnable, which will be executed after
     * the current values have been captured for the transition.
     * This is equivalent to creating a Scene and calling {@link
     * Scene#setEnterAction(Runnable)} with the runnable, then calling
     * {@link #go(Scene, Transition)}. The given transition will be
     * used to animate the changes.
     *
     * <p>Passing in <code>null</code> for the transition parameter will
     * result in the scene changing without any transition running, and is
     * equivalent to calling {@link Scene#exit()} on the scene root's
     * {@link ViewGroup#getCurrentScene() current scene}, followed by
     * {@link Scene#enter()} on a new scene specified by the
     * <code>action</code> parameter.</p>
     *
     * @param sceneRoot The root of the View hierarchy to run the transition on.
     * @param action The runnable whose {@link Runnable#run() run()} method will
     * be called.
     * @param transition The transition to use for this change. A
     * value of null causes the change to happen with no transition.
     */
    public static void go(ViewGroup sceneRoot, Runnable action, Transition transition) {
        Scene scene = new Scene(sceneRoot);
        scene.setEnterAction(action);
        changeScene(scene, transition);
    }

    /**
     * Static utility method to animate to a new scene defined by all changes within
     * the given scene root between calling this method and the next rendering frame.
     * Calling this method causes TransitionManager to capture current values in the
     * scene root and then post a request to run a transition on the next frame.
     * At that time, the new values in the scene root will be captured and changes
     * will be animated. There is no need to create a Scene; it is implied by
     * changes which take place between calling this method and the next frame when
     * the transition begins.
     *
     * <p>Calling this method several times before the next frame (for example, if
     * unrelated code also wants to make dynamic changes and run a transition on
     * the same scene root), only the first call will trigger capturing values
     * and exiting the current scene. Subsequent calls to the method with the
     * same scene root during the same frame will be ignored.</p>
     *
     * <p>Passing in <code>null</code> for the transition parameter will
     * cause the TransitionManager to use its default transition.</p>
     *
     * @param sceneRoot The root of the View hierarchy to run the transition on.
     * @param transition The transition to use for this change. A
     * value of null causes the TransitionManager to use the default transition.
     */
    public static void beginDelayedTransition(final ViewGroup sceneRoot, Transition transition) {

        if (!sPendingTransitions.contains(sceneRoot)) {
            sPendingTransitions.add(sceneRoot);
            if (transition == null) {
                transition = sDefaultTransition;
            }
            final Transition finalTransition = transition;
            sceneChangeSetup(sceneRoot, transition);
            sceneRoot.setCurrentScene(null);
            sceneRoot.postOnAnimation(new Runnable() {
                @Override
                public void run() {
                    sPendingTransitions.remove(sceneRoot);
                    sceneChangeRunTransition(sceneRoot, finalTransition);
                }
            });
        }
    }
}
