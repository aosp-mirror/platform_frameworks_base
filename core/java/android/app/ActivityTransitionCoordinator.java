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

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcelable;
import android.os.ResultReceiver;
import android.transition.Transition;
import android.transition.TransitionSet;
import android.transition.Visibility;
import android.util.ArrayMap;
import android.view.GhostView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroupOverlay;
import android.view.ViewParent;
import android.view.ViewRootImpl;
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
 * Typical finishAfterTransition goes like this:
 * 1) finishAfterTransition() creates an ExitTransitionCoordinator and calls startExit()
 *    - The Window start transitioning to Translucent with a new ActivityOptions.
 *    - If no background exists, a black background is substituted
 *    - The shared elements in the scene are matched against those shared elements
 *      that were sent by comparing the names.
 *    - The exit transition is started by setting Views to INVISIBLE.
 * 2) The ActivityOptions is received by the Activity and an EnterTransitionCoordinator is created.
 *    - All transitioning views are made VISIBLE to reverse what was done when onActivityStopped()
 *      was called
 * 3) The Window is made translucent and a callback is received
 *    - The background alpha is animated to 0
 * 4) The background alpha animation completes
 * 5) The shared element transition completes
 *    - After both 4 & 5 complete, MSG_TAKE_SHARED_ELEMENTS is sent to the
 *      EnterTransitionCoordinator
 * 6) MSG_TAKE_SHARED_ELEMENTS is received by EnterTransitionCoordinator
 *    - Shared elements are made VISIBLE
 *    - Shared elements positions and size are set to match the end state of the calling
 *      Activity.
 *    - The shared element transition is started
 *    - If the window allows overlapping transitions, the views transition is started by setting
 *      the entering Views to VISIBLE.
 *    - MSG_HIDE_SHARED_ELEMENTS is sent to the ExitTransitionCoordinator
 * 7) MSG_HIDE_SHARED_ELEMENTS is received by the ExitTransitionCoordinator
 *    - The shared elements are made INVISIBLE
 * 8) The exit transition completes in the finishing Activity.
 *    - MSG_EXIT_TRANSITION_COMPLETE is sent to the EnterTransitionCoordinator.
 *    - finish() is called on the exiting Activity
 * 9) The MSG_EXIT_TRANSITION_COMPLETE is received by the EnterTransitionCoordinator.
 *    - If the window doesn't allow overlapping enter transitions, the enter transition is started
 *      by setting entering views to VISIBLE.
 */
abstract class ActivityTransitionCoordinator extends ResultReceiver {
    private static final String TAG = "ActivityTransitionCoordinator";

    /**
     * For Activity transitions, the called Activity's listener to receive calls
     * when transitions complete.
     */
    static final String KEY_REMOTE_RECEIVER = "android:remoteReceiver";

    protected static final String KEY_SCREEN_LEFT = "shared_element:screenLeft";
    protected static final String KEY_SCREEN_TOP = "shared_element:screenTop";
    protected static final String KEY_SCREEN_RIGHT = "shared_element:screenRight";
    protected static final String KEY_SCREEN_BOTTOM= "shared_element:screenBottom";
    protected static final String KEY_TRANSLATION_Z = "shared_element:translationZ";
    protected static final String KEY_SNAPSHOT = "shared_element:bitmap";
    protected static final String KEY_SCALE_TYPE = "shared_element:scaleType";
    protected static final String KEY_IMAGE_MATRIX = "shared_element:imageMatrix";
    protected static final String KEY_ELEVATION = "shared_element:elevation";

    protected static final ImageView.ScaleType[] SCALE_TYPE_VALUES = ImageView.ScaleType.values();

    /**
     * Sent by the exiting coordinator (either EnterTransitionCoordinator
     * or ExitTransitionCoordinator) after the shared elements have
     * become stationary (shared element transition completes). This tells
     * the remote coordinator to take control of the shared elements and
     * that animations may begin. The remote Activity won't start entering
     * until this message is received, but may wait for
     * MSG_EXIT_TRANSITION_COMPLETE if allowOverlappingTransitions() is true.
     */
    public static final int MSG_SET_REMOTE_RECEIVER = 100;

