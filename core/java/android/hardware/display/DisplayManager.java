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

package android.hardware.display;

import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.HdrCapabilities.HdrType;

import android.Manifest;
import android.annotation.FloatRange;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.LongDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.app.KeyguardManager;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Point;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.Looper;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.view.Display;
import android.view.Surface;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;


/**
 * Manages the properties of attached displays.
 */
@SystemService(Context.DISPLAY_SERVICE)
public final class DisplayManager {
    private static final String TAG = "DisplayManager";
    private static final boolean DEBUG = false;

    private final Context mContext;
    private final DisplayManagerGlobal mGlobal;

    private final Object mLock = new Object();
    private final SparseArray<Display> mDisplays = new SparseArray<Display>();

    private final ArrayList<Display> mTempDisplays = new ArrayList<Display>();

    /**
     * Broadcast receiver that indicates when the Wifi display status changes.
     * <p>
     * The status is provided as a {@link WifiDisplayStatus} object in the
     * {@link #EXTRA_WIFI_DISPLAY_STATUS} extra.
     * </p><p>
     * This broadcast is only sent to registered receivers and can only be sent by the system.
     * </p>
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static final String ACTION_WIFI_DISPLAY_STATUS_CHANGED =
            "android.hardware.display.action.WIFI_DISPLAY_STATUS_CHANGED";

    /**
     * Contains a {@link WifiDisplayStatus} object.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static final String EXTRA_WIFI_DISPLAY_STATUS =
            "android.hardware.display.extra.WIFI_DISPLAY_STATUS";

    /**
     * Display category: Presentation displays.
     * <p>
     * This category can be used to identify secondary displays that are suitable for
     * use as presentation displays such as external or wireless displays.  Applications
     * may automatically project their content to presentation displays to provide
     * richer second screen experiences.
     * </p>
     *
     * @see android.app.Presentation
     * @see Display#FLAG_PRESENTATION
     * @see #getDisplays(String)
     */
    public static final String DISPLAY_CATEGORY_PRESENTATION =
            "android.hardware.display.category.PRESENTATION";

    /**
     * Display category: All displays, including disabled displays.
     * <p>
     * This returns all displays, including currently disabled and inaccessible displays.
     *
     * @see #getDisplays(String)
     * @hide
     */
    public static final String DISPLAY_CATEGORY_ALL_INCLUDING_DISABLED =
            "android.hardware.display.category.ALL_INCLUDING_DISABLED";

