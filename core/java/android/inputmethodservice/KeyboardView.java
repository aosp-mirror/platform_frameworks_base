/*
 * Copyright (C) 2008-2009 Google Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package android.inputmethodservice;

import com.android.internal.R;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Paint.Align;
import android.graphics.drawable.Drawable;
import android.inputmethodservice.Keyboard.Key;
import android.os.Handler;
import android.os.Message;
import android.os.Vibrator;
import android.preference.PreferenceManager;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.widget.Button;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A view that renders a virtual {@link Keyboard}. It handles rendering of keys and
 * detecting key presses and touch movements.
 * 
 * @attr ref android.R.styleable#KeyboardView_keyBackground
 * @attr ref android.R.styleable#KeyboardView_keyPreviewLayout
 * @attr ref android.R.styleable#KeyboardView_keyPreviewOffset
 * @attr ref android.R.styleable#KeyboardView_labelTextSize
 * @attr ref android.R.styleable#KeyboardView_keyTextSize
 * @attr ref android.R.styleable#KeyboardView_keyTextColor
 * @attr ref android.R.styleable#KeyboardView_verticalCorrection
 * @attr ref android.R.styleable#KeyboardView_popupLayout
 */
public class KeyboardView extends View implements View.OnClickListener {

    /**
     * Listener for virtual keyboard events.
     */
    public interface OnKeyboardActionListener {
        /**
         * Send a key press to the listener.
         * @param primaryCode this is the key that was pressed
         * @param keyCodes the codes for all the possible alternative keys
         * with the primary code being the first. If the primary key code is
         * a single character such as an alphabet or number or symbol, the alternatives
         * will include other characters that may be on the same key or adjacent keys.
         * These codes are useful to correct for accidental presses of a key adjacent to
         * the intended key.
         */
        void onKey(int primaryCode, int[] keyCodes);

        /**
         * Called when the user quickly moves the finger from right to left.
         */
        void swipeLeft();
        
        /**
         * Called when the user quickly moves the finger from left to right.
         */
        void swipeRight();
        
        /**
         * Called when the user quickly moves the finger from up to down.
         */
        void swipeDown();
        
        /**
         * Called when the user quickly moves the finger from down to up.
         */
        void swipeUp();
    }

    private static final boolean DEBUG = false;
    private static final int NOT_A_KEY = -1;
    private static final int[] KEY_DELETE = { Keyboard.KEYCODE_DELETE };
    private static final int[] LONG_PRESSABLE_STATE_SET = { R.attr.state_long_pressable };   
    
    private Keyboard mKeyboard;
    private int mCurrentKeyIndex = NOT_A_KEY;
    private int mLabelTextSize;
    private int mKeyTextSize;
    private int mKeyTextColor;
    
    private TextView mPreviewText;
    private PopupWindow mPreviewPopup;
    private int mPreviewTextSizeLarge;
    private int mPreviewOffset;
    private int mPreviewHeight;
    private int[] mOffsetInWindow;

    private PopupWindow mPopupKeyboard;
    private View mMiniKeyboardContainer;
    private KeyboardView mMiniKeyboard;
    private boolean mMiniKeyboardOnScreen;
    private View mPopupParent;
    private int mMiniKeyboardOffsetX;
    private int mMiniKeyboardOffsetY;
    private Map<Key,View> mMiniKeyboardCache;
    private int[] mWindowOffset;

    /** Listener for {@link OnKeyboardActionListener}. */
    private OnKeyboardActionListener mKeyboardActionListener;
    
    private static final int MSG_REMOVE_PREVIEW = 1;
    private static final int MSG_REPEAT = 2;
    
    private int mVerticalCorrection;
    private int mProximityThreshold;

    private boolean mPreviewCentered = false;
    private boolean mShowPreview = true;
    private boolean mShowTouchPoints = false;
    private int mPopupPreviewX;
    private int mPopupPreviewY;

    private int mLastX;
    private int mLastY;
    private int mStartX;
    private int mStartY;

    private boolean mVibrateOn;
    private boolean mSoundOn;
    private boolean mProximityCorrectOn;
    
    private Paint mPaint;
    private Rect mPadding;
    
    private long mDownTime;
    private long mLastMoveTime;
    private int mLastKey;
    private int mLastCodeX;
    private int mLastCodeY;
    private int mCurrentKey = NOT_A_KEY;
    private long mLastKeyTime;
    private long mCurrentKeyTime;
    private int[] mKeyIndices = new int[12];
    private GestureDetector mGestureDetector;
    private int mPopupX;
    private int mPopupY;
    private int mRepeatKeyIndex = NOT_A_KEY;
    private int mPopupLayout;
    private boolean mAbortKey;
    
