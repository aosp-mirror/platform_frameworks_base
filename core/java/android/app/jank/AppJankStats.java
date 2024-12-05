/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.app.jank;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringDef;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 *  This class stores detailed jank statistics for an individual UI widget. These statistics
 *  provide performance insights for specific UI widget states by correlating the number of
 *  "Janky frames" with the total frames rendered while the widget is in that state. This class
 *  can be used by library widgets to provide the system with more detailed information about
 *  where jank is happening for diagnostic purposes.
 */
@FlaggedApi(Flags.FLAG_DETAILED_APP_JANK_METRICS_API)
public final class AppJankStats {
    // UID of the app
    private int mUid;

    // The id that has been set for the widget.
    private String mWidgetId;

    // A general category that the widget applies to.
    private String mWidgetCategory;

    // The states that the UI elements can report
    private String mWidgetState;

    // The number of frames reported during this state.
    private long mTotalFrames;

    // Total number of frames determined to be janky during the reported state.
    private long mJankyFrames;

    // Histogram of frame duration overruns encoded in predetermined buckets.
    private FrameOverrunHistogram mFrameOverrunHistogram;


    /** Used to indicate no widget category has been set. */
    public static final String WIDGET_CATEGORY_UNSPECIFIED =
            "widget_category_unspecified";

    /** UI elements that facilitate scrolling. */
    public static final String SCROLL = "scroll";

    /** UI elements that facilitate playing animations. */
    public static final String ANIMATION = "animation";

    /** UI elements that facilitate media playback. */
    public static final String MEDIA = "media";

    /** UI elements that facilitate in-app navigation. */
    public static final String NAVIGATION = "navigation";

    /** UI elements that facilitate displaying, hiding or interacting with keyboard. */
    public static final String KEYBOARD = "keyboard";

    /** UI elements that facilitate predictive back gesture navigation. */
    public static final String PREDICTIVE_BACK = "predictive_back";

    /** UI elements that don't fall in one or any of the other categories. */
    public static final String OTHER = "other";

    /** Used to indicate no widget state has been set. */
    public static final String WIDGET_STATE_UNSPECIFIED = "widget_state_unspecified";

    /** Used to indicate the UI element currently has no state and is idle. */
    public static final String NONE = "none";

    /** Used to indicate the UI element is currently scrolling. */
    public static final String SCROLLING = "scrolling";

    /** Used to indicate the UI element is currently being flung. */
    public static final String FLINGING = "flinging";

    /** Used to indicate the UI element is currently being swiped. */
    public static final String SWIPING = "swiping";

    /** Used to indicate the UI element is currently being dragged. */
    public static final String DRAGGING = "dragging";

    /** Used to indicate the UI element is currently zooming. */
    public static final String ZOOMING = "zooming";

    /** Used to indicate the UI element is currently animating. */
    public static final String ANIMATING = "animating";

    /** Used to indicate the UI element is currently playing media. */
    public static final String PLAYBACK = "playback";

    /** Used to indicate the UI element is currently being tapped on, for example on a keyboard. */
    public static final String TAPPING = "tapping";


    /**
     * @hide
     */
    @StringDef(value = {
            WIDGET_CATEGORY_UNSPECIFIED,
            SCROLL,
            ANIMATION,
            MEDIA,
            NAVIGATION,
            KEYBOARD,
            PREDICTIVE_BACK,
            OTHER
    })
    @Target({ElementType.TYPE_USE, ElementType.TYPE_PARAMETER})
    @Retention(RetentionPolicy.SOURCE)
    public @interface WidgetCategory {
    }
    /**
     * @hide
     */
    @StringDef(value = {
            WIDGET_STATE_UNSPECIFIED,
            NONE,
            SCROLLING,
            FLINGING,
            SWIPING,
            DRAGGING,
            ZOOMING,
            ANIMATING,
            PLAYBACK,
            TAPPING,
    })
    @Target({ElementType.TYPE_USE, ElementType.TYPE_PARAMETER})
    @Retention(RetentionPolicy.SOURCE)
    public @interface WidgetState {
    }


