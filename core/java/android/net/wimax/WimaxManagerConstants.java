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
    public static final String WIMAX_SERVICE="WiMax";

    /**
     * Broadcast intent action indicating that Wimax has been enabled, disabled,
     * enabling, disabling, or unknown. One extra provides this state as an int.
     * Another extra provides the previous state, if available.
     */
    public static final String WIMAX_STATUS_CHANGED_ACTION
            = "android.net.wimax.WIMAX_STATUS_CHANGED";

    /**
     * The lookup key for an int that indicates whether Wimax is enabled,
     * disabled, enabling, disabling, or unknown.
     */
    public static final String EXTRA_WIMAX_STATUS = "wimax_status";

    /**
     * Broadcast intent action indicating that Wimax data has been recieved, sent. One extra
     * provides the state as int.
     */
    public static final String WIMAX_DATA_USED_ACTION = "android.net.wimax.WIMAX_DATA_USED";

    /**
     * The lookup key for an int that indicates whether Wimax is data is being recieved or sent,
     * up indicates data is being sent and down indicates data being recieved.
     */
    public static final String EXTRA_UP_DOWN_DATA = "upDownData";

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
    * Indicatates no data on wimax.
    */
    public static final int NO_DATA = 0;

    /**
     * Indicatates data is being sent.
     */
    public static final int UP_DATA = 1;

    /**
     * Indicatates dats is being revieved.
     */
    public static final int DOWN_DATA = 2;

    /**
     * Indicatates data is being recieved and sent simultaneously.
     */
    public static final int UP_DOWN_DATA = 3;
}
