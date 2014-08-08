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
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.os.Parcelable;
import android.view.View;

import java.util.List;
import java.util.Map;

/**
 * Listener provided in
 * {@link Activity#setEnterSharedElementListener(SharedElementListener)} and
 * {@link Activity#setExitSharedElementListener(SharedElementListener)}
 * to monitor the Activity transitions. The events can be used to customize Activity
 * Transition behavior.
 */
public abstract class SharedElementListener {
    private Matrix mTempMatrix;

    static final SharedElementListener NULL_LISTENER = new SharedElementListener() {
    };

    /**
     * Called to allow the listener to customize the start state of the shared element when
     * transferring in shared element state.
     * <p>
     *     The shared element will start at the size and position of the shared element
     *     in the launching Activity or Fragment. It will also transfer ImageView scaleType
     *     and imageMatrix if the shared elements in the calling and called Activities are
     *     ImageViews. Some applications may want to make additional changes, such as
     *     changing the clip bounds, scaling, or rotation if the shared element end state
     *     does not map well to the start state.
     * </p>
     *
     * @param sharedElementNames The names of the shared elements that were accepted into
     *                           the View hierarchy.
     * @param sharedElements The shared elements that are part of the View hierarchy.
     * @param sharedElementSnapshots The Views containing snap shots of the shared element
     *                               from the launching Window. These elements will not
     *                               be part of the scene, but will be positioned relative
     *                               to the Window decor View.
     */
    public void setSharedElementStart(List<String> sharedElementNames,
            List<View> sharedElements, List<View> sharedElementSnapshots) {}

    /**
     * Called to allow the listener to customize the end state of the shared element when
     * transferring in shared element state.
     * <p>
     *     Any customization done in
     *     {@link #setSharedElementStart(java.util.List, java.util.List, java.util.List)}
     *     may need to be modified to the final state of the shared element if it is not
     *     automatically corrected by layout. For example, rotation or scale will not
     *     be affected by layout and if changed in {@link #setSharedElementStart(java.util.List,
     *     java.util.List, java.util.List)}, it will also have to be set here again to correct
     *     the end state.
     * </p>
     *
     * @param sharedElementNames The names of the shared elements that were accepted into
     *                           the View hierarchy.
     * @param sharedElements The shared elements that are part of the View hierarchy.
     * @param sharedElementSnapshots The Views containing snap shots of the shared element
     *                               from the launching Window. These elements will not
     *                               be part of the scene, but will be positioned relative
     *                               to the Window decor View.
     */
    public void setSharedElementEnd(List<String> sharedElementNames,
            List<View> sharedElements, List<View> sharedElementSnapshots) {}

    /**
     * Called after {@link #remapSharedElements(java.util.List, java.util.Map)} when
     * transferring shared elements in. Any shared elements that have no mapping will be in
     * <var>rejectedSharedElements</var>. The elements remaining in
     * <var>rejectedSharedElements</var> will be transitioned out of the Scene. If a
     * View is removed from <var>rejectedSharedElements</var>, it must be handled by the
     * <code>SharedElementListener</code>.
     * <p>
     * Views in rejectedSharedElements will have their position and size set to the
     * position of the calling shared element, relative to the Window decor View and contain
     * snapshots of the View from the calling Activity or Fragment. This
     * view may be safely added to the decor View's overlay to remain in position.
     * </p>
     *
     * @param rejectedSharedElements Views containing visual information of shared elements
     *                               that are not part of the entering scene. These Views
     *                               are positioned relative to the Window decor View. A
     *                               View removed from this list will not be transitioned
     *                               automatically.
     */
    public void handleRejectedSharedElements(List<View> rejectedSharedElements) {}

    /**
     * Lets the ActivityTransitionListener adjust the mapping of shared element names to
     * Views.
     *
     * @param names The names of all shared elements transferred from the calling Activity
     *              to the started Activity.
     * @param sharedElements The mapping of shared element names to Views. The best guess
     *                       will be filled into sharedElements based on the transitionNames.
     */
    public void remapSharedElements(List<String> names, Map<String, View> sharedElements) {}

    /**
     * Creates a snapshot of a shared element to be used by the remote Activity and reconstituted
     * with {@link #createSnapshotView(android.content.Context, android.os.Parcelable)}. A
     * null return value will mean that the remote Activity will have a null snapshot View in
     * {@link #setSharedElementStart(java.util.List, java.util.List, java.util.List)} and
     * {@link #setSharedElementEnd(java.util.List, java.util.List, java.util.List)}.
     *
     * @param sharedElement The shared element View to create a snapshot for.
     * @param viewToGlobalMatrix A matrix containing a transform from the view to the screen
     *                           coordinates.
     * @param screenBounds The bounds of shared element in screen coordinate space. This is
     *                     the bounds of the view with the viewToGlobalMatrix applied.
     * @return A snapshot to send to the remote Activity to be reconstituted with
     * {@link #createSnapshotView(android.content.Context, android.os.Parcelable)} and passed
     * into {@link #setSharedElementStart(java.util.List, java.util.List, java.util.List)} and
     * {@link #setSharedElementEnd(java.util.List, java.util.List, java.util.List)}.
     */
    public Parcelable captureSharedElementSnapshot(View sharedElement, Matrix viewToGlobalMatrix,
            RectF screenBounds) {
        int bitmapWidth = Math.round(screenBounds.width());
        int bitmapHeight = Math.round(screenBounds.height());
        Bitmap bitmap = null;
        if (bitmapWidth > 0 && bitmapHeight > 0) {
            if (mTempMatrix == null) {
                mTempMatrix = new Matrix();
            }
            mTempMatrix.set(viewToGlobalMatrix);
            mTempMatrix.postTranslate(-screenBounds.left, -screenBounds.top);
            bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.concat(mTempMatrix);
            sharedElement.draw(canvas);
        }
        return bitmap;
    }

    /**
     * Reconstitutes a snapshot View from a Parcelable returned in
     * {@link #captureSharedElementSnapshot(android.view.View, android.graphics.Matrix,
     * android.graphics.RectF)} to be used in {@link #setSharedElementStart(java.util.List,
     * java.util.List, java.util.List)} and {@link #setSharedElementEnd(java.util.List,
     * java.util.List, java.util.List)}. The returned View will be sized and positioned after
     * this call so that it is ready to be added to the decor View's overlay.
     *
     * @param context The Context used to create the snapshot View.
     * @param snapshot The Parcelable returned by {@link #captureSharedElementSnapshot(
     * android.view.View, android.graphics.Matrix, android.graphics.RectF)}.
     * @return A View to be sent in {@link #setSharedElementStart(java.util.List, java.util.List,
     * java.util.List)} and {@link #setSharedElementEnd(java.util.List, java.util.List,
     * java.util.List)}. A null value will produce a null snapshot value for those two methods.
     */
    public View createSnapshotView(Context context, Parcelable snapshot) {
        View view = null;
        if (snapshot instanceof Bitmap) {
            Bitmap bitmap = (Bitmap) snapshot;
            view = new View(context);
            Resources resources = context.getResources();
            view.setBackground(new BitmapDrawable(resources, bitmap));
        }
        return view;
    }
}
