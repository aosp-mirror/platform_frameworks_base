/*
 * Copyright 2024 The Android Open Source Project
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

package android.hardware.input;

/**
 * The StickyModifierState class is a representation of a modifier state when A11y Sticky keys
 * feature is enabled
 *
 * @hide
 */
public abstract class StickyModifierState {

    /**
     * Represents whether current sticky modifier state includes 'Shift' modifier.
     * <p> If {@code true} the next {@link android.view.KeyEvent} will contain 'Shift' modifier in
     * its metaState.
     *
     * @return whether Shift modifier key is on.
     */
    public abstract boolean isShiftModifierOn();

    /**
     * Represents whether current sticky modifier state includes 'Shift' modifier, and it is
     * locked.
     * <p> If {@code true} any subsequent {@link android.view.KeyEvent} will contain 'Shift'
     * modifier in its metaState and this state will remain sticky (will not be cleared), until
     * user presses 'Shift' key again to clear the locked state.
     *
     * @return whether Shift modifier key is locked.
     */
    public abstract boolean isShiftModifierLocked();

    /**
     * Represents whether current sticky modifier state includes 'Ctrl' modifier.
     * <p> If {@code true} the next {@link android.view.KeyEvent} will contain 'Ctrl' modifier in
     * its metaState.
     *
     * @return whether Ctrl modifier key is on.
     */
    public abstract boolean isCtrlModifierOn();

    /**
     * Represents whether current sticky modifier state includes 'Ctrl' modifier, and it is
     * locked.
     * <p> If {@code true} any subsequent {@link android.view.KeyEvent} will contain 'Ctrl'
     * modifier in its metaState and this state will remain sticky (will not be cleared), until
     * user presses 'Ctrl' key again to clear the locked state.
     *
     * @return whether Ctrl modifier key is locked.
     */
    public abstract boolean isCtrlModifierLocked();

    /**
     * Represents whether current sticky modifier state includes 'Meta' modifier.
     * <p> If {@code true} the next {@link android.view.KeyEvent} will contain 'Meta' modifier in
     * its metaState.
     *
     * @return whether Meta modifier key is on.
     */
    public abstract boolean isMetaModifierOn();

    /**
     * Represents whether current sticky modifier state includes 'Meta' modifier, and it is
     * locked.
     * <p> If {@code true} any subsequent {@link android.view.KeyEvent} will contain 'Meta'
     * modifier in its metaState and this state will remain sticky (will not be cleared), until
     * user presses 'Meta' key again to clear the locked state.
     *
     * @return whether Meta modifier key is locked.
     */
    public abstract boolean isMetaModifierLocked();

    /**
     * Represents whether current sticky modifier state includes 'Alt' modifier.
     * <p> If {@code true} the next {@link android.view.KeyEvent} will contain 'Alt' modifier in
     * its metaState.
     *
     * @return whether Alt modifier key is on.
     */
    public abstract boolean isAltModifierOn();

    /**
     * Represents whether current sticky modifier state includes 'Alt' modifier, and it is
     * locked.
     * <p> If {@code true} any subsequent {@link android.view.KeyEvent} will contain 'Alt'
     * modifier in its metaState and this state will remain sticky (will not be cleared), until
     * user presses 'Alt' key again to clear the locked state.
     *
     * @return whether Alt modifier key is locked.
     */
    public abstract boolean isAltModifierLocked();

    /**
     * Represents whether current sticky modifier state includes 'AltGr' modifier.
     * <p> If {@code true} the next {@link android.view.KeyEvent} will contain 'AltGr' modifier in
     * its metaState.
     *
     * @return whether AltGr modifier key is on.
     */
    public abstract boolean isAltGrModifierOn();

    /**
     * Represents whether current sticky modifier state includes 'AltGr' modifier, and it is
     * locked.
     * <p> If {@code true} any subsequent {@link android.view.KeyEvent} will contain 'AltGr'
     * modifier in its metaState and this state will remain sticky (will not be cleared), until
     * user presses 'AltGr' key again to clear the locked state.
     *
     * @return whether AltGr modifier key is locked.
     */
    public abstract boolean isAltGrModifierLocked();
}