    /**
     * Sent by the entering coordinator to tell the exiting coordinator
     * to hide its shared elements after it has started its shared
     * element transition. This is temporary until the
     * interlock of shared elements is figured out.
     */
    public static final int MSG_HIDE_SHARED_ELEMENTS = 101;

    /**
     * Sent by the exiting coordinator (either EnterTransitionCoordinator
     * or ExitTransitionCoordinator) after the shared elements have
     * become stationary (shared element transition completes). This tells
     * the remote coordinator to take control of the shared elements and
     * that animations may begin. The remote Activity won't start entering
     * until this message is received, but may wait for
     * MSG_EXIT_TRANSITION_COMPLETE if allowOverlappingTransitions() is true.
     */
    public static final int MSG_TAKE_SHARED_ELEMENTS = 103;

    /**
     * Sent by the exiting coordinator (either
     * EnterTransitionCoordinator or ExitTransitionCoordinator) after
     * the exiting Views have finished leaving the scene. This will
     * be ignored if allowOverlappingTransitions() is true on the
     * remote coordinator. If it is false, it will trigger the enter
     * transition to start.
     */
    public static final int MSG_EXIT_TRANSITION_COMPLETE = 104;

    /**
     * Sent by Activity#startActivity to begin the exit transition.
     */
    public static final int MSG_START_EXIT_TRANSITION = 105;

    /**
     * It took too long for a message from the entering Activity, so we canceled the transition.
     */
    public static final int MSG_CANCEL = 106;

    /**
     * When returning, this is the destination location for the shared element.
     */
    public static final int MSG_SHARED_ELEMENT_DESTINATION = 107;

    private Window mWindow;
    final protected ArrayList<String> mAllSharedElementNames;
    final protected ArrayList<View> mSharedElements = new ArrayList<View>();
    final protected ArrayList<String> mSharedElementNames = new ArrayList<String>();
    protected ArrayList<View> mTransitioningViews = new ArrayList<View>();
    protected SharedElementCallback mListener;
    protected ResultReceiver mResultReceiver;
    final private FixedEpicenterCallback mEpicenterCallback = new FixedEpicenterCallback();
    final protected boolean mIsReturning;
    private Runnable mPendingTransition;
    private boolean mIsStartingTransition;
    private ArrayList<GhostViewListeners> mGhostViewListeners =
            new ArrayList<GhostViewListeners>();
    private ArrayMap<View, Float> mOriginalAlphas = new ArrayMap<View, Float>();
    private ArrayList<Matrix> mSharedElementParentMatrices;
    private boolean mSharedElementTransitionComplete;
    private boolean mViewsTransitionComplete;

    public ActivityTransitionCoordinator(Window window,
            ArrayList<String> allSharedElementNames,
            SharedElementCallback listener, boolean isReturning) {
        super(new Handler());
        mWindow = window;
        mListener = listener;
        mAllSharedElementNames = allSharedElementNames;
        mIsReturning = isReturning;
    }

    protected void viewsReady(ArrayMap<String, View> sharedElements) {
        sharedElements.retainAll(mAllSharedElementNames);
        if (mListener != null) {
            mListener.onMapSharedElements(mAllSharedElementNames, sharedElements);
        }
        setSharedElements(sharedElements);
        if (getViewsTransition() != null && mTransitioningViews != null) {
            ViewGroup decorView = getDecor();
            if (decorView != null) {
                decorView.captureTransitioningViews(mTransitioningViews);
            }
            mTransitioningViews.removeAll(mSharedElements);
        }
        setEpicenter();
    }

    /**
     * Iterates over the shared elements and adds them to the members in order.
     * Shared elements that are nested in other shared elements are placed after the
     * elements that they are nested in. This means that layout ordering can be done
     * from first to last.
     *
     * @param sharedElements The map of transition names to shared elements to set into
     *                       the member fields.
     */
    private void setSharedElements(ArrayMap<String, View> sharedElements) {
        boolean isFirstRun = true;
        while (!sharedElements.isEmpty()) {
            final int numSharedElements = sharedElements.size();
            for (int i = numSharedElements - 1; i >= 0; i--) {
                final View view = sharedElements.valueAt(i);
                final String name = sharedElements.keyAt(i);
                if (isFirstRun && (view == null || !view.isAttachedToWindow() || name == null)) {
                    sharedElements.removeAt(i);
                } else if (!isNested(view, sharedElements)) {
                    mSharedElementNames.add(name);
                    mSharedElements.add(view);
                    sharedElements.removeAt(i);
                }
            }
            isFirstRun = false;
        }
    }

