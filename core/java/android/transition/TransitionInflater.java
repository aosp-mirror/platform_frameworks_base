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

import android.annotation.TransitionRes;
import com.android.internal.R;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.util.ArrayMap;
import android.util.AttributeSet;
import android.util.Xml;
import android.view.InflateException;
import android.view.ViewGroup;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

/**
 * This class inflates scenes and transitions from resource files.
 *
 * Information on XML resource descriptions for transitions can be found for
 * {@link android.R.styleable#Transition}, {@link android.R.styleable#TransitionSet},
 * {@link android.R.styleable#TransitionTarget}, {@link android.R.styleable#Fade},
 * and {@link android.R.styleable#TransitionManager}.
 */
public class TransitionInflater {

    private static final Class<?>[] sConstructorSignature = new Class[] {
            Context.class, AttributeSet.class};
    private final static ArrayMap<String, Constructor> sConstructors =
            new ArrayMap<String, Constructor>();

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
    public Transition inflateTransition(@TransitionRes int resource) {
        //noinspection ResourceType
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
     * @param resource The resource id of the transition manager to load
     * @return The loaded TransitionManager object
     * @throws android.content.res.Resources.NotFoundException when the
     * transition manager cannot be loaded
     */
    public TransitionManager inflateTransitionManager(@TransitionRes int resource,
            ViewGroup sceneRoot) {
        //noinspection ResourceType
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
            AttributeSet attrs, Transition parent)
            throws XmlPullParserException, IOException {

        Transition transition = null;

        // Make sure we are on a start tag.
        int type;
        int depth = parser.getDepth();

        TransitionSet transitionSet = (parent instanceof TransitionSet)
                ? (TransitionSet) parent : null;

        while (((type=parser.next()) != XmlPullParser.END_TAG || parser.getDepth() > depth)
                && type != XmlPullParser.END_DOCUMENT) {

            if (type != XmlPullParser.START_TAG) {
                continue;
            }

            String  name = parser.getName();
            if ("fade".equals(name)) {
                transition = new Fade(mContext, attrs);
            } else if ("changeBounds".equals(name)) {
                transition = new ChangeBounds(mContext, attrs);
            } else if ("slide".equals(name)) {
                transition = new Slide(mContext, attrs);
            } else if ("explode".equals(name)) {
                transition = new Explode(mContext, attrs);
            } else if ("changeImageTransform".equals(name)) {
                transition = new ChangeImageTransform(mContext, attrs);
            } else if ("changeTransform".equals(name)) {
                transition = new ChangeTransform(mContext, attrs);
            } else if ("changeClipBounds".equals(name)) {
                transition = new ChangeClipBounds(mContext, attrs);
            } else if ("autoTransition".equals(name)) {
                transition = new AutoTransition(mContext, attrs);
            } else if ("recolor".equals(name)) {
                transition = new Recolor(mContext, attrs);
            } else if ("changeScroll".equals(name)) {
                transition = new ChangeScroll(mContext, attrs);
            } else if ("transitionSet".equals(name)) {
                transition = new TransitionSet(mContext, attrs);
            } else if ("transition".equals(name)) {
                transition = (Transition) createCustom(attrs, Transition.class, "transition");
            } else if ("targets".equals(name)) {
                getTargetIds(parser, attrs, parent);
            } else if ("arcMotion".equals(name)) {
                parent.setPathMotion(new ArcMotion(mContext, attrs));
            } else if ("pathMotion".equals(name)) {
                parent.setPathMotion((PathMotion)createCustom(attrs, PathMotion.class, "pathMotion"));
            } else if ("patternPathMotion".equals(name)) {
                parent.setPathMotion(new PatternPathMotion(mContext, attrs));
            } else {
                throw new RuntimeException("Unknown scene name: " + parser.getName());
            }
            if (transition != null) {
                if (!parser.isEmptyElementTag()) {
                    createTransitionFromXml(parser, attrs, transition);
                }
                if (transitionSet != null) {
                    transitionSet.addTransition(transition);
                    transition = null;
                } else if (parent != null) {
                    throw new InflateException("Could not add transition to another transition.");
                }
            }
        }

        return transition;
    }

    private Object createCustom(AttributeSet attrs, Class expectedType, String tag) {
        String className = attrs.getAttributeValue(null, "class");

        if (className == null) {
            throw new InflateException(tag + " tag must have a 'class' attribute");
        }

        try {
            synchronized (sConstructors) {
                Constructor constructor = sConstructors.get(className);
                if (constructor == null) {
                    Class c = mContext.getClassLoader().loadClass(className)
                            .asSubclass(expectedType);
                    if (c != null) {
                        constructor = c.getConstructor(sConstructorSignature);
                        constructor.setAccessible(true);
                        sConstructors.put(className, constructor);
                    }
                }
                return constructor.newInstance(mContext, attrs);
            }
        } catch (InstantiationException e) {
            throw new InflateException("Could not instantiate " + expectedType + " class " +
                    className, e);
        } catch (ClassNotFoundException e) {
            throw new InflateException("Could not instantiate " + expectedType + " class " +
                    className, e);
        } catch (InvocationTargetException e) {
            throw new InflateException("Could not instantiate " + expectedType + " class " +
                    className, e);
        } catch (NoSuchMethodException e) {
            throw new InflateException("Could not instantiate " + expectedType + " class " +
                    className, e);
        } catch (IllegalAccessException e) {
            throw new InflateException("Could not instantiate " + expectedType + " class " +
                    className, e);
        }
    }

    private void getTargetIds(XmlPullParser parser,
            AttributeSet attrs, Transition transition) throws XmlPullParserException, IOException {

        // Make sure we are on a start tag.
        int type;
        int depth = parser.getDepth();

        while (((type=parser.next()) != XmlPullParser.END_TAG || parser.getDepth() > depth)
                && type != XmlPullParser.END_DOCUMENT) {

            if (type != XmlPullParser.START_TAG) {
                continue;
            }

            String  name = parser.getName();
            if (name.equals("target")) {
                TypedArray a = mContext.obtainStyledAttributes(attrs, R.styleable.TransitionTarget);
                int id = a.getResourceId(R.styleable.TransitionTarget_targetId, 0);
                String transitionName;
                if (id != 0) {
                    transition.addTarget(id);
                } else if ((id = a.getResourceId(R.styleable.TransitionTarget_excludeId, 0)) != 0) {
                    transition.excludeTarget(id, true);
                } else if ((transitionName = a.getString(R.styleable.TransitionTarget_targetName))
                        != null) {
                    transition.addTarget(transitionName);
                } else if ((transitionName = a.getString(R.styleable.TransitionTarget_excludeName))
                        != null) {
                    transition.excludeTarget(transitionName, true);
                } else {
                    String className = a.getString(R.styleable.TransitionTarget_excludeClass);
                    try {
                        if (className != null) {
                            Class clazz = Class.forName(className);
                            transition.excludeTarget(clazz, true);
                        } else if ((className =
                                a.getString(R.styleable.TransitionTarget_targetClass)) != null) {
                            Class clazz = Class.forName(className);
                            transition.addTarget(clazz);
                        }
                    } catch (ClassNotFoundException e) {
                        throw new RuntimeException("Could not create " + className, e);
                    }
                }
            } else {
                throw new RuntimeException("Unknown scene name: " + parser.getName());
            }
        }
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

        TypedArray a = mContext.obtainStyledAttributes(attrs, R.styleable.TransitionManager);
        int transitionId = a.getResourceId(R.styleable.TransitionManager_transition, -1);
        int fromId = a.getResourceId(R.styleable.TransitionManager_fromScene, -1);
        Scene fromScene = (fromId < 0) ? null: Scene.getSceneForLayout(sceneRoot, fromId, mContext);
        int toId = a.getResourceId(R.styleable.TransitionManager_toScene, -1);
        Scene toScene = (toId < 0) ? null : Scene.getSceneForLayout(sceneRoot, toId, mContext);

        if (transitionId >= 0) {
            Transition transition = inflateTransition(transitionId);
            if (transition != null) {
                if (toScene == null) {
                    throw new RuntimeException("No toScene for transition ID " + transitionId);
                }
                if (fromScene == null) {
                    transitionManager.setTransition(toScene, transition);
                } else {
                    transitionManager.setTransition(fromScene, toScene, transition);
                }
            }
        }
        a.recycle();
    }
}
