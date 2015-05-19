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

package android.webkit;

import android.graphics.Rect;
import android.os.Bundle;
import android.text.TextPaint;
import android.view.ViewStructure;


/**
 * TODO This class is temporary. It will be deleted once we update Webview APK to use the
 * new ViewStructure method.
 * @hide
 */
public class ViewAssistStructure extends android.view.ViewAssistStructure {

    private ViewStructure mV;

    public ViewAssistStructure(ViewStructure v) {
        mV = v;
    }

    @Override
    public void setId(int id, String packageName, String typeName, String entryName) {
        mV.setId(id, packageName, typeName, entryName);
    }

    @Override
    public void setDimens(int left, int top, int scrollX, int scrollY, int width,
            int height) {
        mV.setDimens(left, top, scrollX, scrollY, width, height);
    }

    @Override
    public void setVisibility(int visibility) {
        mV.setVisibility(visibility);
    }

    @Override
    public void setAssistBlocked(boolean state) {
        mV.setAssistBlocked(state);
    }

    @Override
    public void setEnabled(boolean state) {
        mV.setEnabled(state);
    }

    @Override
    public void setClickable(boolean state) {
        mV.setClickable(state);
    }

    @Override
    public void setLongClickable(boolean state) {
        mV.setLongClickable(state);
    }

    @Override
    public void setStylusButtonPressable(boolean state) {
        mV.setStylusButtonPressable(state);
    }

    @Override
    public void setFocusable(boolean state) {
        mV.setFocusable(state);
    }

    @Override
    public void setFocused(boolean state) {
        mV.setFocused(state);
    }

    @Override
    public void setAccessibilityFocused(boolean state) {
        mV.setAccessibilityFocused(state);
    }

    @Override
    public void setCheckable(boolean state) {
        mV.setCheckable(state);
    }

    @Override
    public void setChecked(boolean state) {
        mV.setChecked(state);
    }

    @Override
    public void setSelected(boolean state) {
        mV.setSelected(state);
    }

    @Override
    public void setActivated(boolean state) {
        mV.setActivated(state);
    }

    @Override
    public void setClassName(String className) {
        mV.setClassName(className);
    }

    @Override
    public void setContentDescription(CharSequence contentDescription) {
        mV.setContentDescription(contentDescription);
    }

    @Override
    public  void setText(CharSequence text) {
        mV.setText(text);
    }

    @Override
    public  void setText(CharSequence text, int selectionStart, int selectionEnd) {
        mV.setText(text, selectionStart, selectionEnd);
    }

    @Override
    public  void setTextPaint(TextPaint paint) {
        mV.setTextPaint(paint);
    }

    @Override
    public void setTextStyle(int size, int fgColor, int bgColor, int style) {
        mV.setTextStyle(size, fgColor, bgColor, style);
    }

    @Override
    public  void setHint(CharSequence hint) {
        mV.setHint(hint);
    }

    @Override
    public CharSequence getText() {
        return mV.getText();
    }

    @Override
    public  int getTextSelectionStart() {
        return mV.getTextSelectionStart();
    }

    @Override
    public  int getTextSelectionEnd() {
        return mV.getTextSelectionEnd();
    }

    @Override
    public  CharSequence getHint() {
        return mV.getHint();
    }

    @Override
    public  Bundle getExtras() {
        return mV.getExtras();
    }

    @Override
    public  boolean hasExtras() {
        return mV.hasExtras();
    }

    @Override
    public  void setChildCount(int num) {
        mV.setChildCount(num);
    }

    @Override
    public  int getChildCount() {
        return mV.getChildCount();
    }

    @Override
    public  android.view.ViewAssistStructure newChild(int index) {
        return mV.newChild(index);
    }

    @Override
    public  android.view.ViewAssistStructure asyncNewChild(int index) {
        return mV.asyncNewChild(index);
    }

    @Override
    public  void asyncCommit() {
        mV.asyncCommit();
    }

    @Override
    public  Rect getTempRect() {
        return mV.getTempRect();
    }
}