    /**
     * Returns true when view is nested in any of the values of sharedElements.
     */
    private static boolean isNested(View view, ArrayMap<String, View> sharedElements) {
        ViewParent parent = view.getParent();
        boolean isNested = false;
        while (parent instanceof View) {
            View parentView = (View) parent;
            if (sharedElements.containsValue(parentView)) {
                isNested = true;
                break;
            }
            parent = parentView.getParent();
        }
        return isNested;
    }

    protected void stripOffscreenViews() {
        if (mTransitioningViews == null) {
            return;
        }
        Rect r = new Rect();
        for (int i = mTransitioningViews.size() - 1; i >= 0; i--) {
            View view = mTransitioningViews.get(i);
            if (!view.getGlobalVisibleRect(r)) {
                mTransitioningViews.remove(i);
                showView(view, true);
            }
        }
    }

    protected Window getWindow() {
        return mWindow;
    }

    public ViewGroup getDecor() {
        return (mWindow == null) ? null : (ViewGroup) mWindow.getDecorView();
    }

    /**
     * Sets the transition epicenter to the position of the first shared element.
     */
    protected void setEpicenter() {
        View epicenter = null;
        if (!mAllSharedElementNames.isEmpty() && !mSharedElementNames.isEmpty()) {
            int index = mSharedElementNames.indexOf(mAllSharedElementNames.get(0));
            if (index >= 0) {
                epicenter = mSharedElements.get(index);
            }
        }
        setEpicenter(epicenter);
    }

    private void setEpicenter(View view) {
        if (view == null) {
            mEpicenterCallback.setEpicenter(null);
        } else {
            Rect epicenter = new Rect();
            view.getBoundsOnScreen(epicenter);
            mEpicenterCallback.setEpicenter(epicenter);
        }
    }

    public ArrayList<String> getAcceptedNames() {
        return mSharedElementNames;
    }

    public ArrayList<String> getMappedNames() {
        ArrayList<String> names = new ArrayList<String>(mSharedElements.size());
        for (int i = 0; i < mSharedElements.size(); i++) {
            names.add(mSharedElements.get(i).getTransitionName());
        }
        return names;
    }

    public ArrayList<View> copyMappedViews() {
        return new ArrayList<View>(mSharedElements);
    }

    public ArrayList<String> getAllSharedElementNames() { return mAllSharedElementNames; }

    protected Transition setTargets(Transition transition, boolean add) {
        if (transition == null || (add &&
                (mTransitioningViews == null || mTransitioningViews.isEmpty()))) {
            return null;
        }
        // Add the targets to a set containing transition so that transition
        // remains unaffected. We don't want to modify the targets of transition itself.
        TransitionSet set = new TransitionSet();
        if (mTransitioningViews != null) {
            for (int i = mTransitioningViews.size() - 1; i >= 0; i--) {
                View view = mTransitioningViews.get(i);
                if (add) {
                    set.addTarget(view);
                } else {
                    set.excludeTarget(view, true);
                }
            }
        }
        // By adding the transition after addTarget, we prevent addTarget from
        // affecting transition.
        set.addTransition(transition);

        if (!add && mTransitioningViews != null && !mTransitioningViews.isEmpty()) {
            // Allow children of excluded transitioning views, but not the views themselves
            set = new TransitionSet().addTransition(set);
        }

        return set;
    }

    protected Transition configureTransition(Transition transition,
            boolean includeTransitioningViews) {
        if (transition != null) {
            transition = transition.clone();
            transition.setEpicenterCallback(mEpicenterCallback);
            transition = setTargets(transition, includeTransitioningViews);
        }
        noLayoutSuppressionForVisibilityTransitions(transition);
        return transition;
    }

