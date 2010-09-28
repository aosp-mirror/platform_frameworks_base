/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.text.method;

import android.text.Layout;
import android.text.Selection;
import android.text.Spannable;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

// XXX this doesn't extend MetaKeyKeyListener because the signatures
// don't match.  Need to figure that out.  Meanwhile the meta keys
// won't work in fields that don't take input.

public class ArrowKeyMovementMethod implements MovementMethod {
    private boolean isCap(Spannable buffer) {
        return ((MetaKeyKeyListener.getMetaState(buffer, KeyEvent.META_SHIFT_ON) == 1) ||
                (MetaKeyKeyListener.getMetaState(buffer, MetaKeyKeyListener.META_SELECTING) != 0));
    }

    private boolean isAlt(Spannable buffer) {
        return MetaKeyKeyListener.getMetaState(buffer, KeyEvent.META_ALT_ON) == 1;
    }

    private boolean up(TextView widget, Spannable buffer) {
        boolean cap = isCap(buffer);
        boolean alt = isAlt(buffer);
        Layout layout = widget.getLayout();

        if (cap) {
            if (alt) {
                Selection.extendSelection(buffer, 0);
                return true;
            } else {
                return Selection.extendUp(buffer, layout);
            }
        } else {
            if (alt) {
                Selection.setSelection(buffer, 0);
                return true;
            } else {
                return Selection.moveUp(buffer, layout);
            }
        }
    }

    private boolean down(TextView widget, Spannable buffer) {
        boolean cap = isCap(buffer);
        boolean alt = isAlt(buffer);
        Layout layout = widget.getLayout();

        if (cap) {
            if (alt) {
                Selection.extendSelection(buffer, buffer.length());
                return true;
            } else {
                return Selection.extendDown(buffer, layout);
            }
        } else {
            if (alt) {
                Selection.setSelection(buffer, buffer.length());
                return true;
            } else {
                return Selection.moveDown(buffer, layout);
            }
        }
    }

    private boolean left(TextView widget, Spannable buffer) {
        boolean cap = isCap(buffer);
        boolean alt = isAlt(buffer);
        Layout layout = widget.getLayout();

        if (cap) {
            if (alt) {
                return Selection.extendToLeftEdge(buffer, layout);
            } else {
                return Selection.extendLeft(buffer, layout);
            }
        } else {
            if (alt) {
                return Selection.moveToLeftEdge(buffer, layout);
            } else {
                return Selection.moveLeft(buffer, layout); 
            }
        }
    }

    private boolean right(TextView widget, Spannable buffer) {
        boolean cap = isCap(buffer);
        boolean alt = isAlt(buffer);
        Layout layout = widget.getLayout();

        if (cap) {
            if (alt) {
                return Selection.extendToRightEdge(buffer, layout);
            } else {
                return Selection.extendRight(buffer, layout);
            }
        } else {
            if (alt) {
                return Selection.moveToRightEdge(buffer, layout);
            } else {
                return Selection.moveRight(buffer, layout); 
            }
        }
    }

    public boolean onKeyDown(TextView widget, Spannable buffer, int keyCode, KeyEvent event) {
        if (executeDown(widget, buffer, keyCode)) {
            MetaKeyKeyListener.adjustMetaAfterKeypress(buffer);
            MetaKeyKeyListener.resetLockedMeta(buffer);
            return true;
        }

        return false;
    }

    private boolean executeDown(TextView widget, Spannable buffer, int keyCode) {
        boolean handled = false;

        switch (keyCode) {
        case KeyEvent.KEYCODE_DPAD_UP:
            handled |= up(widget, buffer);
            break;

        case KeyEvent.KEYCODE_DPAD_DOWN:
            handled |= down(widget, buffer);
            break;

        case KeyEvent.KEYCODE_DPAD_LEFT:
            handled |= left(widget, buffer);
            break;

        case KeyEvent.KEYCODE_DPAD_RIGHT:
            handled |= right(widget, buffer);
            break;

        case KeyEvent.KEYCODE_DPAD_CENTER:
            if ((MetaKeyKeyListener.getMetaState(buffer, MetaKeyKeyListener.META_SELECTING) != 0) &&
                (widget.showContextMenu())) {
                    handled = true;
            }
        }

        if (handled) {
            MetaKeyKeyListener.adjustMetaAfterKeypress(buffer);
            MetaKeyKeyListener.resetLockedMeta(buffer);
        }

        return handled;
    }

