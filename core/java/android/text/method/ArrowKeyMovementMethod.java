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

import android.util.Log;
import android.view.KeyEvent;
import android.text.*;
import android.widget.TextView;
import android.view.View;
import android.view.MotionEvent;

// XXX this doesn't extend MetaKeyKeyListener because the signatures
// don't match.  Need to figure that out.  Meanwhile the meta keys
// won't work in fields that don't take input.

public class
ArrowKeyMovementMethod
implements MovementMethod
{
    private boolean up(TextView widget, Spannable buffer) {
        boolean cap = (MetaKeyKeyListener.getMetaState(buffer,
                        KeyEvent.META_SHIFT_ON) == 1) ||
                      (MetaKeyKeyListener.getMetaState(buffer,
                        MetaKeyKeyListener.META_SELECTING) != 0);
        boolean alt = MetaKeyKeyListener.getMetaState(buffer,
                        KeyEvent.META_ALT_ON) == 1;
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
        boolean cap = (MetaKeyKeyListener.getMetaState(buffer,
                        KeyEvent.META_SHIFT_ON) == 1) ||
                      (MetaKeyKeyListener.getMetaState(buffer,
                        MetaKeyKeyListener.META_SELECTING) != 0);
        boolean alt = MetaKeyKeyListener.getMetaState(buffer,
                        KeyEvent.META_ALT_ON) == 1;
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
        boolean cap = (MetaKeyKeyListener.getMetaState(buffer,
                        KeyEvent.META_SHIFT_ON) == 1) ||
                      (MetaKeyKeyListener.getMetaState(buffer,
                        MetaKeyKeyListener.META_SELECTING) != 0);
        boolean alt = MetaKeyKeyListener.getMetaState(buffer,
                        KeyEvent.META_ALT_ON) == 1;
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
        boolean cap = (MetaKeyKeyListener.getMetaState(buffer,
                        KeyEvent.META_SHIFT_ON) == 1) ||
                      (MetaKeyKeyListener.getMetaState(buffer,
                        MetaKeyKeyListener.META_SELECTING) != 0);
        boolean alt = MetaKeyKeyListener.getMetaState(buffer,
                        KeyEvent.META_ALT_ON) == 1;
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
            if (MetaKeyKeyListener.getMetaState(buffer, MetaKeyKeyListener.META_SELECTING) != 0) {
                if (widget.showContextMenu()) {
                    handled = true;
                }
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
        if (code != KeyEvent.KEYCODE_UNKNOWN
                && event.getAction() == KeyEvent.ACTION_MULTIPLE) {
            int repeat = event.getRepeatCount();
            boolean handled = false;
            while ((--repeat) > 0) {
                handled |= executeDown(view, text, code);
            }
            return handled;
        }
        return false;
    }
    
    public boolean onTrackballEvent(TextView widget, Spannable text,
            MotionEvent event) {
        return false;
    }
    
    public boolean onTouchEvent(TextView widget, Spannable buffer,
                                MotionEvent event) {
        boolean handled = Touch.onTouchEvent(widget, buffer, event);

        if (widget.isFocused() && !widget.didTouchFocusSelect()) {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                int x = (int) event.getX();
                int y = (int) event.getY();

                x -= widget.getTotalPaddingLeft();
                y -= widget.getTotalPaddingTop();

                x += widget.getScrollX();
                y += widget.getScrollY();

                Layout layout = widget.getLayout();
                int line = layout.getLineForVertical(y);
                int off = layout.getOffsetForHorizontal(line, x);

                boolean cap = (MetaKeyKeyListener.getMetaState(buffer,
                                KeyEvent.META_SHIFT_ON) == 1) ||
                              (MetaKeyKeyListener.getMetaState(buffer,
                                MetaKeyKeyListener.META_SELECTING) != 0);

                if (cap) {
                    Selection.extendSelection(buffer, off);
                } else {
                    Selection.setSelection(buffer, off);
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
            Layout layout = view.getLayout();

            if (layout == null) {
                /*
                 * This shouldn't be null, but do something sensible if it is.
                 */
                Selection.setSelection(text, text.length());
            } else {
                /*
                 * Put the cursor at the end of the first line, which is
                 * either the last offset if there is only one line, or the
                 * offset before the first character of the second line
                 * if there is more than one line.
                 */
                if (layout.getLineCount() == 1) {
                    Selection.setSelection(text, text.length());
                } else {
                    Selection.setSelection(text, layout.getLineStart(1) - 1);
                }
            }
        } else {
            Selection.setSelection(text, text.length());
        }
    }

    public static MovementMethod getInstance() {
        if (sInstance == null)
            sInstance = new ArrowKeyMovementMethod();

        return sInstance;
    }

    private static ArrowKeyMovementMethod sInstance;
}
