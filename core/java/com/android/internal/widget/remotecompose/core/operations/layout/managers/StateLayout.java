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
package com.android.internal.widget.remotecompose.core.operations.layout.managers;

import android.annotation.NonNull;
import android.annotation.Nullable;

import com.android.internal.widget.remotecompose.core.CoreDocument;
import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.PaintContext;
import com.android.internal.widget.remotecompose.core.PaintOperation;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.operations.layout.Component;
import com.android.internal.widget.remotecompose.core.operations.layout.ComponentStartOperation;
import com.android.internal.widget.remotecompose.core.operations.layout.LayoutComponent;
import com.android.internal.widget.remotecompose.core.operations.layout.measure.ComponentMeasure;
import com.android.internal.widget.remotecompose.core.operations.layout.measure.MeasurePass;
import com.android.internal.widget.remotecompose.core.operations.layout.measure.Size;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * State-based animated layout
 *
 * <p>States are defined as child layouts. This layout handles interpolating between the different
 * state in order to provide an automatic transition.
 */
public class StateLayout extends LayoutManager implements ComponentStartOperation {

    public int measuredLayoutIndex = 0;
    public int currentLayoutIndex = 0;
    public int previousLayoutIndex = 0;
    private int mIndexId = 0;

    // This keep track of all the components associated with a given Id,
    // (the key being the id), and the set of components corresponds to the set of states
    // TODO: we should be able to optimize this
    @NonNull public Map<Integer, Component[]> statePaintedComponents = new HashMap<>();

    public int MAX_CACHE_ELEMENTS = 16;
    @NonNull public int[] cacheListElementsId = new int[MAX_CACHE_ELEMENTS];

    public boolean inTransition = false;

    public StateLayout(
            @Nullable Component parent,
            int componentId,
            int animationId,
            float x,
            float y,
            float width,
            float height,
            int indexId) {
        super(parent, componentId, animationId, x, y, width, height);
        //        if (layoutInfo.visibleLayoutIndex != null) {
        //            layoutInfo.visibleLayoutIndex!!.addChangeListener(this)
        //        }
        mIndexId = indexId;
    }

    @Override
    public void inflate() {
        super.inflate();
        hideLayoutsOtherThan(currentLayoutIndex);
    }

    public void findAnimatedComponents() {
        for (int i = 0; i < mChildrenComponents.size(); i++) {
            Component cs = mChildrenComponents.get(i);
            if (cs instanceof LayoutComponent) {
                LayoutComponent state = (LayoutComponent) cs;
                state.setX(0f);
                state.setY(0f);
                ArrayList<Component> childrenComponents = state.getChildrenComponents();
                for (int j = 0; j < childrenComponents.size(); j++) {
                    Component child = childrenComponents.get(j);
                    if (child.getAnimationId() != -1) {
                        if (!statePaintedComponents.containsKey(child.getAnimationId())) {
                            statePaintedComponents.put(
                                    child.getAnimationId(),
                                    new Component[mChildrenComponents.size()]);
                        }
                        statePaintedComponents.get(child.getAnimationId())[i] = child;
                    }
                }
            }
        }
        collapsePaintedComponents();
    }

    public void collapsePaintedComponents() {
        int numStates = mChildrenComponents.size();
        for (Integer id : statePaintedComponents.keySet()) {
            Component[] list = statePaintedComponents.get(id);
            int numComponents = list.length;
            if (numComponents > 1 && list[0] != null) {
                Component c1 = list[0];
                boolean same = true;
                for (int i = 1; i < list.length; i++) {
                    Component c2 = list[i];
                    if (c2 == null || !c1.suitableForTransition(c2)) {
                        same = false;
                        break;
                    }
                }
                if (same) {
                    // TODO: Fix, shouldn't want to recopy all components
                    for (int i = 0; i < numStates; i++) {
                        list[i] = c1;
                    }
                }
            }
        }
    }

    @Override
    public void computeSize(
            @NonNull PaintContext context,
            float minWidth,
            float maxWidth,
            float minHeight,
            float maxHeight,
            @NonNull MeasurePass measure) {
        LayoutManager layout = getLayout(currentLayoutIndex);
        layout.computeSize(context, minWidth, maxWidth, minHeight, maxHeight, measure);
    }

    @Override
    public void internalLayoutMeasure(
            @NonNull PaintContext context,
            // layoutInfo: LayoutInfo,
            @NonNull MeasurePass measure) {
        LayoutManager layout = getLayout(currentLayoutIndex);
        //        layout.internalLayoutMeasure(context, layoutInfo, measure)
        layout.internalLayoutMeasure(context, measure);
    }

