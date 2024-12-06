/*
 * Copyright (C) 2023 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.widget.remotecompose.core.operations.ComponentValue;
import com.android.internal.widget.remotecompose.core.operations.FloatExpression;
import com.android.internal.widget.remotecompose.core.operations.IntegerExpression;
import com.android.internal.widget.remotecompose.core.operations.NamedVariable;
import com.android.internal.widget.remotecompose.core.operations.RootContentBehavior;
import com.android.internal.widget.remotecompose.core.operations.ShaderData;
import com.android.internal.widget.remotecompose.core.operations.TextData;
import com.android.internal.widget.remotecompose.core.operations.Theme;
import com.android.internal.widget.remotecompose.core.operations.layout.ClickModifierOperation;
import com.android.internal.widget.remotecompose.core.operations.layout.Component;
import com.android.internal.widget.remotecompose.core.operations.layout.ComponentEnd;
import com.android.internal.widget.remotecompose.core.operations.layout.ComponentStartOperation;
import com.android.internal.widget.remotecompose.core.operations.layout.LoopEnd;
import com.android.internal.widget.remotecompose.core.operations.layout.LoopOperation;
import com.android.internal.widget.remotecompose.core.operations.layout.OperationsListEnd;
import com.android.internal.widget.remotecompose.core.operations.layout.RootLayoutComponent;
import com.android.internal.widget.remotecompose.core.operations.layout.TouchCancelModifierOperation;
import com.android.internal.widget.remotecompose.core.operations.layout.TouchDownModifierOperation;
import com.android.internal.widget.remotecompose.core.operations.layout.TouchUpModifierOperation;
import com.android.internal.widget.remotecompose.core.operations.layout.modifiers.ComponentModifiers;
import com.android.internal.widget.remotecompose.core.operations.layout.modifiers.ModifierOperation;
import com.android.internal.widget.remotecompose.core.operations.layout.modifiers.ScrollModifierOperation;
import com.android.internal.widget.remotecompose.core.operations.utilities.StringSerializer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Represents a platform independent RemoteCompose document, containing RemoteCompose operations +
 * state
 */
public class CoreDocument {

    private static final boolean DEBUG = false;
    private static final int DOCUMENT_API_LEVEL = 2;

    @NonNull ArrayList<Operation> mOperations = new ArrayList<>();

    @Nullable RootLayoutComponent mRootLayoutComponent = null;

    @NonNull RemoteComposeState mRemoteComposeState = new RemoteComposeState();
    @VisibleForTesting @NonNull public TimeVariables mTimeVariables = new TimeVariables();
    // Semantic version of the document
    @NonNull Version mVersion = new Version(0, 1, 0);

    @Nullable
    String mContentDescription; // text description of the document (used for accessibility)

    long mRequiredCapabilities = 0L; // bitmask indicating needed capabilities of the player(unused)
    int mWidth = 0; // horizontal dimension of the document in pixels
    int mHeight = 0; // vertical dimension of the document in pixels

    int mContentScroll = RootContentBehavior.NONE;
    int mContentSizing = RootContentBehavior.NONE;
    int mContentMode = RootContentBehavior.NONE;

    int mContentAlignment = RootContentBehavior.ALIGNMENT_CENTER;

    @NonNull RemoteComposeBuffer mBuffer = new RemoteComposeBuffer(mRemoteComposeState);

    private final HashMap<Long, IntegerExpression> mIntegerExpressions = new HashMap<>();

    private final HashMap<Integer, FloatExpression> mFloatExpressions = new HashMap<>();

    private HashSet<Component> mAppliedTouchOperations = new HashSet<>();

    private int mLastId = 1; // last component id when inflating the file

    /** Returns a version number that is monotonically increasing. */
    public static int getDocumentApiLevel() {
        return DOCUMENT_API_LEVEL;
    }

    @Nullable
    public String getContentDescription() {
        return mContentDescription;
    }

    public void setContentDescription(@Nullable String contentDescription) {
        this.mContentDescription = contentDescription;
    }

    public long getRequiredCapabilities() {
        return mRequiredCapabilities;
    }

    public void setRequiredCapabilities(long requiredCapabilities) {
        this.mRequiredCapabilities = requiredCapabilities;
    }

    public int getWidth() {
        return mWidth;
    }

    public void setWidth(int width) {
        this.mWidth = width;
        mRemoteComposeState.setWindowWidth(width);
    }

    public int getHeight() {
        return mHeight;
    }

    public void setHeight(int height) {
        this.mHeight = height;
        mRemoteComposeState.setWindowHeight(height);
    }

    @NonNull
    public RemoteComposeBuffer getBuffer() {
        return mBuffer;
    }

    public void setBuffer(@NonNull RemoteComposeBuffer buffer) {
        this.mBuffer = buffer;
    }

    @NonNull
    public RemoteComposeState getRemoteComposeState() {
        return mRemoteComposeState;
    }

    public void setRemoteComposeState(@NonNull RemoteComposeState remoteComposeState) {
        this.mRemoteComposeState = remoteComposeState;
    }

    public int getContentScroll() {
        return mContentScroll;
    }

