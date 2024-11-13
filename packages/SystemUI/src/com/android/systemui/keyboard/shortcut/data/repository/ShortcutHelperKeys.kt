/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.keyboard.shortcut.data.repository

import android.content.Context
import android.view.KeyEvent.KEYCODE_ALT_LEFT
import android.view.KeyEvent.KEYCODE_ALT_RIGHT
import android.view.KeyEvent.KEYCODE_BACK
import android.view.KeyEvent.KEYCODE_BREAK
import android.view.KeyEvent.KEYCODE_BUTTON_A
import android.view.KeyEvent.KEYCODE_BUTTON_B
import android.view.KeyEvent.KEYCODE_BUTTON_C
import android.view.KeyEvent.KEYCODE_BUTTON_L1
import android.view.KeyEvent.KEYCODE_BUTTON_L2
import android.view.KeyEvent.KEYCODE_BUTTON_MODE
import android.view.KeyEvent.KEYCODE_BUTTON_R1
import android.view.KeyEvent.KEYCODE_BUTTON_R2
import android.view.KeyEvent.KEYCODE_BUTTON_SELECT
import android.view.KeyEvent.KEYCODE_BUTTON_START
import android.view.KeyEvent.KEYCODE_BUTTON_X
import android.view.KeyEvent.KEYCODE_BUTTON_Y
import android.view.KeyEvent.KEYCODE_BUTTON_Z
import android.view.KeyEvent.KEYCODE_CTRL_LEFT
import android.view.KeyEvent.KEYCODE_CTRL_RIGHT
import android.view.KeyEvent.KEYCODE_DEL
import android.view.KeyEvent.KEYCODE_DPAD_CENTER
import android.view.KeyEvent.KEYCODE_DPAD_DOWN
import android.view.KeyEvent.KEYCODE_DPAD_LEFT
import android.view.KeyEvent.KEYCODE_DPAD_RIGHT
import android.view.KeyEvent.KEYCODE_DPAD_UP
import android.view.KeyEvent.KEYCODE_EISU
import android.view.KeyEvent.KEYCODE_ENTER
import android.view.KeyEvent.KEYCODE_EQUALS
import android.view.KeyEvent.KEYCODE_ESCAPE
import android.view.KeyEvent.KEYCODE_F1
import android.view.KeyEvent.KEYCODE_F10
import android.view.KeyEvent.KEYCODE_F11
import android.view.KeyEvent.KEYCODE_F12
import android.view.KeyEvent.KEYCODE_F2
import android.view.KeyEvent.KEYCODE_F3
import android.view.KeyEvent.KEYCODE_F4
import android.view.KeyEvent.KEYCODE_F5
import android.view.KeyEvent.KEYCODE_F6
import android.view.KeyEvent.KEYCODE_F7
import android.view.KeyEvent.KEYCODE_F8
import android.view.KeyEvent.KEYCODE_F9
import android.view.KeyEvent.KEYCODE_FORWARD_DEL
import android.view.KeyEvent.KEYCODE_GRAVE
import android.view.KeyEvent.KEYCODE_HENKAN
import android.view.KeyEvent.KEYCODE_HOME
import android.view.KeyEvent.KEYCODE_INSERT
import android.view.KeyEvent.KEYCODE_KATAKANA_HIRAGANA
import android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
import android.view.KeyEvent.KEYCODE_MEDIA_NEXT
import android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
import android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
import android.view.KeyEvent.KEYCODE_MEDIA_REWIND
import android.view.KeyEvent.KEYCODE_MEDIA_STOP
import android.view.KeyEvent.KEYCODE_MINUS
import android.view.KeyEvent.KEYCODE_MOVE_END
import android.view.KeyEvent.KEYCODE_MOVE_HOME
import android.view.KeyEvent.KEYCODE_MUHENKAN
import android.view.KeyEvent.KEYCODE_NUMPAD_0
import android.view.KeyEvent.KEYCODE_NUMPAD_1
import android.view.KeyEvent.KEYCODE_NUMPAD_2
import android.view.KeyEvent.KEYCODE_NUMPAD_3
import android.view.KeyEvent.KEYCODE_NUMPAD_4
import android.view.KeyEvent.KEYCODE_NUMPAD_5
import android.view.KeyEvent.KEYCODE_NUMPAD_6
import android.view.KeyEvent.KEYCODE_NUMPAD_7
import android.view.KeyEvent.KEYCODE_NUMPAD_8
import android.view.KeyEvent.KEYCODE_NUMPAD_9
import android.view.KeyEvent.KEYCODE_NUMPAD_ADD
import android.view.KeyEvent.KEYCODE_NUMPAD_COMMA
import android.view.KeyEvent.KEYCODE_NUMPAD_DIVIDE
import android.view.KeyEvent.KEYCODE_NUMPAD_DOT
import android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
import android.view.KeyEvent.KEYCODE_NUMPAD_EQUALS
import android.view.KeyEvent.KEYCODE_NUMPAD_LEFT_PAREN
import android.view.KeyEvent.KEYCODE_NUMPAD_MULTIPLY
import android.view.KeyEvent.KEYCODE_NUMPAD_RIGHT_PAREN
import android.view.KeyEvent.KEYCODE_NUMPAD_SUBTRACT
import android.view.KeyEvent.KEYCODE_NUM_LOCK
import android.view.KeyEvent.KEYCODE_PAGE_DOWN
import android.view.KeyEvent.KEYCODE_PAGE_UP
import android.view.KeyEvent.KEYCODE_PERIOD
import android.view.KeyEvent.KEYCODE_RECENT_APPS
import android.view.KeyEvent.KEYCODE_SCROLL_LOCK
import android.view.KeyEvent.KEYCODE_SHIFT_LEFT
import android.view.KeyEvent.KEYCODE_SHIFT_RIGHT
import android.view.KeyEvent.KEYCODE_SPACE
import android.view.KeyEvent.KEYCODE_SYSRQ
import android.view.KeyEvent.KEYCODE_TAB
import android.view.KeyEvent.KEYCODE_ZENKAKU_HANKAKU
import android.view.KeyEvent.META_ALT_ON
import android.view.KeyEvent.META_CTRL_ON
import android.view.KeyEvent.META_FUNCTION_ON
import android.view.KeyEvent.META_META_ON
import android.view.KeyEvent.META_SHIFT_ON
import android.view.KeyEvent.META_SYM_ON
import com.android.systemui.res.R

