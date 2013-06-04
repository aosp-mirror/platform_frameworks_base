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
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.util.ArrayMap;
import android.util.AttributeSet;
import android.util.SparseArray;
import android.util.Xml;
import android.view.InflateException;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;

/**
 * This class inflates scenes and transitions from resource files.
 */
public class TransitionInflater {

    // We only need one inflater for any given context. Also, this allows us to associate
    // ids with unique instances per-Context, used to avoid re-inflating
    // already-inflated resources into new/different instances
    private static final ArrayMap<Context, TransitionInflater> sInflaterMap =
            new ArrayMap<Context, TransitionInflater>();

    private Context mContext;
    // TODO: do we need id maps for transitions and transitionMgrs as well?
    SparseArray<Scene> mScenes = new SparseArray<Scene>();

    private TransitionInflater(Context context) {
        mContext = context;
    }

    /**
     * Obtains the TransitionInflater from the given context.
     */
    public static TransitionInflater from(Context context) {
        TransitionInflater inflater = sInflaterMap.get(context);
        if (inflater != null) {
            return inflater;
        }
        inflater = new TransitionInflater(context);
        sInflaterMap.put(context, inflater);
        return inflater;
    }

    /**
     * Loads a {@link Transition} object from a resource
     *
     * @param resource The resource id of the transition to load
     * @return The loaded Transition object
     * @throws android.content.res.Resources.NotFoundException when the
     * transition cannot be loaded
     */
    public Transition inflateTransition(int resource) {
        XmlResourceParser parser =  mContext.getResources().getXml(resource);
        try {
            return createTransitionFromXml(parser, Xml.asAttributeSet(parser), null);
        } catch (XmlPullParserException e) {
            InflateException ex = new InflateException(e.getMessage());
            ex.initCause(e);
            throw ex;
        } catch (IOException e) {
            InflateException ex = new InflateException(
                    parser.getPositionDescription()
                            + ": " + e.getMessage());
            ex.initCause(e);
            throw ex;
        } finally {
            parser.close();
        }
    }

    /**
     * Loads a {@link TransitionManager} object from a resource
     *
     *
     *
     * @param resource The resource id of the transition manager to load
     * @return The loaded TransitionManager object
     * @throws android.content.res.Resources.NotFoundException when the
     * transition manager cannot be loaded
     */
    public TransitionManager inflateTransitionManager(int resource, ViewGroup sceneRoot) {
        XmlResourceParser parser =  mContext.getResources().getXml(resource);
        try {
            return createTransitionManagerFromXml(parser, Xml.asAttributeSet(parser), sceneRoot);
        } catch (XmlPullParserException e) {
            InflateException ex = new InflateException(e.getMessage());
            ex.initCause(e);
            throw ex;
        } catch (IOException e) {
            InflateException ex = new InflateException(
                    parser.getPositionDescription()
                            + ": " + e.getMessage());
            ex.initCause(e);
            throw ex;
        } finally {
            parser.close();
        }
    }

    /**
     * Loads a {@link Scene} object from a resource
     *
     * @param resource The resource id of the scene to load
     * @return The loaded Scene object
     * @throws android.content.res.Resources.NotFoundException when the scene
     * cannot be loaded
     */
    public Scene inflateScene(int resource, ViewGroup parent) {
        Scene scene = mScenes.get(resource);
        if (scene != null) {
            return scene;
        }
        XmlResourceParser parser =  mContext.getResources().getXml(resource);
        try {
            scene = createSceneFromXml(parser, Xml.asAttributeSet(parser), parent);
            mScenes.put(resource, scene);
            return scene;
        } catch (XmlPullParserException e) {
            InflateException ex = new InflateException(e.getMessage());
            ex.initCause(e);
            throw ex;
        } catch (IOException e) {
            InflateException ex = new InflateException(
                    parser.getPositionDescription()
                            + ": " + e.getMessage());
            ex.initCause(e);
            throw ex;
        } finally {
            parser.close();
        }
    }