    /**
     * Creates a new AppJankStats object.
     *
     * @param appUid the Uid of the App that is collecting jank stats.
     * @param widgetId the widget id that frames will be associated to.
     * @param widgetCategory a general functionality category that the widget falls into. Must be
     *                       one of the following: SCROLL, ANIMATION, MEDIA, NAVIGATION, KEYBOARD,
     *                       PREDICTIVE_BACK, OTHER or will be set to WIDGET_CATEGORY_UNSPECIFIED
     *                       if no value is passed.
     * @param widgetState the state the widget was in while frames were counted. Must be one of
     *                    the following: NONE, SCROLLING, FLINGING, SWIPING, DRAGGING, ZOOMING,
     *                    ANIMATING, PLAYBACK, TAPPING or will be set to WIDGET_STATE_UNSPECIFIED
     *                    if no value is passed.
     * @param totalFrames the total number of frames that were counted for this stat.
     * @param jankyFrames the total number of janky frames that were counted for this stat.
     * @param frameOverrunHistogram the histogram with predefined buckets. See
     * {@link #getFrameOverrunHistogram()} for details.
     *
     */
    public AppJankStats(int appUid, @NonNull String widgetId,
            @Nullable @WidgetCategory String widgetCategory,
            @Nullable @WidgetState String widgetState, long totalFrames, long jankyFrames,
            @NonNull FrameOverrunHistogram frameOverrunHistogram) {
        mUid = appUid;
        mWidgetId = widgetId;
        mWidgetCategory = widgetCategory != null ? widgetCategory : WIDGET_CATEGORY_UNSPECIFIED;
        mWidgetState = widgetState != null ? widgetState : WIDGET_STATE_UNSPECIFIED;
        mTotalFrames = totalFrames;
        mJankyFrames = jankyFrames;
        mFrameOverrunHistogram = frameOverrunHistogram;
    }

    /**
     * Returns the app uid.
     *
     * @return the app uid.
     */
    public int getUid() {
        return mUid;
    }

    /**
     * Returns the id of the widget that reported state changes.
     *
     * @return the id of the widget that reported state changes. This value cannot be null.
     */
    public @NonNull String getWidgetId() {
        return mWidgetId;
    }

    /**
     * Returns the category that the widget's functionality generally falls into, or
     * widget_category_unspecified {@link #WIDGET_CATEGORY_UNSPECIFIED} if no value was passed in.
     *
     * @return the category that the widget's functionality generally falls into, this value cannot
     * be null.
     */
    public @NonNull @WidgetCategory String getWidgetCategory() {
        return mWidgetCategory;
    }

    /**
     * Returns the widget's state that was reported for this stat, or widget_state_unspecified
     * {@link #WIDGET_STATE_UNSPECIFIED} if no value was passed in.
     *
     * @return the widget's state that was reported for this stat. This value cannot be null.
     */
    public @NonNull @WidgetState String getWidgetState() {
        return mWidgetState;
    }

    /**
     * Returns the number of frames that were determined to be janky for this stat.
     *
     * @return the number of frames that were determined to be janky for this stat.
     */
    public long getJankyFrameCount() {
        return mJankyFrames;
    }

    /**
     * Returns the total number of frames counted for this stat.
     *
     * @return the total number of frames counted for this stat.
     */
    public long getTotalFrameCount() {
        return mTotalFrames;
    }

    /**
     * Returns a Histogram containing frame overrun times in millis grouped into predefined buckets.
     * See {@link FrameOverrunHistogram} for more information.
     *
     * @return Histogram containing frame overrun times in predefined buckets. This value cannot
     * be null.
     */
    public @NonNull FrameOverrunHistogram getFrameOverrunHistogram() {
        return mFrameOverrunHistogram;
    }
}
