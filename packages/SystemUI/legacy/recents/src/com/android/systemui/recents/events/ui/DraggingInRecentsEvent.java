package com.android.systemui.recents.events.ui;

import com.android.systemui.recents.events.EventBus.Event;

/**
 * This event is sent when the user changed how far they are dragging in recents.
 */
public class DraggingInRecentsEvent extends Event {

    public final float distanceFromTop;

    public DraggingInRecentsEvent(float distanceFromTop) {
        this.distanceFromTop = distanceFromTop;
    }
}