    //
    // Transition loading
    //

    private Transition createTransitionFromXml(XmlPullParser parser,
            AttributeSet attrs, TransitionGroup transitionGroup)
            throws XmlPullParserException, IOException {

        Transition transition = null;

        // Make sure we are on a start tag.
        int type;
        int depth = parser.getDepth();

        while (((type=parser.next()) != XmlPullParser.END_TAG || parser.getDepth() > depth)
                && type != XmlPullParser.END_DOCUMENT) {

            boolean newTransition = false;

            if (type != XmlPullParser.START_TAG) {
                continue;
            }

            String  name = parser.getName();
            if ("fade".equals(name)) {
                transition = new Fade();
                newTransition = true;
            } else if ("move".equals(name)) {
                transition = new Move();
                newTransition = true;
            } else if ("slide".equals(name)) {
                transition = new Slide();
                newTransition = true;
            } else if ("autoTransition".equals(name)) {
                transition = new AutoTransition();
                newTransition = true;
            } else if ("recolor".equals(name)) {
                transition = new Recolor();
                newTransition = true;
            } else if ("transitionGroup".equals(name)) {
                transition = new TransitionGroup();
                createTransitionFromXml(parser, attrs, ((TransitionGroup) transition));
                newTransition = true;
            } else if ("targets".equals(name)) {
                if (parser.getDepth() - 1 > depth && transition != null) {
                    // We're inside the child tag - add targets to the child
                    getTargetIDs(parser, attrs, transition);
                } else if (parser.getDepth() - 1 == depth && transitionGroup != null) {
                    // add targets to the group
                    getTargetIDs(parser, attrs, transitionGroup);
                }
            }
            if (transition != null || "targets".equals(name)) {
                if (newTransition) {
                    loadTransition(transition, attrs);
                    if (transitionGroup != null) {
                        transitionGroup.addTransitions(transition);
                    }
                }
            } else {
                throw new RuntimeException("Unknown scene name: " + parser.getName());
            }
        }

        return transition;
    }

    private void getTargetIDs(XmlPullParser parser,
            AttributeSet attrs, Transition transition) throws XmlPullParserException, IOException {

        // Make sure we are on a start tag.
        int type;
        int depth = parser.getDepth();

        ArrayList<Integer> targetIds = new ArrayList<Integer>();
        while (((type=parser.next()) != XmlPullParser.END_TAG || parser.getDepth() > depth)
                && type != XmlPullParser.END_DOCUMENT) {

            if (type != XmlPullParser.START_TAG) {
                continue;
            }

            String  name = parser.getName();
            if (name.equals("target")) {
                TypedArray a = mContext.obtainStyledAttributes(attrs,
                        com.android.internal.R.styleable.Transition);
                int id = a.getResourceId(com.android.internal.R.styleable.Transition_targetID, -1);
                if (id >= 0) {
                    targetIds.add(id);
                }
            } else {
                throw new RuntimeException("Unknown scene name: " + parser.getName());
            }
        }
        int numTargets = targetIds.size();
        if (numTargets > 0) {
            int[] targetsArray = new int[numTargets];
            for (int i = 0; i < targetIds.size(); ++i) {
                targetsArray[i] = targetIds.get(i);
            }
            transition.setTargetIds(targetsArray);
        }
    }

    private Transition loadTransition(Transition transition, AttributeSet attrs)
            throws Resources.NotFoundException {

        TypedArray a =
                mContext.obtainStyledAttributes(attrs, com.android.internal.R.styleable.Transition);
        long duration = a.getInt(com.android.internal.R.styleable.Transition_duration, -1);
        if (duration >= 0) {
            transition.setDuration(duration);
        }
        long startOffset = a.getInt(com.android.internal.R.styleable.Transition_startOffset, -1);
        if (startOffset > 0) {
            transition.setStartDelay(startOffset);
        }
        final int resID =
                a.getResourceId(com.android.internal.R.styleable.Animator_interpolator, 0);
        if (resID > 0) {
            transition.setInterpolator(AnimationUtils.loadInterpolator(mContext, resID));
        }
        a.recycle();
        return transition;
    }