object ShortcutHelperKeys {

    val keyIcons =
        mapOf(
            META_META_ON to R.drawable.ic_ksh_key_meta,
            KEYCODE_BACK to R.drawable.ic_arrow_back_2,
            KEYCODE_HOME to R.drawable.ic_radio_button_unchecked,
            KEYCODE_RECENT_APPS to R.drawable.ic_check_box_outline_blank,
        )

    val specialKeyLabels =
        mapOf<Int, (Context) -> String>(
            KEYCODE_HOME to { context -> context.getString(R.string.keyboard_key_home) },
            KEYCODE_BACK to { context -> context.getString(R.string.keyboard_key_back) },
            KEYCODE_DPAD_UP to { context -> context.getString(R.string.keyboard_key_dpad_up) },
            KEYCODE_DPAD_DOWN to { context -> context.getString(R.string.keyboard_key_dpad_down) },
            KEYCODE_DPAD_LEFT to { context -> context.getString(R.string.keyboard_key_dpad_left) },
            KEYCODE_DPAD_RIGHT to
                { context ->
                    context.getString(R.string.keyboard_key_dpad_right)
                },
            KEYCODE_DPAD_CENTER to
                { context ->
                    context.getString(R.string.keyboard_key_dpad_center)
                },
            KEYCODE_PERIOD to { "." },
            KEYCODE_TAB to { context -> context.getString(R.string.keyboard_key_tab) },
            KEYCODE_SPACE to { context -> context.getString(R.string.keyboard_key_space) },
            KEYCODE_ENTER to { context -> context.getString(R.string.keyboard_key_enter) },
            KEYCODE_DEL to { context -> context.getString(R.string.keyboard_key_backspace) },
            KEYCODE_MEDIA_PLAY_PAUSE to
                { context ->
                    context.getString(R.string.keyboard_key_media_play_pause)
                },
            KEYCODE_MEDIA_STOP to
                { context ->
                    context.getString(R.string.keyboard_key_media_stop)
                },
            KEYCODE_MEDIA_NEXT to
                { context ->
                    context.getString(R.string.keyboard_key_media_next)
                },
            KEYCODE_MEDIA_PREVIOUS to
                { context ->
                    context.getString(R.string.keyboard_key_media_previous)
                },
            KEYCODE_MEDIA_REWIND to
                { context ->
                    context.getString(R.string.keyboard_key_media_rewind)
                },
            KEYCODE_MEDIA_FAST_FORWARD to
                { context ->
                    context.getString(R.string.keyboard_key_media_fast_forward)
                },
            KEYCODE_PAGE_UP to { context -> context.getString(R.string.keyboard_key_page_up) },
            KEYCODE_PAGE_DOWN to { context -> context.getString(R.string.keyboard_key_page_down) },
            KEYCODE_BUTTON_A to
                { context ->
                    context.getString(R.string.keyboard_key_button_template, "A")
                },
            KEYCODE_BUTTON_B to
                { context ->
                    context.getString(R.string.keyboard_key_button_template, "B")
                },
            KEYCODE_BUTTON_C to
                { context ->
                    context.getString(R.string.keyboard_key_button_template, "C")
                },
            KEYCODE_BUTTON_X to
                { context ->
                    context.getString(R.string.keyboard_key_button_template, "X")
                },
            KEYCODE_BUTTON_Y to
                { context ->
                    context.getString(R.string.keyboard_key_button_template, "Y")
                },
            KEYCODE_BUTTON_Z to
                { context ->
                    context.getString(R.string.keyboard_key_button_template, "Z")
                },
            KEYCODE_BUTTON_L1 to
                { context ->
                    context.getString(R.string.keyboard_key_button_template, "L1")
                },
            KEYCODE_BUTTON_R1 to
                { context ->
                    context.getString(R.string.keyboard_key_button_template, "R1")
                },
            KEYCODE_BUTTON_L2 to
                { context ->
                    context.getString(R.string.keyboard_key_button_template, "L2")
                },
            KEYCODE_BUTTON_R2 to
                { context ->
                    context.getString(R.string.keyboard_key_button_template, "R2")
                },
            KEYCODE_BUTTON_START to
                { context ->
                    context.getString(R.string.keyboard_key_button_template, "Start")
                },
            KEYCODE_BUTTON_SELECT to
                { context ->
                    context.getString(R.string.keyboard_key_button_template, "Select")
                },
            KEYCODE_BUTTON_MODE to
                { context ->
                    context.getString(R.string.keyboard_key_button_template, "Mode")
                },
            KEYCODE_FORWARD_DEL to
                { context ->
                    context.getString(R.string.keyboard_key_forward_del)
                },
            KEYCODE_ESCAPE to { context -> context.getString(R.string.keyboard_key_esc) },
            KEYCODE_SYSRQ to { "SysRq" },
            KEYCODE_BREAK to { "Break" },
            KEYCODE_SCROLL_LOCK to { "Scroll Lock" },
            KEYCODE_MOVE_HOME to { context -> context.getString(R.string.keyboard_key_move_home) },
            KEYCODE_MOVE_END to { context -> context.getString(R.string.keyboard_key_move_end) },
            KEYCODE_INSERT to { context -> context.getString(R.string.keyboard_key_insert) },
            KEYCODE_F1 to { "F1" },
            KEYCODE_F2 to { "F2" },
            KEYCODE_F3 to { "F3" },
            KEYCODE_F4 to { "F4" },
            KEYCODE_F5 to { "F5" },
            KEYCODE_F6 to { "F6" },
            KEYCODE_F7 to { "F7" },
            KEYCODE_F8 to { "F8" },
            KEYCODE_F9 to { "F9" },
            KEYCODE_F10 to { "F10" },
            KEYCODE_F11 to { "F11" },
            KEYCODE_F12 to { "F12" },
            KEYCODE_NUM_LOCK to { context -> context.getString(R.string.keyboard_key_num_lock) },
            KEYCODE_MINUS to { "-" },
            KEYCODE_GRAVE to { "`" },
            KEYCODE_EQUALS to { "=" },
            KEYCODE_NUMPAD_0 to
                { context ->
                    context.getString(R.string.keyboard_key_numpad_template, "0")
                },
            KEYCODE_NUMPAD_1 to
                { context ->
                    context.getString(R.string.keyboard_key_numpad_template, "1")
                },
            KEYCODE_NUMPAD_2 to
                { context ->
                    context.getString(R.string.keyboard_key_numpad_template, "2")
                },
            KEYCODE_NUMPAD_3 to
                { context ->
                    context.getString(R.string.keyboard_key_numpad_template, "3")
                },
            KEYCODE_NUMPAD_4 to
                { context ->
                    context.getString(R.string.keyboard_key_numpad_template, "4")
                },
            KEYCODE_NUMPAD_5 to
                { context ->
                    context.getString(R.string.keyboard_key_numpad_template, "5")
                },
            KEYCODE_NUMPAD_6 to
                { context ->
                    context.getString(R.string.keyboard_key_numpad_template, "6")
                },
            KEYCODE_NUMPAD_7 to
                { context ->
                    context.getString(R.string.keyboard_key_numpad_template, "7")
                },
            KEYCODE_NUMPAD_8 to
                { context ->
                    context.getString(R.string.keyboard_key_numpad_template, "8")
                },
            KEYCODE_NUMPAD_9 to
                { context ->
                    context.getString(R.string.keyboard_key_numpad_template, "9")
                },
            KEYCODE_NUMPAD_DIVIDE to
                { context ->
                    context.getString(R.string.keyboard_key_numpad_template, "/")
                },
            KEYCODE_NUMPAD_MULTIPLY to
                { context ->
                    context.getString(R.string.keyboard_key_numpad_template, "*")
                },
            KEYCODE_NUMPAD_SUBTRACT to
                { context ->
                    context.getString(R.string.keyboard_key_numpad_template, "-")
                },
            KEYCODE_NUMPAD_ADD to
                { context ->
                    context.getString(R.string.keyboard_key_numpad_template, "+")
                },
            KEYCODE_NUMPAD_DOT to
                { context ->
                    context.getString(R.string.keyboard_key_numpad_template, ".")
                },
            KEYCODE_NUMPAD_COMMA to
                { context ->
                    context.getString(R.string.keyboard_key_numpad_template, ",")
                },
            KEYCODE_NUMPAD_ENTER to
                { context ->
                    context.getString(
                        R.string.keyboard_key_numpad_template,
                        context.getString(R.string.keyboard_key_enter)
                    )
                },
            KEYCODE_NUMPAD_EQUALS to
                { context ->
                    context.getString(R.string.keyboard_key_numpad_template, "=")
                },
            KEYCODE_NUMPAD_LEFT_PAREN to
                { context ->
                    context.getString(R.string.keyboard_key_numpad_template, "(")
                },
            KEYCODE_NUMPAD_RIGHT_PAREN to
                { context ->
                    context.getString(R.string.keyboard_key_numpad_template, ")")
                },
            KEYCODE_ZENKAKU_HANKAKU to { "半角/全角" },
            KEYCODE_EISU to { "英数" },
            KEYCODE_MUHENKAN to { "無変換" },
            KEYCODE_HENKAN to { "変換" },
            KEYCODE_KATAKANA_HIRAGANA to { "かな" },
            KEYCODE_ALT_LEFT to { "Alt" },
            KEYCODE_ALT_RIGHT to { "Alt" },
            KEYCODE_CTRL_LEFT to { "Ctrl" },
            KEYCODE_CTRL_RIGHT to { "Ctrl" },
            KEYCODE_SHIFT_LEFT to { "Shift" },
            KEYCODE_SHIFT_RIGHT to { "Shift" },

            // Modifiers
            META_META_ON to { "Meta" },
            META_CTRL_ON to { "Ctrl" },
            META_ALT_ON to { "Alt" },
            META_SHIFT_ON to { "Shift" },
            META_SYM_ON to { "Sym" },
            META_FUNCTION_ON to { "Fn" },
        )
}
