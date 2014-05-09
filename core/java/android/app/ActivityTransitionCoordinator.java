/*
 * Copyright (C) 2014 The Android Open Source Project
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

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.ResultReceiver;
import android.transition.Transition;
import android.transition.TransitionManager;
import android.transition.TransitionSet;
import android.util.ArrayMap;
import android.util.Pair;
import android.util.SparseArray;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroupOverlay;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.widget.ImageView;

import java.util.ArrayList;
import java.util.Collection;

/**
 * Base class for ExitTransitionCoordinator and EnterTransitionCoordinator, classes
 * that manage activity transitions and the communications coordinating them between
 * Activities. The ExitTransitionCoordinator is created in the
 * ActivityOptions#makeSceneTransitionAnimation. The EnterTransitionCoordinator
 * is created by ActivityOptions#createEnterActivityTransition by Activity when the window is
 * attached.
 *
 * Typical startActivity goes like this:
 * 1) ExitTransitionCoordinator created with ActivityOptions#makeSceneTransitionAnimation
 * 2) Activity#startActivity called and that calls startExit() through
 *    ActivityOptions#dispatchStartExit
 *    - Exit transition starts by setting transitioning Views to INVISIBLE
 * 3) Launched Activity starts, creating an EnterTransitionCoordinator.
 *    - The Window is made translucent
 *    - The Window background alpha is set to 0
 *    - The transitioning views are made INVISIBLE
 *    - MSG_SET_LISTENER is sent back to the ExitTransitionCoordinator.
 * 4) The shared element transition completes.
 *    - MSG_TAKE_SHARED_ELEMENTS is sent to the EnterTransitionCoordinator
 * 5) The MSG_TAKE_SHARED_ELEMENTS is received by the EnterTransitionCoordinator.
 *    - Shared elements are made VISIBLE
 *    - Shared elements positions and size are set to match the end state of the calling
 *      Activity.
 *    - The shared element transition is started
 *    - If the window allows overlapping transitions, the views transition is started by setting
 *      the entering Views to VISIBLE and the background alpha is animated to opaque.
 *    - MSG_HIDE_SHARED_ELEMENTS is sent to the ExitTransitionCoordinator
 * 6) MSG_HIDE_SHARED_ELEMENTS is received by the ExitTransitionCoordinator
 *    - The shared elements are made INVISIBLE
 * 7) The exit transition completes in the calling Activity.
 *    - MSG_EXIT_TRANSITION_COMPLETE is sent to the EnterTransitionCoordinator.
 * 8) The MSG_EXIT_TRANSITION_COMPLETE is received by the EnterTransitionCoordinator.
 *    - If the window doesn't allow overlapping enter transitions, the enter transition is started
 *      by setting entering views to VISIBLE and the background is animated to opaque.
 * 9) The background opacity animation completes.
 *    - The window is made opaque
 * 10) The calling Activity gets an onStop() call
 *    - onActivityStopped() is called and all exited Views are made VISIBLE.
 *
 * Typical finishWithTransition goes like this:
 * 1) finishWithTransition() calls startExit()
 *    - The Window start transitioning to Translucent
 *    - If no background exists, a black background is substituted
 *    - MSG_PREPARE_RESTORE is sent to the ExitTransitionCoordinator
 *    - The shared elements in the scene are matched against those shared elements
 *      that were sent by comparing the names.
 *    - The exit transition is started by setting Views to INVISIBLE.
 * 2) MSG_PREPARE_RESTORE is received by the EnterTransitionCoordinator
 *    - All transitioning views are made VISIBLE to reverse what was done when onActivityStopped()
 *      was called
 * 3) The Window is made translucent and a callback is received
 *    - The background alpha is animated to 0
 * 4) The background alpha animation completes
 * 5) The shared element transition completes
 *    - After both 4 & 5 complete, MSG_TAKE_SHARED_ELEMENTS is sent to the
 *      ExitTransitionCoordinator
 * 6) MSG_TAKE_SHARED_ELEMENTS is received by ExitTransitionCoordinator
 *    - Shared elements are made VISIBLE
 *    - Shared elements positions and size are set to match the end state of the calling
 *      Activity.
 *    - The shared element transition is started
 *    - If the window allows overlapping transitions, the views transition is started by setting
 *      the entering Views to VISIBLE.
 *    - MSG_HIDE_SHARED_ELEMENTS is sent to the EnterTransitionCoordinator
 * 7) MSG_HIDE_SHARED_ELEMENTS is received by the EnterTransitionCoordinator
 *    - The shared elements are made INVISIBLE
 * 8) The exit transition completes in the finishing Activity.
 *    - MSG_EXIT_TRANSITION_COMPLETE is sent to the ExitTransitionCoordinator.
 *    - finish() is called on the exiting Activity
 * 9) The MSG_EXIT_TRANSITION_COMPLETE is received by the ExitTransitionCoordinator.
 *    - If the window doesn't allow overlapping enter transitions, the enter transition is started
 *      by setting entering views to VISIBLE.
 */