    /** Subclasses can implement this to provide wrap sizing */
    @Override
    public void computeWrapSize(
            @NonNull PaintContext context,
            float maxWidth,
            float maxHeight,
            boolean horizontalWrap,
            boolean verticalWrap,
            @NonNull MeasurePass measure,
            @NonNull Size size) {
        LayoutManager layout = getLayout(currentLayoutIndex);
        layout.computeWrapSize(
                context, maxWidth, maxHeight, horizontalWrap, verticalWrap, measure, size);
    }

    @Override
    public void onClick(
            @NonNull RemoteContext context, @NonNull CoreDocument document, float x, float y) {
        if (!contains(x, y)) {
            return;
        }
        LayoutManager layout = getLayout(currentLayoutIndex);
        layout.onClick(context, document, x, y);
    }

    @Override
    public void layout(@NonNull RemoteContext context, @NonNull MeasurePass measure) {
        ComponentMeasure self = measure.get(this);
        super.selfLayout(context, measure);

        // We can simply layout the current layout...
        LayoutManager layout = getLayout(currentLayoutIndex);

        // Pass through the measure information from the state layout to the currently
        // selected component that this being laid out.
        ComponentMeasure layoutMeasure = measure.get(layout.getComponentId());
        layoutMeasure.copyFrom(self);

        layout.layout(context, measure);

        // but if we are in a transition, we might have to layout previous widgets
        if (inTransition && previousLayoutIndex != currentLayoutIndex) {
            LayoutManager previous = getLayout(previousLayoutIndex);
            for (Component c : previous.getChildrenComponents()) {
                int id = c.getComponentId();
                if (c.getAnimationId() != -1) {
                    id = c.getAnimationId();
                    Component[] rc = statePaintedComponents.get(id);
                    for (Component ac : rc) {
                        if (ac != null) {
                            ac.layout(context, measure);
                        }
                    }
                }
                if (measure.contains(id)) {
                    c.layout(context, measure);
                }
            }
        }

        mFirstLayout = false;
    }

    @Override
    public void measure(
            @NonNull PaintContext context,
            float minWidth,
            float maxWidth,
            float minHeight,
            float maxHeight,
            @NonNull MeasurePass measure) {
        // The general approach for this widget is to do most of the work/setup in measure.
        // layout and paint then simply use what's been setup in the measure phase.

        // First, let's initialize the statePaintedComponents array;
        // it contains for each animation id a set of components associated, one for each state.
        // For now to keep things simple, all components sets have the same size (== number of
        // states)
        if (statePaintedComponents.isEmpty()) {
            findAnimatedComponents();
        }

        // TODO : FIRST LAYOUT ANIMATE THE GONE ELEMENT, RESIZING IT. We should be able to fix it
        // if we resize things before animting.

        LayoutManager layout = getLayout(currentLayoutIndex);

        // ok so *before* we do the layout, we should make sure to set the *new* widgets (that
        // share the same id) to be at the same bounds / position as the current displayed ones
        if (inTransition && currentLayoutIndex != previousLayoutIndex) {
            LayoutManager previousLayout = getLayout(previousLayoutIndex);
            for (Component c : layout.getChildrenComponents()) {
                int id = c.getAnimationId();
                if (id == -1) {
                    continue;
                }
                for (Component pc : previousLayout.getChildrenComponents()) {
                    if (pc.getAnimationId() == id) {
                        Component prev =
                                statePaintedComponents.get(c.getAnimationId())[previousLayoutIndex];
                        if (c != prev) {
                            c.measure(
                                    context,
                                    prev.getWidth(),
                                    prev.getWidth(),
                                    prev.getHeight(),
                                    prev.getHeight(),
                                    measure);
                            c.layout(context.getContext(), measure);
                            c.setX(prev.getX());
                            c.setY(prev.getY());
                            c.mVisibility = Visibility.GONE;
                        }
                        break;
                    }
                }
            }
        }

        // Alright, now that things are set in place, let's go ahead and measure the new world...
        layout.measure(context, minWidth, maxWidth, minHeight, maxHeight, measure);

        // recopy to animationIds the values
        for (Component c : layout.getChildrenComponents()) {
            ComponentMeasure cm = measure.get(c);
            if (c.getAnimationId() != -1) {
                // First, we grab the current component for an animation id, and get its measure,
                // then set this measure to the measure for the animation id
                ComponentMeasure m = measure.get(c.getAnimationId());
                m.copyFrom(cm);

                m.setVisibility(Visibility.VISIBLE);

                // Then for each components sharing the id in all the states...
                Component[] components = statePaintedComponents.get(c.getAnimationId());
                for (int idx = 0; idx < components.length; idx++) {
                    Component ac = components[idx];
                    if (ac != null) {
                        ComponentMeasure m2 = measure.get(ac.getComponentId());

                        // ... we set their measures to be the measure of the current component
                        if (c != ac) {
                            m2.copyFrom(cm);
                        }

                        // Finally let's make sure that for all components we set their visibility
                        if (idx == currentLayoutIndex) {
                            m2.setVisibility(Visibility.VISIBLE);
                        } else {
                            if (c != ac) {
                                m2.setVisibility(Visibility.GONE);
                            }
                        }

                        // if the component isn't the current one, we should measure it
                        if (c != ac) {
                            ac.measure(context, m.getW(), m.getW(), m.getH(), m.getH(), measure);
                        }
                    }
                }
            } else {
                // TODO: Ideally unify the visibility handing so that we also work in terms of
                // component and not panel visibility. Ideally do not change the .visibility
                // attribute at all and actually use the "current index" to decide whether to
                // draw or not.
                cm.setVisibility(Visibility.VISIBLE);
            }
        }

        // Make sure to mark the components that are not in the new layout as being GONE
        if (previousLayoutIndex != currentLayoutIndex) {
            LayoutManager previousLayout = getLayout(previousLayoutIndex);
            for (Component c : previousLayout.getChildrenComponents()) {
                int id = c.getComponentId();
                if (c.getAnimationId() != -1) {
                    id = c.getAnimationId();
                }
                if (!measure.contains(id)) {
                    ComponentMeasure m = measure.get(c.getComponentId());
                    m.setX(c.getX());
                    m.setY(c.getY());
                    m.setW(c.getWidth());
                    m.setH(c.getHeight());
                    m.setVisibility(Visibility.GONE);
                }
            }
        }

        ComponentMeasure m = measure.get(layout);
        ComponentMeasure own = measure.get(this);
        own.copyFrom(m);
        measuredLayoutIndex = currentLayoutIndex;
    }

