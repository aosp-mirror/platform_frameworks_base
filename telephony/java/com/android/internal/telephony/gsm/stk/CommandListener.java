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

package com.android.internal.telephony.gsm.stk;

import android.graphics.Bitmap;

import java.util.BitSet;
import java.util.List;


/**
 * Interface for command notification from STK Service to Apps
 *
 * {@hide}
 */
public interface CommandListener {

    /**
     * Call back function to be called when the session with the SIM ends.
     * Application must go back to the main SIM Toolkit application screen when
     * this is called.
     */
    void onSessionEnd();

    /**
     * Call back function to be called when the SIM wants a call to be set up.
     * Application must call {@code AppInterface.acceptOrRejectCall()} after
     * this method returns or inside this method.
     * 
     * @param confirmMsg     User confirmation phase message.
     * @param textAttrs      List of text attributes to be applied. Can be null.
     * @param callMsg        Call set up phase message.
     */
    void onCallSetup(String confirmMsg, List<TextAttribute> textAttrs,
            String callMsg);

    /**
     * Call back function to be called for handling DISPLAY_TEXT proactive
     * commands.
     * @param text           A text to be displayed
     * @param textAttrs      List of text attributes to be applied. Can be null.
     * @param isHighPriority High priority
     * @param userClear      Wait for user to clear message if true, clear
     *                       message after a delay if false.
     */
    void onDisplayText(String text, List<TextAttribute> textAttrs,
            boolean isHighPriority, boolean userClear, boolean responseNeeded,
            Bitmap icon);

    /**
     * Call back function to be called for handling SET_UP_MENU proactive
     * commands. The menu can be retrieved by calling {@code
     * AppInterface.getMainMenu}.
     * 
     * @param menu application main menu.
     */
    void onSetUpMenu(Menu menu);

    /**
     * Call back function to be called for handling GET_INKEY proactive
     * commands.
     * Application must call {@code AppInterface.notifyInkey()} after this
     * method returns or inside this method.
     *
     * @param text      A text to be used as a prompt.
     * @param textAttrs List of text attributes to be applied. Can be null.
     * @param yesNo     "Yes/No" response is requested if true. When this is
     *                  true, {@code digitOnly} and {@code ucs2} are ignored.
     * @param digitOnly Digits (0 to 9, *, # and +) only if true. Alphabet set
     *                  if false.
     * @param ucs2      UCS2 alphabet if true, SMS default alphabet if false.
     * @param immediateResponse An immediate digit response (0 to 9, * and #)
     *                  is required if true. User response shall be displayed
     *                  and the terminal may allow alteration and/or
     *                  confirmation if false.
     * @param helpAvailable Help information available.
     */
    void onGetInkey(String text, List<TextAttribute> textAttrs, boolean yesNo, boolean digitOnly,
            boolean ucs2, boolean immediateResponse, boolean helpAvailable);
    /**
     * Call back function to be called for handling GET_INPUT proactive
     * commands. Application must call {@code AppInterface.notifyInput()} after
     * this method returns or inside this method.
     * 
     * @param text A text to be used as a prompt
     * @param defaultText A text to be used as a default input
     * @param minLen Mininum length of response (0 indicates there is no mininum
     *        length requirement).
     * @param maxLen Maximum length of response (between 0 and 0xfe).
     * @param noMaxLimit If true, there is no limit in maximum length of
     *        response.
     * @param textAttrs List of text attributes to be applied. Can be null.
     * @param digitOnly Digits (0 to 9, *, # and +) only if true. Alphabet set
     *        if false.
     * @param ucs2 UCS2 alphabet if true, SMS default alphabet if false.
     * @param echo Terminal may echo user input on the display if true. User
     *        input shall not be revealed in any way if false.
     * @param helpAvailable Help information available.
     */
    void onGetInput(String text, String defaultText, int minLen, int maxLen,
            boolean noMaxLimit, List<TextAttribute> textAttrs,
            boolean digitOnly, boolean ucs2, boolean echo, boolean helpAvailable);

    /**
     * Call back function to be called for handling SELECT_ITEM proactive
     * commands.
     * Application must call {@code AppInterface.notifySelectedItem()} after
     * this method returns or inside this method.
     *
     * @param menu Items menu.
     * @param presentationType Presentation type of the choices.
     */
    void onSelectItem(Menu menu, PresentationType presentationType);

    /**
     * Call back function to be called for handling SET_UP_EVENT_LIST proactive
     * commands.
     * @param events    BitSet object each bit of which represents an event
     *                  that UICC wants the terminal to monitor.
     *                  <ul>
     *                   <li>0x00: MT call
     *                   <li>0x01: Call connected
     *                   <li>0x02: Call disconnected
     *                   <li>0x03: Location status
     *                   <li>0x04: User activity
     *                   <li>0x05: Idle screen available
     *                   <li>0x06: Card reader status
     *                   <li>0x07: Language selection
     *                   <li>0x08: Browser termination
     *                   <li>0x09: Data available
     *                   <li>0x0A: Channel status
     *                   <li>0x0B: Access Technology Change
     *                   <li>0x0C: Display parameters changed
     *                   <li>0x0D: Local connection
     *                   <li>0x0E: Network Search Mode Change
     *                   <li>0x0F: Browsing status
     *                   <li>0x10: Frames Information Change
     *                   <li>0x11: reserved for 3GPP (I-WLAN Access Status)
     *                  </ul>
     *                  These values are defined in Service as UICC_EVENT_*.
     * @throws ResultException must be BEYOND_TERMINAL_CAPABILITY
     *         if the ME is not able to successfully accept all events
     */
    void onSetUpEventList(BitSet events) throws ResultException;

    /**
     * Call back function to be called for handling LAUNCH_BROWSER proactive
     * commands.
     * 
     * @param useDefaultUrl     If true, use the system default URL, otherwise use
     *                          {@code url} as the URL.
     * @param confirmMsg        A text to be used as the user confirmation message. Can
     *        be null.
     * @param confirmMsgAttrs   List of text attributes to be applied to {code
     *        confirmMsgAttrs}. Can be null.
     * @param mode Launch mode.
     */
    void onLaunchBrowser(String url, String confirmMsg,
            List<TextAttribute> confirmMsgAttrs, LaunchBrowserMode mode);

    /**
     * Call back function to be called for handling PLAY_TONE proactive
     * commands.
     * 
     * @param tone      Tone to be played
     * @param text      A text to be displayed. Can be null.
     * @param textAttrs List of text attributes to be applied. Can be null.
     * @param duration  Time duration to play the tone.
     * @throws ResultException
     */
    void onPlayTone(Tone tone, String text, List<TextAttribute> textAttrs,
            Duration duration) throws ResultException;
}
