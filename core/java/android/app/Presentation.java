/*
 * Copyright (C) 2012 The Android Open Source Project
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

import static android.view.WindowManager.LayoutParams.INVALID_WINDOW_TYPE;
import static android.view.WindowManager.LayoutParams.TYPE_PRESENTATION;
import static android.view.WindowManager.LayoutParams.TYPE_PRIVATE_PRESENTATION;

import android.annotation.NonNull;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.res.Resources;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManager.DisplayListener;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.Display;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams.WindowType;

import java.util.Objects;

/**
 * Base class for presentations.
 * <p>
 * A presentation is a special kind of dialog whose purpose is to present
 * content on a secondary display.  A {@link Presentation} is associated with
 * the target {@link Display} at creation time and configures its context and
 * resource configuration according to the display's metrics.
 * </p><p>
 * Notably, the {@link Context} of a presentation is different from the context
 * of its containing {@link Activity}.  It is important to inflate the layout
 * of a presentation and load other resources using the presentation's own context
 * to ensure that assets of the correct size and density for the target display
 * are loaded.
 * </p><p>
 * A presentation is automatically canceled (see {@link Dialog#cancel()}) when
 * the display to which it is attached is removed.  An activity should take
 * care of pausing and resuming whatever content is playing within the presentation
 * whenever the activity itself is paused or resumed.
 * </p>
 *
 * <h3>Choosing a presentation display</h3>
 * <p>
 * Before showing a {@link Presentation} it's important to choose the {@link Display}
 * on which it will appear.  Choosing a presentation display is sometimes difficult
 * because there may be multiple displays attached.  Rather than trying to guess
 * which display is best, an application should let the system choose a suitable
 * presentation display.
 * </p><p>
 * There are two main ways to choose a {@link Display}.
 * </p>
 *
 * <h4>Using the media router to choose a presentation display</h4>
 * <p>
 * The easiest way to choose a presentation display is to use the
 * {@link android.media.MediaRouter MediaRouter} API.  The media router service keeps
 * track of which audio and video routes are available on the system.
 * The media router sends notifications whenever routes are selected or unselected
 * or when the preferred presentation display of a route changes.
 * So an application can simply watch for these notifications and show or dismiss
 * a presentation on the preferred presentation display automatically.
 * </p><p>
 * The preferred presentation display is the display that the media router recommends
 * that the application should use if it wants to show content on the secondary display.
 * Sometimes there may not be a preferred presentation display in which
 * case the application should show its content locally without using a presentation.
 * </p><p>
 * Here's how to use the media router to create and show a presentation on the preferred
 * presentation display using {@link android.media.MediaRouter.RouteInfo#getPresentationDisplay()}.
 * </p>
 * <pre>
 * MediaRouter mediaRouter = (MediaRouter) context.getSystemService(Context.MEDIA_ROUTER_SERVICE);
 * MediaRouter.RouteInfo route = mediaRouter.getSelectedRoute();
 * if (route != null) {
 *     Display presentationDisplay = route.getPresentationDisplay();
 *     if (presentationDisplay != null) {
 *         Presentation presentation = new MyPresentation(context, presentationDisplay);
 *         presentation.show();
 *     }
 * }</pre>
 * <p>
 * The following sample code from <code>ApiDemos</code> demonstrates how to use the media
 * router to automatically switch between showing content in the main activity and showing
 * the content in a presentation when a presentation display is available.
 * </p>
 * {@sample development/samples/ApiDemos/src/com/example/android/apis/app/PresentationWithMediaRouterActivity.java
 *      activity}
 *
 * <h4>Using the display manager to choose a presentation display</h4>
 * <p>
 * Another way to choose a presentation display is to use the {@link DisplayManager} API
 * directly.  The display manager service provides functions to enumerate and describe all
 * displays that are attached to the system including displays that may be used
 * for presentations.
 * </p><p>
 * The display manager keeps track of all displays in the system.  However, not all
 * displays are appropriate for showing presentations.  For example, if an activity
 * attempted to show a presentation on the main display it might obscure its own content
 * (it's like opening a dialog on top of your activity).  Creating a presentation on the main
 * display will result in {@link android.view.WindowManager.InvalidDisplayException} being thrown
 * when invoking {@link #show()}.
 * </p><p>
 * Here's how to identify suitable displays for showing presentations using
 * {@link DisplayManager#getDisplays(String)} and the
 * {@link DisplayManager#DISPLAY_CATEGORY_PRESENTATION} category.
 * </p>
 * <pre>
 * DisplayManager displayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
 * Display[] presentationDisplays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
 * if (presentationDisplays.length > 0) {
 *     // If there is more than one suitable presentation display, then we could consider
 *     // giving the user a choice.  For this example, we simply choose the first display
 *     // which is the one the system recommends as the preferred presentation display.
 *     Display display = presentationDisplays[0];
 *     Presentation presentation = new MyPresentation(context, presentationDisplay);
 *     presentation.show();
 * }</pre>
 * <p>
 * The following sample code from <code>ApiDemos</code> demonstrates how to use the display
 * manager to enumerate displays and show content on multiple presentation displays
 * simultaneously.
 * </p>
 * {@sample development/samples/ApiDemos/src/com/example/android/apis/app/PresentationActivity.java
 *      activity}
 *
 * @see android.media.MediaRouter#ROUTE_TYPE_LIVE_VIDEO for information on about live
 * video routes and how to obtain the preferred presentation display for the
 * current media route.
 * @see DisplayManager for information on how to enumerate displays and receive
 * notifications when displays are added or removed.
 */