    /** @hide **/
    @IntDef(prefix = "VIRTUAL_DISPLAY_FLAG_", flag = true, value = {
            VIRTUAL_DISPLAY_FLAG_PUBLIC,
            VIRTUAL_DISPLAY_FLAG_PRESENTATION,
            VIRTUAL_DISPLAY_FLAG_SECURE,
            VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
            VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            VIRTUAL_DISPLAY_FLAG_CAN_SHOW_WITH_INSECURE_KEYGUARD,
            VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH,
            VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT,
            VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL,
            VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS,
            VIRTUAL_DISPLAY_FLAG_TRUSTED,
            VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP,
            VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED,
            VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface VirtualDisplayFlag {}

    /**
     * Virtual display flag: Create a public display.
     *
     * <h3>Public virtual displays</h3>
     * <p>
     * When this flag is set, the virtual display is public.
     * </p><p>
     * A public virtual display behaves just like most any other display that is connected
     * to the system such as an external or wireless display.  Applications can open
     * windows on the display and the system may mirror the contents of other displays
     * onto it.
     * </p><p>
     * Creating a public virtual display that isn't restricted to own-content only implicitly
     * creates an auto-mirroring display. See {@link #VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR} for
     * restrictions on who is allowed to create an auto-mirroring display.
     * </p>
     *
     * <h3>Private virtual displays</h3>
     * <p>
     * When this flag is not set, the virtual display is private as defined by the
     * {@link Display#FLAG_PRIVATE} display flag.
     * </p>
     *
     * <p>
     * A private virtual display belongs to the application that created it.  Only the a owner of a
     * private virtual display and the apps that are already on that display are allowed to place
     * windows upon it.  The private virtual display also does not participate in display mirroring:
     * it will neither receive mirrored content from another display nor allow its own content to be
     * mirrored elsewhere.  More precisely, the only processes that are allowed to enumerate or
     * interact with the private display are those that have the same UID as the application that
     * originally created the private virtual display or as the activities that are already on that
     * display.
     * </p>
     *
     * @see #createVirtualDisplay
     * @see #VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
     * @see #VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
     */
    public static final int VIRTUAL_DISPLAY_FLAG_PUBLIC = 1 << 0;

    /**
     * Virtual display flag: Create a presentation display.
     *
     * <h3>Presentation virtual displays</h3>
     * <p>
     * When this flag is set, the virtual display is registered as a presentation
     * display in the {@link #DISPLAY_CATEGORY_PRESENTATION presentation display category}.
     * Applications may automatically project their content to presentation displays
     * to provide richer second screen experiences.
     * </p>
     *
     * <h3>Non-presentation virtual displays</h3>
     * <p>
     * When this flag is not set, the virtual display is not registered as a presentation
     * display.  Applications can still project their content on the display but they
     * will typically not do so automatically.  This option is appropriate for
     * more special-purpose displays.
     * </p>
     *
     * @see android.app.Presentation
     * @see #createVirtualDisplay
     * @see #DISPLAY_CATEGORY_PRESENTATION
     * @see Display#FLAG_PRESENTATION
     */
    public static final int VIRTUAL_DISPLAY_FLAG_PRESENTATION = 1 << 1;

    /**
     * Virtual display flag: Create a secure display.
     *
     * <h3>Secure virtual displays</h3>
     * <p>
     * When this flag is set, the virtual display is considered secure as defined
     * by the {@link Display#FLAG_SECURE} display flag.  The caller promises to take
     * reasonable measures, such as over-the-air encryption, to prevent the contents
     * of the display from being intercepted or recorded on a persistent medium.
     * </p><p>
     * Creating a secure virtual display requires the CAPTURE_SECURE_VIDEO_OUTPUT permission.
     * This permission is reserved for use by system components and is not available to
     * third-party applications.
     * </p>
     *
     * <h3>Non-secure virtual displays</h3>
     * <p>
     * When this flag is not set, the virtual display is considered unsecure.
     * The content of secure windows will be blanked if shown on this display.
     * </p>
     *
     * @see Display#FLAG_SECURE
     * @see #createVirtualDisplay
     */
    public static final int VIRTUAL_DISPLAY_FLAG_SECURE = 1 << 2;

    /**
     * Virtual display flag: Only show this display's own content; do not mirror
     * the content of another display.
     *
     * <p>
     * This flag is used in conjunction with {@link #VIRTUAL_DISPLAY_FLAG_PUBLIC}.
     * Ordinarily public virtual displays will automatically mirror the content of the
     * default display if they have no windows of their own.  When this flag is
     * specified, the virtual display will only ever show its own content and
     * will be blanked instead if it has no windows.
     * </p>
     *
     * <p>
     * This flag is mutually exclusive with {@link #VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR}.  If both
     * flags are specified then the own-content only behavior will be applied.
     * </p>
     *
     * <p>
     * This behavior of this flag is implied whenever neither {@link #VIRTUAL_DISPLAY_FLAG_PUBLIC}
     * nor {@link #VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR} have been set.  This flag is only required to
     * override the default behavior when creating a public display.
     * </p>
     *
     * @see #createVirtualDisplay
     */
    public static final int VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY = 1 << 3;


    /**
     * Virtual display flag: Allows content to be mirrored on private displays when no content is
     * being shown.
     *
     * <p>
     * This flag is mutually exclusive with {@link #VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY}.
     * If both flags are specified then the own-content only behavior will be applied.
     * </p>
     *
     * <p>
     * The behavior of this flag is implied whenever {@link #VIRTUAL_DISPLAY_FLAG_PUBLIC} is set
     * and {@link #VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY} has not been set.   This flag is only
     * required to override the default behavior when creating a private display.
     * </p>
     *
     * <p>
     * Creating an auto-mirroing virtual display requires the CAPTURE_VIDEO_OUTPUT
     * or CAPTURE_SECURE_VIDEO_OUTPUT permission.
     * These permissions are reserved for use by system components and are not available to
     * third-party applications.
     *
     * Alternatively, an appropriate {@link MediaProjection} may be used to create an
     * auto-mirroring virtual display.
     * </p>
     *
     * @see #createVirtualDisplay
     */
    public static final int VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR = 1 << 4;

    /**
     * Virtual display flag: Allows content to be displayed on private virtual displays when
     * keyguard is shown but is insecure.
     *
     * <p>
     * This might be used in a case when the content of a virtual display is captured and sent to an
     * external hardware display that is not visible to the system directly. This flag will allow
     * the continued display of content while other displays will be covered by a keyguard which
     * doesn't require providing credentials to unlock. This means that there is either no password
     * or other authentication method set, or the device is in a trusted state -
     * {@link android.service.trust.TrustAgentService} has available and active trust agent.
     * </p><p>
     * This flag can only be applied to private displays as defined by the
     * {@link Display#FLAG_PRIVATE} display flag. It is mutually exclusive with
     * {@link #VIRTUAL_DISPLAY_FLAG_PUBLIC}. If both flags are specified then this flag's behavior
     * will not be applied.
     * </p>
     *
     * @see #createVirtualDisplay
     * @see KeyguardManager#isDeviceSecure()
     * @see KeyguardManager#isDeviceLocked()
     * @hide
     */
    // TODO (b/114338689): Remove the flag and use IWindowManager#shouldShowWithInsecureKeyguard
    // TODO: Update name and documentation and un-hide the flag. Don't change the value before that.
    public static final int VIRTUAL_DISPLAY_FLAG_CAN_SHOW_WITH_INSECURE_KEYGUARD = 1 << 5;

    /**
     * Virtual display flag: Specifies that the virtual display can be associated with a
     * touchpad device that matches its uniqueId.
     *
     * @see #createVirtualDisplay
     * @hide
     */
    public static final int VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH = 1 << 6;

    /**
     * Virtual display flag: Indicates that the orientation of this display device is coupled to
     * the rotation of its associated logical display.
     *
     * @see #createVirtualDisplay
     * @hide
     */
    public static final int VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT = 1 << 7;

    /**
     * Virtual display flag: Indicates that the contents will be destroyed once
     * the display is removed.
     *
     * Public virtual displays without this flag will move their content to main display
     * stack once they're removed. Private vistual displays will always destroy their
     * content on removal even without this flag.
     *
     * @see #createVirtualDisplay
     * @hide
     */
    // TODO (b/114338689): Remove the flag and use WindowManager#REMOVE_CONTENT_MODE_DESTROY
    public static final int VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 << 8;

    /**
     * Virtual display flag: Indicates that the display should support system decorations. Virtual
     * displays without this flag shouldn't show home, IME or any other system decorations.
     * <p>This flag doesn't work without {@link #VIRTUAL_DISPLAY_FLAG_TRUSTED}</p>
     *
     * @see #createVirtualDisplay
     * @see #VIRTUAL_DISPLAY_FLAG_TRUSTED
     * @hide
     */
    // TODO (b/114338689): Remove the flag and use IWindowManager#setShouldShowSystemDecors
    @TestApi
    public static final int VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS = 1 << 9;

    /**
     * Virtual display flags: Indicates that the display is trusted to show system decorations and
     * receive inputs without users' touch.
     *
     * @see #createVirtualDisplay
     * @see #VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS
     * @hide
     */
    @SystemApi
    public static final int VIRTUAL_DISPLAY_FLAG_TRUSTED = 1 << 10;

    /**
     * Virtual display flags: Indicates that the display should not be a part of the default
     * DisplayGroup and instead be part of a new DisplayGroup.
     *
     * @see #createVirtualDisplay
     * @hide
     */
    public static final int VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP = 1 << 11;

    /**
     * Virtual display flags: Indicates that the virtual display should always be unlocked and not
     * have keyguard displayed on it. Only valid for virtual displays that aren't in the default
     * display group.
     *
     * @see #createVirtualDisplay
     * @see #VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP
     * @hide
     */
    public static final int VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED = 1 << 12;

    /**
     * Virtual display flags: Indicates that the display should not play sound effects or perform
     * haptic feedback when the user touches the screen.
     *
     * @see #createVirtualDisplay
     * @hide
     */
    public static final int VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED = 1 << 13;

    /** @hide */
    @IntDef(prefix = {"MATCH_CONTENT_FRAMERATE_"}, value = {
            MATCH_CONTENT_FRAMERATE_UNKNOWN,
            MATCH_CONTENT_FRAMERATE_NEVER,
            MATCH_CONTENT_FRAMERATE_SEAMLESSS_ONLY,
            MATCH_CONTENT_FRAMERATE_ALWAYS,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface MatchContentFrameRateType {}

    /**
     * Match content frame rate user preference is unknown.
     */
    public static final int MATCH_CONTENT_FRAMERATE_UNKNOWN = -1;

    /**
     * No mode switching is allowed.
     */
    public static final int MATCH_CONTENT_FRAMERATE_NEVER = 0;

    /**
     * Only refresh rate switches without visual interruptions are allowed.
     */
    public static final int MATCH_CONTENT_FRAMERATE_SEAMLESSS_ONLY = 1;

    /**
     * Refresh rate switches between all refresh rates are allowed even if they have visual
     * interruptions for the user.
     */
    public static final int MATCH_CONTENT_FRAMERATE_ALWAYS = 2;

    /** @hide */
    @IntDef(prefix = {"SWITCHING_TYPE_"}, value = {
            SWITCHING_TYPE_NONE,
            SWITCHING_TYPE_WITHIN_GROUPS,
            SWITCHING_TYPE_ACROSS_AND_WITHIN_GROUPS,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SwitchingType {}

    /**
     * No mode switching will happen.
     * @hide
     */
    @TestApi
    public static final int SWITCHING_TYPE_NONE = 0;

    /**
     * Allow only refresh rate switching between modes in the same configuration group. This way
     * only switches without visual interruptions for the user will be allowed.
     * @hide
     */
    @TestApi
    public static final int SWITCHING_TYPE_WITHIN_GROUPS = 1;

    /**
     * Allow refresh rate switching between all refresh rates even if the switch with have visual
     * interruptions for the user.
     * @hide
     */
    @TestApi
    public static final int SWITCHING_TYPE_ACROSS_AND_WITHIN_GROUPS = 2;

    /**
     * @hide
     */
    @LongDef(flag = true, prefix = {"EVENT_FLAG_"}, value = {
            EVENT_FLAG_DISPLAY_ADDED,
            EVENT_FLAG_DISPLAY_CHANGED,
            EVENT_FLAG_DISPLAY_REMOVED,
            EVENT_FLAG_DISPLAY_BRIGHTNESS
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface EventsMask {}

    /**
     * Event type for when a new display is added.
     *
     * @see #registerDisplayListener(DisplayListener, Handler, long)
     *
     * @hide
     */
    public static final long EVENT_FLAG_DISPLAY_ADDED = 1L << 0;

    /**
     * Event type for when a display is removed.
     *
     * @see #registerDisplayListener(DisplayListener, Handler, long)
     *
     * @hide
     */
    public static final long EVENT_FLAG_DISPLAY_REMOVED = 1L << 1;

    /**
     * Event type for when a display is changed.
     *
     * @see #registerDisplayListener(DisplayListener, Handler, long)
     *
     * @hide
     */
    public static final long EVENT_FLAG_DISPLAY_CHANGED = 1L << 2;

    /**
     * Event flag to register for a display's brightness changes. This notification is sent
     * through the {@link DisplayListener#onDisplayChanged} callback method. New brightness
     * values can be retrieved via {@link android.view.Display#getBrightnessInfo()}.
     *
     * @see #registerDisplayListener(DisplayListener, Handler, long)
     *
     * @hide
     */
    public static final long EVENT_FLAG_DISPLAY_BRIGHTNESS = 1L << 3;

    /** @hide */
    public DisplayManager(Context context) {
        mContext = context;
        mGlobal = DisplayManagerGlobal.getInstance();
    }

    /**
     * Gets information about a logical display.
     *
     * The display metrics may be adjusted to provide compatibility
     * for legacy applications.
     *
     * @param displayId The logical display id.
     * @return The display object, or null if there is no valid display with the given id.
     */
    public Display getDisplay(int displayId) {
        synchronized (mLock) {
            return getOrCreateDisplayLocked(displayId, false /*assumeValid*/);
        }
    }

    /**
     * Gets all currently valid logical displays.
     *
     * @return An array containing all displays.
     */
    public Display[] getDisplays() {
        return getDisplays(null);
    }

    /**
     * Gets all currently valid logical displays of the specified category.
     * <p>
     * When there are multiple displays in a category the returned displays are sorted
     * of preference.  For example, if the requested category is
     * {@link #DISPLAY_CATEGORY_PRESENTATION} and there are multiple presentation displays
     * then the displays are sorted so that the first display in the returned array
     * is the most preferred presentation display.  The application may simply
     * use the first display or allow the user to choose.
     * </p>
     *
     * @param category The requested display category or null to return all displays.
     * @return An array containing all displays sorted by order of preference.
     *
     * @see #DISPLAY_CATEGORY_PRESENTATION
     */
    public Display[] getDisplays(String category) {
        boolean includeDisabled = (category != null
                && category.equals(DISPLAY_CATEGORY_ALL_INCLUDING_DISABLED));
        final int[] displayIds = mGlobal.getDisplayIds(includeDisabled);
        synchronized (mLock) {
            try {
                if (DISPLAY_CATEGORY_PRESENTATION.equals(category)) {
                    addPresentationDisplaysLocked(mTempDisplays, displayIds, Display.TYPE_WIFI);
                    addPresentationDisplaysLocked(mTempDisplays, displayIds, Display.TYPE_EXTERNAL);
                    addPresentationDisplaysLocked(mTempDisplays, displayIds, Display.TYPE_OVERLAY);
                    addPresentationDisplaysLocked(mTempDisplays, displayIds, Display.TYPE_VIRTUAL);
                    addPresentationDisplaysLocked(mTempDisplays, displayIds, Display.TYPE_INTERNAL);
                } else if (category == null
                        || DISPLAY_CATEGORY_ALL_INCLUDING_DISABLED.equals(category)) {
                    addAllDisplaysLocked(mTempDisplays, displayIds);
                }
                return mTempDisplays.toArray(new Display[mTempDisplays.size()]);
            } finally {
                mTempDisplays.clear();
            }
        }
    }

    private void addAllDisplaysLocked(ArrayList<Display> displays, int[] displayIds) {
        for (int i = 0; i < displayIds.length; i++) {
            Display display = getOrCreateDisplayLocked(displayIds[i], true /*assumeValid*/);
            if (display != null) {
                displays.add(display);
            }
        }
    }

    private void addPresentationDisplaysLocked(
            ArrayList<Display> displays, int[] displayIds, int matchType) {
        for (int i = 0; i < displayIds.length; i++) {
            if (displayIds[i] == DEFAULT_DISPLAY) {
                continue;
            }
            Display display = getOrCreateDisplayLocked(displayIds[i], true /*assumeValid*/);
            if (display != null
                    && (display.getFlags() & Display.FLAG_PRESENTATION) != 0
                    && display.getType() == matchType) {
                displays.add(display);
            }
        }
    }

    private Display getOrCreateDisplayLocked(int displayId, boolean assumeValid) {
        Display display = mDisplays.get(displayId);
        if (display == null) {
            // TODO: We cannot currently provide any override configurations for metrics on displays
            // other than the display the context is associated with.
            final Resources resources = mContext.getDisplayId() == displayId
                    ? mContext.getResources() : null;

            display = mGlobal.getCompatibleDisplay(displayId, resources);
            if (display != null) {
                mDisplays.put(displayId, display);
            }
        } else if (!assumeValid && !display.isValid()) {
            display = null;
        }
        return display;
    }

    /**
     * Registers a display listener to receive notifications about when
     * displays are added, removed or changed.
     *
     * @param listener The listener to register.
     * @param handler The handler on which the listener should be invoked, or null
     * if the listener should be invoked on the calling thread's looper.
     *
     * @see #unregisterDisplayListener
     */
    public void registerDisplayListener(DisplayListener listener, Handler handler) {
        registerDisplayListener(listener, handler, EVENT_FLAG_DISPLAY_ADDED
                | EVENT_FLAG_DISPLAY_CHANGED | EVENT_FLAG_DISPLAY_REMOVED);
    }

    /**
     * Registers a display listener to receive notifications about given display event types.
     *
     * @param listener The listener to register.
     * @param handler The handler on which the listener should be invoked, or null
     * if the listener should be invoked on the calling thread's looper.
     * @param eventsMask A bitmask of the event types for which this listener is subscribed.
     *
     * @see #EVENT_FLAG_DISPLAY_ADDED
     * @see #EVENT_FLAG_DISPLAY_CHANGED
     * @see #EVENT_FLAG_DISPLAY_REMOVED
     * @see #EVENT_FLAG_DISPLAY_BRIGHTNESS
     * @see #registerDisplayListener(DisplayListener, Handler)
     * @see #unregisterDisplayListener
     *
     * @hide
     */
    public void registerDisplayListener(@NonNull DisplayListener listener,
            @Nullable Handler handler, @EventsMask long eventsMask) {
        mGlobal.registerDisplayListener(listener, handler, eventsMask);
    }

    /**
     * Unregisters a display listener.
     *
     * @param listener The listener to unregister.
     *
     * @see #registerDisplayListener
     */
    public void unregisterDisplayListener(DisplayListener listener) {
        mGlobal.unregisterDisplayListener(listener);
    }

    /**
     * Starts scanning for available Wifi displays.
     * The results are sent as a {@link #ACTION_WIFI_DISPLAY_STATUS_CHANGED} broadcast.
     * <p>
     * Calls to this method nest and must be matched by an equal number of calls to
     * {@link #stopWifiDisplayScan()}.
     * </p><p>
     * Requires {@link android.Manifest.permission#CONFIGURE_WIFI_DISPLAY}.
     * </p>
     *
     * @hide
     */
    @UnsupportedAppUsage
    public void startWifiDisplayScan() {
        mGlobal.startWifiDisplayScan();
    }

    /**
     * Stops scanning for available Wifi displays.
     * <p>
     * Requires {@link android.Manifest.permission#CONFIGURE_WIFI_DISPLAY}.
     * </p>
     *
     * @hide
     */
    @UnsupportedAppUsage
    public void stopWifiDisplayScan() {
        mGlobal.stopWifiDisplayScan();
    }

    /**
     * Connects to a Wifi display.
     * The results are sent as a {@link #ACTION_WIFI_DISPLAY_STATUS_CHANGED} broadcast.
     * <p>
     * Automatically remembers the display after a successful connection, if not
     * already remembered.
     * </p><p>
     * Requires {@link android.Manifest.permission#CONFIGURE_WIFI_DISPLAY}.
     * </p>
     *
     * @param deviceAddress The MAC address of the device to which we should connect.
     * @hide
     */
    @UnsupportedAppUsage
    public void connectWifiDisplay(String deviceAddress) {
        mGlobal.connectWifiDisplay(deviceAddress);
    }

    /** @hide */
    @UnsupportedAppUsage
    public void pauseWifiDisplay() {
        mGlobal.pauseWifiDisplay();
    }

    /** @hide */
    @UnsupportedAppUsage
    public void resumeWifiDisplay() {
        mGlobal.resumeWifiDisplay();
    }

    /**
     * Disconnects from the current Wifi display.
     * The results are sent as a {@link #ACTION_WIFI_DISPLAY_STATUS_CHANGED} broadcast.
     * @hide
     */
    @UnsupportedAppUsage
    public void disconnectWifiDisplay() {
        mGlobal.disconnectWifiDisplay();
    }

    /**
     * Renames a Wifi display.
     * <p>
     * The display must already be remembered for this call to succeed.  In other words,
     * we must already have successfully connected to the display at least once and then
     * not forgotten it.
     * </p><p>
     * Requires {@link android.Manifest.permission#CONFIGURE_WIFI_DISPLAY}.
     * </p>
     *
     * @param deviceAddress The MAC address of the device to rename.
     * @param alias The alias name by which to remember the device, or null
     * or empty if no alias should be used.
     * @hide
     */
    @UnsupportedAppUsage
    public void renameWifiDisplay(String deviceAddress, String alias) {
        mGlobal.renameWifiDisplay(deviceAddress, alias);
    }

    /**
     * Forgets a previously remembered Wifi display.
     * <p>
     * Automatically disconnects from the display if currently connected to it.
     * </p><p>
     * Requires {@link android.Manifest.permission#CONFIGURE_WIFI_DISPLAY}.
     * </p>
     *
     * @param deviceAddress The MAC address of the device to forget.
     * @hide
     */
    @UnsupportedAppUsage
    public void forgetWifiDisplay(String deviceAddress) {
        mGlobal.forgetWifiDisplay(deviceAddress);
    }

    /**
     * Gets the current Wifi display status.
     * Watch for changes in the status by registering a broadcast receiver for
     * {@link #ACTION_WIFI_DISPLAY_STATUS_CHANGED}.
     *
     * @return The current Wifi display status.
     * @hide
     */
    @UnsupportedAppUsage
    public WifiDisplayStatus getWifiDisplayStatus() {
        return mGlobal.getWifiDisplayStatus();
    }

    /**
     * Set the level of color saturation to apply to the display.
     * @param level The amount of saturation to apply, between 0 and 1 inclusive.
     * 0 produces a grayscale image, 1 is normal.
     *
     * @hide
     * @deprecated use {@link ColorDisplayManager#setSaturationLevel(int)} instead. The level passed
     * as a parameter here will be rounded to the nearest hundredth.
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.CONTROL_DISPLAY_SATURATION)
    public void setSaturationLevel(float level) {
        if (level < 0f || level > 1f) {
            throw new IllegalArgumentException("Saturation level must be between 0 and 1");
        }
        final ColorDisplayManager cdm = mContext.getSystemService(ColorDisplayManager.class);
        cdm.setSaturationLevel(Math.round(level * 100f));
    }

    /**
     * Sets the HDR types that have been disabled by user.
     * @param userDisabledTypes the HDR types to disable.
     * @hide
     */
    @TestApi
    @RequiresPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
    public void setUserDisabledHdrTypes(@NonNull @HdrType int[] userDisabledTypes) {
        mGlobal.setUserDisabledHdrTypes(userDisabledTypes);
    }

    /**
     * Sets whether or not the user disabled HDR types are returned from
     * {@link Display#getHdrCapabilities}.
     *
     * @param areUserDisabledHdrTypesAllowed If true, the user-disabled types
     * are ignored and returned, if the display supports them. If false, the
     * user-disabled types are taken into consideration and are never returned,
     * even if the display supports them.
     * @hide
     */
    @TestApi
    @RequiresPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
    public void setAreUserDisabledHdrTypesAllowed(boolean areUserDisabledHdrTypesAllowed) {
        mGlobal.setAreUserDisabledHdrTypesAllowed(areUserDisabledHdrTypesAllowed);
    }

    /**
     * Returns whether or not the user-disabled HDR types are returned from
     * {@link Display#getHdrCapabilities}.
     *
     * @hide
     */
    @TestApi
    public boolean areUserDisabledHdrTypesAllowed() {
        return mGlobal.areUserDisabledHdrTypesAllowed();
    }

    /**
     * Returns the HDR formats disabled by the user.
     *
     * @hide
     */
    @TestApi
    public @NonNull int[] getUserDisabledHdrTypes() {
        return mGlobal.getUserDisabledHdrTypes();
    }


    /**
     * Creates a virtual display.
     *
     * @see #createVirtualDisplay(String, int, int, int, Surface, int,
     * VirtualDisplay.Callback, Handler)
     */
    public VirtualDisplay createVirtualDisplay(@NonNull String name,
            @IntRange(from = 1) int width,
            @IntRange(from = 1) int height,
            @IntRange(from = 1) int densityDpi,
            @Nullable Surface surface,
            @VirtualDisplayFlag int flags) {
        return createVirtualDisplay(name, width, height, densityDpi, surface, flags, null, null);
    }

    /**
     * Creates a virtual display.
     * <p>
     * The content of a virtual display is rendered to a {@link Surface} provided
     * by the application.
     * </p><p>
     * The virtual display should be {@link VirtualDisplay#release released}
     * when no longer needed.  Because a virtual display renders to a surface
     * provided by the application, it will be released automatically when the
     * process terminates and all remaining windows on it will be forcibly removed.
     * </p><p>
     * The behavior of the virtual display depends on the flags that are provided
     * to this method.  By default, virtual displays are created to be private,
     * non-presentation and unsecure.  Permissions may be required to use certain flags.
     * </p><p>
     * As of {@link android.os.Build.VERSION_CODES#KITKAT_WATCH}, the surface may
     * be attached or detached dynamically using {@link VirtualDisplay#setSurface}.
     * Previously, the surface had to be non-null when {@link #createVirtualDisplay}
     * was called and could not be changed for the lifetime of the display.
     * </p><p>
     * Detaching the surface that backs a virtual display has a similar effect to
     * turning off the screen.
     * </p>
     *
     * @param name The name of the virtual display, must be non-empty.
     * @param width The width of the virtual display in pixels, must be greater than 0.
     * @param height The height of the virtual display in pixels, must be greater than 0.
     * @param densityDpi The density of the virtual display in dpi, must be greater than 0.
     * @param surface The surface to which the content of the virtual display should
     * be rendered, or null if there is none initially.
     * @param flags A combination of virtual display flags:
     * {@link #VIRTUAL_DISPLAY_FLAG_PUBLIC}, {@link #VIRTUAL_DISPLAY_FLAG_PRESENTATION},
     * {@link #VIRTUAL_DISPLAY_FLAG_SECURE}, {@link #VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY},
     * or {@link #VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR}.
     * @param callback Callback to call when the state of the {@link VirtualDisplay} changes
     * @param handler The handler on which the listener should be invoked, or null
     * if the listener should be invoked on the calling thread's looper.
     * @return The newly created virtual display, or null if the application could
     * not create the virtual display.
     *
     * @throws SecurityException if the caller does not have permission to create
     * a virtual display with the specified flags.
     */
    public VirtualDisplay createVirtualDisplay(@NonNull String name,
            @IntRange(from = 1) int width,
            @IntRange(from = 1) int height,
            @IntRange(from = 1) int densityDpi,
            @Nullable Surface surface,
            @VirtualDisplayFlag int flags,
            @Nullable VirtualDisplay.Callback callback,
            @Nullable Handler handler) {
        final VirtualDisplayConfig.Builder builder = new VirtualDisplayConfig.Builder(name, width,
                height, densityDpi);
        builder.setFlags(flags);
        if (surface != null) {
            builder.setSurface(surface);
        }
        return createVirtualDisplay(null /* projection */, builder.build(), callback, handler,
                null /* windowContext */);
    }

    // TODO : Remove this hidden API after remove all callers. (Refer to MultiDisplayService)
    /** @hide */
    public VirtualDisplay createVirtualDisplay(
            @Nullable MediaProjection projection,
            @NonNull String name,
            @IntRange(from = 1) int width,
            @IntRange(from = 1) int height,
            @IntRange(from = 1) int densityDpi,
            @Nullable Surface surface,
            @VirtualDisplayFlag int flags,
            @Nullable VirtualDisplay.Callback callback,
            @Nullable Handler handler,
            @Nullable String uniqueId) {
        final VirtualDisplayConfig.Builder builder = new VirtualDisplayConfig.Builder(name, width,
                height, densityDpi);
        builder.setFlags(flags);
        if (uniqueId != null) {
            builder.setUniqueId(uniqueId);
        }
        if (surface != null) {
            builder.setSurface(surface);
        }
        return createVirtualDisplay(projection, builder.build(), callback, handler,
                null /* windowContext */);
    }

    /** @hide */
    public VirtualDisplay createVirtualDisplay(@Nullable MediaProjection projection,
            @NonNull VirtualDisplayConfig virtualDisplayConfig,
            @Nullable VirtualDisplay.Callback callback, @Nullable Handler handler,
            @Nullable Context windowContext) {
        Executor executor = null;
        // If callback is null, the executor will not be used. Avoid creating the handler and the
        // handler executor.
        if (callback != null) {
            executor = new HandlerExecutor(
                    Handler.createAsync(handler != null ? handler.getLooper() : Looper.myLooper()));
        }
        return mGlobal.createVirtualDisplay(mContext, projection, virtualDisplayConfig, callback,
                executor, windowContext);
    }

    /**
     * Gets the stable device display size, in pixels.
     *
     * This should really only be used for things like server-side filtering of available
     * applications. Most applications don't need the level of stability guaranteed by this and
     * should instead query either the size of the display they're currently running on or the
     * size of the default display.
     * @hide
     */
    @SystemApi
    public Point getStableDisplaySize() {
        return mGlobal.getStableDisplaySize();
    }

    /**
     * Fetch {@link BrightnessChangeEvent}s.
     * @hide until we make it a system api.
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.BRIGHTNESS_SLIDER_USAGE)
    public List<BrightnessChangeEvent> getBrightnessEvents() {
        return mGlobal.getBrightnessEvents(mContext.getOpPackageName());
    }

    /**
     * Fetch {@link AmbientBrightnessDayStats}s.
     *
     * @hide until we make it a system api
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.ACCESS_AMBIENT_LIGHT_STATS)
    public List<AmbientBrightnessDayStats> getAmbientBrightnessStats() {
        return mGlobal.getAmbientBrightnessStats();
    }

    /**
     * Sets the global display brightness configuration.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.CONFIGURE_DISPLAY_BRIGHTNESS)
    public void setBrightnessConfiguration(BrightnessConfiguration c) {
        setBrightnessConfigurationForUser(c, mContext.getUserId(), mContext.getPackageName());
    }

    /**
     * Sets the brightness configuration for the specified display.
     * If the specified display doesn't exist, then this will return and do nothing.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.CONFIGURE_DISPLAY_BRIGHTNESS)
    public void setBrightnessConfigurationForDisplay(@NonNull BrightnessConfiguration c,
            @NonNull String uniqueId) {
        mGlobal.setBrightnessConfigurationForDisplay(c, uniqueId, mContext.getUserId(),
                mContext.getPackageName());
    }

    /**
     * Gets the brightness configuration for the specified display and default user.
     * Returns the default configuration if unset or display is invalid.
     *
     * @hide
     */
    @Nullable
    @SystemApi
    @RequiresPermission(Manifest.permission.CONFIGURE_DISPLAY_BRIGHTNESS)
    public BrightnessConfiguration getBrightnessConfigurationForDisplay(
            @NonNull String uniqueId) {
        return mGlobal.getBrightnessConfigurationForDisplay(uniqueId, mContext.getUserId());
    }

    /**
     * Sets the global display brightness configuration for a specific user.
     *
     * Note this requires the INTERACT_ACROSS_USERS permission if setting the configuration for a
     * user other than the one you're currently running as.
     *
     * @hide
     */
    public void setBrightnessConfigurationForUser(BrightnessConfiguration c, int userId,
            String packageName) {
        mGlobal.setBrightnessConfigurationForUser(c, userId, packageName);
    }

    /**
     * Gets the global display brightness configuration or the default curve if one hasn't been set.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.CONFIGURE_DISPLAY_BRIGHTNESS)
    public BrightnessConfiguration getBrightnessConfiguration() {
        return getBrightnessConfigurationForUser(mContext.getUserId());
    }

    /**
     * Gets the global display brightness configuration or the default curve if one hasn't been set
     * for a specific user.
     *
     * Note this requires the INTERACT_ACROSS_USERS permission if getting the configuration for a
     * user other than the one you're currently running as.
     *
     * @hide
     */
    public BrightnessConfiguration getBrightnessConfigurationForUser(int userId) {
        return mGlobal.getBrightnessConfigurationForUser(userId);
    }

    /**
     * Gets the default global display brightness configuration or null one hasn't
     * been configured.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.CONFIGURE_DISPLAY_BRIGHTNESS)
    @Nullable
    public BrightnessConfiguration getDefaultBrightnessConfiguration() {
        return mGlobal.getDefaultBrightnessConfiguration();
    }


    /**
     * Gets the last requested minimal post processing setting for the display with displayId.
     *
     * @hide
     */
    @TestApi
    public boolean isMinimalPostProcessingRequested(int displayId) {
        return mGlobal.isMinimalPostProcessingRequested(displayId);
    }

    /**
     * Temporarily sets the brightness of the display.
     * <p>
     * Requires the {@link android.Manifest.permission#CONTROL_DISPLAY_BRIGHTNESS} permission.
     * </p>
     *
     * @param brightness The brightness value from 0.0f to 1.0f.
     *
     * @hide Requires signature permission.
     */
    public void setTemporaryBrightness(int displayId, float brightness) {
        mGlobal.setTemporaryBrightness(displayId, brightness);
    }


    /**
     * Sets the brightness of the specified display.
     * <p>
     * Requires the {@link android.Manifest.permission#CONTROL_DISPLAY_BRIGHTNESS}
     * permission.
     * </p>
     *
     * @param displayId the logical display id
     * @param brightness The brightness value from 0.0f to 1.0f.
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.CONTROL_DISPLAY_BRIGHTNESS)
    public void setBrightness(int displayId, @FloatRange(from = 0f, to = 1f) float brightness) {
        mGlobal.setBrightness(displayId, brightness);
    }


    /**
     * Gets the brightness of the specified display.
     * <p>
     * Requires the {@link android.Manifest.permission#CONTROL_DISPLAY_BRIGHTNESS}
     * permission.
     * </p>
     *
     * @param displayId The display of which brightness value to get from.
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.CONTROL_DISPLAY_BRIGHTNESS)
    @FloatRange(from = 0f, to = 1f)
    public float getBrightness(int displayId) {
        return mGlobal.getBrightness(displayId);
    }


    /**
     * Temporarily sets the auto brightness adjustment factor.
     * <p>
     * Requires the {@link android.Manifest.permission#CONTROL_DISPLAY_BRIGHTNESS} permission.
     * </p>
     *
     * @param adjustment The adjustment factor from -1.0 to 1.0.
     *
     * @hide Requires signature permission.
     */
    public void setTemporaryAutoBrightnessAdjustment(float adjustment) {
        mGlobal.setTemporaryAutoBrightnessAdjustment(adjustment);
    }

    /**
     * Returns the minimum brightness curve, which guarantess that any brightness curve that dips
     * below it is rejected by the system.
     * This prevent auto-brightness from setting the screen so dark as to prevent the user from
     * resetting or disabling it, and maps lux to the absolute minimum nits that are still readable
     * in that ambient brightness.
     *
     * @return The minimum brightness curve (as lux values and their corresponding nits values).
     *
     * @hide
     */
    @SystemApi
    public Pair<float[], float[]> getMinimumBrightnessCurve() {
        return mGlobal.getMinimumBrightnessCurve();
    }

    /**
     * Sets the global default {@link Display.Mode}.  The display mode includes preference for
     * resolution and refresh rate. The mode change is applied globally, i.e. to all the connected
     * displays. If the mode specified is not supported by a connected display, then no mode change
     * occurs for that display.
     *
     * @param mode The {@link Display.Mode} to set, which can include resolution and/or
     * refresh-rate. It is created using {@link Display.Mode.Builder}.
     *`
     * @hide
     */
    @TestApi
    @RequiresPermission(Manifest.permission.MODIFY_USER_PREFERRED_DISPLAY_MODE)
    public void setGlobalUserPreferredDisplayMode(@NonNull Display.Mode mode) {
        // Create a new object containing default values for the unused fields like mode ID and
        // alternative refresh rates.
        Display.Mode preferredMode = new Display.Mode(mode.getPhysicalWidth(),
                mode.getPhysicalHeight(), mode.getRefreshRate());
        mGlobal.setUserPreferredDisplayMode(Display.INVALID_DISPLAY, preferredMode);
    }

    /**
     * Removes the global user preferred display mode.
     * User preferred display mode is cleared for all the connected displays.
     *
     * @hide
     */
    @TestApi
    @RequiresPermission(Manifest.permission.MODIFY_USER_PREFERRED_DISPLAY_MODE)
    public void clearGlobalUserPreferredDisplayMode() {
        mGlobal.setUserPreferredDisplayMode(Display.INVALID_DISPLAY, null);
    }

    /**
     * Returns the global user preferred display mode.
     * If no user preferred mode has been set, or it has been cleared, this method returns null.
     *
     * @hide
     */
    @TestApi
    @Nullable
    public Display.Mode getGlobalUserPreferredDisplayMode() {
        return mGlobal.getUserPreferredDisplayMode(Display.INVALID_DISPLAY);
    }

    /**
     * When enabled the app requested mode is always selected regardless of user settings and
     * policies for low brightness, low battery, etc.
     *
     * @hide
     */
    @TestApi
    @RequiresPermission(Manifest.permission.OVERRIDE_DISPLAY_MODE_REQUESTS)
    public void setShouldAlwaysRespectAppRequestedMode(boolean enabled) {
        mGlobal.setShouldAlwaysRespectAppRequestedMode(enabled);
    }

    /**
     * Returns whether we are running in a mode which always selects the app requested display mode
     * and ignores user settings and policies for low brightness, low battery etc.
     *
     * @hide
     */
    @TestApi
    @RequiresPermission(Manifest.permission.OVERRIDE_DISPLAY_MODE_REQUESTS)
    public boolean shouldAlwaysRespectAppRequestedMode() {
        return mGlobal.shouldAlwaysRespectAppRequestedMode();
    }

    /**
     * Sets the refresh rate switching type.
     * This matches {@link android.provider.Settings.Secure.MATCH_CONTENT_FRAME_RATE}
     *
     * @hide
     */
    @TestApi
    @RequiresPermission(Manifest.permission.MODIFY_REFRESH_RATE_SWITCHING_TYPE)
    public void setRefreshRateSwitchingType(@SwitchingType int newValue) {
        mGlobal.setRefreshRateSwitchingType(newValue);
    }

    /**
     * Returns the user preference for "Match content frame rate".
     * <p>
     * Never: Even if the app requests it, the device will never try to match its output to the
     * original frame rate of the content.
     * </p><p>
     * Seamless: If the app requests it, the device will match its output to the original frame
     * rate of the content, ONLY if the display can transition seamlessly.
     * </p><p>
     * Always: If the app requests it, the device will match its output to the original
     * frame rate of the content. This may cause the screen to go blank for a
     * second when exiting or entering a video playback.
     * </p>
     */
    @MatchContentFrameRateType public int getMatchContentFrameRateUserPreference() {
        return toMatchContentFrameRateSetting(mGlobal.getRefreshRateSwitchingType());
    }

    @MatchContentFrameRateType
    private int toMatchContentFrameRateSetting(@SwitchingType int switchingType) {
        switch (switchingType) {
            case SWITCHING_TYPE_NONE:
                return MATCH_CONTENT_FRAMERATE_NEVER;
            case SWITCHING_TYPE_WITHIN_GROUPS:
                return MATCH_CONTENT_FRAMERATE_SEAMLESSS_ONLY;
            case SWITCHING_TYPE_ACROSS_AND_WITHIN_GROUPS:
                return MATCH_CONTENT_FRAMERATE_ALWAYS;
            default:
                Slog.e(TAG, switchingType + " is not a valid value of switching type.");
                return MATCH_CONTENT_FRAMERATE_UNKNOWN;
        }
    }

    /**
     * Listens for changes in available display devices.
     */
    public interface DisplayListener {
        /**
         * Called whenever a logical display has been added to the system.
         * Use {@link DisplayManager#getDisplay} to get more information about
         * the display.
         *
         * @param displayId The id of the logical display that was added.
         */
        void onDisplayAdded(int displayId);

        /**
         * Called whenever a logical display has been removed from the system.
         *
         * @param displayId The id of the logical display that was removed.
         */
        void onDisplayRemoved(int displayId);

        /**
         * Called whenever the properties of a logical {@link android.view.Display},
         * such as size and density, have changed.
         *
         * @param displayId The id of the logical display that changed.
         */
        void onDisplayChanged(int displayId);
    }

    /**
     * Interface for accessing keys belonging to {@link
     * android.provider.DeviceConfig#NAMESPACE_DISPLAY_MANAGER}.
     * @hide
     */
    public interface DeviceConfig {

        /**
         * Key for refresh rate in the low zone defined by thresholds.
         *
         * Note that the name and value don't match because they were added before we had a high
         * zone to consider.
         * @see android.provider.DeviceConfig#NAMESPACE_DISPLAY_MANAGER
         * @see android.R.integer#config_defaultZoneBehavior
         */
        String KEY_REFRESH_RATE_IN_LOW_ZONE = "refresh_rate_in_zone";

        /**
         * Key for accessing the low display brightness thresholds for the configured refresh
         * rate zone.
         * The value will be a pair of comma separated integers representing the minimum and maximum
         * thresholds of the zone, respectively, in display backlight units (i.e. [0, 255]).
         *
         * Note that the name and value don't match because they were added before we had a high
         * zone to consider.
         *
         * @see android.provider.DeviceConfig#NAMESPACE_DISPLAY_MANAGER
         * @see android.R.array#config_brightnessThresholdsOfPeakRefreshRate
         * @hide
         */
        String KEY_FIXED_REFRESH_RATE_LOW_DISPLAY_BRIGHTNESS_THRESHOLDS =
                "peak_refresh_rate_brightness_thresholds";

        /**
         * Key for accessing the low ambient brightness thresholds for the configured refresh
         * rate zone. The value will be a pair of comma separated integers representing the minimum
         * and maximum thresholds of the zone, respectively, in lux.
         *
         * Note that the name and value don't match because they were added before we had a high
         * zone to consider.
         *
         * @see android.provider.DeviceConfig#NAMESPACE_DISPLAY_MANAGER
         * @see android.R.array#config_ambientThresholdsOfPeakRefreshRate
         * @hide
         */
        String KEY_FIXED_REFRESH_RATE_LOW_AMBIENT_BRIGHTNESS_THRESHOLDS =
                "peak_refresh_rate_ambient_thresholds";
        /**
         * Key for refresh rate in the high zone defined by thresholds.
         *
         * @see android.provider.DeviceConfig#NAMESPACE_DISPLAY_MANAGER
         * @see android.R.integer#config_fixedRefreshRateInHighZone
         */
        String KEY_REFRESH_RATE_IN_HIGH_ZONE = "refresh_rate_in_high_zone";

        /**
         * Key for accessing the display brightness thresholds for the configured refresh rate zone.
         * The value will be a pair of comma separated integers representing the minimum and maximum
         * thresholds of the zone, respectively, in display backlight units (i.e. [0, 255]).
         *
         * @see android.provider.DeviceConfig#NAMESPACE_DISPLAY_MANAGER
         * @see android.R.array#config_brightnessHighThresholdsOfFixedRefreshRate
         * @hide
         */
        String KEY_FIXED_REFRESH_RATE_HIGH_DISPLAY_BRIGHTNESS_THRESHOLDS =
                "fixed_refresh_rate_high_display_brightness_thresholds";

        /**
         * Key for accessing the ambient brightness thresholds for the configured refresh rate zone.
         * The value will be a pair of comma separated integers representing the minimum and maximum
         * thresholds of the zone, respectively, in lux.
         *
         * @see android.provider.DeviceConfig#NAMESPACE_DISPLAY_MANAGER
         * @see android.R.array#config_ambientHighThresholdsOfFixedRefreshRate
         * @hide
         */
        String KEY_FIXED_REFRESH_RATE_HIGH_AMBIENT_BRIGHTNESS_THRESHOLDS =
                "fixed_refresh_rate_high_ambient_brightness_thresholds";

        /**
         * Key for refresh rate when the device is in high brightness mode for sunlight visility.
         *
         * @see android.provider.DeviceConfig#NAMESPACE_DISPLAY_MANAGER
         * @see android.R.integer#config_defaultRefreshRateInHbmSunlight
         */
        String KEY_REFRESH_RATE_IN_HBM_SUNLIGHT = "refresh_rate_in_hbm_sunlight";

        /**
         * Key for refresh rate when the device is in high brightness mode for HDR.
         *
         * @see android.provider.DeviceConfig#NAMESPACE_DISPLAY_MANAGER
         * @see android.R.integer#config_defaultRefreshRateInHbmHdr
         */
        String KEY_REFRESH_RATE_IN_HBM_HDR = "refresh_rate_in_hbm_hdr";

        /**
         * Key for default peak refresh rate
         *
         * @see android.provider.DeviceConfig#NAMESPACE_DISPLAY_MANAGER
         * @see android.R.integer#config_defaultPeakRefreshRate
         * @hide
         */
        String KEY_PEAK_REFRESH_RATE_DEFAULT = "peak_refresh_rate_default";

        // TODO(b/162536543): rename it once it is proved not harmful for users.
        /**
         * Key for controlling which packages are explicitly blocked from running at refresh rates
         * higher than 60hz. An app may be added to this list if they exhibit performance issues at
         * higher refresh rates.
         *
         * @see android.provider.DeviceConfig#NAMESPACE_DISPLAY_MANAGER
         * @see android.R.array#config_highRefreshRateBlacklist
         * @hide
         */
        String KEY_HIGH_REFRESH_RATE_BLACKLIST = "high_refresh_rate_blacklist";

        /**
         * Key for the brightness throttling data as a String formatted:
         * <displayId>,<no of throttling levels>,[<severity as string>,<brightness cap>]
         * Where the latter part is repeated for each throttling level, and the entirety is repeated
         * for each display, separated by a semicolon.
         * For example:
         * 123,1,critical,0.8;456,2,moderate,0.9,critical,0.7
         */
        String KEY_BRIGHTNESS_THROTTLING_DATA = "brightness_throttling_data";
    }
}