abstract class ActivityTransitionCoordinator extends ResultReceiver {
    private static final String TAG = "ActivityTransitionCoordinator";

    /**
     * The names of shared elements that are transitioned to the started Activity.
     * This is also the name of shared elements that the started Activity accepted.
     */
    public static final String KEY_SHARED_ELEMENT_NAMES = "android:shared_element_names";

    public static final String KEY_SHARED_ELEMENT_STATE = "android:shared_element_state";

    /**
     * For Activity transitions, the called Activity's listener to receive calls
     * when transitions complete.
     */
    static final String KEY_TRANSITION_RESULTS_RECEIVER = "android:transitionTargetListener";

    private static final String KEY_SCREEN_X = "shared_element:screenX";
    private static final String KEY_SCREEN_Y = "shared_element:screenY";
    private static final String KEY_TRANSLATION_Z = "shared_element:translationZ";
    private static final String KEY_WIDTH = "shared_element:width";
    private static final String KEY_HEIGHT = "shared_element:height";
    private static final String KEY_NAME = "shared_element:name";
    private static final String KEY_BITMAP = "shared_element:bitmap";
    private static final String KEY_SCALE_TYPE = "shared_element:scaleType";
    private static final String KEY_IMAGE_MATRIX = "shared_element:imageMatrix";

    private static final ImageView.ScaleType[] SCALE_TYPE_VALUES = ImageView.ScaleType.values();

    /**
     * Sent by the exiting coordinator (either EnterTransitionCoordinator
     * or ExitTransitionCoordinator) after the shared elements have
     * become stationary (shared element transition completes). This tells
     * the remote coordinator to take control of the shared elements and
     * that animations may begin. The remote Activity won't start entering
     * until this message is received, but may wait for
     * MSG_EXIT_TRANSITION_COMPLETE if allowOverlappingTransitions() is true.
     */
    public static final int MSG_SET_LISTENER = 100;

    /**
     * Sent by the entering coordinator to tell the exiting coordinator
     * to hide its shared elements after it has started its shared
     * element transition. This is temporary until the
     * interlock of shared elements is figured out.
     */
    public static final int MSG_HIDE_SHARED_ELEMENTS = 101;

    /**
     * Sent by the EnterTransitionCoordinator to tell the
     * ExitTransitionCoordinator to hide all of its exited views after
     * MSG_ACTIVITY_STOPPED has caused them all to show.
     */
    public static final int MSG_PREPARE_RESTORE = 102;

    /**
     * Sent by the exiting Activity in ActivityOptions#dispatchActivityStopped
     * to leave the Activity in a good state after it has been hidden.
     */
    public static final int MSG_ACTIVITY_STOPPED = 103;

    /**
     * Sent by the exiting coordinator (either EnterTransitionCoordinator
     * or ExitTransitionCoordinator) after the shared elements have
     * become stationary (shared element transition completes). This tells
     * the remote coordinator to take control of the shared elements and
     * that animations may begin. The remote Activity won't start entering
     * until this message is received, but may wait for
     * MSG_EXIT_TRANSITION_COMPLETE if allowOverlappingTransitions() is true.
     */
    public static final int MSG_TAKE_SHARED_ELEMENTS = 104;

    /**
     * Sent by the exiting coordinator (either
     * EnterTransitionCoordinator or ExitTransitionCoordinator) after
     * the exiting Views have finished leaving the scene. This will
     * be ignored if allowOverlappingTransitions() is true on the
     * remote coordinator. If it is false, it will trigger the enter
     * transition to start.
     */
    public static final int MSG_EXIT_TRANSITION_COMPLETE = 105;

    /**
     * Sent by Activity#startActivity to begin the exit transition.
     */
    public static final int MSG_START_EXIT_TRANSITION = 106;