public class Presentation extends Dialog {
    private static final String TAG = "Presentation";

    private final Display mDisplay;
    private final DisplayManager mDisplayManager;
    private final Handler mHandler = new Handler(Objects.requireNonNull(Looper.myLooper(),
            "Presentation must be constructed on a looper thread."));

    /**
     * Creates a new presentation that is attached to the specified display
     * using the default theme.
     *
     * @param outerContext The context of the application that is showing the presentation.
     * The presentation will create its own context (see {@link #getContext()}) based
     * on this context and information about the associated display.
     * @param display The display to which the presentation should be attached.
     */
    public Presentation(Context outerContext, Display display) {
        this(outerContext, display, 0);
    }

    /**
     * Creates a new presentation that is attached to the specified display
     * using the optionally specified theme.
     *
     * @param outerContext The context of the application that is showing the presentation.
     * The presentation will create its own context (see {@link #getContext()}) based
     * on this context and information about the associated display.
     * From {@link android.os.Build.VERSION_CODES#S}, the presentation will create its own window
     * context based on this context, information about the associated display. Customizing window
     * type by {@link Window#setType(int) #getWindow#setType(int)} causes the mismatch of the window
     * and the created window context, which leads to
     * {@link android.view.WindowManager.InvalidDisplayException} when invoking {@link #show()}.
     * @param display The display to which the presentation should be attached.
     * @param theme A style resource describing the theme to use for the window.
     * See <a href="{@docRoot}guide/topics/resources/available-resources.html#stylesandthemes">
     * Style and Theme Resources</a> for more information about defining and using
     * styles.  This theme is applied on top of the current theme in
     * <var>outerContext</var>.  If 0, the default presentation theme will be used.
     */
    public Presentation(Context outerContext, Display display, int theme) {
        this(outerContext, display, theme, INVALID_WINDOW_TYPE);
    }

    /**
     * Creates a new presentation that is attached to the specified display
     * using the optionally specified theme, and override the default window type for the
     * presentation.
     * @param outerContext The context of the application that is showing the presentation.
     * The presentation will create its own context (see {@link #getContext()}) based
     * on this context and information about the associated display.
     * From {@link android.os.Build.VERSION_CODES#S}, the presentation will create its own window
     * context based on this context, information about the associated display and the window type.
     * If the window type is not specified, the presentation will choose the default type for the
     * presentation.
     * @param display The display to which the presentation should be attached.
     * @param theme A style resource describing the theme to use for the window.
     * See <a href="{@docRoot}guide/topics/resources/available-resources.html#stylesandthemes">
     * Style and Theme Resources</a> for more information about defining and using
     * styles.  This theme is applied on top of the current theme in
     * <var>outerContext</var>.  If 0, the default presentation theme will be used.
     * @param type Window type.
     *
     * @hide
     */
    public Presentation(@NonNull Context outerContext, @NonNull Display display, int theme,
            @WindowType int type) {
        super(createPresentationContext(outerContext, display, theme, type), theme, false);

        mDisplay = display;
        mDisplayManager = getContext().getSystemService(DisplayManager.class);

        final Window w = getWindow();
        final WindowManager.LayoutParams attr = w.getAttributes();
        w.setAttributes(attr);
        w.setGravity(Gravity.FILL);
        w.setType(getWindowType(type, display));
        setCanceledOnTouchOutside(false);
    }

