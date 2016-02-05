package com.android.hotspot2.osu;

import android.net.Network;

import java.net.URL;

public interface UserInputListener {
    /**
     * Launch an appropriate application to handle user input and HTTP exchanges to the target
     * URL. Under normal circumstances this implies that a web-browser is started and pointed at
     * the target URL from which it is supposed to perform an initial HTTP GET operation.
     * This call must not block beyond the time it takes to launch the user agent, i.e. must return
     * well before the HTTP exchange terminates.
     * @param target A fully encoded URL to which to send an initial HTTP GET and then handle
     *               subsequent HTTP exchanges.
     * @param endRedirect A URL to which the user agent will be redirected upon completion of
     *                    the HTTP exchange. This parameter is for informational purposes only
     *                    as the redirect to the URL is the responsibility of the remote server.
     */
    public void requestUserInput(URL target, Network network, URL endRedirect);

    /**
     * Notification that status of the OSU operation has changed. The implementation may choose to
     * return a string that will be passed to the user agent. Please note that the string is
     * passed as the payload of (the redirect) HTTP connection to the agent and must be formatted
     * appropriately (e.g. as well formed HTML).
     * Returning a null string on the initial status update of UserInputComplete or UserInputAborted
     * will cause the local "redirect" web-server to terminate and any further strings returned will
     * be ignored.
     * If programmatic termination of the user agent is desired, it should be initiated from within
     * the implementation of this method.
     * @param status
     * @param message
     * @return
     */
    public String operationStatus(String spIdentity, OSUOperationStatus status, String message);

    /**
     * Notify the user that a de-authentication event is imminent.
     * @param ess set to indicate that the de-authentication is for an ESS instead of a BSS
     * @param delay delay the number of seconds that the user will have to wait before
     *              reassociating with the BSS or ESS.
     * @param url a URL to which to redirect the user
     */
    public void deAuthNotification(String spIdentity, boolean ess, int delay, URL url);
}