    public int getContentSizing() {
        return mContentSizing;
    }

    public int getContentMode() {
        return mContentMode;
    }

    /**
     * Sets the way the player handles the content
     *
     * @param scroll set the horizontal behavior (NONE|SCROLL_HORIZONTAL|SCROLL_VERTICAL)
     * @param alignment set the alignment of the content (TOP|CENTER|BOTTOM|START|END)
     * @param sizing set the type of sizing for the content (NONE|SIZING_LAYOUT|SIZING_SCALE)
     * @param mode set the mode of sizing, either LAYOUT modes or SCALE modes the LAYOUT modes are:
     *     - LAYOUT_MATCH_PARENT - LAYOUT_WRAP_CONTENT or adding an horizontal mode and a vertical
     *     mode: - LAYOUT_HORIZONTAL_MATCH_PARENT - LAYOUT_HORIZONTAL_WRAP_CONTENT -
     *     LAYOUT_HORIZONTAL_FIXED - LAYOUT_VERTICAL_MATCH_PARENT - LAYOUT_VERTICAL_WRAP_CONTENT -
     *     LAYOUT_VERTICAL_FIXED The LAYOUT_*_FIXED modes will use the intrinsic document size
     */
    public void setRootContentBehavior(int scroll, int alignment, int sizing, int mode) {
        this.mContentScroll = scroll;
        this.mContentAlignment = alignment;
        this.mContentSizing = sizing;
        this.mContentMode = mode;
    }

    /**
     * Given dimensions w x h of where to paint the content, returns the corresponding scale factor
     * according to the contentSizing information
     *
     * @param w horizontal dimension of the rendering area
     * @param h vertical dimension of the rendering area
     * @param scaleOutput will contain the computed scale factor
     */
    public void computeScale(float w, float h, @NonNull float[] scaleOutput) {
        float contentScaleX = 1f;
        float contentScaleY = 1f;
        if (mContentSizing == RootContentBehavior.SIZING_SCALE) {
            // we need to add canvas transforms ops here
            float scaleX = 1f;
            float scaleY = 1f;
            float scale = 1f;
            switch (mContentMode) {
                case RootContentBehavior.SCALE_INSIDE:
                    scaleX = w / mWidth;
                    scaleY = h / mHeight;
                    scale = Math.min(1f, Math.min(scaleX, scaleY));
                    contentScaleX = scale;
                    contentScaleY = scale;
                    break;
                case RootContentBehavior.SCALE_FIT:
                    scaleX = w / mWidth;
                    scaleY = h / mHeight;
                    scale = Math.min(scaleX, scaleY);
                    contentScaleX = scale;
                    contentScaleY = scale;
                    break;
                case RootContentBehavior.SCALE_FILL_WIDTH:
                    scale = w / mWidth;
                    contentScaleX = scale;
                    contentScaleY = scale;
                    break;
                case RootContentBehavior.SCALE_FILL_HEIGHT:
                    scale = h / mHeight;
                    contentScaleX = scale;
                    contentScaleY = scale;
                    break;
                case RootContentBehavior.SCALE_CROP:
                    scaleX = w / mWidth;
                    scaleY = h / mHeight;
                    scale = Math.max(scaleX, scaleY);
                    contentScaleX = scale;
                    contentScaleY = scale;
                    break;
                case RootContentBehavior.SCALE_FILL_BOUNDS:
                    scaleX = w / mWidth;
                    scaleY = h / mHeight;
                    contentScaleX = scaleX;
                    contentScaleY = scaleY;
                    break;
                default:
                    // nothing
            }
        }
        scaleOutput[0] = contentScaleX;
        scaleOutput[1] = contentScaleY;
    }

    /**
     * Given dimensions w x h of where to paint the content, returns the corresponding translation
     * according to the contentAlignment information
     *
     * @param w horizontal dimension of the rendering area
     * @param h vertical dimension of the rendering area
     * @param contentScaleX the horizontal scale we are going to use for the content
     * @param contentScaleY the vertical scale we are going to use for the content
     * @param translateOutput will contain the computed translation
     */
    private void computeTranslate(
            float w,
            float h,
            float contentScaleX,
            float contentScaleY,
            @NonNull float[] translateOutput) {
        int horizontalContentAlignment = mContentAlignment & 0xF0;
        int verticalContentAlignment = mContentAlignment & 0xF;
        float translateX = 0f;
        float translateY = 0f;
        float contentWidth = mWidth * contentScaleX;
        float contentHeight = mHeight * contentScaleY;

        switch (horizontalContentAlignment) {
            case RootContentBehavior.ALIGNMENT_START:
                // nothing
                break;
            case RootContentBehavior.ALIGNMENT_HORIZONTAL_CENTER:
                translateX = (w - contentWidth) / 2f;
                break;
            case RootContentBehavior.ALIGNMENT_END:
                translateX = w - contentWidth;
                break;
            default:
                // nothing (same as alignment_start)
        }
        switch (verticalContentAlignment) {
            case RootContentBehavior.ALIGNMENT_TOP:
                // nothing
                break;
            case RootContentBehavior.ALIGNMENT_VERTICAL_CENTER:
                translateY = (h - contentHeight) / 2f;
                break;
            case RootContentBehavior.ALIGNMENT_BOTTOM:
                translateY = h - contentHeight;
                break;
            default:
                // nothing (same as alignment_top)
        }

        translateOutput[0] = translateX;
        translateOutput[1] = translateY;
    }

