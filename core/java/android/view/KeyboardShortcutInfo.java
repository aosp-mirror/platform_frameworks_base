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
package android.view;

import android.annotation.Nullable;
import android.graphics.drawable.Icon;
import android.os.Parcel;
import android.os.Parcelable;

import static com.android.internal.util.Preconditions.checkArgument;
import static java.lang.Character.MIN_VALUE;

/**
 * Information about a Keyboard Shortcut.
 */
public final class KeyboardShortcutInfo implements Parcelable {
    private final CharSequence mLabel;
    private final Icon mIcon;
    private final char mBaseCharacter;
    private final int mModifiers;

    /**
     * @param label The label that identifies the action performed by this shortcut.
     * @param icon An icon that identifies the action performed by this shortcut.
     * @param baseCharacter The character that triggers the shortcut.
     * @param modifiers The set of modifiers that, combined with the key, trigger the shortcut.
     *     These should be a combination of {@link KeyEvent#META_CTRL_ON},
     *     {@link KeyEvent#META_SHIFT_ON}, {@link KeyEvent#META_META_ON} and
     *     {@link KeyEvent#META_ALT_ON}.
     *
     * @hide
     */
    public KeyboardShortcutInfo(
            @Nullable CharSequence label, @Nullable Icon icon, char baseCharacter, int modifiers) {
        mLabel = label;
        mIcon = icon;
        checkArgument(baseCharacter != MIN_VALUE);
        mBaseCharacter = baseCharacter;
        mModifiers = modifiers;
    }

    /**
     * Convenience constructor for shortcuts with a label and no icon.
     *
     * @param label The label that identifies the action performed by this shortcut.
     * @param baseCharacter The character that triggers the shortcut.
     * @param modifiers The set of modifiers that, combined with the key, trigger the shortcut.
     *     These should be a combination of {@link KeyEvent#META_CTRL_ON},
     *     {@link KeyEvent#META_SHIFT_ON}, {@link KeyEvent#META_META_ON} and
     *     {@link KeyEvent#META_ALT_ON}.
     */
    public KeyboardShortcutInfo(CharSequence label, char baseCharacter, int modifiers) {
        mLabel = label;
        checkArgument(baseCharacter != MIN_VALUE);
        mBaseCharacter = baseCharacter;
        mModifiers = modifiers;
        mIcon = null;
    }

    private KeyboardShortcutInfo(Parcel source) {
        mLabel = source.readCharSequence();
        mIcon = (Icon) source.readParcelable(null);
        mBaseCharacter = (char) source.readInt();
        mModifiers = source.readInt();
    }

    /**
     * Returns the label to be used to describe this shortcut.
     */
    @Nullable
    public CharSequence getLabel() {
        return mLabel;
    }

    /**
     * Returns the icon to be used to describe this shortcut.
     *
     * @hide
     */
    @Nullable
    public Icon getIcon() {
        return mIcon;
    }

    /**
     * Returns the base character that, combined with the modifiers, triggers this shortcut.
     */
    public char getBaseCharacter() {
        return mBaseCharacter;
    }

    /**
     * Returns the set of modifiers that, combined with the key, trigger this shortcut.
     */
    public int getModifiers() {
        return mModifiers;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeCharSequence(mLabel);
        dest.writeParcelable(mIcon, 0);
        dest.writeInt(mBaseCharacter);
        dest.writeInt(mModifiers);
    }

    public static final Creator<KeyboardShortcutInfo> CREATOR =
            new Creator<KeyboardShortcutInfo>() {
        public KeyboardShortcutInfo createFromParcel(Parcel source) {
            return new KeyboardShortcutInfo(source);
        }
        public KeyboardShortcutInfo[] newArray(int size) {
            return new KeyboardShortcutInfo[size];
        }
    };
}