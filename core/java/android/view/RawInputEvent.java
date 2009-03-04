/**
 * 
 */
package android.view;

/**
 * @hide
 * This really belongs in services.jar; WindowManagerPolicy should go there too.
 */
public class RawInputEvent {
    // Event class as defined by EventHub.
    public static final int CLASS_KEYBOARD = 0x00000001;
    public static final int CLASS_ALPHAKEY = 0x00000002;
    public static final int CLASS_TOUCHSCREEN = 0x00000004;
    public static final int CLASS_TRACKBALL = 0x00000008;
    
    // More special classes for QueuedEvent below.
    public static final int CLASS_CONFIGURATION_CHANGED = 0x10000000;
    
    // Event types.

    public static final int EV_SYN = 0x00;
    public static final int EV_KEY = 0x01;
    public static final int EV_REL = 0x02;
    public static final int EV_ABS = 0x03;
    public static final int EV_MSC = 0x04;
    public static final int EV_SW = 0x05;
    public static final int EV_LED = 0x11;
    public static final int EV_SND = 0x12;
    public static final int EV_REP = 0x14;
    public static final int EV_FF = 0x15;
    public static final int EV_PWR = 0x16;
    public static final int EV_FF_STATUS = 0x17;

    // Platform-specific event types.
    
    public static final int EV_DEVICE_ADDED = 0x10000000;
    public static final int EV_DEVICE_REMOVED = 0x20000000;
    
    // Special key (EV_KEY) scan codes for pointer buttons.

    public static final int BTN_FIRST = 0x100;

    public static final int BTN_MISC = 0x100;
    public static final int BTN_0 = 0x100;
    public static final int BTN_1 = 0x101;
    public static final int BTN_2 = 0x102;
    public static final int BTN_3 = 0x103;
    public static final int BTN_4 = 0x104;
    public static final int BTN_5 = 0x105;
    public static final int BTN_6 = 0x106;
    public static final int BTN_7 = 0x107;
    public static final int BTN_8 = 0x108;
    public static final int BTN_9 = 0x109;

    public static final int BTN_MOUSE = 0x110;
    public static final int BTN_LEFT = 0x110;
    public static final int BTN_RIGHT = 0x111;
    public static final int BTN_MIDDLE = 0x112;
    public static final int BTN_SIDE = 0x113;
    public static final int BTN_EXTRA = 0x114;
    public static final int BTN_FORWARD = 0x115;
    public static final int BTN_BACK = 0x116;
    public static final int BTN_TASK = 0x117;

    public static final int BTN_JOYSTICK = 0x120;
    public static final int BTN_TRIGGER = 0x120;
    public static final int BTN_THUMB = 0x121;
    public static final int BTN_THUMB2 = 0x122;
    public static final int BTN_TOP = 0x123;
    public static final int BTN_TOP2 = 0x124;
    public static final int BTN_PINKIE = 0x125;
    public static final int BTN_BASE = 0x126;
    public static final int BTN_BASE2 = 0x127;
    public static final int BTN_BASE3 = 0x128;
    public static final int BTN_BASE4 = 0x129;
    public static final int BTN_BASE5 = 0x12a;
    public static final int BTN_BASE6 = 0x12b;
    public static final int BTN_DEAD = 0x12f;

    public static final int BTN_GAMEPAD = 0x130;
    public static final int BTN_A = 0x130;
    public static final int BTN_B = 0x131;
    public static final int BTN_C = 0x132;
    public static final int BTN_X = 0x133;
    public static final int BTN_Y = 0x134;
    public static final int BTN_Z = 0x135;
    public static final int BTN_TL = 0x136;
    public static final int BTN_TR = 0x137;
    public static final int BTN_TL2 = 0x138;
    public static final int BTN_TR2 = 0x139;
    public static final int BTN_SELECT = 0x13a;
    public static final int BTN_START = 0x13b;
    public static final int BTN_MODE = 0x13c;
    public static final int BTN_THUMBL = 0x13d;
    public static final int BTN_THUMBR = 0x13e;

    public static final int BTN_DIGI = 0x140;
    public static final int BTN_TOOL_PEN = 0x140;
    public static final int BTN_TOOL_RUBBER = 0x141;
    public static final int BTN_TOOL_BRUSH = 0x142;
    public static final int BTN_TOOL_PENCIL = 0x143;
    public static final int BTN_TOOL_AIRBRUSH = 0x144;
    public static final int BTN_TOOL_FINGER = 0x145;
    public static final int BTN_TOOL_MOUSE = 0x146;
    public static final int BTN_TOOL_LENS = 0x147;
    public static final int BTN_TOUCH = 0x14a;
    public static final int BTN_STYLUS = 0x14b;
    public static final int BTN_STYLUS2 = 0x14c;
    public static final int BTN_TOOL_DOUBLETAP = 0x14d;
    public static final int BTN_TOOL_TRIPLETAP = 0x14e;

    public static final int BTN_WHEEL = 0x150;
    public static final int BTN_GEAR_DOWN = 0x150;
    public static final int BTN_GEAR_UP = 0x151;

    public static final int BTN_LAST = 0x15f;

    // Relative axes (EV_REL) scan codes.

    public static final int REL_X = 0x00;
    public static final int REL_Y = 0x01;
    public static final int REL_Z = 0x02;
    public static final int REL_RX = 0x03;
    public static final int REL_RY = 0x04;
    public static final int REL_RZ = 0x05;
    public static final int REL_HWHEEL = 0x06;
    public static final int REL_DIAL = 0x07;
    public static final int REL_WHEEL = 0x08;
    public static final int REL_MISC = 0x09;
    public static final int REL_MAX = 0x0f;

    // Absolute axes (EV_ABS) scan codes.

    public static final int ABS_X = 0x00;
    public static final int ABS_Y = 0x01;
    public static final int ABS_Z = 0x02;
    public static final int ABS_RX = 0x03;
    public static final int ABS_RY = 0x04;
    public static final int ABS_RZ = 0x05;
    public static final int ABS_THROTTLE = 0x06;
    public static final int ABS_RUDDER = 0x07;
    public static final int ABS_WHEEL = 0x08;
    public static final int ABS_GAS = 0x09;
    public static final int ABS_BRAKE = 0x0a;
    public static final int ABS_HAT0X = 0x10;
    public static final int ABS_HAT0Y = 0x11;
    public static final int ABS_HAT1X = 0x12;
    public static final int ABS_HAT1Y = 0x13;
    public static final int ABS_HAT2X = 0x14;
    public static final int ABS_HAT2Y = 0x15;
    public static final int ABS_HAT3X = 0x16;
    public static final int ABS_HAT3Y = 0x17;
    public static final int ABS_PRESSURE = 0x18;
    public static final int ABS_DISTANCE = 0x19;
    public static final int ABS_TILT_X = 0x1a;
    public static final int ABS_TILT_Y = 0x1b;
    public static final int ABS_TOOL_WIDTH = 0x1c;
    public static final int ABS_VOLUME = 0x20;
    public static final int ABS_MISC = 0x28;
    public static final int ABS_MAX = 0x3f;

    public int deviceId;
    public int type;
    public int scancode;
    public int keycode;
    public int flags;
    public int value;
    public long when;
}