    private Window mWindow;
    private ArrayList<View> mSharedElements = new ArrayList<View>();
    private ArrayList<String> mTargetSharedNames = new ArrayList<String>();
    private ActivityOptions.ActivityTransitionListener mListener =
            new ActivityOptions.ActivityTransitionListener();
    private ArrayList<View> mEnteringViews;
    private ResultReceiver mRemoteResultReceiver;
    private boolean mNotifiedSharedElementTransitionComplete;
    private boolean mNotifiedExitTransitionComplete;
    private boolean mSharedElementTransitionStarted;

    private FixedEpicenterCallback mEpicenterCallback = new FixedEpicenterCallback();

    private Transition.TransitionListener mSharedElementListener =
            new Transition.TransitionListenerAdapter() {
        @Override
        public void onTransitionEnd(Transition transition) {
            transition.removeListener(this);
            onSharedElementTransitionEnd();
        }
    };

    private Transition.TransitionListener mExitListener =
            new Transition.TransitionListenerAdapter() {
        @Override
        public void onTransitionEnd(Transition transition) {
            transition.removeListener(this);
            onExitTransitionEnd();
        }
    };

    public ActivityTransitionCoordinator(Window window)
    {
        super(new Handler());
        mWindow = window;
    }

    // -------------------- ResultsReceiver Overrides ----------------------
    @Override
    protected void onReceiveResult(int resultCode, Bundle resultData) {
        switch (resultCode) {
            case MSG_SET_LISTENER:
                ResultReceiver resultReceiver
                        = resultData.getParcelable(KEY_TRANSITION_RESULTS_RECEIVER);
                setRemoteResultReceiver(resultReceiver);
                onSetResultReceiver();
                break;
            case MSG_HIDE_SHARED_ELEMENTS:
                onHideSharedElements();
                break;
            case MSG_PREPARE_RESTORE:
                onPrepareRestore();
                break;
            case MSG_EXIT_TRANSITION_COMPLETE:
                if (!mSharedElementTransitionStarted) {
                    send(resultCode, resultData);
                } else {
                    onRemoteSceneExitComplete();
                }
                break;
            case MSG_TAKE_SHARED_ELEMENTS:
                ArrayList<String> sharedElementNames
                        = resultData.getStringArrayList(KEY_SHARED_ELEMENT_NAMES);
                Bundle sharedElementState = resultData.getBundle(KEY_SHARED_ELEMENT_STATE);
                onTakeSharedElements(sharedElementNames, sharedElementState);
                break;
            case MSG_ACTIVITY_STOPPED:
                onActivityStopped();
                break;
            case MSG_START_EXIT_TRANSITION:
                startExit();
                break;
        }
    }

    // -------------------- calls that can be overridden by subclasses --------------------

    /**
     * Called when MSG_SET_LISTENER is received. This will only be received by
     * ExitTransitionCoordinator.
     */
    protected void onSetResultReceiver() {}

    /**
     * Called when MSG_HIDE_SHARED_ELEMENTS is received
     */
    protected void onHideSharedElements() {
        setViewVisibility(getSharedElements(), View.INVISIBLE);
        mListener.onSharedElementTransferred(getSharedElementNames(), getSharedElements());
    }

    /**
     * Called when MSG_PREPARE_RESTORE is called. This will only be received by
     * ExitTransitionCoordinator.
     */
    protected void onPrepareRestore() {
        mListener.onEnterReady();
    }

    /**
     * Called when MSG_EXIT_TRANSITION_COMPLETE is received -- the remote coordinator has
     * completed its exit transition. This can be called by the ExitTransitionCoordinator when
     * starting an Activity or EnterTransitionCoordinator when called with finishWithTransition.
     */
    protected void onRemoteSceneExitComplete() {
        if (!allowOverlappingTransitions()) {
            Transition transition = beginTransition(mEnteringViews, false, true, true);
            onStartEnterTransition(transition, mEnteringViews);
        }
        mListener.onRemoteExitComplete();
    }

