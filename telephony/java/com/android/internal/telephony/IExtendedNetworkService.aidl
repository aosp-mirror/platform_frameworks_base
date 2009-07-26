package com.android.internal.telephony;

/**
 * Interface used to interact with extended MMI/USSD network service.
 */
interface IExtendedNetworkService {
    /**
     * Set a MMI/USSD command to ExtendedNetworkService for further process.
     * This should be called when a MMI command is placed from panel.
     * @param number the dialed MMI/USSD number.
     */
    void setMmiString(String number);

    /**
     * return the specific string which is used to prompt MMI/USSD is running
     */
    CharSequence getMmiRunningText();

    /**
     * Get specific message which should be displayed on pop-up dialog.
     * @param text original MMI/USSD message response from framework
     * @return specific user message correspond to text. null stands for no pop-up dialog need to show.
     */
    CharSequence getUserMessage(CharSequence text);

    /**
     * Clear pre-set MMI/USSD command.
     * This should be called when user cancel a pre-dialed MMI command.
     */
    void clearMmiString();
}
