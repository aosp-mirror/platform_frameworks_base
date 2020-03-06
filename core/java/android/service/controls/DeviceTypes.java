/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.service.controls;

import android.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Device types for {@link Control}.
 *
 * Each {@link Control} declares a type for the device they represent. This type will be used to
 * determine icons and colors.
 * <p>
 * The type of the device may change on status updates of the {@link Control}. For example, a
 * device of {@link #TYPE_OUTLET} could be determined by the {@link ControlsProviderService} to be
 * a {@link #TYPE_COFFEE_MAKER} and change the type for that {@link Control}, therefore possibly
 * changing icon and color.
 * <p>
 * In case the device type is not know by the application but the basic function is, or there is no
 * provided type, one of the generic types (those starting with {@code TYPE_GENERIC}) can be used.
 * These will provide an identifiable icon based on the basic function of the device.
 */
public class DeviceTypes {

    // Update this when adding new concrete types. Does not count TYPE_UNKNOWN
    private static final int NUM_CONCRETE_TYPES = 52;

    public static final @DeviceType int TYPE_UNKNOWN = 0;
    public static final @DeviceType int TYPE_AC_HEATER = 1;
    public static final @DeviceType int TYPE_AC_UNIT = 2;
    public static final @DeviceType int TYPE_AIR_FRESHENER = 3;
    public static final @DeviceType int TYPE_AIR_PURIFIER = 4;
    public static final @DeviceType int TYPE_COFFEE_MAKER = 5;
    public static final @DeviceType int TYPE_DEHUMIDIFIER = 6;
    public static final @DeviceType int TYPE_DISPLAY = 7;
    public static final @DeviceType int TYPE_FAN = 8;
    public static final @DeviceType int TYPE_HOOD = 10;
    public static final @DeviceType int TYPE_HUMIDIFIER = 11;
    public static final @DeviceType int TYPE_KETTLE = 12;
    public static final @DeviceType int TYPE_LIGHT = 13;
    public static final @DeviceType int TYPE_MICROWAVE = 14;
    public static final @DeviceType int TYPE_OUTLET = 15;
    public static final @DeviceType int TYPE_RADIATOR = 16;
    public static final @DeviceType int TYPE_REMOTE_CONTROL = 17;
    public static final @DeviceType int TYPE_SET_TOP = 18;
    public static final @DeviceType int TYPE_STANDMIXER = 19;
    public static final @DeviceType int TYPE_STYLER = 20;
    public static final @DeviceType int TYPE_SWITCH = 21;
    public static final @DeviceType int TYPE_TV = 22;
    public static final @DeviceType int TYPE_WATER_HEATER = 23;

    public static final @DeviceType int TYPE_DISHWASHER = 24;
    public static final @DeviceType int TYPE_DRYER = 25;
    public static final @DeviceType int TYPE_MOP = 26;
    public static final @DeviceType int TYPE_MOWER = 27;
    public static final @DeviceType int TYPE_MULTICOOKER = 28;
    public static final @DeviceType int TYPE_SHOWER = 29;
    public static final @DeviceType int TYPE_SPRINKLER = 30;
    public static final @DeviceType int TYPE_WASHER = 31;
    public static final @DeviceType int TYPE_VACUUM = 32;

    public static final @DeviceType int TYPE_AWNING = 33;
    public static final @DeviceType int TYPE_BLINDS = 34;
    public static final @DeviceType int TYPE_CLOSET = 35;
    public static final @DeviceType int TYPE_CURTAIN = 36;
    public static final @DeviceType int TYPE_DOOR = 37;
    public static final @DeviceType int TYPE_DRAWER = 38;
    public static final @DeviceType int TYPE_GARAGE = 39;
    public static final @DeviceType int TYPE_GATE = 40;
    public static final @DeviceType int TYPE_PERGOLA = 41;
    public static final @DeviceType int TYPE_SHUTTER = 42;
    public static final @DeviceType int TYPE_WINDOW = 43;
    public static final @DeviceType int TYPE_VALVE = 44;

    public static final @DeviceType int TYPE_LOCK = 45;

    public static final @DeviceType int TYPE_SECURITY_SYSTEM = 46;

    public static final @DeviceType int TYPE_HEATER = 47;
    public static final @DeviceType int TYPE_REFRIGERATOR = 48;
    public static final @DeviceType int TYPE_THERMOSTAT = 49;

    public static final @DeviceType int TYPE_CAMERA = 50;
    public static final @DeviceType int TYPE_DOORBELL = 51;

    /*
     * Also known as macros, routines can aggregate a series of actions across multiple devices
     */
    public static final @DeviceType int TYPE_ROUTINE = 52;

    // Update this when adding new generic types.
    private static final int NUM_GENERIC_TYPES = 7;
    public static final @DeviceType int TYPE_GENERIC_ON_OFF = -1;
    public static final @DeviceType int TYPE_GENERIC_START_STOP = -2;
    public static final @DeviceType int TYPE_GENERIC_OPEN_CLOSE = -3;
    public static final @DeviceType int TYPE_GENERIC_LOCK_UNLOCK = -4;
    public static final @DeviceType int TYPE_GENERIC_ARM_DISARM = -5;
    public static final @DeviceType int TYPE_GENERIC_TEMP_SETTING = -6;
    public static final @DeviceType int TYPE_GENERIC_VIEWSTREAM = -7;

    public static boolean validDeviceType(int deviceType) {
        return deviceType >= -NUM_GENERIC_TYPES && deviceType <= NUM_CONCRETE_TYPES;
    }

    /**
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            TYPE_GENERIC_ON_OFF,
            TYPE_GENERIC_START_STOP,
            TYPE_GENERIC_OPEN_CLOSE,
            TYPE_GENERIC_LOCK_UNLOCK,
            TYPE_GENERIC_ARM_DISARM,
            TYPE_GENERIC_TEMP_SETTING,
            TYPE_GENERIC_VIEWSTREAM,

            TYPE_UNKNOWN,

            TYPE_AC_HEATER,
            TYPE_AC_UNIT,
            TYPE_AIR_FRESHENER,
            TYPE_AIR_PURIFIER,
            TYPE_COFFEE_MAKER,
            TYPE_DEHUMIDIFIER,
            TYPE_DISPLAY,
            TYPE_FAN,
            TYPE_HOOD,
            TYPE_HUMIDIFIER,
            TYPE_KETTLE,
            TYPE_LIGHT,
            TYPE_MICROWAVE,
            TYPE_OUTLET,
            TYPE_RADIATOR,
            TYPE_REMOTE_CONTROL,
            TYPE_SET_TOP,
            TYPE_STANDMIXER,
            TYPE_STYLER,
            TYPE_SWITCH,
            TYPE_TV,
            TYPE_WATER_HEATER,
            TYPE_DISHWASHER,
            TYPE_DRYER,
            TYPE_MOP,
            TYPE_MOWER,
            TYPE_MULTICOOKER,
            TYPE_SHOWER,
            TYPE_SPRINKLER,
            TYPE_WASHER,
            TYPE_VACUUM,
            TYPE_AWNING,
            TYPE_BLINDS,
            TYPE_CLOSET,
            TYPE_CURTAIN,
            TYPE_DOOR,
            TYPE_DRAWER,
            TYPE_GARAGE,
            TYPE_GATE,
            TYPE_PERGOLA,
            TYPE_SHUTTER,
            TYPE_WINDOW,
            TYPE_VALVE,
            TYPE_LOCK,
            TYPE_SECURITY_SYSTEM,
            TYPE_HEATER,
            TYPE_REFRIGERATOR,
            TYPE_THERMOSTAT,
            TYPE_CAMERA,
            TYPE_DOORBELL,
            TYPE_ROUTINE
            })
    public @interface DeviceType {}

    private DeviceTypes() {}
}