    protected static Transition mergeTransitions(Transition transition1, Transition transition2) {
        if (transition1 == null) {
            return transition2;
        } else if (transition2 == null) {
            return transition1;
        } else {
            TransitionSet transitionSet = new TransitionSet();
            transitionSet.addTransition(transition1);
            transitionSet.addTransition(transition2);
            return transitionSet;
        }
    }

    protected ArrayMap<String, View> mapSharedElements(ArrayList<String> accepted,
            ArrayList<View> localViews) {
        ArrayMap<String, View> sharedElements = new ArrayMap<String, View>();
        if (accepted != null) {
            for (int i = 0; i < accepted.size(); i++) {
                sharedElements.put(accepted.get(i), localViews.get(i));
            }
        } else {
            ViewGroup decorView = getDecor();
            if (decorView != null) {
                decorView.findNamedViews(sharedElements);
            }
        }
        return sharedElements;
    }

    protected void setResultReceiver(ResultReceiver resultReceiver) {
        mResultReceiver = resultReceiver;
    }

    protected abstract Transition getViewsTransition();

    private void setSharedElementState(View view, String name, Bundle transitionArgs,
            Matrix tempMatrix, RectF tempRect, int[] decorLoc) {
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
                    tempMatrix.setValues(matrixValues);
                    imageView.setImageMatrix(tempMatrix);
                }
            }
        }

        float z = sharedElementBundle.getFloat(KEY_TRANSLATION_Z);
        view.setTranslationZ(z);
        float elevation = sharedElementBundle.getFloat(KEY_ELEVATION);
        view.setElevation(elevation);

        float left = sharedElementBundle.getFloat(KEY_SCREEN_LEFT);
        float top = sharedElementBundle.getFloat(KEY_SCREEN_TOP);
        float right = sharedElementBundle.getFloat(KEY_SCREEN_RIGHT);
        float bottom = sharedElementBundle.getFloat(KEY_SCREEN_BOTTOM);

        if (decorLoc != null) {
            left -= decorLoc[0];
            top -= decorLoc[1];
            right -= decorLoc[0];
            bottom -= decorLoc[1];
        } else {
            // Find the location in the view's parent
            getSharedElementParentMatrix(view, tempMatrix);
            tempRect.set(left, top, right, bottom);
            tempMatrix.mapRect(tempRect);

            float leftInParent = tempRect.left;
            float topInParent = tempRect.top;

            // Find the size of the view
            view.getInverseMatrix().mapRect(tempRect);
            float width = tempRect.width();
            float height = tempRect.height();

            // Now determine the offset due to view transform:
            view.setLeft(0);
            view.setTop(0);
            view.setRight(Math.round(width));
            view.setBottom(Math.round(height));
            tempRect.set(0, 0, width, height);
            view.getMatrix().mapRect(tempRect);

            left = leftInParent - tempRect.left;
            top = topInParent - tempRect.top;
            right = left + width;
            bottom = top + height;
        }

        int x = Math.round(left);
        int y = Math.round(top);
        int width = Math.round(right) - x;
        int height = Math.round(bottom) - y;
        int widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY);
        int heightSpec = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY);
        view.measure(widthSpec, heightSpec);

        view.layout(x, y, x + width, y + height);
    }

    private void setSharedElementMatrices() {
        int numSharedElements = mSharedElements.size();
        if (numSharedElements > 0) {
            mSharedElementParentMatrices = new ArrayList<Matrix>(numSharedElements);
        }
        for (int i = 0; i < numSharedElements; i++) {
            View view = mSharedElements.get(i);

            // Find the location in the view's parent
            ViewGroup parent = (ViewGroup) view.getParent();
            Matrix matrix = new Matrix();
            parent.transformMatrixToLocal(matrix);
            matrix.postTranslate(parent.getScrollX(), parent.getScrollY());
            mSharedElementParentMatrices.add(matrix);
        }
    }

    private void getSharedElementParentMatrix(View view, Matrix matrix) {
        final int index = mSharedElementParentMatrices == null ? -1
                : mSharedElements.indexOf(view);
        if (index < 0) {
            matrix.reset();
            ViewParent viewParent = view.getParent();
            if (viewParent instanceof ViewGroup) {
                // Find the location in the view's parent
                ViewGroup parent = (ViewGroup) viewParent;
                parent.transformMatrixToLocal(matrix);
                matrix.postTranslate(parent.getScrollX(), parent.getScrollY());
            }
        } else {
            // The indices of mSharedElementParentMatrices matches the
            // mSharedElement matrices.
            Matrix parentMatrix = mSharedElementParentMatrices.get(index);
            matrix.set(parentMatrix);
        }
    }

    protected ArrayList<SharedElementOriginalState> setSharedElementState(
            Bundle sharedElementState, final ArrayList<View> snapshots) {
        ArrayList<SharedElementOriginalState> originalImageState =
                new ArrayList<SharedElementOriginalState>();
        if (sharedElementState != null) {
            Matrix tempMatrix = new Matrix();
            RectF tempRect = new RectF();
            final int numSharedElements = mSharedElements.size();
            for (int i = 0; i < numSharedElements; i++) {
                View sharedElement = mSharedElements.get(i);
                String name = mSharedElementNames.get(i);
                SharedElementOriginalState originalState = getOldSharedElementState(sharedElement,
                        name, sharedElementState);
                originalImageState.add(originalState);
                setSharedElementState(sharedElement, name, sharedElementState,
                        tempMatrix, tempRect, null);
            }
        }
        if (mListener != null) {
            mListener.onSharedElementStart(mSharedElementNames, mSharedElements, snapshots);
        }
        return originalImageState;
    }

    protected void notifySharedElementEnd(ArrayList<View> snapshots) {
        if (mListener != null) {
            mListener.onSharedElementEnd(mSharedElementNames, mSharedElements, snapshots);
        }
    }

    protected void scheduleSetSharedElementEnd(final ArrayList<View> snapshots) {
        final View decorView = getDecor();
        if (decorView != null) {
            decorView.getViewTreeObserver().addOnPreDrawListener(
                    new ViewTreeObserver.OnPreDrawListener() {
                        @Override
                        public boolean onPreDraw() {
                            decorView.getViewTreeObserver().removeOnPreDrawListener(this);
                            notifySharedElementEnd(snapshots);
                            return true;
                        }
                    }
            );
        }
    }

    private static SharedElementOriginalState getOldSharedElementState(View view, String name,
            Bundle transitionArgs) {

        SharedElementOriginalState state = new SharedElementOriginalState();
        state.mLeft = view.getLeft();
        state.mTop = view.getTop();
        state.mRight = view.getRight();
        state.mBottom = view.getBottom();
        state.mMeasuredWidth = view.getMeasuredWidth();
        state.mMeasuredHeight = view.getMeasuredHeight();
        state.mTranslationZ = view.getTranslationZ();
        state.mElevation = view.getElevation();
        if (!(view instanceof ImageView)) {
            return state;
        }
        Bundle bundle = transitionArgs.getBundle(name);
        if (bundle == null) {
            return state;
        }
        int scaleTypeInt = bundle.getInt(KEY_SCALE_TYPE, -1);
        if (scaleTypeInt < 0) {
            return state;
        }

        ImageView imageView = (ImageView) view;
        state.mScaleType = imageView.getScaleType();
        if (state.mScaleType == ImageView.ScaleType.MATRIX) {
            state.mMatrix = new Matrix(imageView.getImageMatrix());
        }
        return state;
    }

    protected ArrayList<View> createSnapshots(Bundle state, Collection<String> names) {
        int numSharedElements = names.size();
        ArrayList<View> snapshots = new ArrayList<View>(numSharedElements);
        if (numSharedElements == 0) {
            return snapshots;
        }
        Context context = getWindow().getContext();
        int[] decorLoc = new int[2];
        ViewGroup decorView = getDecor();
        if (decorView != null) {
            decorView.getLocationOnScreen(decorLoc);
        }
        Matrix tempMatrix = new Matrix();
        for (String name: names) {
            Bundle sharedElementBundle = state.getBundle(name);
            View snapshot = null;
            if (sharedElementBundle != null) {
                Parcelable parcelable = sharedElementBundle.getParcelable(KEY_SNAPSHOT);
                if (parcelable != null && mListener != null) {
                    snapshot = mListener.onCreateSnapshotView(context, parcelable);
                }
                if (snapshot != null) {
                    setSharedElementState(snapshot, name, state, tempMatrix, null, decorLoc);
                }
            }
            // Even null snapshots are added so they remain in the same order as shared elements.
            snapshots.add(snapshot);
        }
        return snapshots;
    }

    protected static void setOriginalSharedElementState(ArrayList<View> sharedElements,
            ArrayList<SharedElementOriginalState> originalState) {
        for (int i = 0; i < originalState.size(); i++) {
            View view = sharedElements.get(i);
            SharedElementOriginalState state = originalState.get(i);
            if (view instanceof ImageView && state.mScaleType != null) {
                ImageView imageView = (ImageView) view;
                imageView.setScaleType(state.mScaleType);
                if (state.mScaleType == ImageView.ScaleType.MATRIX) {
                  imageView.setImageMatrix(state.mMatrix);
                }
            }
            view.setElevation(state.mElevation);
            view.setTranslationZ(state.mTranslationZ);
            int widthSpec = View.MeasureSpec.makeMeasureSpec(state.mMeasuredWidth,
                    View.MeasureSpec.EXACTLY);
            int heightSpec = View.MeasureSpec.makeMeasureSpec(state.mMeasuredHeight,
                    View.MeasureSpec.EXACTLY);
            view.measure(widthSpec, heightSpec);
            view.layout(state.mLeft, state.mTop, state.mRight, state.mBottom);
        }
    }

    protected Bundle captureSharedElementState() {
        Bundle bundle = new Bundle();
        RectF tempBounds = new RectF();
        Matrix tempMatrix = new Matrix();
        for (int i = 0; i < mSharedElements.size(); i++) {
            View sharedElement = mSharedElements.get(i);
            String name = mSharedElementNames.get(i);
            captureSharedElementState(sharedElement, name, bundle, tempMatrix, tempBounds);
        }
        return bundle;
    }

    protected void clearState() {
        // Clear the state so that we can't hold any references accidentally and leak memory.
        mWindow = null;
        mSharedElements.clear();
        mTransitioningViews = null;
        mOriginalAlphas.clear();
        mResultReceiver = null;
        mPendingTransition = null;
        mListener = null;
        mSharedElementParentMatrices = null;
    }

    protected long getFadeDuration() {
        return getWindow().getTransitionBackgroundFadeDuration();
    }

    protected void hideViews(ArrayList<View> views) {
        int count = views.size();
        for (int i = 0; i < count; i++) {
            View view = views.get(i);
            if (!mOriginalAlphas.containsKey(view)) {
                mOriginalAlphas.put(view, view.getAlpha());
            }
            view.setAlpha(0f);
        }
    }

    protected void showViews(ArrayList<View> views, boolean setTransitionAlpha) {
        int count = views.size();
        for (int i = 0; i < count; i++) {
            showView(views.get(i), setTransitionAlpha);
        }
    }

    private void showView(View view, boolean setTransitionAlpha) {
        Float alpha = mOriginalAlphas.remove(view);
        if (alpha != null) {
            view.setAlpha(alpha);
        }
        if (setTransitionAlpha) {
            view.setTransitionAlpha(1f);
        }
    }

    /**
     * Captures placement information for Views with a shared element name for
     * Activity Transitions.
     *
     * @param view           The View to capture the placement information for.
     * @param name           The shared element name in the target Activity to apply the placement
     *                       information for.
     * @param transitionArgs Bundle to store shared element placement information.
     * @param tempBounds     A temporary Rect for capturing the current location of views.
     */
    protected void captureSharedElementState(View view, String name, Bundle transitionArgs,
            Matrix tempMatrix, RectF tempBounds) {
        Bundle sharedElementBundle = new Bundle();
        tempMatrix.reset();
        view.transformMatrixToGlobal(tempMatrix);
        tempBounds.set(0, 0, view.getWidth(), view.getHeight());
        tempMatrix.mapRect(tempBounds);

        sharedElementBundle.putFloat(KEY_SCREEN_LEFT, tempBounds.left);
        sharedElementBundle.putFloat(KEY_SCREEN_RIGHT, tempBounds.right);
        sharedElementBundle.putFloat(KEY_SCREEN_TOP, tempBounds.top);
        sharedElementBundle.putFloat(KEY_SCREEN_BOTTOM, tempBounds.bottom);
        sharedElementBundle.putFloat(KEY_TRANSLATION_Z, view.getTranslationZ());
        sharedElementBundle.putFloat(KEY_ELEVATION, view.getElevation());

        Parcelable bitmap = null;
        if (mListener != null) {
            bitmap = mListener.onCaptureSharedElementSnapshot(view, tempMatrix, tempBounds);
        }

        if (bitmap != null) {
            sharedElementBundle.putParcelable(KEY_SNAPSHOT, bitmap);
        }

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


    protected void startTransition(Runnable runnable) {
        if (mIsStartingTransition) {
            mPendingTransition = runnable;
        } else {
            mIsStartingTransition = true;
            runnable.run();
        }
    }

    protected void transitionStarted() {
        mIsStartingTransition = false;
    }

    /**
     * Cancels any pending transitions and returns true if there is a transition is in
     * the middle of starting.
     */
    protected boolean cancelPendingTransitions() {
        mPendingTransition = null;
        return mIsStartingTransition;
    }

    protected void moveSharedElementsToOverlay() {
        if (mWindow == null || !mWindow.getSharedElementsUseOverlay()) {
            return;
        }
        setSharedElementMatrices();
        int numSharedElements = mSharedElements.size();
        ViewGroup decor = getDecor();
        if (decor != null) {
            boolean moveWithParent = moveSharedElementWithParent();
            Matrix tempMatrix = new Matrix();
            for (int i = 0; i < numSharedElements; i++) {
                View view = mSharedElements.get(i);
                tempMatrix.reset();
                mSharedElementParentMatrices.get(i).invert(tempMatrix);
                GhostView.addGhost(view, decor, tempMatrix);
                ViewGroup parent = (ViewGroup) view.getParent();
                if (moveWithParent && !isInTransitionGroup(parent, decor)) {
                    GhostViewListeners listener = new GhostViewListeners(view, parent, decor);
                    parent.getViewTreeObserver().addOnPreDrawListener(listener);
                    mGhostViewListeners.add(listener);
                }
            }
        }
    }

    protected boolean moveSharedElementWithParent() {
        return true;
    }

    public static boolean isInTransitionGroup(ViewParent viewParent, ViewGroup decor) {
        if (viewParent == decor || !(viewParent instanceof ViewGroup)) {
            return false;
        }
        ViewGroup parent = (ViewGroup) viewParent;
        if (parent.isTransitionGroup()) {
            return true;
        } else {
            return isInTransitionGroup(parent.getParent(), decor);
        }
    }

    protected void moveSharedElementsFromOverlay() {
        int numListeners = mGhostViewListeners.size();
        for (int i = 0; i < numListeners; i++) {
            GhostViewListeners listener = mGhostViewListeners.get(i);
            ViewGroup parent = (ViewGroup) listener.getView().getParent();
            parent.getViewTreeObserver().removeOnPreDrawListener(listener);
        }
        mGhostViewListeners.clear();

        if (mWindow == null || !mWindow.getSharedElementsUseOverlay()) {
            return;
        }
        ViewGroup decor = getDecor();
        if (decor != null) {
            ViewGroupOverlay overlay = decor.getOverlay();
            int count = mSharedElements.size();
            for (int i = 0; i < count; i++) {
                View sharedElement = mSharedElements.get(i);
                GhostView.removeGhost(sharedElement);
            }
        }
    }

    protected void setGhostVisibility(int visibility) {
        int numSharedElements = mSharedElements.size();
        for (int i = 0; i < numSharedElements; i++) {
            GhostView ghostView = GhostView.getGhost(mSharedElements.get(i));
            if (ghostView != null) {
                ghostView.setVisibility(visibility);
            }
        }
    }

    protected void scheduleGhostVisibilityChange(final int visibility) {
        final View decorView = getDecor();
        if (decorView != null) {
            decorView.getViewTreeObserver()
                    .addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                        @Override
                        public boolean onPreDraw() {
                            decorView.getViewTreeObserver().removeOnPreDrawListener(this);
                            setGhostVisibility(visibility);
                            return true;
                        }
                    });
        }
    }

    protected boolean isViewsTransitionComplete() {
        return mViewsTransitionComplete;
    }

    protected void viewsTransitionComplete() {
        mViewsTransitionComplete = true;
        startInputWhenTransitionsComplete();
    }

    protected void sharedElementTransitionComplete() {
        mSharedElementTransitionComplete = true;
        startInputWhenTransitionsComplete();
    }
    private void startInputWhenTransitionsComplete() {
        if (mViewsTransitionComplete && mSharedElementTransitionComplete) {
            final View decor = getDecor();
            if (decor != null) {
                final ViewRootImpl viewRoot = decor.getViewRootImpl();
                if (viewRoot != null) {
                    viewRoot.setPausedForTransition(false);
                }
            }
            onTransitionsComplete();
        }
    }

    protected void pauseInput() {
        final View decor = getDecor();
        final ViewRootImpl viewRoot = decor == null ? null : decor.getViewRootImpl();
        if (viewRoot != null) {
            viewRoot.setPausedForTransition(true);
        }
    }

    protected void onTransitionsComplete() {}

    protected class ContinueTransitionListener extends Transition.TransitionListenerAdapter {
        @Override
        public void onTransitionStart(Transition transition) {
            mIsStartingTransition = false;
            Runnable pending = mPendingTransition;
            mPendingTransition = null;
            if (pending != null) {
                startTransition(pending);
            }
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

    protected void setTransitioningViewsVisiblity(int visiblity, boolean invalidate) {
        final int numElements = mTransitioningViews == null ? 0 : mTransitioningViews.size();
        for (int i = 0; i < numElements; i++) {
            final View view = mTransitioningViews.get(i);
            view.setTransitionVisibility(visiblity);
            if (invalidate) {
                view.invalidate();
            }
        }
    }

    /**
     * Blocks suppressLayout from Visibility transitions. It is ok to suppress the layout,
     * but we don't want to force the layout when suppressLayout becomes false. This leads
     * to visual glitches.
     */
    private static void noLayoutSuppressionForVisibilityTransitions(Transition transition) {
        if (transition instanceof Visibility) {
            final Visibility visibility = (Visibility) transition;
            visibility.setSuppressLayout(false);
        } else if (transition instanceof TransitionSet) {
            final TransitionSet set = (TransitionSet) transition;
            final int count = set.getTransitionCount();
            for (int i = 0; i < count; i++) {
                noLayoutSuppressionForVisibilityTransitions(set.getTransitionAt(i));
            }
        }
    }

    private static class FixedEpicenterCallback extends Transition.EpicenterCallback {
        private Rect mEpicenter;

        public void setEpicenter(Rect epicenter) { mEpicenter = epicenter; }

        @Override
        public Rect onGetEpicenter(Transition transition) {
            return mEpicenter;
        }
    }

    private static class GhostViewListeners implements ViewTreeObserver.OnPreDrawListener {
        private View mView;
        private ViewGroup mDecor;
        private View mParent;
        private Matrix mMatrix = new Matrix();

        public GhostViewListeners(View view, View parent, ViewGroup decor) {
            mView = view;
            mParent = parent;
            mDecor = decor;
        }

        public View getView() {
            return mView;
        }

        @Override
        public boolean onPreDraw() {
            GhostView ghostView = GhostView.getGhost(mView);
            if (ghostView == null) {
                mParent.getViewTreeObserver().removeOnPreDrawListener(this);
            } else {
                GhostView.calculateMatrix(mView, mDecor, mMatrix);
                ghostView.setMatrix(mMatrix);
            }
            return true;
        }
    }

    static class SharedElementOriginalState {
        int mLeft;
        int mTop;
        int mRight;
        int mBottom;
        int mMeasuredWidth;
        int mMeasuredHeight;
        ImageView.ScaleType mScaleType;
        Matrix mMatrix;
        float mTranslationZ;
        float mElevation;
    }
}