    public void hideLayoutsOtherThan(int idx) {
        int index = 0;
        for (Component pane : mChildrenComponents) {
            if (pane instanceof LayoutComponent) {
                if (index != idx) {
                    pane.mVisibility = Visibility.GONE;
                } else {
                    pane.mVisibility = Visibility.VISIBLE;
                }
                index++;
            }
        }
    }

    public @NonNull LayoutManager getLayout(int idx) {
        int index = 0;
        for (Component pane : mChildrenComponents) {
            if (pane instanceof LayoutComponent) {
                if (index == idx) {
                    return (LayoutManager) pane;
                }
                index++;
            }
        }
        return (LayoutManager) mChildrenComponents.get(0);
    }

    @Override
    public void paint(@NonNull PaintContext context) {
        if (mIndexId != 0) {
            int newValue = context.getContext().mRemoteComposeState.getInteger(mIndexId);
            if (newValue != currentLayoutIndex) {
                previousLayoutIndex = currentLayoutIndex;
                currentLayoutIndex = newValue;
                inTransition = true;
                // System.out.println("currentLayout index is $currentLayoutIndex");
                // executeValueSetActions(getLayout(currentLayoutIndex));
                invalidateMeasure();
            }
        }
        //        System.out.println("PAINTING LAYOUT STATELAYOUT, CURRENT INDEX " +
        // currentLayoutIndex);
        // Make sure to mark any components that are not in either the current or previous layout
        // as being GONE.
        int index = 0;
        for (Component pane : mChildrenComponents) {
            if (pane instanceof LayoutComponent) {
                if (index != currentLayoutIndex && index != previousLayoutIndex) {
                    pane.mVisibility = Visibility.GONE;
                }
                if (index == currentLayoutIndex && pane.mVisibility != Visibility.VISIBLE) {
                    pane.mVisibility = Visibility.VISIBLE;
                }
                index++;
            }
        }

        LayoutManager currentLayout = getLayout(measuredLayoutIndex);
        boolean needsToPaintTransition = inTransition && previousLayoutIndex != measuredLayoutIndex;
        if (needsToPaintTransition) {
            // in case we have switched to a new state, during the transition
            // we might still need to display the previous components that are not part of
            // the new state (to enable them to run their exit animation)

            LayoutManager previousLayout = getLayout(previousLayoutIndex);
            int numPreviousComponents = previousLayout.getChildrenComponents().size();
            if (numPreviousComponents > MAX_CACHE_ELEMENTS) {
                MAX_CACHE_ELEMENTS *= 2;
                cacheListElementsId = new int[MAX_CACHE_ELEMENTS];
            }
            // Make sure to apply the animation if there...
            previousLayout.applyAnimationAsNeeded(context);

            // Let's grab all the ids for the components of the previous layout...
            int idIndex = 0;
            for (Component c : previousLayout.getChildrenComponents()) {
                cacheListElementsId[idIndex] = c.getPaintId();
                idIndex++;
            }
            // ...then remove them if they are in the new layout
            int count = idIndex;
            for (Component c : currentLayout.getChildrenComponents()) {
                int id = c.getPaintId();
                for (int i = 0; i < idIndex; i++) {
                    if (cacheListElementsId[i] == id) {
                        cacheListElementsId[i] = -1;
                        count--;
                    }
                }
            }
            // If we have components not present in the new state, paint them
            if (count > 0) {
                context.save();
                context.translate(previousLayout.getX(), previousLayout.getY());
                for (Component c : previousLayout.getChildrenComponents()) {
                    int id = c.getPaintId();
                    for (int i = 0; i < idIndex; i++) {
                        if (cacheListElementsId[i] == id) {
                            c.paint(context);
                            break;
                        }
                    }
                }
                context.restore();
            }

            // Make sure to apply the animation if there...
            currentLayout.applyAnimationAsNeeded(context);
        }

        // We paint all the components and operations of the current layout
        context.save();
        context.translate(currentLayout.getX(), currentLayout.getY());
        for (Operation op : currentLayout.getList()) {
            if (op instanceof Component && ((Component) op).getAnimationId() != -1) {
                Component[] stateComponents =
                        statePaintedComponents.get(((Component) op).getAnimationId());
                Component component = stateComponents[measuredLayoutIndex];
                if (needsToPaintTransition) {
                    // We might have two components to paint, as in case two different
                    // components share the same id, we'll fade the previous components out
                    // and fade in the new one
                    Component previousComponent = stateComponents[previousLayoutIndex];
                    if (previousComponent != null && component != previousComponent) {
                        previousComponent.paint(context);
                    }
                }
                component.paint(context);
            } else if (op instanceof PaintOperation) {
                ((PaintOperation) op).paint(context);
            }
        }
        context.restore();

        if (needsToPaintTransition) {
            checkEndOfTransition();
        }
    }

