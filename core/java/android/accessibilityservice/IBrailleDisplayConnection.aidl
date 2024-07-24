package android.accessibilityservice;

/**
 * Interface given to a BrailleDisplayController to talk to a BrailleDisplayConnection
 * in system_server.
 *
 * @hide
 */
interface IBrailleDisplayConnection {
    oneway void disconnect();
    oneway void write(in byte[] output);
}