    /**
     * Returns the list of click areas
     *
     * @return list of click areas in document coordinates
     */
    @NonNull
    public Set<ClickAreaRepresentation> getClickAreas() {
        return mClickAreas;
    }

    /**
     * Returns the root layout component
     *
     * @return returns the root component if it exists, null otherwise
     */
    @Nullable
    public RootLayoutComponent getRootLayoutComponent() {
        return mRootLayoutComponent;
    }

    /** Invalidate the document for layout measures. This will trigger a layout remeasure pass. */
    public void invalidateMeasure() {
        if (mRootLayoutComponent != null) {
            mRootLayoutComponent.invalidateMeasure();
        }
    }

    /**
     * Returns the component with the given id
     *
     * @param id component id
     * @return the component if it exists, null otherwise
     */
    @Nullable
    public Component getComponent(int id) {
        if (mRootLayoutComponent != null) {
            return mRootLayoutComponent.getComponent(id);
        }
        return null;
    }

    /**
     * Returns a string representation of the component hierarchy of the document
     *
     * @return a standardized string representation of the component hierarchy
     */
    @NonNull
    public String displayHierarchy() {
        StringSerializer serializer = new StringSerializer();
        for (Operation op : mOperations) {
            if (op instanceof RootLayoutComponent) {
                ((RootLayoutComponent) op).displayHierarchy((Component) op, 0, serializer);
            } else if (op instanceof SerializableToString) {
                ((SerializableToString) op).serializeToString(0, serializer);
            }
        }
        return serializer.toString();
    }

    /**
     * Execute an integer expression with the given id and put its value on the targetId
     *
     * @param expressionId the id of the integer expression
     * @param targetId the id of the value to update with the expression
     * @param context the current context
     */
    public void evaluateIntExpression(
            long expressionId, int targetId, @NonNull RemoteContext context) {
        IntegerExpression expression = mIntegerExpressions.get(expressionId);
        if (expression != null) {
            int v = expression.evaluate(context);
            context.overrideInteger(targetId, v);
        }
    }

    /**
     * Execute an integer expression with the given id and put its value on the targetId
     *
     * @param expressionId the id of the integer expression
     * @param targetId the id of the value to update with the expression
     * @param context the current context
     */
    public void evaluateFloatExpression(
            int expressionId, int targetId, @NonNull RemoteContext context) {
        FloatExpression expression = mFloatExpressions.get(expressionId);
        if (expression != null) {
            float v = expression.evaluate(context);
            context.overrideFloat(targetId, v);
        }
    }

    // ============== Haptic support ==================
    public interface HapticEngine {
        void haptic(int type);
    }

    HapticEngine mHapticEngine;

    public void setHapticEngine(HapticEngine engine) {
        mHapticEngine = engine;
    }

    public void haptic(int type) {
        if (mHapticEngine != null) {
            mHapticEngine.haptic(type);
        }
    }

    // ============== Haptic support ==================

    public void appliedTouchOperation(Component operation) {
        mAppliedTouchOperations.add(operation);
    }

    /** Callback interface for host actions */
    public interface ActionCallback {
        void onAction(@NonNull String name, Object value);
    }

    @NonNull HashSet<ActionCallback> mActionListeners = new HashSet<ActionCallback>();

    /**
     * Warn action listeners for the given named action
     *
     * @param name the action name
     * @param value a parameter to the action
     */
    public void runNamedAction(@NonNull String name, Object value) {
        // TODO: we might add an interface to group all valid parameter types
        for (ActionCallback callback : mActionListeners) {
            callback.onAction(name, value);
        }
    }

    /**
     * Add a callback for handling the named host actions
     *
     * @param callback
     */
    public void addActionCallback(@NonNull ActionCallback callback) {
        mActionListeners.add(callback);
    }

    /** Clear existing callbacks for named host actions */
    public void clearActionCallbacks() {
        mActionListeners.clear();
    }

    public interface IdActionCallback {
        void onAction(int id, @Nullable String metadata);
    }

    @NonNull HashSet<IdActionCallback> mIdActionListeners = new HashSet<>();
    @NonNull HashSet<TouchListener> mTouchListeners = new HashSet<>();
    @NonNull HashSet<ClickAreaRepresentation> mClickAreas = new HashSet<>();

    static class Version {
        public final int major;
        public final int minor;
        public final int patchLevel;

        Version(int major, int minor, int patchLevel) {
            this.major = major;
            this.minor = minor;
            this.patchLevel = patchLevel;
        }
    }

