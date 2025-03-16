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
package com.android.internal.widget.remotecompose.player;

import android.app.Application;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;

import com.android.internal.widget.remotecompose.accessibility.RemoteComposeTouchHelper;
import com.android.internal.widget.remotecompose.core.CoreDocument;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.operations.NamedVariable;
import com.android.internal.widget.remotecompose.core.operations.RootContentBehavior;
import com.android.internal.widget.remotecompose.player.platform.RemoteComposeCanvas;

/** A view to to display and play RemoteCompose documents */
public class RemoteComposePlayer extends FrameLayout {
    private RemoteComposeCanvas mInner;

    private static final int MAX_SUPPORTED_MAJOR_VERSION = 0;
    private static final int MAX_SUPPORTED_MINOR_VERSION = 1;

    public RemoteComposePlayer(Context context) {
        super(context);
        init(context, null, 0);
    }

    public RemoteComposePlayer(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context, attrs, 0);
    }

    public RemoteComposePlayer(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context, attrs, defStyleAttr);
    }

    /**
     * Returns true if the document supports drag touch events
     *
     * @return true if draggable content, false otherwise
     */
    public boolean isDraggable() {
        return mInner.isDraggable();
    }

    /**
     * Turn on debug information
     *
     * @param debugFlags 1 to set debug on
     */
    public void setDebug(int debugFlags) {
        mInner.setDebug(debugFlags);
    }

    public RemoteComposeDocument getDocument() {
        return mInner.getDocument();
    }

    public void setDocument(RemoteComposeDocument value) {
        if (value != null) {
            if (value.canBeDisplayed(
                    MAX_SUPPORTED_MAJOR_VERSION, MAX_SUPPORTED_MINOR_VERSION, 0L)) {
                mInner.setDocument(value);
                int contentBehavior = value.getDocument().getContentScroll();
                applyContentBehavior(contentBehavior);
            } else {
                Log.e("RemoteComposePlayer", "Unsupported document ");
            }

            RemoteComposeTouchHelper.REGISTRAR.setAccessibilityDelegate(this, value.getDocument());
        } else {
            mInner.setDocument(null);

            RemoteComposeTouchHelper.REGISTRAR.clearAccessibilityDelegate(this);
        }
        mapColors();
        setupSensors();
        mInner.setHapticEngine(
                new CoreDocument.HapticEngine() {

                    @Override
                    public void haptic(int type) {
                        provideHapticFeedback(type);
                    }
                });
        mInner.checkShaders(mShaderControl);
    }

    /**
     * Apply the content behavior (NONE|SCROLL_HORIZONTAL|SCROLL_VERTICAL) to the player, adding or
     * removing scrollviews as needed.
     *
     * @param contentBehavior document content behavior (NONE|SCROLL_HORIZONTAL|SCROLL_VERTICAL)
     */
    private void applyContentBehavior(int contentBehavior) {
        switch (contentBehavior) {
            case RootContentBehavior.SCROLL_HORIZONTAL:
                if (!(mInner.getParent() instanceof HorizontalScrollView)) {
                    ((ViewGroup) mInner.getParent()).removeView(mInner);
                    removeAllViews();
                    LayoutParams layoutParamsInner =
                            new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT);
                    HorizontalScrollView horizontalScrollView =
                            new HorizontalScrollView(getContext());
                    horizontalScrollView.setBackgroundColor(Color.TRANSPARENT);
                    horizontalScrollView.setFillViewport(true);
                    horizontalScrollView.addView(mInner, layoutParamsInner);
                    LayoutParams layoutParams =
                            new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
                    addView(horizontalScrollView, layoutParams);
                }
                break;
            case RootContentBehavior.SCROLL_VERTICAL:
                if (!(mInner.getParent() instanceof ScrollView)) {
                    ((ViewGroup) mInner.getParent()).removeView(mInner);
                    removeAllViews();
                    LayoutParams layoutParamsInner =
                            new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
                    ScrollView scrollView = new ScrollView(getContext());
                    scrollView.setBackgroundColor(Color.TRANSPARENT);
                    scrollView.setFillViewport(true);
                    scrollView.addView(mInner, layoutParamsInner);
                    LayoutParams layoutParams =
                            new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
                    addView(scrollView, layoutParams);
                }
                break;
            default:
                if (mInner.getParent() != this) {
                    ((ViewGroup) mInner.getParent()).removeView(mInner);
                    removeAllViews();
                    LayoutParams layoutParams =
                            new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
                    addView(mInner, layoutParams);
                }
        }
    }

    private void init(Context context, AttributeSet attrs, int defStyleAttr) {
        LayoutParams layoutParams =
                new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        setBackgroundColor(Color.TRANSPARENT);
        mInner = new RemoteComposeCanvas(context, attrs, defStyleAttr);
        mInner.setBackgroundColor(Color.TRANSPARENT);
        addView(mInner, layoutParams);
    }

    /**
     * Set an override for a string resource
     *
     * @param domain domain (SYSTEM or USER)
     * @param name name of the string
     * @param content content of the string
     */
    public void setLocalString(String domain, String name, String content) {
        mInner.setLocalString(domain + ":" + name, content);
    }

    /**
     * Clear the override of the given string
     *
     * @param domain domain (SYSTEM or USER)
     * @param name name of the string
     */
    public void clearLocalString(String domain, String name) {
        mInner.clearLocalString(domain + ":" + name);
    }

    /**
     * Set an override for a user domain string resource
     *
     * @param name name of the string
     * @param content content of the string
     */
    public void setUserLocalString(String name, String content) {
        mInner.setLocalString("USER:" + name, content);
    }

    /**
     * Set an override for a user domain int resource
     *
     * @param name name of the int
     * @param value value of the int
     */
    public void setUserLocalInt(String name, int value) {
        mInner.setLocalInt("USER:" + name, value);
    }

    /**
     * Clear the override of the given user string
     *
     * @param name name of the string
     */
    public void clearUserLocalString(String name) {
        mInner.clearLocalString("USER:" + name);
    }

    /**
     * Clear the override of the given user int
     *
     * @param name name of the int
     */
    public void clearUserLocalInt(String name) {
        mInner.clearLocalInt("USER:" + name);
    }

    /**
     * Set an override for a system domain string resource
     *
     * @param name name of the string
     * @param content content of the string
     */
    public void setSystemLocalString(String name, String content) {
        mInner.setLocalString("SYSTEM:" + name, content);
    }

    /**
     * Clear the override of the given system string
     *
     * @param name name of the string
     */
    public void clearSystemLocalString(String name) {
        mInner.clearLocalString("SYSTEM:" + name);
    }

    /**
     * This is the number of ops used to calculate the last frame.
     *
     * @return number of ops
     */
    public int getOpsPerFrame() {
        return mInner.getDocument().mDocument.getOpsPerFrame();
    }

    /** Id action callback interface */
    public interface IdActionCallbacks {
        /**
         * Callback for on action
         *
         * @param id the id of the action
         * @param metadata the metadata of the action
         */
        void onAction(int id, String metadata);
    }

    /**
     * Add a callback for handling id actions events on the document
     *
     * @param callback the callback lambda that will be used when a action is executed
     *     <p>The parameter of the callback are:
     *     <ul>
     *       <li>id : the id of the action
     *       <li>metadata: a client provided unstructured string associated with that id action
     *     </ul>
     */
    public void addIdActionListener(IdActionCallbacks callback) {
        mInner.addIdActionListener((id, metadata) -> callback.onAction(id, metadata));
    }

    /**
     * Set the playback theme for the document. This allows to filter operations in order to have
     * the document adapt to the given theme. This method is intended to be used to support
     * night/light themes (system or app level), not custom themes.
     *
     * @param theme the theme used for playing the document. Possible values for theme are: -
     *     Theme.UNSPECIFIED -- all instructions in the document will be executed - Theme.DARK --
     *     only executed NON Light theme instructions - Theme.LIGHT -- only executed NON Dark theme
     *     instructions
     */
    public void setTheme(int theme) {
        if (mInner.getTheme() != theme) {
            mInner.setTheme(theme);
            mInner.invalidate();
        }
    }

    /**
     * This returns a list of colors that have names in the Document.
     *
     * @return the names of named Strings or null
     */
    public String[] getNamedColors() {
        return mInner.getNamedColors();
    }

    /**
     * This returns a list of floats that have names in the Document.
     *
     * @return return the names of named floats in the document
     */
    public String[] getNamedFloats() {
        return mInner.getNamedVariables(NamedVariable.FLOAT_TYPE);
    }

    /**
     * This returns a list of string name that have names in the Document.
     *
     * @return the name of named string (not the string itself)
     */
    public String[] getNamedStrings() {
        return mInner.getNamedVariables(NamedVariable.STRING_TYPE);
    }

    /**
     * This returns a list of images that have names in the Document.
     *
     * @return
     */
    public String[] getNamedImages() {
        return mInner.getNamedVariables(NamedVariable.IMAGE_TYPE);
    }

    /**
     * This sets a color based on its name. Overriding the color set in the document.
     *
     * @param colorName Name of the color
     * @param colorValue The new color value
     */
    public void setColor(String colorName, int colorValue) {
        mInner.setColor(colorName, colorValue);
    }

    private void mapColors() {
        String[] name = getNamedColors();

        // make every effort to terminate early
        if (name == null) {
            return;
        }
        boolean found = false;
        for (int i = 0; i < name.length; i++) {
            if (name[i].startsWith("android.")) {
                found = true;
                break;
            }
        }
        if (!found) {
            return;
        }

        for (int i = 0; i < name.length; i++) {
            String s = name[i];
            if (!s.startsWith("android.")) {
                continue;
            }
            String sub = s.substring("android.".length());
            switch (sub) {
                case "actionBarItemBackground":
                    setRColor(s, android.R.attr.actionBarItemBackground);
                    break;
                case "actionModeBackground":
                    setRColor(s, android.R.attr.actionModeBackground);
                    break;
                case "actionModeSplitBackground":
                    setRColor(s, android.R.attr.actionModeSplitBackground);
                    break;
                case "activatedBackgroundIndicator":
                    setRColor(s, android.R.attr.activatedBackgroundIndicator);
                    break;
                case "colorAccent": // Highlight color for interactive elements
                    setRColor(s, android.R.attr.colorAccent);
                    break;
                case "colorActivatedHighlight":
                    setRColor(s, android.R.attr.colorActivatedHighlight);
                    break;
                case "colorBackground": // background color for the app’s window
                    setRColor(s, android.R.attr.colorBackground);
                    break;
                case "colorBackgroundCacheHint":
                    setRColor(s, android.R.attr.colorBackgroundCacheHint);
                    break;
                //  Background color for floating elements
                case "colorBackgroundFloating":
                    setRColor(s, android.R.attr.colorBackgroundFloating);
                    break;
                case "colorButtonNormal": // The default color for buttons
                    setRColor(s, android.R.attr.colorButtonNormal);
                    break;
                // Color for activated (checked) state of controls.
                case "colorControlActivated":
                    setRColor(s, android.R.attr.colorControlActivated);
                    break;
                case "colorControlHighlight": // Color for highlights on controls
                    setRColor(s, android.R.attr.colorControlHighlight);
                    break;
                // Default color for controls in their normal state.
                case "colorControlNormal":
                    setRColor(s, android.R.attr.colorControlNormal);
                    break;
                // Color for edge effects (e.g., overscroll glow)
                case "colorEdgeEffect":
                    setRColor(s, android.R.attr.colorEdgeEffect);
                    break;
                case "colorError":
                    setRColor(s, android.R.attr.colorError);
                    break;
                case "colorFocusedHighlight":
                    setRColor(s, android.R.attr.colorFocusedHighlight);
                    break;
                case "colorForeground": // General foreground color for views.
                    setRColor(s, android.R.attr.colorForeground);
                    break;
                // Foreground color for inverse backgrounds.
                case "colorForegroundInverse":
                    setRColor(s, android.R.attr.colorForegroundInverse);
                    break;
                case "colorLongPressedHighlight":
                    setRColor(s, android.R.attr.colorLongPressedHighlight);
                    break;
                case "colorMultiSelectHighlight":
                    setRColor(s, android.R.attr.colorMultiSelectHighlight);
                    break;
                case "colorPressedHighlight":
                    setRColor(s, android.R.attr.colorPressedHighlight);
                    break;
                case "colorPrimary": // The primary branding color for the app.
                    setRColor(s, android.R.attr.colorPrimary);
                    break;
                case "colorPrimaryDark": // darker variant of the primary color
                    setRColor(s, android.R.attr.colorPrimaryDark);
                    break;
                case "colorSecondary":
                    setRColor(s, android.R.attr.colorSecondary);
                    break;
                case "detailsElementBackground":
                    setRColor(s, android.R.attr.detailsElementBackground);
                    break;
                case "editTextBackground":
                    setRColor(s, android.R.attr.editTextBackground);
                    break;
                case "galleryItemBackground":
                    setRColor(s, android.R.attr.galleryItemBackground);
                    break;
                case "headerBackground":
                    setRColor(s, android.R.attr.headerBackground);
                    break;
                case "itemBackground":
                    setRColor(s, android.R.attr.itemBackground);
                    break;
                case "numbersBackgroundColor":
                    setRColor(s, android.R.attr.numbersBackgroundColor);
                    break;
                case "panelBackground":
                    setRColor(s, android.R.attr.panelBackground);
                    break;
                case "panelColorBackground":
                    setRColor(s, android.R.attr.panelColorBackground);
                    break;
                case "panelFullBackground":
                    setRColor(s, android.R.attr.panelFullBackground);
                    break;
                case "popupBackground":
                    setRColor(s, android.R.attr.popupBackground);
                    break;
                case "queryBackground":
                    setRColor(s, android.R.attr.queryBackground);
                    break;
                case "selectableItemBackground":
                    setRColor(s, android.R.attr.selectableItemBackground);
                    break;
                case "submitBackground":
                    setRColor(s, android.R.attr.submitBackground);
                    break;
                case "textColor":
                    setRColor(s, android.R.attr.textColor);
                    break;
                case "windowBackground":
                    setRColor(s, android.R.attr.windowBackground);
                    break;
                case "windowBackgroundFallback":
                    setRColor(s, android.R.attr.windowBackgroundFallback);
                    break;
                // Primary text color for inverse backgrounds
                case "textColorPrimaryInverse":
                    setRColor(s, android.R.attr.textColorPrimaryInverse);
                    break;
                // Secondary text color for inverse backgrounds
                case "textColorSecondaryInverse":
                    setRColor(s, android.R.attr.textColorSecondaryInverse);
                    break;
                // Tertiary text color for less important text.
                case "textColorTertiary":
                    setRColor(s, android.R.attr.textColorTertiary);
                    break;
                // Tertiary text color for inverse backgrounds
                case "textColorTertiaryInverse":
                    setRColor(s, android.R.attr.textColorTertiaryInverse);
                    break;
                // Text highlight color (e.g., selected text background).
                case "textColorHighlight":
                    setRColor(s, android.R.attr.textColorHighlight);
                    break;
                // Color for hyperlinks.
                case "textColorLink":
                    setRColor(s, android.R.attr.textColorLink);
                    break;
                //  Color for hint text.
                case "textColorHint":
                    setRColor(s, android.R.attr.textColorHint);
                    break;
                // text color for inverse backgrounds..
                case "textColorHintInverse":
                    setRColor(s, android.R.attr.textColorHintInverse);
                    break;
                // Default color for the thumb of switches.
                case "colorSwitchThumbNormal":
                    setRColor(s, android.R.attr.colorControlNormal);
                    break;
            }
        }
    }

    private void setRColor(String name, int id) {
        int color = getColorFromResource(id);
        setColor(name, color);
    }

    private int getColorFromResource(int id) {

        TypedValue typedValue = new TypedValue();
        try (TypedArray arr =
                getContext()
                        .getApplicationContext()
                        .obtainStyledAttributes(typedValue.data, new int[] {id})) {
            int color = arr.getColor(0, -1);
            return color;
        }
    }

    private static int[] sHapticTable = {
        HapticFeedbackConstants.NO_HAPTICS,
        HapticFeedbackConstants.LONG_PRESS,
        HapticFeedbackConstants.VIRTUAL_KEY,
        HapticFeedbackConstants.KEYBOARD_TAP,
        HapticFeedbackConstants.CLOCK_TICK,
        HapticFeedbackConstants.CONTEXT_CLICK,
        HapticFeedbackConstants.KEYBOARD_PRESS,
        HapticFeedbackConstants.KEYBOARD_RELEASE,
        HapticFeedbackConstants.VIRTUAL_KEY_RELEASE,
        HapticFeedbackConstants.TEXT_HANDLE_MOVE,
        HapticFeedbackConstants.GESTURE_START,
        HapticFeedbackConstants.GESTURE_END,
        HapticFeedbackConstants.CONFIRM,
        HapticFeedbackConstants.REJECT,
        HapticFeedbackConstants.TOGGLE_ON,
        HapticFeedbackConstants.TOGGLE_OFF,
        HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE,
        HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE,
        HapticFeedbackConstants.DRAG_START,
        HapticFeedbackConstants.SEGMENT_TICK,
        HapticFeedbackConstants.SEGMENT_FREQUENT_TICK,
    };

    private void provideHapticFeedback(int type) {
        performHapticFeedback(sHapticTable[type % sHapticTable.length]);
    }

    SensorManager mSensorManager;
    Sensor mAcc = null, mGyro = null, mMag = null, mLight = null;
    SensorEventListener mListener;

    private void setupSensors() {

        int minId = RemoteContext.ID_ACCELERATION_X;
        int maxId = RemoteContext.ID_LIGHT;
        int[] ids = new int[1 + maxId - minId];

        int count = mInner.hasSensorListeners(ids);
        mAcc = null;
        mGyro = null;
        mMag = null;
        mLight = null;
        if (count > 0) {
            Application app = (Application) getContext().getApplicationContext();

            mSensorManager = (SensorManager) app.getSystemService(Context.SENSOR_SERVICE);
            for (int i = 0; i < count; i++) {
                switch (ids[i]) {
                    case RemoteContext.ID_ACCELERATION_X:
                    case RemoteContext.ID_ACCELERATION_Y:
                    case RemoteContext.ID_ACCELERATION_Z:
                        if (mAcc == null) {
                            mAcc = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
                        }
                        break;
                    case RemoteContext.ID_GYRO_ROT_X:
                    case RemoteContext.ID_GYRO_ROT_Y:
                    case RemoteContext.ID_GYRO_ROT_Z:
                        if (mGyro == null) {
                            mGyro = mSensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
                        }
                        break;
                    case RemoteContext.ID_MAGNETIC_X:
                    case RemoteContext.ID_MAGNETIC_Y:
                    case RemoteContext.ID_MAGNETIC_Z:
                        if (mMag == null) {
                            mMag = mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
                        }
                        break;
                    case RemoteContext.ID_LIGHT:
                        if (mLight == null) {
                            mLight = mSensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
                        }
                }
            }
        }
        registerListener();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        unregisterListener();
    }

    public void registerListener() {
        Sensor[] s = {mAcc, mGyro, mMag, mLight};
        if (mListener != null) {
            unregisterListener();
        }
        SensorEventListener listener =
                new SensorEventListener() {
                    @Override
                    public void onSensorChanged(SensorEvent event) {
                        if (event.sensor == mAcc) {
                            mInner.setExternalFloat(
                                    RemoteContext.ID_ACCELERATION_X, event.values[0]);
                            mInner.setExternalFloat(
                                    RemoteContext.ID_ACCELERATION_Y, event.values[1]);
                            mInner.setExternalFloat(
                                    RemoteContext.ID_ACCELERATION_Z, event.values[2]);
                        } else if (event.sensor == mGyro) {
                            mInner.setExternalFloat(RemoteContext.ID_GYRO_ROT_X, event.values[0]);
                            mInner.setExternalFloat(RemoteContext.ID_GYRO_ROT_Y, event.values[1]);
                            mInner.setExternalFloat(RemoteContext.ID_GYRO_ROT_Z, event.values[2]);
                        } else if (event.sensor == mMag) {
                            mInner.setExternalFloat(RemoteContext.ID_MAGNETIC_X, event.values[0]);
                            mInner.setExternalFloat(RemoteContext.ID_MAGNETIC_Y, event.values[1]);
                            mInner.setExternalFloat(RemoteContext.ID_MAGNETIC_Z, event.values[2]);
                        } else if (event.sensor == mLight) {
                            mInner.setExternalFloat(RemoteContext.ID_LIGHT, event.values[0]);
                        }
                    }

                    @Override
                    public void onAccuracyChanged(Sensor sensor, int accuracy) {}
                };

        Sensor[] sensors = {mAcc, mGyro, mMag, mLight};
        for (int i = 0; i < sensors.length; i++) {
            Sensor sensor = sensors[i];
            if (sensor != null) {
                mListener = listener;
                mSensorManager.registerListener(
                        mListener, sensor, SensorManager.SENSOR_DELAY_NORMAL);
            }
        }
    }

    public void unregisterListener() {
        if (mListener != null && mSensorManager != null) {
            mSensorManager.unregisterListener(mListener);
        }
        mListener = null;
    }

    /**
     * This returns the amount of time in ms the player used to evalueate a pass it is averaged over
     * a number of evaluations.
     *
     * @return time in ms
     */
    public float getEvalTime() {
        return mInner.getEvalTime();
    }

    private CoreDocument.ShaderControl mShaderControl =
            (shader) -> {
                return false;
            };

    /**
     * Sets the controller for shaders. Note set before loading the document. The default is to not
     * accept shaders.
     *
     * @param ctl the controller
     */
    public void setShaderControl(CoreDocument.ShaderControl ctl) {
        mShaderControl = ctl;
    }
}
