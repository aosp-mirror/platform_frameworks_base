/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.documentsui;

import android.graphics.Point;
import android.support.v7.widget.RecyclerView;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

/**
 * Utility code for dealing with MotionEvents.
 */
public final class Events {

    /**
     * Returns true if event was triggered by a mouse.
     */
    public static boolean isMouseEvent(MotionEvent e) {
        return isMouseType(e.getToolType(0));
    }

    /**
     * Returns true if event was triggered by a finger or stylus touch.
     */
    public static boolean isTouchEvent(MotionEvent e) {
        return isTouchType(e.getToolType(0));
    }

    /**
     * Returns true if event was triggered by a mouse.
     */
    public static boolean isMouseType(int toolType) {
        return toolType == MotionEvent.TOOL_TYPE_MOUSE;
    }

    /**
     * Returns true if event was triggered by a finger or stylus touch.
     */
    public static boolean isTouchType(int toolType) {
        return toolType == MotionEvent.TOOL_TYPE_FINGER
                || toolType == MotionEvent.TOOL_TYPE_STYLUS
                || toolType == MotionEvent.TOOL_TYPE_UNKNOWN;
    }

    /**
     * Returns true if event was triggered by a finger or stylus touch.
     */
    public static boolean isActionDown(MotionEvent e) {
        return e.getActionMasked() == MotionEvent.ACTION_DOWN;
    }

    /**
     * Returns true if event was triggered by a finger or stylus touch.
     */
    public static boolean isActionUp(MotionEvent e) {
        return e.getActionMasked() == MotionEvent.ACTION_UP;
    }

    /**
     * Returns true if the shift is pressed.
     */
    public boolean isShiftPressed(MotionEvent e) {
        return hasShiftBit(e.getMetaState());
    }

    /**
     * Whether or not the given keyCode represents a navigation keystroke (e.g. up, down, home).
     *
     * @param keyCode
     * @return
     */
    public static boolean isNavigationKeyCode(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MOVE_HOME:
            case KeyEvent.KEYCODE_MOVE_END:
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_PAGE_DOWN:
                return true;
            default:
                return false;
        }
    }


    /**
     * Returns true if the "SHIFT" bit is set.
     */
    public static boolean hasShiftBit(int metaState) {
        return (metaState & KeyEvent.META_SHIFT_ON) != 0;
    }

    /**
     * A facade over MotionEvent primarily designed to permit for unit testing
     * of related code.
     */
    public interface InputEvent {
        boolean isMouseEvent();
        boolean isPrimaryButtonPressed();
        boolean isSecondaryButtonPressed();
        boolean isShiftKeyDown();

        /** Returns true if the action is the initial press of a mouse or touch. */
        boolean isActionDown();

        /** Returns true if the action is the final release of a mouse or touch. */
        boolean isActionUp();

        Point getOrigin();

        /** Returns true if the there is an item under the finger/cursor. */
        boolean isOverItem();

        /** Returns the adapter position of the item under the finger/cursor. */
        int getItemPosition();
    }

    public static final class MotionInputEvent implements InputEvent {
        private final MotionEvent mEvent;
        private final int mPosition;

        public MotionInputEvent(MotionEvent event, RecyclerView view) {
            mEvent = event;

            // Consider determining position lazily as an optimization.
            View child = view.findChildViewUnder(mEvent.getX(), mEvent.getY());
            mPosition = (child!= null)
                    ? view.getChildAdapterPosition(child)
                    : RecyclerView.NO_POSITION;
        }

        @Override
        public boolean isMouseEvent() {
            return Events.isMouseEvent(mEvent);
        }

        @Override
        public boolean isPrimaryButtonPressed() {
            return mEvent.isButtonPressed(MotionEvent.BUTTON_PRIMARY);
        }

        @Override
        public boolean isSecondaryButtonPressed() {
            return mEvent.isButtonPressed(MotionEvent.BUTTON_SECONDARY);
        }

        @Override
        public boolean isShiftKeyDown() {
            return Events.hasShiftBit(mEvent.getMetaState());
        }

        @Override
        public boolean isActionDown() {
            return mEvent.getActionMasked() == MotionEvent.ACTION_DOWN;
        }

        @Override
        public boolean isActionUp() {
            return mEvent.getActionMasked() == MotionEvent.ACTION_UP;
        }

        @Override
        public Point getOrigin() {
            return new Point((int) mEvent.getX(), (int) mEvent.getY());
        }

        @Override
        public boolean isOverItem() {
            return getItemPosition() != RecyclerView.NO_POSITION;
        }

        @Override
        public int getItemPosition() {
            return mPosition;
        }

        @Override
        public String toString() {
            return new StringBuilder()
                    .append("MotionInputEvent {")
                    .append("isMouseEvent=").append(isMouseEvent())
                    .append(" isPrimaryButtonPressed=").append(isPrimaryButtonPressed())
                    .append(" isSecondaryButtonPressed=").append(isSecondaryButtonPressed())
                    .append(" isShiftKeyDown=").append(isShiftKeyDown())
                    .append(" isActionDown=").append(isActionDown())
                    .append(" isActionUp=").append(isActionUp())
                    .append(" getOrigin=").append(getOrigin())
                    .append(" isOverItem=").append(isOverItem())
                    .append(" getItemPosition=").append(getItemPosition())
                    .append("}")
                    .toString();
        }
    }
}