    public boolean onKeyUp(TextView widget, Spannable buffer, int keyCode, KeyEvent event) {
        return false;
    }

    public boolean onKeyOther(TextView view, Spannable text, KeyEvent event) {
        int code = event.getKeyCode();
        if (code != KeyEvent.KEYCODE_UNKNOWN && event.getAction() == KeyEvent.ACTION_MULTIPLE) {
            int repeat = event.getRepeatCount();
            boolean handled = false;
            while ((--repeat) > 0) {
                handled |= executeDown(view, text, code);
            }
            return handled;
        }
        return false;
    }

    public boolean onTrackballEvent(TextView widget, Spannable text, MotionEvent event) {
        return false;
    }

    public boolean onTouchEvent(TextView widget, Spannable buffer, MotionEvent event) {
        int initialScrollX = -1, initialScrollY = -1;
        if (event.getAction() == MotionEvent.ACTION_UP) {
            initialScrollX = Touch.getInitialScrollX(widget, buffer);
            initialScrollY = Touch.getInitialScrollY(widget, buffer);
        }

        boolean handled = Touch.onTouchEvent(widget, buffer, event);

        if (widget.isFocused() && !widget.didTouchFocusSelect()) {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
              boolean cap = isCap(buffer);
              if (cap) {
                  int offset = widget.getOffset((int) event.getX(), (int) event.getY());
                  
                  buffer.setSpan(LAST_TAP_DOWN, offset, offset, Spannable.SPAN_POINT_POINT);

                  // Disallow intercepting of the touch events, so that
                  // users can scroll and select at the same time.
                  // without this, users would get booted out of select
                  // mode once the view detected it needed to scroll.
                  widget.getParent().requestDisallowInterceptTouchEvent(true);
              }
            } else if (event.getAction() == MotionEvent.ACTION_MOVE) {
                boolean cap = isCap(buffer);

                if (cap && handled) {
                    // Before selecting, make sure we've moved out of the "slop".
                    // handled will be true, if we're in select mode AND we're
                    // OUT of the slop

                    // Turn long press off while we're selecting. User needs to
                    // re-tap on the selection to enable long press
                    widget.cancelLongPress();

                    // Update selection as we're moving the selection area.

                    // Get the current touch position
                    int offset = widget.getOffset((int) event.getX(), (int) event.getY());

                    Selection.extendSelection(buffer, offset);
                    return true;
                }
            } else if (event.getAction() == MotionEvent.ACTION_UP) {
                // If we have scrolled, then the up shouldn't move the cursor,
                // but we do need to make sure the cursor is still visible at
                // the current scroll offset to avoid the scroll jumping later
                // to show it.
                if ((initialScrollY >= 0 && initialScrollY != widget.getScrollY()) ||
                    (initialScrollX >= 0 && initialScrollX != widget.getScrollX())) {
                    widget.moveCursorToVisibleOffset();
                    return true;
                }

                int offset = widget.getOffset((int) event.getX(), (int) event.getY());
                if (isCap(buffer)) {
                    buffer.removeSpan(LAST_TAP_DOWN);
                    Selection.extendSelection(buffer, offset);
                } else {
                    Selection.setSelection(buffer, offset);
                }

                MetaKeyKeyListener.adjustMetaAfterKeypress(buffer);
                MetaKeyKeyListener.resetLockedMeta(buffer);

                return true;
            }
        }

        return handled;
    }

    public boolean canSelectArbitrarily() {
        return true;
    }

    public void initialize(TextView widget, Spannable text) {
        Selection.setSelection(text, 0);
    }

    public void onTakeFocus(TextView view, Spannable text, int dir) {
        if ((dir & (View.FOCUS_FORWARD | View.FOCUS_DOWN)) != 0) {
            if (view.getLayout() == null) {
                // This shouldn't be null, but do something sensible if it is.
                Selection.setSelection(text, text.length());
            }
        } else {
            Selection.setSelection(text, text.length());
        }
    }

    public static MovementMethod getInstance() {
        if (sInstance == null) {
            sInstance = new ArrowKeyMovementMethod();
        }

        return sInstance;
    }


    private static final Object LAST_TAP_DOWN = new Object();
    private static ArrowKeyMovementMethod sInstance;
}
