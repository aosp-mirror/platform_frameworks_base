package android.accessibilityservice;

import android.accessibilityservice.IBrailleDisplayConnection;

/**
 * Interface given to a BrailleDisplayConnection to talk to a BrailleDisplayController
 * in an accessibility service.
 *
 * IPCs from system_server to apps must be oneway, so designate this entire interface as oneway.
 * @hide
 */
oneway interface IBrailleDisplayController {
    void onConnected(in IBrailleDisplayConnection connection, in byte[] hidDescriptor);
    void onConnectionFailed(int error);
    void onInput(in byte[] input);
    void onDisconnected();
}