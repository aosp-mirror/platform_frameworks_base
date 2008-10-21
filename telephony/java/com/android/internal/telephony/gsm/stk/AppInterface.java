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

/**
 * Interface for communication from Apps to STK Service
 *
 * {@hide}
 */
public interface AppInterface {
    /**
     * STK state
     */
    public enum State {
        /**
         * Idle state
         */
        IDLE,
        /**
         * Idle but main menu exists. Menu selection should be done by calling {@code
         * notifyMenuSelection()}.
         */
        MAIN_MENU,
        /**
         * Waiting for a key input. Key input should be notified by calling
         * {@code notifyInkey()}.
         */
        GET_INKEY,
        /**
         * Waiting for a user input. Text input should be notified by calling
         * {@code notifyInput()}.
         */
        GET_INPUT,
        /**
         * Waiting for a user selection. The selection should be notified by
         * calling {@code notifySelectedItem()}.
         */
        SELECT_ITEM,
        /**
         * Waiting for the user to accept or reject a call. It should be
         * notified by calling {@code acceptOrRejectCall()}.
         */
        CALL_SETUP,
        /**
         * Waiting for user to confirm Display Text message.
         * notified by calling {@code notifyDisplayTextEnded()}.
         */
        DISPLAY_TEXT,
        /**
         * Waiting for user to confirm launching the browser.
         * notified by calling {@code notifyLaunchedBrowserConfirmed()}.
         */
        LAUNCH_BROWSER,
        /**
         * Waiting for the application to play the requested tone.
         */
        PLAY_TONE
    }

    /**
     * Sets the {@link CommandListener CommandListener} object that is used for
     * notifying of proactive commands or events from the SIM/RIL.
     *
     * @param l CommandListener object that handles the proactive commands and
     *          events from the SIM/RIL.
     */
    void setCommandListener(CommandListener l);

    /**
     * Gets the current state of STK service.
     * @return The current state.
     */
    State getState();

    /**
     * Gets the main menu that has been setup by the SIM.
     *
     * @return The main menu that has been setup by the SIM. It can be null.
     */
    Menu getCurrentMenu();

    /**
     * Notifies the SIM of the menu selection among a set of menu options
     * supplied by the SIM using SET UP MENU.
     *
     * @param menuId        ID of the selected menu item. It can be between 1 and
     *                      255.
     * @param helpRequired True if just help information is requested on a menu
     *                      item rather than menu selection. False if the menu item
     *                      is actually selected.
     */
    void notifyMenuSelection(int menuId, boolean helpRequired);

    /**
     * Notifies the SIM that a user activity has occurred. It is actually sent
     * to the SIM when it has registered to be notified of this event via SET
     * UP EVENT LIST command.
     */
    void notifyUserActivity();

    /**
     * Notifies the SIM that the idle screen is available. It is actually sent
     * to the SIM when it has registered to be notified of this event via SET
     * UP EVENT LIST command.
     */
    void notifyIdleScreenAvailable();

    /**
     * Notifies the SIM that the currently used language has changed. It is
     * actually sent to the SIM when it has registered to be notified of this
     * event via SET UP EVENT LIST command.
     *
     * @param langCode Language code of the currently selected language.
     *                 Language code is defined in ISO 639. This must be a
     *                 string of two characters.
     */
    void notifyLanguageSelection(String langCode);

    /**
     * Notifies the SIM that the browser is terminated. It is actually sent to
     * the SIM when it has registered to be notified of this event via SET UP
     * EVENT LIST command.
     *
     * @param isErrorTermination True if the cause is "Error Termination",
     *                           false if the cause is "User Termination".
     */
    void notifyBrowserTermination(boolean isErrorTermination);

    /**
     * Notifies the SIM about the launch browser confirmation. This method 
     * should be called only after the application gets notified by {@code
     * CommandListener.onLaunchBrowser()} or inside that method.
     *
     * @param userConfirmed True if user choose to confirm browser launch,
     *                    False if user choose not to confirm browser launch.
     */
    void notifyLaunchBrowser(boolean userConfirmed);

    /**
     * Notifies the SIM that a tone had been played. This method should be called
     * only after the application gets notified by {@code
     * CommandListener.onPlayTone()} or inside that method.
     *
     */
    void notifyToneEnded();

    /**
     * Notifies the SIM that the user input a text. This method should be
     * called only after the application gets notified by {@code
     * CommandListener.onGetInput()} or inside that method.
     *
     * @param input         The text string that the user has typed.
     * @param helpRequired  True if just help information is requested on a menu
     *                      item rather than menu selection. False if the menu 
     *                      item is actually selected.
     */
    void notifyInput(String input, boolean helpRequired);

    /**
     * Notifies the SIM that the user input a key in Yes/No scenario. 
     * This method should be called only after the application gets notified by 
     * {@code CommandListener.onGetInkey()} or inside that method.
     *
     * @param yesNoResponse User's choice for Yes/No scenario.
     * @param helpRequired  True if just help information is requested on a menu
     *                      item rather than menu selection. False if the menu 
     *                      item is actually selected.
     */
    void notifyInkey(boolean yesNoResponse, boolean helpRequired);

    /**
     * Notifies the SIM that the user input a key. This method should be called
     * only after the application gets notified by {@code
     * CommandListener.onGetInkey()} or inside that method.
     *
     * @param key           The key that the user has typed. If the SIM required
     * @param helpRequired  True if just help information is requested on a menu
     *                      item rather than menu selection. False if the menu 
     *                      item is actually selected.
     */
    void notifyInkey(char key, boolean helpRequired);

    /**
     * Notifies the SIM that no response was received from the user.
     */
    void notifyNoResponse();

    /**
     * Send terminal response for backward move in the proactive SIM session
     * requested by the user
     *
     * Only available when responding following proactive commands
     *      DISPLAY_TEXT(0x21),
     *      GET_INKEY(0x22),
     *      GET_INPUT(0x23),
     *      SET_UP_MENU(0x25);
     *
     * @return true if stk can send backward move response
     */
    boolean backwardMove();

    /**
     * Send terminal response for proactive SIM session terminated by the user
     *
     * Only available when responding following proactive commands
     *      DISPLAY_TEXT(0x21),
     *      GET_INKEY(0x22),
     *      GET_INPUT(0x23),
     *      PLAY_TONE(0x20),
     *      SET_UP_MENU(0x25);
     *
     * @return true if stk can send terminate session response
     */
    boolean terminateSession();

    /**
     * Notifies the SIM that the user selected an item. This method should be
     * called only after the application gets notified by {@code
     * CommandListener.onSelectItem()} or inside that method.
     *
     * @param id        The menu item that the user has selected.
     * @param wantsHelp Indicates if the user requested help for the id item.
     */
    void notifySelectedItem(int id, boolean wantsHelp);
    
    /**
     * Notifies the SIM that No response was received from the user for display 
     * text message dialog.
     * 
     * * @param terminationCode indication for display text termination. Uses  
     * {@code ResultCode } values.
     */
    public void notifyDisplayTextEnded(ResultCode terminationCode);

    /**
     * Notifies the SIM whether the user accepted the call or not. This method
     * should be called only after the application gets notified by {@code
     * CommandListener.onCallSetup()} or inside that method.
     *
     * @param accept True if the user has accepted the call, false if not.
     */
    void acceptOrRejectCall(boolean accept);
}
