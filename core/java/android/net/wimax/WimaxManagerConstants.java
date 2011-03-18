package android.net.wimax;

/**
 * {@hide}
 */
public class WimaxManagerConstants
{

    /**
     * Used by android.net.wimax.WimaxManager for handling management of
     * Wimax access.
     */
    public static final String WIMAX_SERVICE = "WiMax";

    /**
     * Broadcast intent action indicating that Wimax has been enabled, disabled,
     * enabling, disabling, or unknown. One extra provides this state as an int.
     * Another extra provides the previous state, if available.
     */
    public static final String WIMAX_ENABLED_STATUS_CHANGED =
        "android.net.wimax.WIMAX_STATUS_CHANGED";

    /**
     * The lookup key for an int that indicates whether Wimax is enabled,
     * disabled, enabling, disabling, or unknown.
     */
    public static final String EXTRA_WIMAX_STATUS = "wimax_status";

    /**
     * Broadcast intent action indicating that Wimax state has been changed
     * state could be scanning, connecting, connected, disconnecting, disconnected
     * initializing, initialized, unknown and ready. One extra provides this state as an int.
     * Another extra provides the previous state, if available.
     */
    public static final String  WIMAX_STATE_CHANGED_ACTION =
        "android.net.wimax.WIMAX_STATE_CHANGE";

    /**
     * Broadcast intent action indicating that Wimax signal level has been changed.
     * Level varies from 0 to 3.
     */
    public static final String SIGNAL_LEVEL_CHANGED_ACTION =
        "android.net.wimax.SIGNAL_LEVEL_CHANGED";

    /**
     * The lookup key for an int that indicates whether Wimax state is
     * scanning, connecting, connected, disconnecting, disconnected
     * initializing, initialized, unknown and ready.
     */
    public static final String EXTRA_WIMAX_STATE = "WimaxState";

    /**
     * The lookup key for an int that indicates whether state of Wimax
     * is idle.
     */
    public static final String EXTRA_WIMAX_STATE_DETAIL = "WimaxStateDetail";

    /**
     * The lookup key for an int that indicates Wimax signal level.
     */
    public static final String EXTRA_NEW_SIGNAL_LEVEL = "newSignalLevel";

    /**
     * Indicatates Wimax is disabled.
     */
    public static final int WIMAX_STATUS_DISABLED = 1;

    /**
     * Indicatates Wimax is enabled.
     */
    public static final int WIMAX_STATUS_ENABLED = 3;

    /**
     * Indicatates Wimax status is known.
     */
    public static final int WIMAX_STATUS_UNKNOWN = 4;

    /**
     * Indicatates Wimax is in idle state.
     */
    public static final int WIMAX_IDLE = 6;

    /**
     * Indicatates Wimax is being deregistered.
     */
    public static final int WIMAX_DEREGISTRATION = 8;

    /**
     * Indicatates wimax state is unknown.
     */
    public static final int WIMAX_STATE_UNKNOWN = 0;

    /**
     * Indicatates wimax state is connected.
     */
    public static final int WIMAX_STATE_CONNECTED = 7;

    /**
     * Indicatates wimax state is disconnected.
     */
    public static final int WIMAX_STATE_DISCONNECTED = 9;

}
