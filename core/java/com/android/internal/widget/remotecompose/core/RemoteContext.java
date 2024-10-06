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
package com.android.internal.widget.remotecompose.core;

import com.android.internal.widget.remotecompose.core.operations.FloatExpression;
import com.android.internal.widget.remotecompose.core.operations.ShaderData;
import com.android.internal.widget.remotecompose.core.operations.Theme;
import com.android.internal.widget.remotecompose.core.operations.Utils;
import com.android.internal.widget.remotecompose.core.operations.layout.Component;
import com.android.internal.widget.remotecompose.core.operations.utilities.ArrayAccess;
import com.android.internal.widget.remotecompose.core.operations.utilities.CollectionsAccess;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Specify an abstract context used to playback RemoteCompose documents
 *
 * This allows us to intercept the different operations in a document and react to them.
 *
 * We also contain a PaintContext, so that any operation can draw as needed.
 */
public abstract class RemoteContext {
    protected CoreDocument mDocument;
    public RemoteComposeState mRemoteComposeState;
    long mStart = System.nanoTime(); // todo This should be set at a hi level
    protected PaintContext mPaintContext = null;
    ContextMode mMode = ContextMode.UNSET;

    boolean mDebug = false;

    private int mTheme = Theme.UNSPECIFIED;

    public float mWidth = 0f;
    public float mHeight = 0f;
    private float mAnimationTime;

    private boolean mAnimate = true;

    public Component lastComponent;
    public long currentTime = 0L;

    public boolean isAnimationEnabled() {
        return mAnimate;
    }

    public void setAnimationEnabled(boolean value) {
        mAnimate = value;
    }

    /**
     * Provide access to the table of collections
     *
     * @return the CollectionsAccess implementation
     */
    public CollectionsAccess getCollectionsAccess() {
        return mRemoteComposeState;
    }

    /**
     * Load a path under an id.
     * Paths can be use in clip drawPath and drawTweenPath
     *
     * @param instanceId
     * @param floatPath
     */
    public abstract void loadPathData(int instanceId, float[] floatPath);

    /**
     * Associate a name with a give id.
     *
     * @param varName the name
     * @param varId   the id (color,integer,float etc.)
     * @param varType thetype
     */
    public abstract void loadVariableName(String varName, int varId, int varType);

    /**
     * Save a color under a given id
     *
     * @param id    the id of the color
     * @param color the color to set
     */
    public abstract void loadColor(int id, int color);

    /**
     * Set the animation time allowing the creator to control animation rates
     *
     * @param time the animation time in seconds
     */
    public void setAnimationTime(float time) {
        mAnimationTime = time;
    }

    /**
     * gets the time animation clock as float in seconds
     *
     * @return a monotonic time in seconds (arbitrary zero point)
     */
    public float getAnimationTime() {
        mAnimationTime = (System.nanoTime() - mStart) * 1E-9f; // Eliminate
        return mAnimationTime;
    }

    /**
     * Set the value of a named Color.
     * This overrides the color in the document
     *
     * @param colorName the name of the color to override
     * @param color     Override the default color
     */
    public abstract void setNamedColorOverride(String colorName, int color);

    /**
     * Set the value of a named String.
     * This overrides the string in the document
     * @param stringName the name of the string to override
     * @param value Override the default string
     */
    public abstract void setNamedStringOverride(String stringName, String value);


    /**
     * Allows to clear a named String.
     *
     * If an override exists, we revert back to the default value in the document.
     *
     * @param stringName the name of the string to override
     */
    public abstract void clearNamedStringOverride(String stringName);

    /**
     * Set the value of a named Integer.
     * This overrides the integer in the document
     * @param integerName the name of the integer to override
     * @param value Override the default integer
     */
    public abstract void setNamedIntegerOverride(String integerName, int value);

    /**
     * Allows to clear a named Integer.
     *
     * If an override exists, we revert back to the default value in the document.
     *
     * @param integerName the name of the integer to override
     */
    public abstract void clearNamedIntegerOverride(String integerName);


    /**
     * Support Collections by registering this collection
     *
     * @param id         id of the collection
     * @param collection the collection under this id
     */
    public abstract void addCollection(int id, ArrayAccess collection);

    public abstract void runAction(int id, String metadata);

    public abstract void runNamedAction(int textId);


    /**
     * The context can be used in a few different mode, allowing operations to skip being executed:
     * - UNSET : all operations will get executed
     * - DATA : only operations dealing with DATA (eg loading a bitmap) should execute
     * - PAINT : only operations painting should execute
     */
    public enum ContextMode {
        UNSET, DATA, PAINT
    }

    public int getTheme() {
        return mTheme;
    }

    public void setTheme(int theme) {
        this.mTheme = theme;
    }

    public ContextMode getMode() {
        return mMode;
    }

    public void setMode(ContextMode mode) {
        this.mMode = mode;
    }

    public PaintContext getPaintContext() {
        return mPaintContext;
    }