    private Drawable mKeyBackground;
    
    private static final String PREF_VIBRATE_ON = "vibrate_on";
    private static final String PREF_SOUND_ON = "sound_on";
    private static final String PREF_PROXIMITY_CORRECTION = "hit_correction";

    private static final int REPEAT_INTERVAL = 50; // ~20 keys per second
    private static final int REPEAT_START_DELAY = 400;

    private Vibrator mVibrator;
    private long[] mVibratePattern = new long[] {1, 20};

    private static int MAX_NEARBY_KEYS = 12;
    private int[] mDistances = new int[MAX_NEARBY_KEYS];

    // For multi-tap
    private int mLastSentIndex;
    private int mTapCount;
    private long mLastTapTime;
    private boolean mInMultiTap;
    private static final int MULTITAP_INTERVAL = 800; // milliseconds
    private StringBuilder mPreviewLabel = new StringBuilder(1);

    Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_REMOVE_PREVIEW:
                    mPreviewText.setVisibility(INVISIBLE);
                    break;
                case MSG_REPEAT:
                    if (repeatKey()) {
                        Message repeat = Message.obtain(this, MSG_REPEAT);
                        sendMessageDelayed(repeat, REPEAT_INTERVAL);                        
                    }
                    break;
            }
            
        }
    };

    public KeyboardView(Context context, AttributeSet attrs) {
        this(context, attrs, com.android.internal.R.attr.keyboardViewStyle);
    }

    public KeyboardView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        TypedArray a =
            context.obtainStyledAttributes(
                attrs, android.R.styleable.KeyboardView, defStyle, 0);

        LayoutInflater inflate =
                (LayoutInflater) context
                        .getSystemService(Context.LAYOUT_INFLATER_SERVICE);

        int previewLayout = 0;
        int keyTextSize = 0;

        int n = a.getIndexCount();
        
        for (int i = 0; i < n; i++) {
            int attr = a.getIndex(i);

            switch (attr) {
            case com.android.internal.R.styleable.KeyboardView_keyBackground:
                mKeyBackground = a.getDrawable(attr);
                break;
            case com.android.internal.R.styleable.KeyboardView_verticalCorrection:
                mVerticalCorrection = a.getDimensionPixelOffset(attr, 0);
                break;
            case com.android.internal.R.styleable.KeyboardView_keyPreviewLayout:
                previewLayout = a.getResourceId(attr, 0);
                break;
            case com.android.internal.R.styleable.KeyboardView_keyPreviewOffset:
                mPreviewOffset = a.getDimensionPixelOffset(attr, 0);
                break;
            case com.android.internal.R.styleable.KeyboardView_keyPreviewHeight:
                mPreviewHeight = a.getDimensionPixelSize(attr, 80);
                break;
            case com.android.internal.R.styleable.KeyboardView_keyTextSize:
                mKeyTextSize = a.getDimensionPixelSize(attr, 18);
                break;
            case com.android.internal.R.styleable.KeyboardView_keyTextColor:
                mKeyTextColor = a.getColor(attr, 0xFF000000);
                break;
            case com.android.internal.R.styleable.KeyboardView_labelTextSize:
                mLabelTextSize = a.getDimensionPixelSize(attr, 14);
                break;
            case com.android.internal.R.styleable.KeyboardView_popupLayout:
                mPopupLayout = a.getResourceId(attr, 0);
                break;
            }
        }
        
        // Get the settings preferences
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        mVibrateOn = sp.getBoolean(PREF_VIBRATE_ON, mVibrateOn);
        mSoundOn = sp.getBoolean(PREF_SOUND_ON, mSoundOn);
        mProximityCorrectOn = sp.getBoolean(PREF_PROXIMITY_CORRECTION, true);
        
        mPreviewPopup = new PopupWindow(context);
        if (previewLayout != 0) {
            mPreviewText = (TextView) inflate.inflate(previewLayout, null);
            mPreviewTextSizeLarge = (int) mPreviewText.getTextSize();
            mPreviewPopup.setContentView(mPreviewText);
            mPreviewPopup.setBackgroundDrawable(null);
        } else {
            mShowPreview = false;
        }
        
        mPreviewPopup.setTouchable(false);
        
        mPopupKeyboard = new PopupWindow(context);
        mPopupKeyboard.setBackgroundDrawable(null);
        //mPopupKeyboard.setClippingEnabled(false);
        
        mPopupParent = this;
        //mPredicting = true;
        
        mPaint = new Paint();
        mPaint.setAntiAlias(true);
        mPaint.setTextSize(keyTextSize);
        mPaint.setTextAlign(Align.CENTER);

        mPadding = new Rect(0, 0, 0, 0);
        mMiniKeyboardCache = new HashMap<Key,View>();
        mKeyBackground.getPadding(mPadding);
        
        resetMultiTap();
        initGestureDetector();
    }
    
    private void initGestureDetector() {
        mGestureDetector = new GestureDetector(new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent me1, MotionEvent me2, 
                    float velocityX, float velocityY) {
                if (velocityX > 400 && Math.abs(velocityY) < 400) {
                    swipeRight();
                    return true;
                } else if (velocityX < -400 && Math.abs(velocityY) < 400) {
                    swipeLeft();
                    return true;
                } else if (velocityY < -400 && Math.abs(velocityX) < 400) {
                    swipeUp();
                    return true;
                } else if (velocityY > 400 && Math.abs(velocityX) < 400) {
                    swipeDown();
                    return true;
                }
                return false;
            }
            
            @Override
            public void onLongPress(MotionEvent me) {
                openPopupIfRequired(me);
            }
        });
    }

    public void setOnKeyboardActionListener(OnKeyboardActionListener listener) {
        mKeyboardActionListener = listener;
    }

    /**
     * Returns the {@link OnKeyboardActionListener} object.
     * @return the listener attached to this keyboard
     */
    protected OnKeyboardActionListener getOnKeyboardActionListener() {
        return mKeyboardActionListener;
    }

    /**
     * Attaches a keyboard to this view. The keyboard can be switched at any time and the
     * view will re-layout itself to accommodate the keyboard.
     * @see Keyboard
     * @see #getKeyboard()
     * @param keyboard the keyboard to display in this view
     */
    public void setKeyboard(Keyboard keyboard) {
        mKeyboard = keyboard;
        requestLayout();
        invalidate();
        computeProximityThreshold(keyboard);
    }

    /**
     * Returns the current keyboard being displayed by this view.
     * @return the currently attached keyboard
     * @see #setKeyboard(Keyboard)
     */
    public Keyboard getKeyboard() {
        return mKeyboard;
    }
    
    /**
     * Sets the state of the shift key of the keyboard, if any.
     * @param shifted whether or not to enable the state of the shift key
     * @return true if the shift key state changed, false if there was no change
     * @see KeyboardView#isShifted()
     */
    public boolean setShifted(boolean shifted) {
        if (mKeyboard != null) {
            if (mKeyboard.setShifted(shifted)) {
                // The whole keyboard probably needs to be redrawn
                invalidate();
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the state of the shift key of the keyboard, if any.
     * @return true if the shift is in a pressed state, false otherwise. If there is
     * no shift key on the keyboard or there is no keyboard attached, it returns false.
     * @see KeyboardView#setShifted(boolean)
     */
    public boolean isShifted() {
        if (mKeyboard != null) {
            return mKeyboard.isShifted();
        }
        return false;
    }

    /**
     * Enables or disables the key feedback popup. This is a popup that shows a magnified
     * version of the depressed key. By default the preview is enabled. 
     * @param previewEnabled whether or not to enable the key feedback popup
     * @see #isPreviewEnabled()
     */
    public void setPreviewEnabled(boolean previewEnabled) {
        mShowPreview = previewEnabled;
    }

    /**
     * Returns the enabled state of the key feedback popup.
     * @return whether or not the key feedback popup is enabled
     * @see #setPreviewEnabled(boolean)
     */
    public boolean isPreviewEnabled() {
        return mShowPreview;
    }
    
    public void setVerticalCorrection(int verticalOffset) {
        
    }
    public void setPopupParent(View v) {
        mPopupParent = v;
    }
    
    public void setPopupOffset(int x, int y) {
        mMiniKeyboardOffsetX = x;
        mMiniKeyboardOffsetY = y;
        if (mPreviewPopup.isShowing()) {
            mPreviewPopup.dismiss();
        }
    }
    
    /** 
     * Popup keyboard close button clicked.
     * @hide 
     */
    public void onClick(View v) {
        dismissPopupKeyboard();
    }

    private CharSequence adjustCase(CharSequence label) {
        if (mKeyboard.isShifted() && label != null && label.length() == 1
                && Character.isLowerCase(label.charAt(0))) {
            label = label.toString().toUpperCase();
        }
        return label;
    }

    @Override
    public void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // Round up a little
        if (mKeyboard == null) {
            setMeasuredDimension(mPaddingLeft + mPaddingRight, mPaddingTop + mPaddingBottom);
        } else {
            int width = mKeyboard.getMinWidth() + mPaddingLeft + mPaddingRight;
            if (MeasureSpec.getSize(widthMeasureSpec) < width + 10) {
                width = MeasureSpec.getSize(widthMeasureSpec);
            }
            setMeasuredDimension(width, mKeyboard.getHeight() + mPaddingTop + mPaddingBottom);
        }
    }

    /**
     * Compute the average distance between adjacent keys (horizontally and vertically)
     * and square it to get the proximity threshold. We use a square here and in computing
     * the touch distance from a key's center to avoid taking a square root.
     * @param keyboard
     */
    private void computeProximityThreshold(Keyboard keyboard) {
        if (keyboard == null) return;
        List<Key> keys = keyboard.getKeys();
        if (keys == null) return;
        int length = keys.size();
        int dimensionSum = 0;
        for (int i = 0; i < length; i++) {
            Key key = keys.get(i);
            dimensionSum += key.width + key.gap + key.height; 
        }
        if (dimensionSum < 0 || length == 0) return;
        mProximityThreshold = dimensionSum / (length * 2);
        mProximityThreshold *= mProximityThreshold; // Square it
    }
    
    @Override
    public void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (mKeyboard == null) return;
        
        final Paint paint = mPaint;
        //final int descent = (int) paint.descent();
        final Drawable keyBackground = mKeyBackground;
        final Rect padding = mPadding;
        final int kbdPaddingLeft = mPaddingLeft;
        final int kbdPaddingTop = mPaddingTop;
        List<Key> keys = mKeyboard.getKeys();
        //canvas.translate(0, mKeyboardPaddingTop);
        paint.setAlpha(255);
        paint.setColor(mKeyTextColor);

        final int keyCount = keys.size();
        for (int i = 0; i < keyCount; i++) {
            final Key key = keys.get(i);
            int[] drawableState = key.getCurrentDrawableState();
            keyBackground.setState(drawableState);
            
            // Switch the character to uppercase if shift is pressed
            String label = key.label == null? null : adjustCase(key.label).toString();
            
            final Rect bounds = keyBackground.getBounds();
            if (key.width != bounds.right || 
                    key.height != bounds.bottom) {
                keyBackground.setBounds(0, 0, key.width, key.height);
            }
            canvas.translate(key.x + kbdPaddingLeft, key.y + kbdPaddingTop);
            keyBackground.draw(canvas);
            
            if (label != null) {
                // For characters, use large font. For labels like "Done", use small font.
                if (label.length() > 1 && key.codes.length < 2) {
                    paint.setTextSize(mLabelTextSize);
                    paint.setFakeBoldText(true);
                } else {
                    paint.setTextSize(mKeyTextSize);
                    paint.setFakeBoldText(false);
                }
                // Draw a drop shadow for the text
                paint.setShadowLayer(3f, 0, 0, 0xCC000000);
                // Draw the text
                canvas.drawText(label,
                    (key.width - padding.left - padding.right) / 2
                            + padding.left,
                    (key.height - padding.top - padding.bottom) / 2
                            + (paint.getTextSize() - paint.descent()) / 2 + padding.top,
                    paint);
                // Turn off drop shadow
                paint.setShadowLayer(0, 0, 0, 0);
            } else if (key.icon != null) {
                final int drawableX = (key.width - padding.left - padding.right 
                                - key.icon.getIntrinsicWidth()) / 2 + padding.left;
                final int drawableY = (key.height - padding.top - padding.bottom 
                        - key.icon.getIntrinsicHeight()) / 2 + padding.top;
                canvas.translate(drawableX, drawableY);
                key.icon.setBounds(0, 0, 
                        key.icon.getIntrinsicWidth(), key.icon.getIntrinsicHeight());
                key.icon.draw(canvas);
                canvas.translate(-drawableX, -drawableY);
            }
            canvas.translate(-key.x - kbdPaddingLeft, -key.y - kbdPaddingTop);
        }
        
        // Overlay a dark rectangle to dim the keyboard
        if (mMiniKeyboardOnScreen) {
            paint.setColor(0xA0000000);
            canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
        }

        if (DEBUG && mShowTouchPoints) {
            paint.setAlpha(128);
            paint.setColor(0xFFFF0000);
            canvas.drawCircle(mStartX, mStartY, 3, paint);
            canvas.drawLine(mStartX, mStartY, mLastX, mLastY, paint);
            paint.setColor(0xFF0000FF);
            canvas.drawCircle(mLastX, mLastY, 3, paint);
            paint.setColor(0xFF00FF00);
            canvas.drawCircle((mStartX + mLastX) / 2, (mStartY + mLastY) / 2, 2, paint);
        }
    }

    private void playKeyClick() {
        if (mSoundOn) {
            playSoundEffect(0);
        }
    }

    private void vibrate() {
        if (!mVibrateOn) {
            return;
        }
        if (mVibrator == null) {
            mVibrator = new Vibrator();
        }
        mVibrator.vibrate(mVibratePattern, -1);
    }

    private int getKeyIndices(int x, int y, int[] allKeys) {
        final List<Key> keys = mKeyboard.getKeys();
        final boolean shifted = mKeyboard.isShifted();
        int primaryIndex = NOT_A_KEY;
        int closestKey = NOT_A_KEY;
        int closestKeyDist = mProximityThreshold + 1;
        java.util.Arrays.fill(mDistances, Integer.MAX_VALUE);
        final int keyCount = keys.size();
        for (int i = 0; i < keyCount; i++) {
            final Key key = keys.get(i);
            int dist = 0;
            boolean isInside = key.isInside(x,y);
            if (((mProximityCorrectOn 
                    && (dist = key.squaredDistanceFrom(x, y)) < mProximityThreshold) 
                    || isInside)
                    && key.codes[0] > 32) {
                // Find insertion point
                final int nCodes = key.codes.length;
                if (dist < closestKeyDist) {
                    closestKeyDist = dist;
                    closestKey = i;
                }
                
                if (allKeys == null) continue;
                
                for (int j = 0; j < mDistances.length; j++) {
                    if (mDistances[j] > dist) {
                        // Make space for nCodes codes
                        System.arraycopy(mDistances, j, mDistances, j + nCodes,
                                mDistances.length - j - nCodes);
                        System.arraycopy(allKeys, j, allKeys, j + nCodes,
                                allKeys.length - j - nCodes);
                        for (int c = 0; c < nCodes; c++) {
                            allKeys[j + c] = key.codes[c];
                            if (shifted) {
                                //allKeys[j + c] = Character.toUpperCase(key.codes[c]);
                            }
                            mDistances[j + c] = dist;
                        }
                        break;
                    }
                }
            }
            
            if (isInside) {
                primaryIndex = i;
            }
        }
        if (primaryIndex == NOT_A_KEY) {
            primaryIndex = closestKey;
        }
        return primaryIndex;
    }

    private void detectAndSendKey(int x, int y, long eventTime) {
        int index = mCurrentKey;
        if (index != NOT_A_KEY) {
            vibrate();
            final Key key = mKeyboard.getKeys().get(index);
            if (key.text != null) {
                for (int i = 0; i < key.text.length(); i++) {
                    mKeyboardActionListener.onKey(key.text.charAt(i), key.codes);
                }
            } else {
                int code = key.codes[0];
                //TextEntryState.keyPressedAt(key, x, y);
                int[] codes = new int[MAX_NEARBY_KEYS];
                Arrays.fill(codes, NOT_A_KEY);
                getKeyIndices(x, y, codes);
                // Multi-tap
                if (mInMultiTap) {
                    if (mTapCount != -1) {
                        mKeyboardActionListener.onKey(Keyboard.KEYCODE_DELETE, KEY_DELETE);
                    } else {
                        mTapCount = 0;
                    }
                    code = key.codes[mTapCount];
                }
                mKeyboardActionListener.onKey(code, codes);
            }
            mLastSentIndex = index;
            mLastTapTime = eventTime;
        }
    }

    /**
     * Handle multi-tap keys by producing the key label for the current multi-tap state.
     */
    private CharSequence getPreviewText(Key key) {
        if (mInMultiTap) {
            // Multi-tap
            mPreviewLabel.setLength(0);
            mPreviewLabel.append((char) key.codes[mTapCount < 0 ? 0 : mTapCount]);
            return adjustCase(mPreviewLabel);
        } else {
            return adjustCase(key.label);
        }
    }

    private void showPreview(int keyIndex) {
        int oldKeyIndex = mCurrentKeyIndex;
        final PopupWindow previewPopup = mPreviewPopup;
        
        mCurrentKeyIndex = keyIndex;
        // Release the old key and press the new key
        final List<Key> keys = mKeyboard.getKeys();
        if (oldKeyIndex != mCurrentKeyIndex) {
            if (oldKeyIndex != NOT_A_KEY && keys.size() > oldKeyIndex) {
                keys.get(oldKeyIndex).onReleased(mCurrentKeyIndex == NOT_A_KEY);
                invalidateKey(oldKeyIndex);
            }
            if (mCurrentKeyIndex != NOT_A_KEY && keys.size() > mCurrentKeyIndex) {
                keys.get(mCurrentKeyIndex).onPressed();
                invalidateKey(mCurrentKeyIndex);
            }
        }
        // If key changed and preview is on ...
        if (oldKeyIndex != mCurrentKeyIndex && mShowPreview) {
            if (previewPopup.isShowing()) {
                if (keyIndex == NOT_A_KEY) {
                    mHandler.sendMessageDelayed(mHandler
                            .obtainMessage(MSG_REMOVE_PREVIEW), 60);
                }
            }
            if (keyIndex != NOT_A_KEY) {
                Key key = keys.get(keyIndex);
                if (key.icon != null) {
                    mPreviewText.setCompoundDrawables(null, null, null, 
                            key.iconPreview != null ? key.iconPreview : key.icon);
                    mPreviewText.setText(null);
                } else {
                    mPreviewText.setCompoundDrawables(null, null, null, null);
                    mPreviewText.setText(getPreviewText(key));
                    if (key.label.length() > 1 && key.codes.length < 2) {
                        mPreviewText.setTextSize(mLabelTextSize);
                    } else {
                        mPreviewText.setTextSize(mPreviewTextSizeLarge);
                    }
                }
                mPreviewText.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), 
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                int popupWidth = Math.max(mPreviewText.getMeasuredWidth(), key.width 
                        + mPreviewText.getPaddingLeft() + mPreviewText.getPaddingRight());
                final int popupHeight = mPreviewHeight;
                LayoutParams lp = mPreviewText.getLayoutParams();
                if (lp != null) {
                    lp.width = popupWidth;
                    lp.height = popupHeight;
                }
                previewPopup.setWidth(popupWidth);
                previewPopup.setHeight(popupHeight);
                if (!mPreviewCentered) {
                    mPopupPreviewX = key.x - mPreviewText.getPaddingLeft() + mPaddingLeft;
                    mPopupPreviewY = key.y - popupHeight + mPreviewOffset;
                } else {
                    // TODO: Fix this if centering is brought back
                    mPopupPreviewX = 160 - mPreviewText.getMeasuredWidth() / 2;
                    mPopupPreviewY = - mPreviewText.getMeasuredHeight();
                }
                mHandler.removeMessages(MSG_REMOVE_PREVIEW);
                if (mOffsetInWindow == null) {
                    mOffsetInWindow = new int[2];
                    getLocationInWindow(mOffsetInWindow);
                    mOffsetInWindow[0] += mMiniKeyboardOffsetX; // Offset may be zero
                    mOffsetInWindow[1] += mMiniKeyboardOffsetY; // Offset may be zero
                }
                // Set the preview background state
                mPreviewText.getBackground().setState(
                        key.popupResId != 0 ? LONG_PRESSABLE_STATE_SET : EMPTY_STATE_SET);
                if (previewPopup.isShowing()) {
                    previewPopup.update(mPopupPreviewX + mOffsetInWindow[0], 
                            mPopupPreviewY + mOffsetInWindow[1], 
                            popupWidth, popupHeight);
                } else {
                    previewPopup.showAtLocation(mPopupParent, Gravity.NO_GRAVITY, 
                            mPopupPreviewX + mOffsetInWindow[0], 
                            mPopupPreviewY + mOffsetInWindow[1]);
                }
                mPreviewText.setVisibility(VISIBLE);
            }
        }
    }

    private void invalidateKey(int keyIndex) {
        if (keyIndex < 0 || keyIndex >= mKeyboard.getKeys().size()) {
            return;
        }
        final Key key = mKeyboard.getKeys().get(keyIndex);
        invalidate(key.x + mPaddingLeft, key.y + mPaddingTop, 
                key.x + key.width + mPaddingLeft, key.y + key.height + mPaddingTop);
    }

    private boolean openPopupIfRequired(MotionEvent me) {
        // Check if we have a popup layout specified first.
        if (mPopupLayout == 0) {
            return false;
        }
        if (mCurrentKey < 0 || mCurrentKey >= mKeyboard.getKeys().size()) {
            return false;
        }

        Key popupKey = mKeyboard.getKeys().get(mCurrentKey);        
        boolean result = onLongPress(popupKey);
        if (result) {
            mAbortKey = true;
            showPreview(NOT_A_KEY);
        }
        return result;
    }

    /**
     * Called when a key is long pressed. By default this will open any popup keyboard associated
     * with this key through the attributes popupLayout and popupCharacters.
     * @param popupKey the key that was long pressed
     * @return true if the long press is handled, false otherwise. Subclasses should call the
     * method on the base class if the subclass doesn't wish to handle the call.
     */
    protected boolean onLongPress(Key popupKey) {
        int popupKeyboardId = popupKey.popupResId;

        if (popupKeyboardId != 0) {
            mMiniKeyboardContainer = mMiniKeyboardCache.get(popupKey);
            if (mMiniKeyboardContainer == null) {
                LayoutInflater inflater = (LayoutInflater) getContext().getSystemService(
                        Context.LAYOUT_INFLATER_SERVICE);
                mMiniKeyboardContainer = inflater.inflate(mPopupLayout, null);
                mMiniKeyboard = (KeyboardView) mMiniKeyboardContainer.findViewById(
                        com.android.internal.R.id.keyboardView);
                View closeButton = mMiniKeyboardContainer.findViewById(
                        com.android.internal.R.id.button_close);
                if (closeButton != null) closeButton.setOnClickListener(this);
                mMiniKeyboard.setOnKeyboardActionListener(new OnKeyboardActionListener() {
                    public void onKey(int primaryCode, int[] keyCodes) {
                        mKeyboardActionListener.onKey(primaryCode, keyCodes);
                        dismissPopupKeyboard();
                    }
                    
                    public void swipeLeft() { }
                    public void swipeRight() { }
                    public void swipeUp() { }
                    public void swipeDown() { }
                });
                //mInputView.setSuggest(mSuggest);
                Keyboard keyboard;
                if (popupKey.popupCharacters != null) {
                    keyboard = new Keyboard(getContext(), popupKeyboardId, 
                            popupKey.popupCharacters, -1, getPaddingLeft() + getPaddingRight());
                } else {
                    keyboard = new Keyboard(getContext(), popupKeyboardId);
                }
                mMiniKeyboard.setKeyboard(keyboard);
                mMiniKeyboard.setPopupParent(this);
                mMiniKeyboardContainer.measure(
                        MeasureSpec.makeMeasureSpec(getWidth(), MeasureSpec.AT_MOST), 
                        MeasureSpec.makeMeasureSpec(getHeight(), MeasureSpec.AT_MOST));
                
                mMiniKeyboardCache.put(popupKey, mMiniKeyboardContainer);
            } else {
                mMiniKeyboard = (KeyboardView) mMiniKeyboardContainer.findViewById(
                        com.android.internal.R.id.keyboardView);
            }
            if (mWindowOffset == null) {
                mWindowOffset = new int[2];
                getLocationInWindow(mWindowOffset);
            }
            mPopupX = popupKey.x + mPaddingLeft;
            mPopupY = popupKey.y + mPaddingTop;
            mPopupX = mPopupX + popupKey.width - mMiniKeyboardContainer.getMeasuredWidth();
            mPopupY = mPopupY - mMiniKeyboardContainer.getMeasuredHeight();
            final int x = mPopupX + mMiniKeyboardContainer.getPaddingRight() + mWindowOffset[0];
            final int y = mPopupY + mMiniKeyboardContainer.getPaddingBottom() + mWindowOffset[1];
            mMiniKeyboard.setPopupOffset(x < 0 ? 0 : x, y);
            mMiniKeyboard.setShifted(isShifted());
            mPopupKeyboard.setContentView(mMiniKeyboardContainer);
            mPopupKeyboard.setWidth(mMiniKeyboardContainer.getMeasuredWidth());
            mPopupKeyboard.setHeight(mMiniKeyboardContainer.getMeasuredHeight());
            mPopupKeyboard.showAtLocation(this, Gravity.NO_GRAVITY, x, y);
            mMiniKeyboardOnScreen = true;
            //mMiniKeyboard.onTouchEvent(getTranslatedEvent(me));
            invalidate();
            return true;
        }
        return false;
    }
    
    @Override
    public boolean onTouchEvent(MotionEvent me) {
        int touchX = (int) me.getX() - mPaddingLeft;
        int touchY = (int) me.getY() + mVerticalCorrection - mPaddingTop;
        int action = me.getAction();
        long eventTime = me.getEventTime();
        int keyIndex = getKeyIndices(touchX, touchY, null);
        
        if (mGestureDetector.onTouchEvent(me)) {
            showPreview(NOT_A_KEY);
            mHandler.removeMessages(MSG_REPEAT);
            return true;
        }
        
        // Needs to be called after the gesture detector gets a turn, as it may have
        // displayed the mini keyboard
        if (mMiniKeyboardOnScreen) {
            return true;
        }

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                mAbortKey = false;
                mStartX = touchX;
                mStartY = touchY;
                mLastCodeX = touchX;
                mLastCodeY = touchY;
                mLastKeyTime = 0;
                mCurrentKeyTime = 0;
                mLastKey = NOT_A_KEY;
                mCurrentKey = keyIndex;
                mDownTime = me.getEventTime();
                mLastMoveTime = mDownTime;
                checkMultiTap(eventTime, keyIndex);
                if (mCurrentKey >= 0 && mKeyboard.getKeys().get(mCurrentKey).repeatable) {
                    mRepeatKeyIndex = mCurrentKey;
                    repeatKey();
                    Message msg = mHandler.obtainMessage(MSG_REPEAT);
                    mHandler.sendMessageDelayed(msg, REPEAT_START_DELAY);
                }
                showPreview(keyIndex);
                playKeyClick();
                vibrate();
                break;

            case MotionEvent.ACTION_MOVE:
                if (keyIndex != NOT_A_KEY) {
                    if (mCurrentKey == NOT_A_KEY) {
                        mCurrentKey = keyIndex;
                        mCurrentKeyTime = eventTime - mDownTime;
                    } else {
                        if (keyIndex == mCurrentKey) {
                            mCurrentKeyTime += eventTime - mLastMoveTime;
                        } else {
                            resetMultiTap();
                            mLastKey = mCurrentKey;
                            mLastCodeX = mLastX;
                            mLastCodeY = mLastY;
                            mLastKeyTime =
                                    mCurrentKeyTime + eventTime - mLastMoveTime;
                            mCurrentKey = keyIndex;
                            mCurrentKeyTime = 0;
                        }
                    }
                    if (keyIndex != mRepeatKeyIndex) {
                        mHandler.removeMessages(MSG_REPEAT);
                        mRepeatKeyIndex = NOT_A_KEY;
                    }
                }
                showPreview(keyIndex);
                break;

            case MotionEvent.ACTION_UP:
                mHandler.removeMessages(MSG_REPEAT);
                if (keyIndex == mCurrentKey) {
                    mCurrentKeyTime += eventTime - mLastMoveTime;
                } else {
                    resetMultiTap();
                    mLastKey = mCurrentKey;
                    mLastKeyTime = mCurrentKeyTime + eventTime - mLastMoveTime;
                    mCurrentKey = keyIndex;
                    mCurrentKeyTime = 0;
                }
                if (mCurrentKeyTime < mLastKeyTime && mLastKey != NOT_A_KEY) {
                    mCurrentKey = mLastKey;
                    touchX = mLastCodeX;
                    touchY = mLastCodeY;
                }
                showPreview(NOT_A_KEY);
                Arrays.fill(mKeyIndices, NOT_A_KEY);
                invalidateKey(keyIndex);
                // If we're not on a repeating key (which sends on a DOWN event)
                if (mRepeatKeyIndex == NOT_A_KEY && !mMiniKeyboardOnScreen && !mAbortKey) {
                    detectAndSendKey(touchX, touchY, eventTime);
                }
                mRepeatKeyIndex = NOT_A_KEY;
                break;
        }
        mLastX = touchX;
        mLastY = touchY;
        return true;
    }

    private boolean repeatKey() {
        Key key = mKeyboard.getKeys().get(mRepeatKeyIndex);
        detectAndSendKey(key.x, key.y, mLastTapTime);
        return true;
    }
    
    protected void swipeRight() {
        mKeyboardActionListener.swipeRight();
    }
    
    protected void swipeLeft() {
        mKeyboardActionListener.swipeLeft();
    }

    protected void swipeUp() {
        mKeyboardActionListener.swipeUp();
    }

    protected void swipeDown() {
        mKeyboardActionListener.swipeDown();
    }

    public void closing() {
        if (mPreviewPopup.isShowing()) {
            mPreviewPopup.dismiss();
        }
        dismissPopupKeyboard();
    }
    
    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        closing();
    }

    private void dismissPopupKeyboard() {
        if (mPopupKeyboard.isShowing()) {
            mPopupKeyboard.dismiss();
            mMiniKeyboardOnScreen = false;
            invalidate();
        }
    }
    
    public boolean handleBack() {
        if (mPopupKeyboard.isShowing()) {
            dismissPopupKeyboard();
            return true;
        }
        return false;
    }

    private void resetMultiTap() {
        mLastSentIndex = NOT_A_KEY;
        mTapCount = 0;
        mLastTapTime = -1;
        mInMultiTap = false;
    }
    
    private void checkMultiTap(long eventTime, int keyIndex) {
        if (keyIndex == NOT_A_KEY) return;
        Key key = mKeyboard.getKeys().get(keyIndex);
        if (key.codes.length > 1) {
            mInMultiTap = true;
            if (eventTime < mLastTapTime + MULTITAP_INTERVAL
                    && keyIndex == mLastSentIndex) {
                mTapCount = (mTapCount + 1) % key.codes.length;
                return;
            } else {
                mTapCount = -1;
                return;
            }
        }
        if (eventTime > mLastTapTime + MULTITAP_INTERVAL || keyIndex != mLastSentIndex) {
            resetMultiTap();
        }
    }
}
