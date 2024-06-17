/*
 * Copyright (C) 2010 The Android Open Source Project
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

import android.app.IOnProjectionStateChangedListener;
import android.app.IUiModeManagerCallback;

/**
 * Interface used to control special UI modes.
 * @hide
 */
interface IUiModeManager {
    /**
     * @hide
     */
    void addCallback(IUiModeManagerCallback callback);

    /**
     * Enables the car mode. Only the system can do this.
     * @hide
     */
    void enableCarMode(int flags, int priority, String callingPackage);

    /**
     * Disables the car mode.
     */
    @UnsupportedAppUsage(maxTargetSdk = 28)
    void disableCarMode(int flags);

    /**
     * Disables car mode (the original version is marked unsupported app usage so cannot be changed
     * for the time being).
     */
    void disableCarModeByCallingPackage(int flags, String callingPackage);

    /**
     * Return the current running mode.
     */
    int getCurrentModeType();
    
    /**
     * Sets the night mode.
     * <p>
     * The mode can be one of:
     * <ol>notnight mode</ol>
     * <ol>night mode</ol>
     * <ol>custom schedule mode switching</ol>
     */
    void setNightMode(int mode);

    /**
     * Gets the currently configured night mode.
     * <p>
     * Returns
     * <ol>notnight mode</ol>
     * <ol>night mode</ol>
     * <ol>custom schedule mode switching</ol>
     */
    int getNightMode();

    /**
     * Sets the current night mode to {@link #MODE_NIGHT_CUSTOM} with the custom night mode type
     * {@code nightModeCustomType}.
     *
     * @param nightModeCustomType
     * @hide
     */
    @EnforcePermission("MODIFY_DAY_NIGHT_MODE")
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.MODIFY_DAY_NIGHT_MODE)")
    void setNightModeCustomType(int nightModeCustomType);

    /**
     * Returns the custom night mode type.
     * <p>
     * If the current night mode is not {@link #MODE_NIGHT_CUSTOM}, returns
     * {@link #MODE_NIGHT_CUSTOM_TYPE_UNKNOWN}.
     * @hide
     */
    @EnforcePermission("MODIFY_DAY_NIGHT_MODE")
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.MODIFY_DAY_NIGHT_MODE)")
    int getNightModeCustomType();

    /**
     * Overlays current Night Mode value.
     * {@code attentionModeThemeOverlayType}.
     *
     * @param attentionModeThemeOverlayType
     * @hide
     */
    @EnforcePermission("MODIFY_DAY_NIGHT_MODE")
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.MODIFY_DAY_NIGHT_MODE)")
    void setAttentionModeThemeOverlay(int attentionModeThemeOverlayType);


    /**
     * Returns current Attention Mode overlay type.
     * <p>
     * returns
     *  <ul>
     *    <li>{@link #MODE_ATTENTION_OFF}</li>
     *    <li>{@link #MODE_ATTENTION_NIGHT}</li>
     *    <li>{@link #MODE_ATTENTION_DAY}</li>
     *  </ul>
     * </p>
     * @hide
     */
    @EnforcePermission("MODIFY_DAY_NIGHT_MODE")
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.MODIFY_DAY_NIGHT_MODE)")
    int getAttentionModeThemeOverlay();

    /**
     * Sets the dark mode for the given application. This setting is persisted and will override the
     * system configuration for this application.
     *   1 - notnight mode
     *   2 - night mode
     *   3 - automatic mode switching
     */
    void setApplicationNightMode(in int mode);

    /**
     * Tells if UI mode is locked or not.
     */
    boolean isUiModeLocked();

    /**
     * Tells if Night mode is locked or not.
     */
    boolean isNightModeLocked();

    /**
     * [De]activating night mode for the current user if the current night mode is custom and the
     * custom type matches {@code nightModeCustomType}.
     *
     * @param nightModeCustomType the specify type of custom mode
     * @param active {@code true} to activate night mode. Otherwise, deactivate night mode
     * @return {@code true} if night mode has successfully activated for the requested
     *         {@code nightModeCustomType}.
     * @hide
     */
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.MODIFY_DAY_NIGHT_MODE)")
    boolean setNightModeActivatedForCustomMode(int nightModeCustom, boolean active);

    /**
     * [De]Activates night mode.
     * @hide
     */
    boolean setNightModeActivated(boolean active);

    /**
    * Returns custom start clock time
    */
    long getCustomNightModeStart();

    /**
    * Sets custom start clock time
    */
    void setCustomNightModeStart(long time);

    /**
    * Returns custom end clock time
    */
    long getCustomNightModeEnd();

    /**
    * Sets custom end clock time
    */
    void setCustomNightModeEnd(long time);

    /**
    * Sets projection state for the caller for the given projection type.
    */
    boolean requestProjection(in IBinder binder, int projectionType, String callingPackage);

    /**
    * Releases projection state for the caller for the given projection type.
    */
    boolean releaseProjection(int projectionType, String callingPackage);

    /**
    * Registers a listener for changes to projection state.
    */
    @EnforcePermission("READ_PROJECTION_STATE")
    void addOnProjectionStateChangedListener(in IOnProjectionStateChangedListener listener, int projectionType);

    /**
    * Unregisters a listener for changes to projection state.
    */
    @EnforcePermission("READ_PROJECTION_STATE")
    void removeOnProjectionStateChangedListener(in IOnProjectionStateChangedListener listener);

    /**
    * Returns packages that have currently set the given projection type.
    */
    @EnforcePermission("READ_PROJECTION_STATE")
    List<String> getProjectingPackages(int projectionType);

    /**
    * Returns currently set projection types.
    */
    @EnforcePermission("READ_PROJECTION_STATE")
    int getActiveProjectionTypes();

    /**
    * Returns the contrast for the current user
    */
    float getContrast();
}
