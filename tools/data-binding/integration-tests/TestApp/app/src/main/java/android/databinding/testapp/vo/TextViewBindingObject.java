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
package android.databinding.testapp.vo;

import android.databinding.Bindable;
import android.databinding.adapters.TextViewBindingAdapter;
import android.databinding.testapp.BR;
import android.text.Editable;
import android.text.InputType;
import android.text.method.KeyListener;
import android.text.method.TextKeyListener;
import android.text.util.Linkify;
import android.view.KeyEvent;
import android.view.View;
import android.widget.TextView;

public class TextViewBindingObject extends BindingAdapterBindingObject {

    @Bindable
    private int mAutoLink = Linkify.WEB_URLS;

    @Bindable
    private int mDrawablePadding;

    @Bindable
    private int mInputType = InputType.TYPE_CLASS_PHONE;

    @Bindable
    private boolean mScrollHorizontally;

    @Bindable
    private boolean mTextAllCaps;

    @Bindable
    private int mTextColorHighlight;

    @Bindable
    private int mTextColorHint;

    @Bindable
    private int mTextColorLink;

    @Bindable
    private boolean mAutoText;

    @Bindable
    private TextKeyListener.Capitalize mCapitalize = TextKeyListener.Capitalize.NONE;

    @Bindable
    private TextView.BufferType mBufferType = TextView.BufferType.NORMAL;

    @Bindable
    private String mDigits = "abcdefg";

    @Bindable
    private int mNumeric = TextViewBindingAdapter.DECIMAL;

    @Bindable
    private boolean mPhoneNumber;

    @Bindable
    private int mDrawableBottom;

    @Bindable
    private int mDrawableTop;

    @Bindable
    private int mDrawableLeft;

    @Bindable
    private int mDrawableRight;

    @Bindable
    private int mDrawableStart;

    @Bindable
    private int mDrawableEnd;

    @Bindable
    private String mImeActionLabel;

    @Bindable
    private int mImeActionId;

    @Bindable
    private String mInputMethod
            = "android.databinding.testapp.vo.TextViewBindingObject$KeyListener1";

    @Bindable
    private float mLineSpacingExtra;

    @Bindable
    private float mLineSpacingMultiplier;

    @Bindable
    private int mMaxLength;

    @Bindable
    private int mShadowColor;

    @Bindable
    private float mShadowDx;

    @Bindable
    private float mShadowDy;

    @Bindable
    private float mShadowRadius;

    @Bindable
    private float mTextSize = 10f;

    public TextView.BufferType getBufferType() {
        return mBufferType;
    }

    public float getLineSpacingExtra() {
        return mLineSpacingExtra;
    }

    public float getLineSpacingMultiplier() {
        return mLineSpacingMultiplier;
    }

    public float getShadowDx() {
        return mShadowDx;
    }

    public float getShadowDy() {
        return mShadowDy;
    }

    public float getShadowRadius() {
        return mShadowRadius;
    }

    public float getTextSize() {
        return mTextSize;
    }

    public int getAutoLink() {
        return mAutoLink;
    }

    public int getDrawableBottom() {
        return mDrawableBottom;
    }

    public int getDrawableEnd() {
        return mDrawableEnd;
    }

    public int getDrawableLeft() {
        return mDrawableLeft;
    }

    public int getDrawablePadding() {
        return mDrawablePadding;
    }

    public int getDrawableRight() {
        return mDrawableRight;
    }

    public int getDrawableStart() {
        return mDrawableStart;
    }

    public int getDrawableTop() {
        return mDrawableTop;
    }

    public int getImeActionId() {
        return mImeActionId;
    }

    public int getInputType() {
        return mInputType;
    }

    public int getMaxLength() {
        return mMaxLength;
    }

    public int getNumeric() {
        return mNumeric;
    }

    public int getShadowColor() {
        return mShadowColor;
    }

    public int getTextColorHighlight() {
        return mTextColorHighlight;
    }

    public int getTextColorHint() {
        return mTextColorHint;
    }

    public int getTextColorLink() {
        return mTextColorLink;
    }

    public String getDigits() {
        return mDigits;
    }

    public String getImeActionLabel() {
        return mImeActionLabel;
    }

    public String getInputMethod() {
        return mInputMethod;
    }

    public boolean isAutoText() {
        return mAutoText;
    }

    public TextKeyListener.Capitalize getCapitalize() {
        return mCapitalize;
    }

    public void setCapitalize(TextKeyListener.Capitalize capitalize) {
        mCapitalize = capitalize;
        notifyPropertyChanged(BR.capitalize);
    }

    public boolean isPhoneNumber() {
        return mPhoneNumber;
    }

    public boolean isScrollHorizontally() {
        return mScrollHorizontally;
    }

    public boolean isTextAllCaps() {
        return mTextAllCaps;
    }

    public void changeValues() {
        mAutoLink = Linkify.EMAIL_ADDRESSES;
        mDrawablePadding = 10;
        mInputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS;
        mScrollHorizontally = true;
        mTextAllCaps = true;
        mTextColorHighlight = 0xFF00FF00;
        mTextColorHint = 0xFFFF0000;
        mTextColorLink = 0xFF0000FF;
        mAutoText = true;
        mCapitalize = TextKeyListener.Capitalize.SENTENCES;
        mBufferType = TextView.BufferType.SPANNABLE;
        mDigits = "hijklmno";
        mNumeric = TextViewBindingAdapter.SIGNED;
        mPhoneNumber = true;
        mDrawableBottom = 0xFF880088;
        mDrawableTop = 0xFF111111;
        mDrawableLeft = 0xFF222222;
        mDrawableRight = 0xFF333333;
        mDrawableStart = 0xFF444444;
        mDrawableEnd = 0xFF555555;
        mImeActionLabel = "Hello World";
        mImeActionId = 3;
        mInputMethod = "android.databinding.testapp.vo.TextViewBindingObject$KeyListener2";
        mLineSpacingExtra = 2;
        mLineSpacingMultiplier = 3;
        mMaxLength = 100;
        mShadowColor = 0xFF666666;
        mShadowDx = 2;
        mShadowDy = 3;
        mShadowRadius = 4;
        mTextSize = 20f;
        notifyChange();
    }

    public static class KeyListener1 implements KeyListener {

        @Override
        public int getInputType() {
            return InputType.TYPE_CLASS_TEXT;
        }

        @Override
        public boolean onKeyDown(View view, Editable text, int keyCode, KeyEvent event) {
            return false;
        }

        @Override
        public boolean onKeyUp(View view, Editable text, int keyCode, KeyEvent event) {
            return false;
        }

        @Override
        public boolean onKeyOther(View view, Editable text, KeyEvent event) {
            return false;
        }

        @Override
        public void clearMetaKeyState(View view, Editable content, int states) {
        }
    }

    public static class KeyListener2 extends KeyListener1 {

    }
}