    public static class ClickAreaRepresentation {
        int mId;
        @Nullable final String mContentDescription;
        float mLeft;
        float mTop;
        float mRight;
        float mBottom;
        @Nullable final String mMetadata;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ClickAreaRepresentation)) return false;
            ClickAreaRepresentation that = (ClickAreaRepresentation) o;
            return mId == that.mId
                    && Objects.equals(mContentDescription, that.mContentDescription)
                    && Objects.equals(mMetadata, that.mMetadata);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mId, mContentDescription, mMetadata);
        }

        public ClickAreaRepresentation(
                int id,
                @Nullable String contentDescription,
                float left,
                float top,
                float right,
                float bottom,
                @Nullable String metadata) {
            this.mId = id;
            this.mContentDescription = contentDescription;
            this.mLeft = left;
            this.mTop = top;
            this.mRight = right;
            this.mBottom = bottom;
            this.mMetadata = metadata;
        }

        /**
         * Returns true if x,y coordinate is within bounds
         *
         * @param x x-coordinate
         * @param y y-coordinate
         * @return x, y coordinate is within bounds
         */
        public boolean contains(float x, float y) {
            return x >= mLeft && x < mRight && y >= mTop && y < mBottom;
        }

        public float getLeft() {
            return mLeft;
        }

        public float getTop() {
            return mTop;
        }

        public float width() {
            return Math.max(0, mRight - mLeft);
        }

        public float height() {
            return Math.max(0, mBottom - mTop);
        }

        public int getId() {
            return mId;
        }

        public @Nullable String getContentDescription() {
            return mContentDescription;
        }

        @Nullable
        public String getMetadata() {
            return mMetadata;
        }
    }

    /** Load operations from the given buffer */
    public void initFromBuffer(@NonNull RemoteComposeBuffer buffer) {
        mOperations = new ArrayList<Operation>();
        buffer.inflateFromBuffer(mOperations);
        for (Operation op : mOperations) {
            if (op instanceof IntegerExpression) {
                IntegerExpression expression = (IntegerExpression) op;
                mIntegerExpressions.put((long) expression.mId, expression);
            }
            if (op instanceof FloatExpression) {
                FloatExpression expression = (FloatExpression) op;
                mFloatExpressions.put(expression.mId, expression);
            }
        }
        mOperations = inflateComponents(mOperations);
        mBuffer = buffer;
        for (Operation op : mOperations) {
            if (op instanceof RootLayoutComponent) {
                mRootLayoutComponent = (RootLayoutComponent) op;
                break;
            }
        }
        if (mRootLayoutComponent != null) {
            mRootLayoutComponent.assignIds(mLastId);
        }
    }

    /**
     * Inflate a component tree
     *
     * @param operations flat list of operations
     * @return nested list of operations / components
     */
    @NonNull
    private ArrayList<Operation> inflateComponents(@NonNull ArrayList<Operation> operations) {
        Component currentComponent = null;
        ArrayList<Component> components = new ArrayList<>();
        ArrayList<Operation> finalOperationsList = new ArrayList<>();
        ArrayList<Operation> ops = finalOperationsList;
        ClickModifierOperation currentClickModifier = null;
        TouchDownModifierOperation currentTouchDownModifier = null;
        TouchUpModifierOperation currentTouchUpModifier = null;
        TouchCancelModifierOperation currentTouchCancelModifier = null;
        LoopOperation currentLoop = null;
        ScrollModifierOperation currentScrollModifier = null;

        mLastId = -1;
        for (Operation o : operations) {
            if (o instanceof ComponentStartOperation) {
                Component component = (Component) o;
                component.setParent(currentComponent);
                components.add(component);
                currentComponent = component;
                ops.add(currentComponent);
                ops = currentComponent.getList();
                if (component.getComponentId() < mLastId) {
                    mLastId = component.getComponentId();
                }
            } else if (o instanceof ComponentEnd) {
                if (currentComponent != null) {
                    currentComponent.inflate();
                }
                components.remove(components.size() - 1);
                if (!components.isEmpty()) {
                    currentComponent = components.get(components.size() - 1);
                    ops = currentComponent.getList();
                } else {
                    ops = finalOperationsList;
                }
            } else if (o instanceof ClickModifierOperation) {
                // TODO: refactor to add container <- component...
                currentClickModifier = (ClickModifierOperation) o;
                ops = currentClickModifier.getList();
            } else if (o instanceof TouchDownModifierOperation) {
                currentTouchDownModifier = (TouchDownModifierOperation) o;
                ops = currentTouchDownModifier.getList();
            } else if (o instanceof TouchUpModifierOperation) {
                currentTouchUpModifier = (TouchUpModifierOperation) o;
                ops = currentTouchUpModifier.getList();
            } else if (o instanceof TouchCancelModifierOperation) {
                currentTouchCancelModifier = (TouchCancelModifierOperation) o;
                ops = currentTouchCancelModifier.getList();
            } else if (o instanceof ScrollModifierOperation) {
                currentScrollModifier = (ScrollModifierOperation) o;
                ops = currentScrollModifier.getList();
            } else if (o instanceof OperationsListEnd) {
                ops = currentComponent.getList();
                if (currentClickModifier != null) {
                    ops.add(currentClickModifier);
                    currentClickModifier = null;
                } else if (currentTouchDownModifier != null) {
                    ops.add(currentTouchDownModifier);
                    currentTouchDownModifier = null;
                } else if (currentTouchUpModifier != null) {
                    ops.add(currentTouchUpModifier);
                    currentTouchUpModifier = null;
                } else if (currentTouchCancelModifier != null) {
                    ops.add(currentTouchCancelModifier);
                    currentTouchCancelModifier = null;
                } else if (currentScrollModifier != null) {
                    ops.add(currentScrollModifier);
                    currentScrollModifier = null;
                }
            } else if (o instanceof LoopOperation) {
                currentLoop = (LoopOperation) o;
                ops = currentLoop.getList();
            } else if (o instanceof LoopEnd) {
                if (currentComponent != null) {
                    ops = currentComponent.getList();
                    ops.add(currentLoop);
                } else {
                    ops = finalOperationsList;
                }
                currentLoop = null;
            } else {
                ops.add(o);
            }
        }
        return ops;
    }

    @NonNull private HashMap<Integer, Component> mComponentMap = new HashMap<Integer, Component>();

    private void registerVariables(
            @NonNull RemoteContext context, @NonNull ArrayList<Operation> list) {
        for (Operation op : list) {
            if (op instanceof VariableSupport) {
                ((VariableSupport) op).updateVariables(context);
                ((VariableSupport) op).registerListening(context);
            }
            if (op instanceof Component) {
                mComponentMap.put(((Component) op).getComponentId(), (Component) op);
                registerVariables(context, ((Component) op).getList());
            }
            if (op instanceof ComponentValue) {
                ComponentValue v = (ComponentValue) op;
                Component component = mComponentMap.get(v.getComponentId());
                if (component != null) {
                    component.addComponentValue(v);
                } else {
                    System.out.println("=> Component not found for id " + v.getComponentId());
                }
            }
            if (op instanceof ComponentModifiers) {
                for (ModifierOperation modifier : ((ComponentModifiers) op).getList()) {
                    if (modifier instanceof VariableSupport) {
                        ((VariableSupport) modifier).updateVariables(context);
                        ((VariableSupport) modifier).registerListening(context);
                    }
                }
            }
            op.markNotDirty();
            op.apply(context);
            context.incrementOpCount();
        }
    }

    /**
     * Called when an initialization is needed, allowing the document to eg load resources / cache
     * them.
     */
    public void initializeContext(@NonNull RemoteContext context) {
        mRemoteComposeState.reset();
        mRemoteComposeState.setContext(context);
        mClickAreas.clear();
        mRemoteComposeState.setNextId(RemoteComposeState.START_ID);
        context.mDocument = this;
        context.mRemoteComposeState = mRemoteComposeState;
        // mark context to be in DATA mode, which will skip the painting ops.
        context.mMode = RemoteContext.ContextMode.DATA;
        mTimeVariables.updateTime(context);

        registerVariables(context, mOperations);
        context.mMode = RemoteContext.ContextMode.UNSET;
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////
    // Document infos
    ///////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Returns true if the document can be displayed given this version of the player
     *
     * @param majorVersion the max major version supported by the player
     * @param minorVersion the max minor version supported by the player
     * @param capabilities a bitmask of capabilities the player supports (unused for now)
     */
    public boolean canBeDisplayed(int majorVersion, int minorVersion, long capabilities) {
        return mVersion.major <= majorVersion && mVersion.minor <= minorVersion;
    }

    /**
     * Set the document version, following semantic versioning.
     *
     * @param majorVersion major version number, increased upon changes breaking the compatibility
     * @param minorVersion minor version number, increased when adding new features
     * @param patch patch level, increased upon bugfixes
     */
    void setVersion(int majorVersion, int minorVersion, int patch) {
        mVersion = new Version(majorVersion, minorVersion, patch);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////
    // Click handling
    ///////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Add a click area to the document, in root coordinates. We are not doing any specific sorting
     * through the declared areas on click detections, which means that the first one containing the
     * click coordinates will be the one reported; the order of addition of those click areas is
     * therefore meaningful.
     *
     * @param id the id of the area, which will be reported on click
     * @param contentDescription the content description (used for accessibility)
     * @param left the left coordinate of the click area (in pixels)
     * @param top the top coordinate of the click area (in pixels)
     * @param right the right coordinate of the click area (in pixels)
     * @param bottom the bottom coordinate of the click area (in pixels)
     * @param metadata arbitrary metadata associated with the are, also reported on click
     */
    public void addClickArea(
            int id,
            @Nullable String contentDescription,
            float left,
            float top,
            float right,
            float bottom,
            @Nullable String metadata) {

        ClickAreaRepresentation car =
                new ClickAreaRepresentation(
                        id, contentDescription, left, top, right, bottom, metadata);

        boolean old = mClickAreas.remove(car);
        mClickAreas.add(car);
    }

    /**
     * Called by commands to listen to touch events
     *
     * @param listener
     */
    public void addTouchListener(TouchListener listener) {
        mTouchListeners.add(listener);
    }

    /**
     * Add an id action listener. This will get called when e.g. a click is detected on the document
     *
     * @param callback called when an action is executed, passing the id and metadata.
     */
    public void addIdActionListener(@NonNull IdActionCallback callback) {
        mIdActionListeners.add(callback);
    }

    /**
     * Returns the list of set click listeners
     *
     * @return set of click listeners
     */
    @NonNull
    public HashSet<IdActionCallback> getIdActionListeners() {
        return mIdActionListeners;
    }

    /**
     * Passing a click event to the document. This will possibly result in calling the click
     * listeners.
     */
    public void onClick(@NonNull RemoteContext context, float x, float y) {
        for (ClickAreaRepresentation clickArea : mClickAreas) {
            if (clickArea.contains(x, y)) {
                warnClickListeners(clickArea);
            }
        }
        if (mRootLayoutComponent != null) {
            mRootLayoutComponent.onClick(context, this, x, y);
        }
    }

    /**
     * Programmatically trigger the click response for the given id
     *
     * @param id the click area id
     */
    public void performClick(int id) {
        for (ClickAreaRepresentation clickArea : mClickAreas) {
            if (clickArea.mId == id) {
                warnClickListeners(clickArea);
            }
        }
        for (IdActionCallback listener : mIdActionListeners) {
            listener.onAction(id, "");
        }
    }

    /** Warn click listeners when a click area is activated */
    private void warnClickListeners(@NonNull ClickAreaRepresentation clickArea) {
        for (IdActionCallback listener : mIdActionListeners) {
            listener.onAction(clickArea.mId, clickArea.mMetadata);
        }
    }

    /**
     * Returns true if the document has touch listeners
     *
     * @return true if the document needs to react to touch events
     */
    public boolean hasTouchListener() {
        boolean hasComponentsTouchListeners =
                mRootLayoutComponent != null && mRootLayoutComponent.hasTouchListeners();
        return hasComponentsTouchListeners || !mTouchListeners.isEmpty();
    }

    // TODO support velocity estimate support, support regions
    /**
     * Support touch drag events on commands supporting touch
     *
     * @param x position of touch
     * @param y position of touch
     */
    public boolean touchDrag(RemoteContext context, float x, float y) {
        context.loadFloat(RemoteContext.ID_TOUCH_POS_X, x);
        context.loadFloat(RemoteContext.ID_TOUCH_POS_Y, y);
        for (TouchListener clickArea : mTouchListeners) {
            clickArea.touchDrag(context, x, y);
        }
        if (mRootLayoutComponent != null) {
            for (Component component : mAppliedTouchOperations) {
                component.onTouchDrag(context, this, x, y, true);
            }
            if (!mAppliedTouchOperations.isEmpty()) {
                return true;
            }
        }
        if (!mTouchListeners.isEmpty()) {
            return true;
        }
        return false;
    }

    /**
     * Support touch down events on commands supporting touch
     *
     * @param x position of touch
     * @param y position of touch
     */
    public void touchDown(RemoteContext context, float x, float y) {
        context.loadFloat(RemoteContext.ID_TOUCH_POS_X, x);
        context.loadFloat(RemoteContext.ID_TOUCH_POS_Y, y);
        for (TouchListener clickArea : mTouchListeners) {
            clickArea.touchDown(context, x, y);
        }
        if (mRootLayoutComponent != null) {
            mRootLayoutComponent.onTouchDown(context, this, x, y);
        }
        mRepaintNext = 1;
    }

    /**
     * Support touch up events on commands supporting touch
     *
     * @param x position of touch
     * @param y position of touch
     */
    public void touchUp(RemoteContext context, float x, float y, float dx, float dy) {
        context.loadFloat(RemoteContext.ID_TOUCH_POS_X, x);
        context.loadFloat(RemoteContext.ID_TOUCH_POS_Y, y);
        for (TouchListener clickArea : mTouchListeners) {
            clickArea.touchUp(context, x, y, dx, dy);
        }
        if (mRootLayoutComponent != null) {
            for (Component component : mAppliedTouchOperations) {
                component.onTouchUp(context, this, x, y, dx, dy, true);
            }
            mAppliedTouchOperations.clear();
        }
        mRepaintNext = 1;
    }

    /**
     * Support touch cancel events on commands supporting touch
     *
     * @param x position of touch
     * @param y position of touch
     */
    public void touchCancel(RemoteContext context, float x, float y, float dx, float dy) {
        if (mRootLayoutComponent != null) {
            for (Component component : mAppliedTouchOperations) {
                component.onTouchCancel(context, this, x, y, true);
            }
            mAppliedTouchOperations.clear();
        }
        mRepaintNext = 1;
    }

    @NonNull
    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        for (Operation op : mOperations) {
            builder.append(op.toString());
            builder.append("\n");
        }
        return builder.toString();
    }

    /**
     * Gets the names of all named colors.
     *
     * @return array of named colors or null
     */
    @Nullable
    public String[] getNamedColors() {
        return getNamedVariables(NamedVariable.COLOR_TYPE);
    }

    /**
     * Gets the names of all named Variables.
     *
     * @return array of named variables or null
     */
    public String[] getNamedVariables(int type) {
        int count = 0;
        for (Operation op : mOperations) {
            if (op instanceof NamedVariable) {
                NamedVariable n = (NamedVariable) op;
                if (n.mVarType == type) {
                    count++;
                }
            }
        }
        if (count == 0) {
            return null;
        }
        String[] ret = new String[count];
        int i = 0;
        for (Operation op : mOperations) {
            if (op instanceof NamedVariable) {
                NamedVariable n = (NamedVariable) op;
                if (n.mVarType == type) {
                    ret[i++] = n.mVarName;
                }
            }
        }
        return ret;
    }

    //////////////////////////////////////////////////////////////////////////
    // Painting
    //////////////////////////////////////////////////////////////////////////

    private final float[] mScaleOutput = new float[2];
    private final float[] mTranslateOutput = new float[2];
    private int mRepaintNext = -1; // delay to next repaint -1 = don't 1 = asap
    private int mLastOpCount;

    /**
     * This is the number of ops used to calculate the last frame.
     *
     * @return number of ops
     */
    public int getOpsPerFrame() {
        return mLastOpCount;
    }

    /**
     * Returns > 0 if it needs to repaint
     *
     * @return
     */
    public int needsRepaint() {
        return mRepaintNext;
    }

    /**
     * Paint the document
     *
     * @param context the provided PaintContext
     * @param theme the theme we want to use for this document.
     */
    public void paint(@NonNull RemoteContext context, int theme) {
        context.getLastOpCount();
        context.getPaintContext().clearNeedsRepaint();
        context.loadFloat(RemoteContext.ID_DENSITY, context.getDensity());
        context.mMode = RemoteContext.ContextMode.UNSET;
        // current theme starts as UNSPECIFIED, until a Theme setter
        // operation gets executed and modify it.
        context.setTheme(Theme.UNSPECIFIED);

        context.mRemoteComposeState = mRemoteComposeState;
        context.mRemoteComposeState.setContext(context);

        // If we have a content sizing set, we are going to take the original document
        // dimension into account and apply scale+translate according to the RootContentBehavior
        // rules.
        if (mContentSizing == RootContentBehavior.SIZING_SCALE) {
            // we need to add canvas transforms ops here
            computeScale(context.mWidth, context.mHeight, mScaleOutput);
            float sw = mScaleOutput[0];
            float sh = mScaleOutput[1];
            computeTranslate(context.mWidth, context.mHeight, sw, sh, mTranslateOutput);
            context.mPaintContext.translate(mTranslateOutput[0], mTranslateOutput[1]);
            context.mPaintContext.scale(sw, sh);
        } else {
            // If not, we set the document width and height to be the current context width and
            // height.
            setWidth((int) context.mWidth);
            setHeight((int) context.mHeight);
        }
        mTimeVariables.updateTime(context);
        mRepaintNext = context.updateOps();
        if (mRootLayoutComponent != null) {
            if (context.mWidth != mRootLayoutComponent.getWidth()
                    || context.mHeight != mRootLayoutComponent.getHeight()) {
                mRootLayoutComponent.invalidateMeasure();
            }
            if (mRootLayoutComponent.needsMeasure()) {
                mRootLayoutComponent.layout(context);
            }
            if (mRootLayoutComponent.needsBoundsAnimation()) {
                mRepaintNext = 1;
                mRootLayoutComponent.clearNeedsBoundsAnimation();
                mRootLayoutComponent.animatingBounds(context);
            }
            if (DEBUG) {
                String hierarchy = mRootLayoutComponent.displayHierarchy();
                System.out.println(hierarchy);
            }
            if (mRootLayoutComponent.doesNeedsRepaint()) {
                mRepaintNext = 1;
            }
        }
        context.mMode = RemoteContext.ContextMode.PAINT;
        for (Operation op : mOperations) {
            // operations will only be executed if no theme is set (ie UNSPECIFIED)
            // or the theme is equal as the one passed in argument to paint.
            boolean apply = true;
            if (theme != Theme.UNSPECIFIED) {
                apply =
                        op instanceof Theme // always apply a theme setter
                                || context.getTheme() == theme
                                || context.getTheme() == Theme.UNSPECIFIED;
            }
            if (apply) {
                if (op.isDirty() || op instanceof PaintOperation) {
                    if (op.isDirty() && op instanceof VariableSupport) {
                        op.markNotDirty();
                        ((VariableSupport) op).updateVariables(context);
                    }
                    context.incrementOpCount();
                    op.apply(context);
                }
            }
        }
        if (context.getPaintContext().doesNeedsRepaint()
                || (mRootLayoutComponent != null && mRootLayoutComponent.doesNeedsRepaint())) {
            mRepaintNext = 1;
        }
        context.mMode = RemoteContext.ContextMode.UNSET;
        if (DEBUG && mRootLayoutComponent != null) {
            System.out.println(mRootLayoutComponent.displayHierarchy());
        }
        mLastOpCount = context.getLastOpCount();
    }

    /**
     * Get an estimated number of operations executed in a paint
     *
     * @return number of operations
     */
    public int getNumberOfOps() {
        int count = mOperations.size();

        for (Operation mOperation : mOperations) {
            if (mOperation instanceof Component) {
                count += getChildOps((Component) mOperation);
            }
        }
        return count;
    }

    private int getChildOps(@NonNull Component base) {
        int count = base.mList.size();
        for (Operation mOperation : base.mList) {

            if (mOperation instanceof Component) {
                int mult = 1;
                if (mOperation instanceof LoopOperation) {
                    mult = ((LoopOperation) mOperation).estimateIterations();
                }
                count += mult * getChildOps((Component) mOperation);
            }
        }
        return count;
    }

    @NonNull
    public String[] getStats() {
        ArrayList<String> ret = new ArrayList<>();
        WireBuffer buffer = new WireBuffer();
        int count = mOperations.size();
        HashMap<String, int[]> map = new HashMap<>();
        for (Operation mOperation : mOperations) {
            Class<? extends Operation> c = mOperation.getClass();
            int[] values;
            if (map.containsKey(c.getSimpleName())) {
                values = map.get(c.getSimpleName());
            } else {
                values = new int[2];
                map.put(c.getSimpleName(), values);
            }

            values[0] += 1;
            values[1] += sizeOfComponent(mOperation, buffer);
            if (mOperation instanceof Component) {
                Component com = (Component) mOperation;
                count += addChildren(com, map, buffer);
            } else if (mOperation instanceof LoopOperation) {
                LoopOperation com = (LoopOperation) mOperation;
                count += addChildren(com, map, buffer);
            }
        }

        ret.add(0, "number of operations : " + count);

        for (String s : map.keySet()) {
            int[] v = map.get(s);
            ret.add(s + " : " + v[0] + ":" + v[1]);
        }
        return ret.toArray(new String[0]);
    }

    private int sizeOfComponent(@NonNull Operation com, @NonNull WireBuffer tmp) {
        tmp.reset(100);
        com.write(tmp);
        int size = tmp.getSize();
        tmp.reset(100);
        return size;
    }

    private int addChildren(
            @NonNull Component base, @NonNull HashMap<String, int[]> map, @NonNull WireBuffer tmp) {
        int count = base.mList.size();
        for (Operation mOperation : base.mList) {
            Class<? extends Operation> c = mOperation.getClass();
            int[] values;
            if (map.containsKey(c.getSimpleName())) {
                values = map.get(c.getSimpleName());
            } else {
                values = new int[2];
                map.put(c.getSimpleName(), values);
            }
            values[0] += 1;
            values[1] += sizeOfComponent(mOperation, tmp);
            if (mOperation instanceof Component) {
                count += addChildren((Component) mOperation, map, tmp);
            }
            if (mOperation instanceof LoopOperation) {
                count += addChildren((LoopOperation) mOperation, map, tmp);
            }
        }
        return count;
    }

    private int addChildren(
            @NonNull LoopOperation base,
            @NonNull HashMap<String, int[]> map,
            @NonNull WireBuffer tmp) {
        int count = base.mList.size();
        for (Operation mOperation : base.mList) {
            Class<? extends Operation> c = mOperation.getClass();
            int[] values;
            if (map.containsKey(c.getSimpleName())) {
                values = map.get(c.getSimpleName());
            } else {
                values = new int[2];
                map.put(c.getSimpleName(), values);
            }
            values[0] += 1;
            values[1] += sizeOfComponent(mOperation, tmp);
            if (mOperation instanceof Component) {
                count += addChildren((Component) mOperation, map, tmp);
            }
            if (mOperation instanceof LoopOperation) {
                count += addChildren((LoopOperation) mOperation, map, tmp);
            }
        }
        return count;
    }

    @NonNull
    public String toNestedString() {
        StringBuilder ret = new StringBuilder();
        for (Operation mOperation : mOperations) {
            ret.append(mOperation.toString());
            ret.append("\n");
            if (mOperation instanceof Component) {
                toNestedString((Component) mOperation, ret, "  ");
            }
        }
        return ret.toString();
    }

    private void toNestedString(
            @NonNull Component base, @NonNull StringBuilder ret, String indent) {
        for (Operation mOperation : base.mList) {
            ret.append(mOperation.toString());
            ret.append("\n");
            if (mOperation instanceof Component) {
                toNestedString((Component) mOperation, ret, indent + "  ");
            }
        }
    }

    @NonNull
    public List<Operation> getOperations() {
        return mOperations;
    }

    /** defines if a shader can be run */
    public interface ShaderControl {
        boolean isShaderValid(String shader);
    }

    /**
     * validate the shaders
     *
     * @param context the remote context
     * @param ctl the call back to allow evaluation of shaders
     */
    public void checkShaders(RemoteContext context, ShaderControl ctl) {
        for (Operation op : mOperations) {
            if (op instanceof TextData) {
                op.apply(context);
            }
            if (op instanceof ShaderData) {
                ShaderData sd = (ShaderData) op;
                int id = sd.getShaderTextId();
                String str = context.getText(id);
                sd.enable(ctl.isShaderValid(str));
            }
        }
    }
}