    private static @WindowType int getWindowType(@WindowType int type, @NonNull Display display) {
        if (type != INVALID_WINDOW_TYPE) {
            return type;
        }
        return (display.getFlags() & Display.FLAG_PRIVATE) != 0 ? TYPE_PRIVATE_PRESENTATION
                : TYPE_PRESENTATION;
    }

    /**
     * Gets the {@link Display} that this presentation appears on.
     *
     * @return The display.
     */
    public Display getDisplay() {
        return mDisplay;
    }

    /**
     * Gets the {@link Resources} that should be used to inflate the layout of this presentation.
     * This resources object has been configured according to the metrics of the
     * display that the presentation appears on.
     *
     * @return The presentation resources object.
     */
    public Resources getResources() {
        return getContext().getResources();
    }

    @Override
    protected void onStart() {
        super.onStart();
        mDisplayManager.registerDisplayListener(mDisplayListener, mHandler);
    }

    @Override
    protected void onStop() {
        mDisplayManager.unregisterDisplayListener(mDisplayListener);
        super.onStop();
    }

    /**
     * Inherited from {@link Dialog#show}. Will throw
     * {@link android.view.WindowManager.InvalidDisplayException} if the specified secondary
     * {@link Display} can't be found or if it does not have {@link Display#FLAG_PRESENTATION} set.
     */
    @Override
    public void show() {
        super.show();
    }

    /**
     * Called by the system when the {@link Display} to which the presentation
     * is attached has been removed.
     *
     * The system automatically calls {@link #cancel} to dismiss the presentation
     * after sending this event.
     *
     * @see #getDisplay
     */
    public void onDisplayRemoved() {
    }

    /**
     * Called by the system when the properties of the {@link Display} to which
     * the presentation is attached have changed.
     *
     * @see #getDisplay
     */
    public void onDisplayChanged() {
    }

    private void handleDisplayRemoved() {
        onDisplayRemoved();
        cancel();
    }

    private void handleDisplayChanged() {
        onDisplayChanged();
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, publicAlternatives = "{@code N/A}")
    private static Context createPresentationContext(Context outerContext, Display display,
            int theme) {
        return createPresentationContext(outerContext, display, theme, INVALID_WINDOW_TYPE);
    }

    private static Context createPresentationContext(
            Context outerContext, Display display, int theme, @WindowType int type) {
        if (outerContext == null) {
            throw new IllegalArgumentException("outerContext must not be null");
        }
        if (display == null) {
            throw new IllegalArgumentException("display must not be null");
        }

        Context windowContext = outerContext.createDisplayContext(display)
                .createWindowContext(getWindowType(type, display), null /* options */);
        if (theme == 0) {
            TypedValue outValue = new TypedValue();
            windowContext.getTheme().resolveAttribute(
                    com.android.internal.R.attr.presentationTheme, outValue, true);
            theme = outValue.resourceId;
        }
        return new ContextThemeWrapper(windowContext, theme);
    }

    private final DisplayListener mDisplayListener = new DisplayListener() {
        @Override
        public void onDisplayAdded(int displayId) {
        }

        @Override
        public void onDisplayRemoved(int displayId) {
            if (displayId == mDisplay.getDisplayId()) {
                handleDisplayRemoved();
            }
        }

        @Override
        public void onDisplayChanged(int displayId) {
            if (displayId == mDisplay.getDisplayId()) {
                handleDisplayChanged();
            }
        }
    };
}
