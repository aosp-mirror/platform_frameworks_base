package com.android.server.hdmi;

/**
 * Defines constants related to MHL protocol internal implementation.
 */
final class MhlConstants {
    // --------------------------------------------------
    // MHL sub command message types.
    static final int MSG_MSGE  = 0x02;
    static final int MSG_RCP   = 0x10;
    static final int MSG_RCPK  = 0x11;
    static final int MSG_RCPE  = 0x12;
    static final int MSG_RAP   = 0x20;
    static final int MSG_RAPK  = 0x21;

    // MHL RAP messages.
    static final int RAP_ACTION_POLL = 0x00;
    static final int RAP_ACTION_CONTENT_ON = 0x10;
    static final int RAP_ACTION_CONTENT_OFF = 0x11;

    // MHL RAPK messages.
    static final int RAPK_NO_ERROR = 0x00;
    static final int RAPK_UNRECOGNIZED_ACTION = 0x01;
    static final int RAPK_UNSUPPORTED_ACTION = 0x02;
    static final int RAPK_RESPONDER_BUSY = 0x03;

    static final int INVALID_ADOPTER_ID = -1;
    static final int INVALID_DEVICE_ID = -1;

    static final int CBUS_MODE_OCBUS = 1;
    static final int CBUS_MODE_ECBUS_S = 2;
    static final int CBUS_MODE_ECBUS_D = 3;

    // MHL RCPE messages
    static final int RCPE_NO_ERROR = 0x00;
    static final int RCPE_INEFFECTIVE_KEYCODE = 0x01;
    static final int RCPE_RESPONDER_BUSY = 0x02;

    private MhlConstants() { /* cannot be instantiated */ }
}