    /**
     * Called when MSG_TAKE_SHARED_ELEMENTS is received. This means that the shared elements are
     * in a stable state and ready to move to the Window.
     * @param sharedElementNames The names of the shared elements to move.
     * @param state Contains the shared element states (size & position)
     */
    protected void onTakeSharedElements(ArrayList<String> sharedElementNames, Bundle state) {
        setSharedElements();
        reconcileSharedElements(sharedElementNames);
        mEnteringViews.removeAll(mSharedElements);
        final ArrayList<View> accepted = new ArrayList<View>();
        final ArrayList<View> rejected = new ArrayList<View>();
        createSharedElementImages(accepted, rejected, sharedElementNames, state);
        ArrayMap<ImageView, Pair<ImageView.ScaleType, Matrix>> originalImageViewState =
                setSharedElementState(state, accepted);
        handleRejected(rejected);

        if (getViewsTransition() != null) {
            setViewVisibility(mEnteringViews, View.INVISIBLE);
        }
        setViewVisibility(mSharedElements, View.VISIBLE);
        Transition transition = beginTransition(mEnteringViews, true, allowOverlappingTransitions(),
                true);
        setOriginalImageViewState(originalImageViewState);

        if (allowOverlappingTransitions()) {
            onStartEnterTransition(transition, mEnteringViews);
        }

        mRemoteResultReceiver.send(MSG_HIDE_SHARED_ELEMENTS, null);
    }

    /**
     * Called when MSG_ACTIVITY_STOPPED is received. This is received when Activity.onStop is
     * called after running startActivity* is called using an Activity Transition.
     */
    protected void onActivityStopped() {}

    /**
     * Called when the start transition is ready to run. This may be immediately after
     * MSG_TAKE_SHARED_ELEMENTS or MSG_EXIT_TRANSITION_COMPLETE, depending on whether
     * overlapping transitions are allowed.
     * @param transition The transition currently started.
     * @param enteringViews The views entering the scene. This won't include shared elements.
     */
    protected void onStartEnterTransition(Transition transition, ArrayList<View> enteringViews) {
        if (getViewsTransition() != null) {
            setViewVisibility(enteringViews, View.VISIBLE);
        }
        mEnteringViews = null;
        mListener.onStartEnterTransition(getSharedElementNames(), getSharedElements());
    }

    /**
     * Called when the exit transition has started.
     * @param exitingViews The views leaving the scene. This won't include shared elements.
     */
    protected void onStartExitTransition(ArrayList<View> exitingViews) {}

    /**
     * Called during the exit when the shared element transition has completed.
     */
    protected void onSharedElementTransitionEnd() {
        Bundle bundle = new Bundle();
        int[] tempLoc = new int[2];
        for (int i = 0; i < mSharedElements.size(); i++) {
            View sharedElement = mSharedElements.get(i);
            String name = mTargetSharedNames.get(i);
            captureSharedElementState(sharedElement, name, bundle, tempLoc);
        }
        Bundle allValues = new Bundle();
        allValues.putStringArrayList(KEY_SHARED_ELEMENT_NAMES, getSharedElementNames());
        allValues.putBundle(KEY_SHARED_ELEMENT_STATE, bundle);
        sharedElementTransitionComplete(allValues);
        mListener.onSharedElementExitTransitionComplete();
    }

    /**
     * Called after the shared element transition is complete to pass the shared element state
     * to the remote coordinator.
     * @param bundle The Bundle to send to the coordinator containing the shared element state.
     */
    protected abstract void sharedElementTransitionComplete(Bundle bundle);

    /**
     * Called when the exit transition finishes.
     */
    protected void onExitTransitionEnd() {
        mListener.onExitTransitionComplete();
    }

    /**
     * Called to start the exit transition. Launched from ActivityOptions#dispatchStartExit
     */
    protected abstract void startExit();

    /**
     * A non-null transition indicates that the Views of the Window should be made INVISIBLE.
     * @return The Transition used to cause transitioning views to either enter or exit the scene.
     */
    protected abstract Transition getViewsTransition();

    /**
     * @return The Transition used to move the shared elements from the start position and size
     * to the end position and size.
     */
    protected abstract Transition getSharedElementTransition();

    /**
     * @return When the enter transition should overlap with the exit transition of the
     * remote controller.
     */
    protected abstract boolean allowOverlappingTransitions();

    // called by subclasses

    protected void notifySharedElementTransitionComplete(Bundle sharedElements) {
        if (!mNotifiedSharedElementTransitionComplete) {
            mNotifiedSharedElementTransitionComplete = true;
            mRemoteResultReceiver.send(MSG_TAKE_SHARED_ELEMENTS, sharedElements);
        }
    }