    public void setPaintContext(PaintContext paintContext) {
        this.mPaintContext = paintContext;
    }

    public CoreDocument getDocument() {
        return mDocument;
    }

    public boolean isDebug() {
        return mDebug;
    }

    public void setDebug(boolean debug) {
        this.mDebug = debug;
    }

    public void setDocument(CoreDocument document) {
        this.mDocument = document;
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////
    // Operations
    ///////////////////////////////////////////////////////////////////////////////////////////////

    public void header(int majorVersion, int minorVersion, int patchVersion,
                       int width, int height, long capabilities
    ) {
        mRemoteComposeState.setWindowWidth(width);
        mRemoteComposeState.setWindowHeight(height);
        mDocument.setVersion(majorVersion, minorVersion, patchVersion);
        mDocument.setWidth(width);
        mDocument.setHeight(height);
        mDocument.setRequiredCapabilities(capabilities);
    }

    /**
     * Sets the way the player handles the content
     *
     * @param scroll    set the horizontal behavior (NONE|SCROLL_HORIZONTAL|SCROLL_VERTICAL)
     * @param alignment set the alignment of the content (TOP|CENTER|BOTTOM|START|END)
     * @param sizing    set the type of sizing for the content (NONE|SIZING_LAYOUT|SIZING_SCALE)
     * @param mode      set the mode of sizing, either LAYOUT modes or SCALE modes
     *                  the LAYOUT modes are:
     *                  - LAYOUT_MATCH_PARENT
     *                  - LAYOUT_WRAP_CONTENT
     *                  or adding an horizontal mode and a vertical mode:
     *                  - LAYOUT_HORIZONTAL_MATCH_PARENT
     *                  - LAYOUT_HORIZONTAL_WRAP_CONTENT
     *                  - LAYOUT_HORIZONTAL_FIXED
     *                  - LAYOUT_VERTICAL_MATCH_PARENT
     *                  - LAYOUT_VERTICAL_WRAP_CONTENT
     *                  - LAYOUT_VERTICAL_FIXED
     *                  The LAYOUT_*_FIXED modes will use the intrinsic document size
     */
    public void setRootContentBehavior(int scroll, int alignment, int sizing, int mode) {
        mDocument.setRootContentBehavior(scroll, alignment, sizing, mode);
    }

    /**
     * Set a content description for the document
     *
     * @param contentDescriptionId the text id pointing at the description
     */
    public void setDocumentContentDescription(int contentDescriptionId) {
        String contentDescription = (String) mRemoteComposeState.getFromId(contentDescriptionId);
        mDocument.setContentDescription(contentDescription);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////
    // Data handling
    ///////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Save a bitmap under an imageId
     *
     * @param imageId the id of the image
     * @param width   the width of the image
     * @param height  the height of the image
     * @param bitmap  the bytes that represent the image
     */
    public abstract void loadBitmap(int imageId, int width, int height, byte[] bitmap);

    /**
     * Save a string under a given id
     *
     * @param id   the id of the string
     * @param text the value to set
     */
    public abstract void loadText(int id, String text);

    /**
     * Get a string given an id
     *
     * @param id the id of the string
     * @return
     */
    public abstract String getText(int id);

    /**
     * Load a float
     *
     * @param id    id of the float
     * @param value the value to set
     */
    public abstract void loadFloat(int id, float value);

    /**
     * Load a integer
     *
     * @param id    id of the integer
     * @param value the value to set
     */
    public abstract void loadInteger(int id, int value);


    public abstract void overrideInteger(int id, int value);

    public abstract void overrideText(int id, int valueId);

    /**
     * Load an animated float associated with an id
     * Todo: Remove?
     *
     * @param id            the id of the float
     * @param animatedFloat The animated float
     */
    public abstract void loadAnimatedFloat(int id, FloatExpression animatedFloat);

    /**
     * Save a shader under and ID
     *
     * @param id    the id of the Shader
     * @param value the shader
     */
    public abstract void loadShader(int id, ShaderData value);

    /**
     * Get a float given an id
     *
     * @param id the id of the float
     * @return the value of the float
     */
    public abstract float getFloat(int id);

    /**
     * Get a Integer given an id
     *
     * @param id of the integer
     * @return the value
     */
    public abstract int getInteger(int id);

    /**
     * Get the color given and ID
     *
     * @param id of the color
     * @return the color
     */
    public abstract int getColor(int id);

    /**
     * called to notify system that a command is interested in a variable
     *
     * @param id              track when this id changes value
     * @param variableSupport call back when value changes
     */
    public abstract void listensTo(int id, VariableSupport variableSupport);

    /**
     * Notify commands with variables have changed
     *
     * @return the number of ms to next update
     */
    public abstract int updateOps();

    /**
     * Get a shader given the id
     *
     * @param id get a shader given the id
     * @return The shader
     */
    public abstract ShaderData getShader(int id);

    public static final int ID_CONTINUOUS_SEC = 1;
    public static final int ID_TIME_IN_SEC = 2;
    public static final int ID_TIME_IN_MIN = 3;
    public static final int ID_TIME_IN_HR = 4;
    public static final int ID_WINDOW_WIDTH = 5;
    public static final int ID_WINDOW_HEIGHT = 6;
    public static final int ID_COMPONENT_WIDTH = 7;
    public static final int ID_COMPONENT_HEIGHT = 8;
    public static final int ID_CALENDAR_MONTH = 9;
    public static final int ID_OFFSET_TO_UTC = 10;
    public static final int ID_WEEK_DAY = 11;
    public static final int ID_DAY_OF_MONTH = 12;

    /**
     * CONTINUOUS_SEC is seconds from midnight looping every hour 0-3600
     */
    public static final float FLOAT_CONTINUOUS_SEC = Utils.asNan(ID_CONTINUOUS_SEC);
    /**
     * seconds run from Midnight=0 quantized to seconds hour 0..3599
     */
    public static final float FLOAT_TIME_IN_SEC = Utils.asNan(ID_TIME_IN_SEC);
    /**
     * minutes run from Midnight=0 quantized to minutes 0..1439
     */
    public static final float FLOAT_TIME_IN_MIN = Utils.asNan(ID_TIME_IN_MIN);
    /**
     * hours run from Midnight=0 quantized to Hours 0-23
     */
    public static final float FLOAT_TIME_IN_HR = Utils.asNan(ID_TIME_IN_HR);
    /**
     * Moth of Year quantized to MONTHS 1-12. 1 = January
     */
    public static final float FLOAT_CALENDAR_MONTH = Utils.asNan(ID_CALENDAR_MONTH);
    /**
     * DAY OF THE WEEK 1-7. 1 = Monday
     */
    public static final float FLOAT_WEEK_DAY = Utils.asNan(ID_WEEK_DAY);
    /**
     * DAY OF THE MONTH 1-31
     */
    public static final float FLOAT_DAY_OF_MONTH = Utils.asNan(ID_DAY_OF_MONTH);

    public static final float FLOAT_WINDOW_WIDTH = Utils.asNan(ID_WINDOW_WIDTH);
    public static final float FLOAT_WINDOW_HEIGHT = Utils.asNan(ID_WINDOW_HEIGHT);
    public static final float FLOAT_COMPONENT_WIDTH = Utils.asNan(ID_COMPONENT_WIDTH);
    public static final float FLOAT_COMPONENT_HEIGHT = Utils.asNan(ID_COMPONENT_HEIGHT);
    // ID_OFFSET_TO_UTC is the offset from UTC in sec (typically / 3600f)
    public static final float FLOAT_OFFSET_TO_UTC = Utils.asNan(ID_OFFSET_TO_UTC);

    ///////////////////////////////////////////////////////////////////////////////////////////////
    // Click handling
    ///////////////////////////////////////////////////////////////////////////////////////////////
    public static boolean isTime(float fl) {
        int value = Utils.idFromNan(fl);
        return value >= ID_CONTINUOUS_SEC && value <= ID_DAY_OF_MONTH;
    }

    public static float getTime(float fl) {
        LocalDateTime dateTime = LocalDateTime.now();
        // This define the time in the format
        // seconds run from Midnight=0 quantized to seconds hour 0..3599
        // minutes run from Midnight=0 quantized to minutes 0..1439
        // hours run from Midnight=0 quantized to Hours 0-23
        // CONTINUOUS_SEC is seconds from midnight looping every hour 0-3600
        // CONTINUOUS_SEC is accurate to milliseconds due to float precession
        // ID_OFFSET_TO_UTC is the offset from UTC in sec (typically / 3600f)
        int value = Utils.idFromNan(fl);
        int month = dateTime.getMonth().getValue();
        int hour = dateTime.getHour();
        int minute = dateTime.getMinute();
        int seconds = dateTime.getSecond();
        int currentMinute = hour * 60 + minute;
        int currentSeconds = minute * 60 + seconds;
        float sec = currentSeconds + dateTime.getNano() * 1E-9f;
        int day_week = dateTime.getDayOfWeek().getValue();


        ZoneId zone = ZoneId.systemDefault();
        OffsetDateTime offsetDateTime = dateTime.atZone(zone).toOffsetDateTime();
        ZoneOffset offset = offsetDateTime.getOffset();
        switch (value) {
            case ID_OFFSET_TO_UTC:
                return offset.getTotalSeconds();
            case ID_CONTINUOUS_SEC:
                return sec;
            case ID_TIME_IN_SEC:
                return currentSeconds;
            case ID_TIME_IN_MIN:
                return currentMinute;
            case ID_TIME_IN_HR:
                return hour;
            case ID_CALENDAR_MONTH:
            case ID_DAY_OF_MONTH:
                return month;
            case ID_WEEK_DAY:
                return day_week;
        }
        return fl;
    }



    public abstract void addClickArea(
            int id,
            int contentDescription,
            float left,
            float top,
            float right,
            float bottom,
            int metadataId
    );
}

