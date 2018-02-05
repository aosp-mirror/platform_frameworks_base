/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.widget;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.media.session.MediaController;
import android.media.update.ApiLoader;
import android.media.update.MediaControlView2Provider;
import android.media.update.ViewGroupHelper;
import android.util.AttributeSet;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * A View that contains the controls for MediaPlayer2.
 * It provides a wide range of UI including buttons such as "Play/Pause", "Rewind", "Fast Forward",
 * "Subtitle", "Full Screen", and it is also possible to add multiple custom buttons.
 *
 * <p>
 * <em> MediaControlView2 can be initialized in two different ways: </em>
 * 1) When VideoView2 is initialized, it automatically initializes a MediaControlView2 instance and
 * adds it to the view.
 * 2) Initialize MediaControlView2 programmatically and add it to a ViewGroup instance.
 *
 * In the first option, VideoView2 automatically connects MediaControlView2 to MediaController,
 * which is necessary to communicate with MediaSession2. In the second option, however, the
 * developer needs to manually retrieve a MediaController instance and set it to MediaControlView2
 * by calling setController(MediaController controller).
 *
 * <p>
 * There is no separate method that handles the show/hide behavior for MediaControlView2. Instead,
 * one can directly change the visibility of this view by calling View.setVisibility(int). The
 * values supported are View.VISIBLE and View.GONE.
 * In addition, the following customizations are supported:
 * 1. Modify default timeout value of 2 seconds by calling setTimeout(long).
 * 2. Set focus to the play/pause button by calling requestPlayButtonFocus().
 *
 * <p>
 * It is also possible to add custom buttons with custom icons and actions inside MediaControlView2.
 * Those buttons will be shown when the overflow button is clicked.
 * See {@link VideoView2#setCustomActions} for more details on how to add.
 *
 * TODO PUBLIC API
 * @hide
 */
public class MediaControlView2 extends ViewGroupHelper<MediaControlView2Provider> {
    /** @hide */
    @IntDef({
            BUTTON_PLAY_PAUSE,
            BUTTON_FFWD,
            BUTTON_REW,
            BUTTON_NEXT,
            BUTTON_PREV,
            BUTTON_SUBTITLE,
            BUTTON_FULL_SCREEN,
            BUTTON_OVERFLOW,
            BUTTON_MUTE,
            BUTTON_ASPECT_RATIO,
            BUTTON_SETTINGS
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Button {}

    /**
     * MediaControlView2 button value for playing and pausing media.
     */
    public static final int BUTTON_PLAY_PAUSE = 1;
    /**
     * MediaControlView2 button value for jumping 30 seconds forward.
     */
    public static final int BUTTON_FFWD = 2;
    /**
     * MediaControlView2 button value for jumping 10 seconds backward.
     */
    public static final int BUTTON_REW = 3;
    /**
     * MediaControlView2 button value for jumping to next media.
     */
    public static final int BUTTON_NEXT = 4;
    /**
     * MediaControlView2 button value for jumping to previous media.
     */
    public static final int BUTTON_PREV = 5;
    /**
     * MediaControlView2 button value for showing/hiding subtitle track.
     */
    public static final int BUTTON_SUBTITLE = 6;
    /**
     * MediaControlView2 button value for toggling full screen.
     */
    public static final int BUTTON_FULL_SCREEN = 7;
    /**
     * MediaControlView2 button value for showing/hiding overflow buttons.
     */
    public static final int BUTTON_OVERFLOW = 8;
    /**
     * MediaControlView2 button value for muting audio.
     */
    public static final int BUTTON_MUTE = 9;
    /**
     * MediaControlView2 button value for adjusting aspect ratio of view.
     */
    public static final int BUTTON_ASPECT_RATIO = 10;
    /**
     * MediaControlView2 button value for showing/hiding settings page.
     */
    public static final int BUTTON_SETTINGS = 11;

    /**
     * String for receiving command to show subtitle from MediaSession. Can be checked by
     * implementing {@link android.media.session.MediaSession.Callback#onCommand}
     */
    public static final String COMMAND_SHOW_SUBTITLE = "showSubtitle";
    /**
     * String for receiving command to hide subtitle from MediaSession. Can be checked by
     * implementing {@link android.media.session.MediaSession.Callback#onCommand}
     */
    public static final String COMMAND_HIDE_SUBTITLE = "hideSubtitle";
    /**
     * String for receiving command to set fullscreen from MediaSession. Can be checked by
     * implementing {@link android.media.session.MediaSession.Callback#onCommand}
     */
    public static final String COMMAND_SET_FULLSCREEN = "setFullscreen";

    public MediaControlView2(@NonNull Context context) {
        this(context, null);
    }

    public MediaControlView2(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public MediaControlView2(@NonNull Context context, @Nullable AttributeSet attrs,
            int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public MediaControlView2(@NonNull Context context, @Nullable AttributeSet attrs,
            int defStyleAttr, int defStyleRes) {
        super((instance, superProvider, privateProvider) ->
                ApiLoader.getProvider(context).createMediaControlView2(
                        (MediaControlView2) instance, superProvider, privateProvider,
                        attrs, defStyleAttr, defStyleRes),
                context, attrs, defStyleAttr, defStyleRes);
        mProvider.initialize(attrs, defStyleAttr, defStyleRes);
    }

    /**
     * Sets MediaController instance to control corresponding MediaSession.
     */
    public void setController(MediaController controller) {
        mProvider.setController_impl(controller);
    }

    /**
     * Changes the visibility state of an individual button. Default value is View.Visible.
     *
     * @param button the {@code Button} assigned to individual buttons
     * <ul>
     * <li>{@link #BUTTON_PLAY_PAUSE}
     * <li>{@link #BUTTON_FFWD}
     * <li>{@link #BUTTON_REW}
     * <li>{@link #BUTTON_NEXT}
     * <li>{@link #BUTTON_PREV}
     * <li>{@link #BUTTON_SUBTITLE}
     * <li>{@link #BUTTON_FULL_SCREEN}
     * <li>{@link #BUTTON_MUTE}
     * <li>{@link #BUTTON_OVERFLOW}
     * <li>{@link #BUTTON_ASPECT_RATIO}
     * <li>{@link #BUTTON_SETTINGS}
     * </ul>
     * @param visibility One of {@link #VISIBLE}, {@link #INVISIBLE}, or {@link #GONE}.
     */
    public void setButtonVisibility(@Button int button, @Visibility int visibility) {
        mProvider.setButtonVisibility_impl(button, visibility);
    }

    /**
     *  Requests focus for the play/pause button.
     */
    public void requestPlayButtonFocus() {
        mProvider.requestPlayButtonFocus_impl();
    }

    /**
     * Sets a new timeout value (in milliseconds) for showing MediaControlView2. The default value
     * is set as 2 seconds.
     * @param timeout the
     */
    public void setTimeout(long timeout) {
        mProvider.setTimeout_impl(timeout);
    }

    /**
     * Retrieves current timeout value (in milliseconds) for showing MediaControlView2. The default
     * value is set as 2 seconds.
     */
    public long getTimeout() {
        return mProvider.getTimeout_impl();
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        mProvider.onLayout_impl(changed, l, t, r, b);
    }
}