    protected void notifyExitTransitionComplete() {
        if (!mNotifiedExitTransitionComplete) {
            mNotifiedExitTransitionComplete = true;
            mRemoteResultReceiver.send(MSG_EXIT_TRANSITION_COMPLETE, null);
        }
    }

    protected void notifyPrepareRestore() {
        mRemoteResultReceiver.send(MSG_PREPARE_RESTORE, null);
    }

    protected void setRemoteResultReceiver(ResultReceiver resultReceiver) {
        mRemoteResultReceiver = resultReceiver;
    }

    protected void notifySetListener() {
        Bundle bundle = new Bundle();
        bundle.putParcelable(KEY_TRANSITION_RESULTS_RECEIVER, this);
        mRemoteResultReceiver.send(MSG_SET_LISTENER, bundle);
    }

    protected void setEnteringViews(ArrayList<View> views) {
        mEnteringViews = views;
    }

    protected void setSharedElements() {
        Pair<View, String>[] sharedElements = mListener.getSharedElementsMapping();
        mSharedElements.clear();
        mTargetSharedNames.clear();
        if (sharedElements == null) {
            ArrayMap<String, View> map = new ArrayMap<String, View>();
            if (getViewsTransition() != null) {
                setViewVisibility(mEnteringViews, View.VISIBLE);
            }
            getDecor().findNamedViews(map);
            if (getViewsTransition() != null) {
                setViewVisibility(mEnteringViews, View.INVISIBLE);
            }
            for (int i = 0; i < map.size(); i++) {
                View view = map.valueAt(i);
                String name = map.keyAt(i);
                mSharedElements.add(view);
                mTargetSharedNames.add(name);
            }
        } else {
            for (int i = 0; i < sharedElements.length; i++) {
                Pair<View, String> viewStringPair = sharedElements[i];
                View view = viewStringPair.first;
                String name = viewStringPair.second;
                mSharedElements.add(view);
                mTargetSharedNames.add(name);
            }
        }
    }

    protected ArrayList<View> getSharedElements() {
        return mSharedElements;
    }

    protected ArrayList<String> getSharedElementNames() {
        return mTargetSharedNames;
    }

    protected Window getWindow() {
        return mWindow;
    }

    protected ViewGroup getDecor() {
        return (mWindow == null) ? null : (ViewGroup) mWindow.getDecorView();
    }

    protected void startExitTransition(ArrayList<String> sharedElements) {
        setSharedElements();
        reconcileSharedElements(sharedElements);
        ArrayList<View> transitioningViews = captureTransitioningViews();
        beginTransition(transitioningViews, true, true, false);
        onStartExitTransition(transitioningViews);
        if (getViewsTransition() != null) {
            setViewVisibility(transitioningViews, View.INVISIBLE);
        }
        mListener.onStartExitTransition(getSharedElementNames(), getSharedElements());
    }

    protected void clearConnections() {
        mRemoteResultReceiver = null;
    }

    // public API

    public void setActivityTransitionListener(ActivityOptions.ActivityTransitionListener listener) {
        if (listener == null) {
            mListener = new ActivityOptions.ActivityTransitionListener();
        } else {
            mListener = listener;
        }
    }

    // private methods

    private Transition configureTransition(Transition transition) {
        if (transition != null) {
            transition = transition.clone();
            transition.setEpicenterCallback(mEpicenterCallback);
        }
        return transition;
    }

    private void reconcileSharedElements(ArrayList<String> sharedElementNames) {
        // keep only those that are in sharedElementNames.
        int numSharedElements = sharedElementNames.size();
        int targetIndex = 0;
        for (int i = 0; i < numSharedElements; i++) {
            String name = sharedElementNames.get(i);
            int index = mTargetSharedNames.indexOf(name);
            if (index >= 0) {
                // Swap the items at the indexes if necessary.
                if (index != targetIndex) {
                    View temp = mSharedElements.get(index);
                    mSharedElements.set(index, mSharedElements.get(targetIndex));
                    mSharedElements.set(targetIndex, temp);
                    mTargetSharedNames.set(index, mTargetSharedNames.get(targetIndex));
                    mTargetSharedNames.set(targetIndex, name);
                }
                targetIndex++;
            }
        }
        for (int i = mSharedElements.size() - 1; i >= targetIndex; i--) {
            mSharedElements.remove(i);
            mTargetSharedNames.remove(i);
        }
        Rect epicenter = null;
        if (!mTargetSharedNames.isEmpty()
                && mTargetSharedNames.get(0).equals(sharedElementNames.get(0))) {
            epicenter = calcEpicenter(mSharedElements.get(0));
        }
        mEpicenterCallback.setEpicenter(epicenter);
    }

