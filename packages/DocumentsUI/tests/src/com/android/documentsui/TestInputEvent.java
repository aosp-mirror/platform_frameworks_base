package com.android.documentsui;

import android.graphics.Point;
import android.support.v7.widget.RecyclerView;

public class TestInputEvent implements Events.InputEvent {

    public boolean mouseEvent;
    public boolean primaryButtonPressed;
    public boolean secondaryButtonPressed;
    public boolean shiftKeyDow;
    public boolean actionDown;
    public boolean actionUp;
    public Point location;
    public int position = Integer.MIN_VALUE;

    public TestInputEvent() {}

    public TestInputEvent(int position) {
        this.position = position;
    }

    @Override
    public boolean isMouseEvent() {
        return mouseEvent;
    }

    @Override
    public boolean isPrimaryButtonPressed() {
        return primaryButtonPressed;
    }

    @Override
    public boolean isSecondaryButtonPressed() {
        return secondaryButtonPressed;
    }

    @Override
    public boolean isShiftKeyDown() {
        return shiftKeyDow;
    }

    @Override
    public boolean isActionDown() {
        return actionDown;
    }

    @Override
    public boolean isActionUp() {
        return actionUp;
    }

    @Override
    public Point getOrigin() {
        return location;
    }

    @Override
    public boolean isOverItem() {
        return position != Integer.MIN_VALUE && position != RecyclerView.NO_POSITION;
    }

    @Override
    public int getItemPosition() {
        return position;
    }

    public static TestInputEvent tap(int position) {
        return new TestInputEvent(position);
    }

    public static TestInputEvent shiftTap(int position) {
        TestInputEvent e = new TestInputEvent(position);
        e.shiftKeyDow = true;
        return e;
    }

    public static TestInputEvent click(int position) {
        TestInputEvent e = new TestInputEvent(position);
        e.mouseEvent = true;
        return e;
    }

    public static TestInputEvent shiftClick(int position) {
        TestInputEvent e = new TestInputEvent(position);
        e.mouseEvent = true;
        e.shiftKeyDow = true;
        return e;
    }
}
