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

package android.transition;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.util.AttributeSet;
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
 *
 * Information on XML resource descriptions for transitions can be found for
 * {@link android.R.styleable#Transition}, {@link android.R.styleable#TransitionSet},
 * {@link android.R.styleable#TransitionTarget}, {@link android.R.styleable#Fade},
 * and {@link android.R.styleable#TransitionManager}.
 */
public class TransitionInflater {

    private Context mContext;

    private TransitionInflater(Context context) {
        mContext = context;
    }

    /**
     * Obtains the TransitionInflater from the given context.
     */
    public static TransitionInflater from(Context context) {
        return new TransitionInflater(context);
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

    //
    // Transition loading
    //

    private Transition createTransitionFromXml(XmlPullParser parser,
            AttributeSet attrs, TransitionSet transitionSet)
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
                TypedArray a = mContext.obtainStyledAttributes(attrs,
                        com.android.internal.R.styleable.Fade);
                int fadingMode = a.getInt(com.android.internal.R.styleable.Fade_fadingMode,
                        Fade.IN | Fade.OUT);
                transition = new Fade(fadingMode);
                newTransition = true;
            } else if ("changeBounds".equals(name)) {
                transition = new ChangeBounds();
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
            } else if ("transitionSet".equals(name)) {
                transition = new TransitionSet();
                TypedArray a = mContext.obtainStyledAttributes(attrs,
                        com.android.internal.R.styleable.TransitionSet);
                int ordering = a.getInt(
                        com.android.internal.R.styleable.TransitionSet_transitionOrdering,
                        TransitionSet.ORDERING_TOGETHER);
                ((TransitionSet) transition).setOrdering(ordering);
                createTransitionFromXml(parser, attrs, ((TransitionSet) transition));
                a.recycle();
                newTransition = true;
            } else if ("targets".equals(name)) {
                if (parser.getDepth() - 1 > depth && transition != null) {
                    // We're inside the child tag - add targets to the child
                    getTargetIds(parser, attrs, transition);
                } else if (parser.getDepth() - 1 == depth && transitionSet != null) {
                    // add targets to the set
                    getTargetIds(parser, attrs, transitionSet);
                }
            }
            if (transition != null || "targets".equals(name)) {
                if (newTransition) {
                    loadTransition(transition, attrs);
                    if (transitionSet != null) {
                        transitionSet.addTransition(transition);
                    }
                }
            } else {
                throw new RuntimeException("Unknown scene name: " + parser.getName());
            }
        }

        return transition;
    }

    private void getTargetIds(XmlPullParser parser,
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
                        com.android.internal.R.styleable.TransitionTarget);
                int id = a.getResourceId(
                        com.android.internal.R.styleable.TransitionTarget_targetId, -1);
                if (id >= 0) {
                    targetIds.add(id);
                }
            } else {
                throw new RuntimeException("Unknown scene name: " + parser.getName());
            }
        }
        int numTargets = targetIds.size();
        if (numTargets > 0) {
            for (int i = 0; i < numTargets; ++i) {
                transition.addTarget(targetIds.get(i));
            }
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
        long startDelay = a.getInt(com.android.internal.R.styleable.Transition_startDelay, -1);
        if (startDelay > 0) {
            transition.setStartDelay(startDelay);
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
            TransitionManager transitionManager) throws Resources.NotFoundException {

        TypedArray a = mContext.obtainStyledAttributes(attrs,
                com.android.internal.R.styleable.TransitionManager);
        int transitionId = a.getResourceId(
                com.android.internal.R.styleable.TransitionManager_transition, -1);
        Scene fromScene = null, toScene = null;
        int fromId = a.getResourceId(
                com.android.internal.R.styleable.TransitionManager_fromScene, -1);
        if (fromId >= 0) fromScene = Scene.getSceneForLayout(sceneRoot, fromId, mContext);
        int toId = a.getResourceId(
                com.android.internal.R.styleable.TransitionManager_toScene, -1);
        if (toId >= 0) toScene = Scene.getSceneForLayout(sceneRoot, toId, mContext);
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
}
