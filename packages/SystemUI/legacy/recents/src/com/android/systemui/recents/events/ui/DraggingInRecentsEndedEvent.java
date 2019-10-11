package com.android.systemui.recents.events.ui;

import com.android.systemui.recents.events.EventBus.Event;

/**
 * This event is sent when the user finished dragging in recents.
 */
public class DraggingInRecentsEndedEvent extends Event {

    public final float velocity;

    public DraggingInRecentsEndedEvent(float velocity) {
        this.velocity = velocity;
    }
}