    private ArrayMap<ImageView, Pair<ImageView.ScaleType, Matrix>> setSharedElementState(
            Bundle sharedElementState, final ArrayList<View> acceptedOverlayViews) {
        ArrayMap<ImageView, Pair<ImageView.ScaleType, Matrix>> originalImageState =
                new ArrayMap<ImageView, Pair<ImageView.ScaleType, Matrix>>();
        final int[] tempLoc = new int[2];
        if (sharedElementState != null) {
            for (int i = 0; i < mSharedElements.size(); i++) {
                View sharedElement = mSharedElements.get(i);
                String name = mTargetSharedNames.get(i);
                Pair<ImageView.ScaleType, Matrix> originalState = getOldImageState(sharedElement,
                        name, sharedElementState);
                if (originalState != null) {
                    originalImageState.put((ImageView) sharedElement, originalState);
                }
                View parent = (View) sharedElement.getParent();
                parent.getLocationOnScreen(tempLoc);
                setSharedElementState(sharedElement, name, sharedElementState, tempLoc);
                sharedElement.requestLayout();
            }
        }
        mListener.onCaptureSharedElementStart(mTargetSharedNames, mSharedElements,
                acceptedOverlayViews);

        getDecor().getViewTreeObserver().addOnPreDrawListener(
                new ViewTreeObserver.OnPreDrawListener() {
                    @Override
                    public boolean onPreDraw() {
                        getDecor().getViewTreeObserver().removeOnPreDrawListener(this);
                        mListener.onCaptureSharedElementEnd(mTargetSharedNames, mSharedElements,
                                acceptedOverlayViews);
                        mSharedElementTransitionStarted = true;
                        return true;
                    }
                }
        );
        return originalImageState;
    }

    private static Pair<ImageView.ScaleType, Matrix> getOldImageState(View view, String name,
            Bundle transitionArgs) {
        if (!(view instanceof ImageView)) {
            return null;
        }
        Bundle bundle = transitionArgs.getBundle(name);
        int scaleTypeInt = bundle.getInt(KEY_SCALE_TYPE, -1);
        if (scaleTypeInt < 0) {
            return null;
        }

        ImageView imageView = (ImageView) view;
        ImageView.ScaleType originalScaleType = imageView.getScaleType();

        Matrix originalMatrix = null;
        if (originalScaleType == ImageView.ScaleType.MATRIX) {
            originalMatrix = new Matrix(imageView.getImageMatrix());
        }

        return Pair.create(originalScaleType, originalMatrix);
    }

