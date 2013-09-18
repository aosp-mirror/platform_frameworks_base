/*
 * Copyright (C) 2010 The Android Open Source Project
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
import android.text.Spannable;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.widget.TextView;

/**
 * Base classes for movement methods.
 */
public class BaseMovementMethod implements MovementMethod {
    @Override
    public boolean canSelectArbitrarily() {
        return false;
    }

    @Override
    public void initialize(TextView widget, Spannable text) {
    }

    @Override
    public boolean onKeyDown(TextView widget, Spannable text, int keyCode, KeyEvent event) {
        final int movementMetaState = getMovementMetaState(text, event);
        boolean handled = handleMovementKey(widget, text, keyCode, movementMetaState, event);
        if (handled) {
            MetaKeyKeyListener.adjustMetaAfterKeypress(text);
            MetaKeyKeyListener.resetLockedMeta(text);
        }
        return handled;
    }

    @Override
    public boolean onKeyOther(TextView widget, Spannable text, KeyEvent event) {
        final int movementMetaState = getMovementMetaState(text, event);
        final int keyCode = event.getKeyCode();
        if (keyCode != KeyEvent.KEYCODE_UNKNOWN
                && event.getAction() == KeyEvent.ACTION_MULTIPLE) {
            final int repeat = event.getRepeatCount();
            boolean handled = false;
            for (int i = 0; i < repeat; i++) {
                if (!handleMovementKey(widget, text, keyCode, movementMetaState, event)) {
                    break;
                }
                handled = true;
            }
            if (handled) {
                MetaKeyKeyListener.adjustMetaAfterKeypress(text);
                MetaKeyKeyListener.resetLockedMeta(text);
            }
            return handled;
        }
        return false;
    }

    @Override
    public boolean onKeyUp(TextView widget, Spannable text, int keyCode, KeyEvent event) {
        return false;
    }

    @Override
    public void onTakeFocus(TextView widget, Spannable text, int direction) {
    }

    @Override
    public boolean onTouchEvent(TextView widget, Spannable text, MotionEvent event) {
        return false;
    }

    @Override
    public boolean onTrackballEvent(TextView widget, Spannable text, MotionEvent event) {
        return false;
    }

