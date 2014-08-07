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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.util.SparseArray;
import android.view.Display;
import android.view.Surface;

import java.util.ArrayList;

/**
 * Manages the properties of attached displays.
 * <p>
 * Get an instance of this class by calling
 * {@link android.content.Context#getSystemService(java.lang.String)
 * Context.getSystemService()} with the argument
 * {@link android.content.Context#DISPLAY_SERVICE}.
 * </p>
 */
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
    public static final String ACTION_WIFI_DISPLAY_STATUS_CHANGED =
            "android.hardware.display.action.WIFI_DISPLAY_STATUS_CHANGED";

    /**
     * Contains a {@link WifiDisplayStatus} object.
     * @hide
     */
    public static final String EXTRA_WIFI_DISPLAY_STATUS =
            "android.hardware.display.extra.WIFI_DISPLAY_STATUS";

    /**
     * Display category: Presentation displays.
     * <p>
     * This category can be used to identify secondary displays that are suitable for
     * use as presentation displays such as HDMI or Wireless displays.  Applications
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
     * Virtual display flag: Create a public display.
     *
     * <h3>Public virtual displays</h3>
     * <p>
     * When this flag is set, the virtual display is public.
     * </p><p>
     * A public virtual display behaves just like most any other display that is connected
     * to the system such as an HDMI or Wireless display.  Applications can open
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
     * A private virtual display belongs to the application that created it.
     * Only the a owner of a private virtual display is allowed to place windows upon it.
     * The private virtual display also does not participate in display mirroring: it will
     * neither receive mirrored content from another display nor allow its own content to
     * be mirrored elsewhere.  More precisely, the only processes that are allowed to
     * enumerate or interact with the private display are those that have the same UID as the
     * application that originally created the private virtual display.
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
     * Creating a secure virtual display requires the
     * {@link android.Manifest.permission#CAPTURE_SECURE_VIDEO_OUTPUT} permission.
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
     * Creating an auto-mirroing virtual display requires the
     * {@link android.Manifest.permission#CAPTURE_VIDEO_OUTPUT}
     * or {@link android.Manifest.permission#CAPTURE_SECURE_VIDEO_OUTPUT} permission.
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
        final int[] displayIds = mGlobal.getDisplayIds();
        synchronized (mLock) {
            try {
                if (category == null) {
                    addAllDisplaysLocked(mTempDisplays, displayIds);
                } else if (category.equals(DISPLAY_CATEGORY_PRESENTATION)) {
                    addPresentationDisplaysLocked(mTempDisplays, displayIds, Display.TYPE_WIFI);
                    addPresentationDisplaysLocked(mTempDisplays, displayIds, Display.TYPE_HDMI);
                    addPresentationDisplaysLocked(mTempDisplays, displayIds, Display.TYPE_OVERLAY);
                    addPresentationDisplaysLocked(mTempDisplays, displayIds, Display.TYPE_VIRTUAL);
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
            display = mGlobal.getCompatibleDisplay(displayId,
                    mContext.getDisplayAdjustments(displayId));
            if (display != null) {
                mDisplays.put(displayId, display);
            }
        } else if (!assumeValid && !display.isValid()) {
            display = null;
        }
        return display;
    }

    /**
     * Registers an display listener to receive notifications about when
     * displays are added, removed or changed.
     *
     * @param listener The listener to register.
     * @param handler The handler on which the listener should be invoked, or null
     * if the listener should be invoked on the calling thread's looper.
     *
     * @see #unregisterDisplayListener
     */
    public void registerDisplayListener(DisplayListener listener, Handler handler) {
        mGlobal.registerDisplayListener(listener, handler);
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
    public void connectWifiDisplay(String deviceAddress) {
        mGlobal.connectWifiDisplay(deviceAddress);
    }

    /** @hide */
    public void pauseWifiDisplay() {
        mGlobal.pauseWifiDisplay();
    }

    /** @hide */
    public void resumeWifiDisplay() {
        mGlobal.resumeWifiDisplay();
    }

    /**
     * Disconnects from the current Wifi display.
     * The results are sent as a {@link #ACTION_WIFI_DISPLAY_STATUS_CHANGED} broadcast.
     * @hide
     */
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
    public WifiDisplayStatus getWifiDisplayStatus() {
        return mGlobal.getWifiDisplayStatus();
    }

    /**
     * Creates a virtual display.
     *
     * @see #createVirtualDisplay(String, int, int, int, Surface, int, VirtualDisplay.Callbacks)
     */
    public VirtualDisplay createVirtualDisplay(@NonNull String name,
            int width, int height, int densityDpi, @Nullable Surface surface, int flags) {
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
     * @param callbacks Callbacks to call when the state of the {@link VirtualDisplay} changes
     * @param handler The handler on which the listener should be invoked, or null
     * if the listener should be invoked on the calling thread's looper.
     * @return The newly created virtual display, or null if the application could
     * not create the virtual display.
     *
     * @throws SecurityException if the caller does not have permission to create
     * a virtual display with the specified flags.
     */
    public VirtualDisplay createVirtualDisplay(@NonNull String name,
            int width, int height, int densityDpi, @Nullable Surface surface, int flags,
            @Nullable VirtualDisplay.Callbacks callbacks, @Nullable Handler handler) {
        return createVirtualDisplay(null,
                name, width, height, densityDpi, surface, flags, callbacks, handler);
    }

    /** @hide */
    public VirtualDisplay createVirtualDisplay(@Nullable MediaProjection projection,
            @NonNull String name, int width, int height, int densityDpi, @Nullable Surface surface,
            int flags, @Nullable VirtualDisplay.Callbacks callbacks, @Nullable Handler handler) {
        return mGlobal.createVirtualDisplay(mContext, projection,
                name, width, height, densityDpi, surface, flags, callbacks, handler);
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
         * Called whenever the properties of a logical display have changed.
         *
         * @param displayId The id of the logical display that changed.
         */
        void onDisplayChanged(int displayId);
    }
}