    //
    // TransitionManager loading
    //

    private TransitionManager createTransitionManagerFromXml(XmlPullParser parser,
            AttributeSet attrs, ViewGroup sceneRoot) throws XmlPullParserException, IOException {

        // Make sure we are on a start tag.
        int type;
        int depth = parser.getDepth();
        TransitionManager transitionManager = null;

        while (((type=parser.next()) != XmlPullParser.END_TAG || parser.getDepth() > depth)
                && type != XmlPullParser.END_DOCUMENT) {

            if (type != XmlPullParser.START_TAG) {
                continue;
            }

            String  name = parser.getName();
            if (name.equals("transitionManager")) {
                transitionManager = new TransitionManager();
            } else if (name.equals("transition") && (transitionManager != null)) {
                loadTransition(attrs, sceneRoot, transitionManager);
            } else {
                throw new RuntimeException("Unknown scene name: " + parser.getName());
            }
        }
        return transitionManager;
    }

    private void loadTransition(AttributeSet attrs, ViewGroup sceneRoot,
            TransitionManager transitionManager)
            throws Resources.NotFoundException {

        TypedArray a = mContext.obtainStyledAttributes(attrs,
                com.android.internal.R.styleable.TransitionManager);
        int transitionId = attrs.getAttributeResourceValue(
                com.android.internal.R.styleable.TransitionManager_transition, -1);
        Scene fromScene = null, toScene = null;
        int fromId = attrs.getAttributeResourceValue(
                com.android.internal.R.styleable.TransitionManager_fromScene, -1);
        if (fromId >= 0) fromScene = inflateScene(fromId, sceneRoot);
        int toId = attrs.getAttributeResourceValue(
                com.android.internal.R.styleable.TransitionManager_toScene, -1);
        if (toId >= 0) toScene = inflateScene(toId, sceneRoot);
        if (transitionId >= 0) {
            Transition transition = inflateTransition(transitionId);
            if (transition != null) {
                if (fromScene != null) {
                    if (toScene == null){
                        throw new RuntimeException("No matching toScene for given fromScene " +
                                "for transition ID " + transitionId);
                    } else {
                        transitionManager.setTransition(fromScene, toScene, transition);
                    }
                } else if (toId >= 0) {
                    transitionManager.setTransition(toScene, transition);
                }
            }
        }
        a.recycle();
    }

    //
    // Scene loading
    //

    private Scene createSceneFromXml(XmlPullParser parser, AttributeSet attrs, ViewGroup parent)
            throws XmlPullParserException, IOException {
        Scene scene = null;

        // Make sure we are on a start tag.
        int type;
        int depth = parser.getDepth();

        while (((type=parser.next()) != XmlPullParser.END_TAG || parser.getDepth() > depth)
                && type != XmlPullParser.END_DOCUMENT) {

            if (type != XmlPullParser.START_TAG) {
                continue;
            }

            String  name = parser.getName();
            if (name.equals("scene")) {
                scene = loadScene(attrs, parent);
            } else {
                throw new RuntimeException("Unknown scene name: " + parser.getName());
            }
        }

        return scene;
    }

    private Scene loadScene(AttributeSet attrs, ViewGroup parent)
            throws Resources.NotFoundException {

        Scene scene;
        TypedArray a = mContext.obtainStyledAttributes(attrs,
                com.android.internal.R.styleable.Scene);
        int layoutId = a.getResourceId(com.android.internal.R.styleable.Scene_layout, -1);
        if (layoutId >= 0) {
            scene = new Scene(parent, layoutId, mContext);
        } else {
            scene = new Scene(parent);
        }
        a.recycle();
        return scene;
    }
}