    @Override
    public boolean onGenericMotionEvent(TextView widget, Spannable text, MotionEvent event) {
        if ((event.getSource() & InputDevice.SOURCE_CLASS_POINTER) != 0) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_SCROLL: {
                    final float vscroll;
                    final float hscroll;
                    if ((event.getMetaState() & KeyEvent.META_SHIFT_ON) != 0) {
                        vscroll = 0;
                        hscroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
                    } else {
                        vscroll = -event.getAxisValue(MotionEvent.AXIS_VSCROLL);
                        hscroll = event.getAxisValue(MotionEvent.AXIS_HSCROLL);
                    }

                    boolean handled = false;
                    if (hscroll < 0) {
                        handled |= scrollLeft(widget, text, (int)Math.ceil(-hscroll));
                    } else if (hscroll > 0) {
                        handled |= scrollRight(widget, text, (int)Math.ceil(hscroll));
                    }
                    if (vscroll < 0) {
                        handled |= scrollUp(widget, text, (int)Math.ceil(-vscroll));
                    } else if (vscroll > 0) {
                        handled |= scrollDown(widget, text, (int)Math.ceil(vscroll));
                    }
                    return handled;
                }
            }
        }
        return false;
    }

    /**
     * Gets the meta state used for movement using the modifiers tracked by the text
     * buffer as well as those present in the key event.
     *
     * The movement meta state excludes the state of locked modifiers or the SHIFT key
     * since they are not used by movement actions (but they may be used for selection).
     *
     * @param buffer The text buffer.
     * @param event The key event.
     * @return The keyboard meta states used for movement.
     */
    protected int getMovementMetaState(Spannable buffer, KeyEvent event) {
        // We ignore locked modifiers and SHIFT.
        int metaState = MetaKeyKeyListener.getMetaState(buffer, event)
                & ~(MetaKeyKeyListener.META_ALT_LOCKED | MetaKeyKeyListener.META_SYM_LOCKED);
        return KeyEvent.normalizeMetaState(metaState) & ~KeyEvent.META_SHIFT_MASK;
    }

    /**
     * Performs a movement key action.
     * The default implementation decodes the key down and invokes movement actions
     * such as {@link #down} and {@link #up}.
     * {@link #onKeyDown(TextView, Spannable, int, KeyEvent)} calls this method once
     * to handle an {@link KeyEvent#ACTION_DOWN}.
     * {@link #onKeyOther(TextView, Spannable, KeyEvent)} calls this method repeatedly
     * to handle each repetition of an {@link KeyEvent#ACTION_MULTIPLE}.
     *
     * @param widget The text view.
     * @param buffer The text buffer.
     * @param event The key event.
     * @param keyCode The key code.
     * @param movementMetaState The keyboard meta states used for movement.
     * @param event The key event.
     * @return True if the event was handled.
     */
    protected boolean handleMovementKey(TextView widget, Spannable buffer,
            int keyCode, int movementMetaState, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (KeyEvent.metaStateHasNoModifiers(movementMetaState)) {
                    return left(widget, buffer);
                } else if (KeyEvent.metaStateHasModifiers(movementMetaState,
                        KeyEvent.META_CTRL_ON)) {
                    return leftWord(widget, buffer);
                } else if (KeyEvent.metaStateHasModifiers(movementMetaState,
                        KeyEvent.META_ALT_ON)) {
                    return lineStart(widget, buffer);
                }
                break;

            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (KeyEvent.metaStateHasNoModifiers(movementMetaState)) {
                    return right(widget, buffer);
                } else if (KeyEvent.metaStateHasModifiers(movementMetaState,
                        KeyEvent.META_CTRL_ON)) {
                    return rightWord(widget, buffer);
                } else if (KeyEvent.metaStateHasModifiers(movementMetaState,
                        KeyEvent.META_ALT_ON)) {
                    return lineEnd(widget, buffer);
                }
                break;

            case KeyEvent.KEYCODE_DPAD_UP:
                if (KeyEvent.metaStateHasNoModifiers(movementMetaState)) {
                    return up(widget, buffer);
                } else if (KeyEvent.metaStateHasModifiers(movementMetaState,
                        KeyEvent.META_ALT_ON)) {
                    return top(widget, buffer);
                }
                break;

            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (KeyEvent.metaStateHasNoModifiers(movementMetaState)) {
                    return down(widget, buffer);
                } else if (KeyEvent.metaStateHasModifiers(movementMetaState,
                        KeyEvent.META_ALT_ON)) {
                    return bottom(widget, buffer);
                }
                break;

            case KeyEvent.KEYCODE_PAGE_UP:
                if (KeyEvent.metaStateHasNoModifiers(movementMetaState)) {
                    return pageUp(widget, buffer);
                } else if (KeyEvent.metaStateHasModifiers(movementMetaState,
                        KeyEvent.META_ALT_ON)) {
                    return top(widget, buffer);
                }
                break;

            case KeyEvent.KEYCODE_PAGE_DOWN:
                if (KeyEvent.metaStateHasNoModifiers(movementMetaState)) {
                    return pageDown(widget, buffer);
                } else if (KeyEvent.metaStateHasModifiers(movementMetaState,
                        KeyEvent.META_ALT_ON)) {
                    return bottom(widget, buffer);
                }
                break;

            case KeyEvent.KEYCODE_MOVE_HOME:
                if (KeyEvent.metaStateHasNoModifiers(movementMetaState)) {
                    return home(widget, buffer);
                } else if (KeyEvent.metaStateHasModifiers(movementMetaState,
                        KeyEvent.META_CTRL_ON)) {
                    return top(widget, buffer);
                }
                break;

            case KeyEvent.KEYCODE_MOVE_END:
                if (KeyEvent.metaStateHasNoModifiers(movementMetaState)) {
                    return end(widget, buffer);
                } else if (KeyEvent.metaStateHasModifiers(movementMetaState,
                        KeyEvent.META_CTRL_ON)) {
                    return bottom(widget, buffer);
                }
                break;
        }
        return false;
    }

    /**
     * Performs a left movement action.
     * Moves the cursor or scrolls left by one character.
     *
     * @param widget The text view.
     * @param buffer The text buffer.
     * @return True if the event was handled.
     */
    protected boolean left(TextView widget, Spannable buffer) {
        return false;
    }

    /**
     * Performs a right movement action.
     * Moves the cursor or scrolls right by one character.
     *
     * @param widget The text view.
     * @param buffer The text buffer.
     * @return True if the event was handled.
     */
    protected boolean right(TextView widget, Spannable buffer) {
        return false;
    }

    /**
     * Performs an up movement action.
     * Moves the cursor or scrolls up by one line.
     *
     * @param widget The text view.
     * @param buffer The text buffer.
     * @return True if the event was handled.
     */
    protected boolean up(TextView widget, Spannable buffer) {
        return false;
    }

    /**
     * Performs a down movement action.
     * Moves the cursor or scrolls down by one line.
     *
     * @param widget The text view.
     * @param buffer The text buffer.
     * @return True if the event was handled.
     */
    protected boolean down(TextView widget, Spannable buffer) {
        return false;
    }

    /**
     * Performs a page-up movement action.
     * Moves the cursor or scrolls up by one page.
     *
     * @param widget The text view.
     * @param buffer The text buffer.
     * @return True if the event was handled.
     */
    protected boolean pageUp(TextView widget, Spannable buffer) {
        return false;
    }

    /**
     * Performs a page-down movement action.
     * Moves the cursor or scrolls down by one page.
     *
     * @param widget The text view.
     * @param buffer The text buffer.
     * @return True if the event was handled.
     */
    protected boolean pageDown(TextView widget, Spannable buffer) {
        return false;
    }

    /**
     * Performs a top movement action.
     * Moves the cursor or scrolls to the top of the buffer.
     *
     * @param widget The text view.
     * @param buffer The text buffer.
     * @return True if the event was handled.
     */
    protected boolean top(TextView widget, Spannable buffer) {
        return false;
    }

    /**
     * Performs a bottom movement action.
     * Moves the cursor or scrolls to the bottom of the buffer.
     *
     * @param widget The text view.
     * @param buffer The text buffer.
     * @return True if the event was handled.
     */
    protected boolean bottom(TextView widget, Spannable buffer) {
        return false;
    }

    /**
     * Performs a line-start movement action.
     * Moves the cursor or scrolls to the start of the line.
     *
     * @param widget The text view.
     * @param buffer The text buffer.
     * @return True if the event was handled.
     */
    protected boolean lineStart(TextView widget, Spannable buffer) {
        return false;
    }

    /**
     * Performs a line-end movement action.
     * Moves the cursor or scrolls to the end of the line.
     *
     * @param widget The text view.
     * @param buffer The text buffer.
     * @return True if the event was handled.
     */
    protected boolean lineEnd(TextView widget, Spannable buffer) {
        return false;
    }

    /** {@hide} */
    protected boolean leftWord(TextView widget, Spannable buffer) {
        return false;
    }

    /** {@hide} */
    protected boolean rightWord(TextView widget, Spannable buffer) {
        return false;
    }

    /**
     * Performs a home movement action.
     * Moves the cursor or scrolls to the start of the line or to the top of the
     * document depending on whether the insertion point is being moved or
     * the document is being scrolled.
     *
     * @param widget The text view.
     * @param buffer The text buffer.
     * @return True if the event was handled.
     */
    protected boolean home(TextView widget, Spannable buffer) {
        return false;
    }

    /**
     * Performs an end movement action.
     * Moves the cursor or scrolls to the start of the line or to the top of the
     * document depending on whether the insertion point is being moved or
     * the document is being scrolled.
     *
     * @param widget The text view.
     * @param buffer The text buffer.
     * @return True if the event was handled.
     */
    protected boolean end(TextView widget, Spannable buffer) {
        return false;
    }

    private int getTopLine(TextView widget) {
        return widget.getLayout().getLineForVertical(widget.getScrollY());
    }

    private int getBottomLine(TextView widget) {
        return widget.getLayout().getLineForVertical(widget.getScrollY() + getInnerHeight(widget));
    }

    private int getInnerWidth(TextView widget) {
        return widget.getWidth() - widget.getTotalPaddingLeft() - widget.getTotalPaddingRight();
    }

    private int getInnerHeight(TextView widget) {
        return widget.getHeight() - widget.getTotalPaddingTop() - widget.getTotalPaddingBottom();
    }

    private int getCharacterWidth(TextView widget) {
        return (int) Math.ceil(widget.getPaint().getFontSpacing());
    }

    private int getScrollBoundsLeft(TextView widget) {
        final Layout layout = widget.getLayout();
        final int topLine = getTopLine(widget);
        final int bottomLine = getBottomLine(widget);
        if (topLine > bottomLine) {
            return 0;
        }
        int left = Integer.MAX_VALUE;
        for (int line = topLine; line <= bottomLine; line++) {
            final int lineLeft = (int) Math.floor(layout.getLineLeft(line));
            if (lineLeft < left) {
                left = lineLeft;
            }
        }
        return left;
    }

    private int getScrollBoundsRight(TextView widget) {
        final Layout layout = widget.getLayout();
        final int topLine = getTopLine(widget);
        final int bottomLine = getBottomLine(widget);
        if (topLine > bottomLine) {
            return 0;
        }
        int right = Integer.MIN_VALUE;
        for (int line = topLine; line <= bottomLine; line++) {
            final int lineRight = (int) Math.ceil(layout.getLineRight(line));
            if (lineRight > right) {
                right = lineRight;
            }
        }
        return right;
    }

    /**
     * Performs a scroll left action.
     * Scrolls left by the specified number of characters.
     *
     * @param widget The text view.
     * @param buffer The text buffer.
     * @param amount The number of characters to scroll by.  Must be at least 1.
     * @return True if the event was handled.
     * @hide
     */
    protected boolean scrollLeft(TextView widget, Spannable buffer, int amount) {
        final int minScrollX = getScrollBoundsLeft(widget);
        int scrollX = widget.getScrollX();
        if (scrollX > minScrollX) {
            scrollX = Math.max(scrollX - getCharacterWidth(widget) * amount, minScrollX);
            widget.scrollTo(scrollX, widget.getScrollY());
            return true;
        }
        return false;
    }

    /**
     * Performs a scroll right action.
     * Scrolls right by the specified number of characters.
     *
     * @param widget The text view.
     * @param buffer The text buffer.
     * @param amount The number of characters to scroll by.  Must be at least 1.
     * @return True if the event was handled.
     * @hide
     */
    protected boolean scrollRight(TextView widget, Spannable buffer, int amount) {
        final int maxScrollX = getScrollBoundsRight(widget) - getInnerWidth(widget);
        int scrollX = widget.getScrollX();
        if (scrollX < maxScrollX) {
            scrollX = Math.min(scrollX + getCharacterWidth(widget) * amount, maxScrollX);
            widget.scrollTo(scrollX, widget.getScrollY());
            return true;
        }
        return false;
    }

    /**
     * Performs a scroll up action.
     * Scrolls up by the specified number of lines.
     *
     * @param widget The text view.
     * @param buffer The text buffer.
     * @param amount The number of lines to scroll by.  Must be at least 1.
     * @return True if the event was handled.
     * @hide
     */
    protected boolean scrollUp(TextView widget, Spannable buffer, int amount) {
        final Layout layout = widget.getLayout();
        final int top = widget.getScrollY();
        int topLine = layout.getLineForVertical(top);
        if (layout.getLineTop(topLine) == top) {
            // If the top line is partially visible, bring it all the way
            // into view; otherwise, bring the previous line into view.
            topLine -= 1;
        }
        if (topLine >= 0) {
            topLine = Math.max(topLine - amount + 1, 0);
            Touch.scrollTo(widget, layout, widget.getScrollX(), layout.getLineTop(topLine));
            return true;
        }
        return false;
    }

    /**
     * Performs a scroll down action.
     * Scrolls down by the specified number of lines.
     *
     * @param widget The text view.
     * @param buffer The text buffer.
     * @param amount The number of lines to scroll by.  Must be at least 1.
     * @return True if the event was handled.
     * @hide
     */
    protected boolean scrollDown(TextView widget, Spannable buffer, int amount) {
        final Layout layout = widget.getLayout();
        final int innerHeight = getInnerHeight(widget);
        final int bottom = widget.getScrollY() + innerHeight;
        int bottomLine = layout.getLineForVertical(bottom);
        if (layout.getLineTop(bottomLine + 1) < bottom + 1) {
            // Less than a pixel of this line is out of view,
            // so we must have tried to make it entirely in view
            // and now want the next line to be in view instead.
            bottomLine += 1;
        }
        final int limit = layout.getLineCount() - 1;
        if (bottomLine <= limit) {
            bottomLine = Math.min(bottomLine + amount - 1, limit);
            Touch.scrollTo(widget, layout, widget.getScrollX(),
                    layout.getLineTop(bottomLine + 1) - innerHeight);
            return true;
        }
        return false;
    }

    /**
     * Performs a scroll page up action.
     * Scrolls up by one page.
     *
     * @param widget The text view.
     * @param buffer The text buffer.
     * @return True if the event was handled.
     * @hide
     */
    protected boolean scrollPageUp(TextView widget, Spannable buffer) {
        final Layout layout = widget.getLayout();
        final int top = widget.getScrollY() - getInnerHeight(widget);
        int topLine = layout.getLineForVertical(top);
        if (topLine >= 0) {
            Touch.scrollTo(widget, layout, widget.getScrollX(), layout.getLineTop(topLine));
            return true;
        }
        return false;
    }

    /**
     * Performs a scroll page up action.
     * Scrolls down by one page.
     *
     * @param widget The text view.
     * @param buffer The text buffer.
     * @return True if the event was handled.
     * @hide
     */
    protected boolean scrollPageDown(TextView widget, Spannable buffer) {
        final Layout layout = widget.getLayout();
        final int innerHeight = getInnerHeight(widget);
        final int bottom = widget.getScrollY() + innerHeight + innerHeight;
        int bottomLine = layout.getLineForVertical(bottom);
        if (bottomLine <= layout.getLineCount() - 1) {
            Touch.scrollTo(widget, layout, widget.getScrollX(),
                    layout.getLineTop(bottomLine + 1) - innerHeight);
            return true;
        }
        return false;
    }

    /**
     * Performs a scroll to top action.
     * Scrolls to the top of the document.
     *
     * @param widget The text view.
     * @param buffer The text buffer.
     * @return True if the event was handled.
     * @hide
     */
    protected boolean scrollTop(TextView widget, Spannable buffer) {
        final Layout layout = widget.getLayout();
        if (getTopLine(widget) >= 0) {
            Touch.scrollTo(widget, layout, widget.getScrollX(), layout.getLineTop(0));
            return true;
        }
        return false;
    }

    /**
     * Performs a scroll to bottom action.
     * Scrolls to the bottom of the document.
     *
     * @param widget The text view.
     * @param buffer The text buffer.
     * @return True if the event was handled.
     * @hide
     */
    protected boolean scrollBottom(TextView widget, Spannable buffer) {
        final Layout layout = widget.getLayout();
        final int lineCount = layout.getLineCount();
        if (getBottomLine(widget) <= lineCount - 1) {
            Touch.scrollTo(widget, layout, widget.getScrollX(),
                    layout.getLineTop(lineCount) - getInnerHeight(widget));
            return true;
        }
        return false;
    }

    /**
     * Performs a scroll to line start action.
     * Scrolls to the start of the line.
     *
     * @param widget The text view.
     * @param buffer The text buffer.
     * @return True if the event was handled.
     * @hide
     */
    protected boolean scrollLineStart(TextView widget, Spannable buffer) {
        final int minScrollX = getScrollBoundsLeft(widget);
        int scrollX = widget.getScrollX();
        if (scrollX > minScrollX) {
            widget.scrollTo(minScrollX, widget.getScrollY());
            return true;
        }
        return false;
    }

    /**
     * Performs a scroll to line end action.
     * Scrolls to the end of the line.
     *
     * @param widget The text view.
     * @param buffer The text buffer.
     * @return True if the event was handled.
     * @hide
     */
    protected boolean scrollLineEnd(TextView widget, Spannable buffer) {
        final int maxScrollX = getScrollBoundsRight(widget) - getInnerWidth(widget);
        int scrollX = widget.getScrollX();
        if (scrollX < maxScrollX) {
            widget.scrollTo(maxScrollX, widget.getScrollY());
            return true;
        }
        return false;
    }
}