    public void checkEndOfTransition() {
        LayoutManager currentLayout = getLayout(measuredLayoutIndex);
        LayoutManager previousLayout = getLayout(previousLayoutIndex);
        if (inTransition
                && currentLayout.mAnimateMeasure == null
                && previousLayout.mAnimateMeasure == null) {
            inTransition = false;
            LayoutManager previous = getLayout(previousLayoutIndex);
            if (previous != currentLayout && previous.mVisibility != Visibility.GONE) {
                previous.mVisibility = Visibility.GONE;
                previous.needsRepaint();
            }
        }
    }

    //    override fun onValueChanged(origamiValue: OrigamiValue<out Int>, oldValue: Int?, newValue:
    // Int) {
    //        if (newValue != currentLayoutIndex) {
    //            previousLayoutIndex = currentLayoutIndex
    //            currentLayoutIndex = newValue
    //            inTransition = true
    //            println("currentLayout index is $currentLayoutIndex")
    //            executeValueSetActions(getLayout(currentLayoutIndex))
    //            invalidateMeasure()
    //        }
    //    }

    //    fun executeValueSetActions(layout: LayoutManager) {
    //        // FIXME : quick hack to support ValueSetClickActions, need to make that a little more
    //        // robust!
    //        for (op in layout.list) {
    //            if (op is LayoutComponent) {
    //                for (op2 in op.list) {
    //                    if (op2 is OperationsList) {
    //                        for (op3 in op2.list) {
    //                            if (op3 is ValueSetClickAction<*, *>) {
    //                                op3.onClick()
    //                            }
    //                        }
    //                    }
    //                }
    //            }
    //        }
    //    }

    @NonNull
    @Override
    public String toString() {
        return "STATE_LAYOUT";
    }

    //    companion object {
    //        fun documentation(doc: OrigamiDocumentation) {}
    //    }

    public static void apply(
            @NonNull WireBuffer buffer,
            int componentId,
            int animationId,
            int horizontalPositioning,
            int verticalPositioning,
            int indexId) {
        buffer.start(Operations.LAYOUT_STATE);
        buffer.writeInt(componentId);
        buffer.writeInt(animationId);
        buffer.writeInt(horizontalPositioning);
        buffer.writeInt(verticalPositioning);
        buffer.writeInt(indexId);
    }

    /**
     * Read this operation and add it to the list of operations
     *
     * @param buffer the buffer to read
     * @param operations the list of operations that will be added to
     */
    public static void read(@NonNull WireBuffer buffer, @NonNull List<Operation> operations) {
        int componentId = buffer.readInt();
        int animationId = buffer.readInt();
        buffer.readInt(); // horizontalPositioning
        buffer.readInt(); // verticalPositioning
        int indexId = buffer.readInt();
        operations.add(
                new StateLayout(null, componentId, animationId, 0f, 0f, 100f, 100f, indexId));
    }
}