    /**
     * Sets the captured values from a previous
     * {@link #captureSharedElementState(android.view.View, String, android.os.Bundle, int[])}
     * @param view The View to apply placement changes to.
     * @param name The shared element name given from the source Activity.
     * @param transitionArgs A <code>Bundle</code> containing all placementinformation for named
     *                       shared elements in the scene.
     * @param parentLoc The x and y coordinates of the parent's screen position.
     */
    private static void setSharedElementState(View view, String name, Bundle transitionArgs,
            int[] parentLoc) {
        Bundle sharedElementBundle = transitionArgs.getBundle(name);
        if (sharedElementBundle == null) {
            return;
        }

        if (view instanceof ImageView) {
            int scaleTypeInt = sharedElementBundle.getInt(KEY_SCALE_TYPE, -1);
            if (scaleTypeInt >= 0) {
                ImageView imageView = (ImageView) view;
                ImageView.ScaleType scaleType = SCALE_TYPE_VALUES[scaleTypeInt];
                imageView.setScaleType(scaleType);
                if (scaleType == ImageView.ScaleType.MATRIX) {
                    float[] matrixValues = sharedElementBundle.getFloatArray(KEY_IMAGE_MATRIX);
                    Matrix matrix = new Matrix();
                    matrix.setValues(matrixValues);
                    imageView.setImageMatrix(matrix);
                }
            }
        }

        float z = sharedElementBundle.getFloat(KEY_TRANSLATION_Z);
        view.setTranslationZ(z);

        int x = sharedElementBundle.getInt(KEY_SCREEN_X);
        int y = sharedElementBundle.getInt(KEY_SCREEN_Y);
        int width = sharedElementBundle.getInt(KEY_WIDTH);
        int height = sharedElementBundle.getInt(KEY_HEIGHT);

        int widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY);
        int heightSpec = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY);
        view.measure(widthSpec, heightSpec);

        int left = x - parentLoc[0];
        int top = y - parentLoc[1];
        int right = left + width;
        int bottom = top + height;
        view.layout(left, top, right, bottom);
    }

    /**
     * Captures placement information for Views with a shared element name for
     * Activity Transitions.
     * @param view The View to capture the placement information for.
     * @param name The shared element name in the target Activity to apply the placement
     *             information for.
     * @param transitionArgs Bundle to store shared element placement information.
     * @param tempLoc A temporary int[2] for capturing the current location of views.
     * @see #setSharedElementState(android.view.View, String, android.os.Bundle, int[])
     */
    private static void captureSharedElementState(View view, String name, Bundle transitionArgs,
            int[] tempLoc) {
        Bundle sharedElementBundle = new Bundle();
        view.getLocationOnScreen(tempLoc);
        float scaleX = view.getScaleX();
        sharedElementBundle.putInt(KEY_SCREEN_X, tempLoc[0]);
        int width = Math.round(view.getWidth() * scaleX);
        sharedElementBundle.putInt(KEY_WIDTH, width);

        float scaleY = view.getScaleY();
        sharedElementBundle.putInt(KEY_SCREEN_Y, tempLoc[1]);
        int height= Math.round(view.getHeight() * scaleY);
        sharedElementBundle.putInt(KEY_HEIGHT, height);

        sharedElementBundle.putFloat(KEY_TRANSLATION_Z, view.getTranslationZ());

        sharedElementBundle.putString(KEY_NAME, view.getViewName());

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        view.draw(canvas);
        sharedElementBundle.putParcelable(KEY_BITMAP, bitmap);

        if (view instanceof ImageView) {
            ImageView imageView = (ImageView) view;
            int scaleTypeInt = scaleTypeToInt(imageView.getScaleType());
            sharedElementBundle.putInt(KEY_SCALE_TYPE, scaleTypeInt);
            if (imageView.getScaleType() == ImageView.ScaleType.MATRIX) {
                float[] matrix = new float[9];
                imageView.getImageMatrix().getValues(matrix);
                sharedElementBundle.putFloatArray(KEY_IMAGE_MATRIX, matrix);
            }
        }

        transitionArgs.putBundle(name, sharedElementBundle);
    }

    private static Rect calcEpicenter(View view) {
        int[] loc = new int[2];
        view.getLocationOnScreen(loc);
        int left = loc[0] + Math.round(view.getTranslationX());
        int top = loc[1] + Math.round(view.getTranslationY());
        int right = left + view.getWidth();
        int bottom = top + view.getHeight();
        return new Rect(left, top, right, bottom);
    }

    public static void setViewVisibility(Collection<View> views, int visibility) {
        if (views != null) {
            for (View view : views) {
                view.setVisibility(visibility);
            }
        }
    }

    private static Transition addTransitionTargets(Transition transition, Collection<View> views) {
        if (transition == null || views == null || views.isEmpty()) {
            return null;
        }
        TransitionSet set = new TransitionSet();
        set.addTransition(transition.clone());
        if (views != null) {
            for (View view: views) {
                set.addTarget(view);
            }
        }
        return set;
    }

    private ArrayList<View> captureTransitioningViews() {
        if (getViewsTransition() == null) {
            return null;
        }
        ArrayList<View> transitioningViews = new ArrayList<View>();
        getDecor().captureTransitioningViews(transitioningViews);
        transitioningViews.removeAll(getSharedElements());
        return transitioningViews;
    }

    private Transition getSharedElementTransition(boolean isEnter) {
        Transition transition = getSharedElementTransition();
        if (transition == null) {
            return null;
        }
        transition = configureTransition(transition);
        if (!isEnter) {
            transition.addListener(mSharedElementListener);
        }
        return transition;
    }

    private Transition getViewsTransition(ArrayList<View> transitioningViews, boolean isEnter) {
        Transition transition = getViewsTransition();
        if (transition == null) {
            return null;
        }
        transition = configureTransition(transition);
        if (!isEnter) {
            transition.addListener(mExitListener);
        }
        return addTransitionTargets(transition, transitioningViews);
    }

    private Transition beginTransition(ArrayList<View> transitioningViews,
            boolean transitionSharedElement, boolean transitionViews, boolean isEnter) {
        Transition sharedElementTransition = null;
        if (transitionSharedElement) {
            sharedElementTransition = getSharedElementTransition(isEnter);
            if (!isEnter && sharedElementTransition == null) {
                onSharedElementTransitionEnd();
            }
        }
        Transition viewsTransition = null;
        if (transitionViews) {
            viewsTransition = getViewsTransition(transitioningViews, isEnter);
            if (!isEnter && viewsTransition == null) {
                onExitTransitionEnd();
            }
        }

        Transition transition = null;
        if (sharedElementTransition == null) {
            transition = viewsTransition;
        } else if (viewsTransition == null) {
            transition = sharedElementTransition;
        } else {
            TransitionSet set = new TransitionSet();
            set.addTransition(sharedElementTransition);
            set.addTransition(viewsTransition);
            transition = set;
        }
        if (transition != null) {
            TransitionManager.beginDelayedTransition(getDecor(), transition);
            if (transitionSharedElement && !mSharedElements.isEmpty()) {
                mSharedElements.get(0).invalidate();
            } else if (transitionViews && !transitioningViews.isEmpty()) {
                transitioningViews.get(0).invalidate();
            }
        }
        return transition;
    }

    private void handleRejected(final ArrayList<View> rejected) {
        int numRejected = rejected.size();
        if (numRejected == 0) {
            return;
        }
        boolean rejectionHandled = mListener.handleRejectedSharedElements(rejected);
        if (rejectionHandled) {
            return;
        }

        ViewGroupOverlay overlay = getDecor().getOverlay();
        ObjectAnimator animator = null;
        for (int i = 0; i < numRejected; i++) {
            View view = rejected.get(i);
            overlay.add(view);
            animator = ObjectAnimator.ofFloat(view, View.ALPHA, 1, 0);
            animator.start();
        }
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                ViewGroupOverlay overlay = getDecor().getOverlay();
                for (int i = rejected.size() - 1; i >= 0; i--) {
                    overlay.remove(rejected.get(i));
                }
            }
        });
    }

    private void createSharedElementImages(ArrayList<View> accepted, ArrayList<View> rejected,
            ArrayList<String> sharedElementNames, Bundle state) {
        int numSharedElements = sharedElementNames.size();
        Context context = getWindow().getContext();
        int[] parentLoc = new int[2];
        getDecor().getLocationOnScreen(parentLoc);
        for (int i = 0; i < numSharedElements; i++) {
            String name = sharedElementNames.get(i);
            Bundle sharedElementBundle = state.getBundle(name);
            if (sharedElementBundle != null) {
                Bitmap bitmap = sharedElementBundle.getParcelable(KEY_BITMAP);
                ImageView imageView = new ImageView(context);
                imageView.setId(com.android.internal.R.id.shared_element);
                imageView.setScaleType(ImageView.ScaleType.CENTER);
                imageView.setImageBitmap(bitmap);
                imageView.setViewName(name);
                setSharedElementState(imageView, name, state, parentLoc);
                if (mTargetSharedNames.contains(name)) {
                    accepted.add(imageView);
                } else {
                    rejected.add(imageView);
                }
            }
        }
    }

    private static void setOriginalImageViewState(
            ArrayMap<ImageView, Pair<ImageView.ScaleType, Matrix>> originalState) {
        for (int i = 0; i < originalState.size(); i++) {
            ImageView imageView = originalState.keyAt(i);
            Pair<ImageView.ScaleType, Matrix> state = originalState.valueAt(i);
            imageView.setScaleType(state.first);
            imageView.setImageMatrix(state.second);
        }
    }

    private static int scaleTypeToInt(ImageView.ScaleType scaleType) {
        for (int i = 0; i < SCALE_TYPE_VALUES.length; i++) {
            if (scaleType == SCALE_TYPE_VALUES[i]) {
                return i;
            }
        }
        return -1;
    }

    private static class FixedEpicenterCallback extends Transition.EpicenterCallback {
        private Rect mEpicenter;

        public void setEpicenter(Rect epicenter) { mEpicenter = epicenter; }

        @Override
        public Rect getEpicenter(Transition transition) {
            return mEpicenter;
        }
    }
}
