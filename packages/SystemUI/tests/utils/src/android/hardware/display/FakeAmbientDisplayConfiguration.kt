package android.hardware.display

import android.content.Context

class FakeAmbientDisplayConfiguration(context: Context) : AmbientDisplayConfiguration(context) {
    var fakePulseOnNotificationEnabled = true

    override fun pulseOnNotificationEnabled(user: Int) = fakePulseOnNotificationEnabled

    override fun pulseOnNotificationAvailable() = TODO("Not yet implemented")

    override fun pickupGestureEnabled(user: Int) = TODO("Not yet implemented")

    override fun dozePickupSensorAvailable() = TODO("Not yet implemented")

    override fun tapGestureEnabled(user: Int) = TODO("Not yet implemented")

    override fun tapSensorAvailable() = TODO("Not yet implemented")

    override fun doubleTapGestureEnabled(user: Int) = TODO("Not yet implemented")

    override fun doubleTapSensorAvailable() = TODO("Not yet implemented")

    override fun quickPickupSensorEnabled(user: Int) = TODO("Not yet implemented")

    override fun screenOffUdfpsEnabled(user: Int) = TODO("Not yet implemented")

    override fun wakeScreenGestureAvailable() = TODO("Not yet implemented")

    override fun wakeLockScreenGestureEnabled(user: Int) = TODO("Not yet implemented")

    override fun wakeDisplayGestureEnabled(user: Int) = TODO("Not yet implemented")

    override fun getWakeLockScreenDebounce() = TODO("Not yet implemented")

    override fun doubleTapSensorType() = TODO("Not yet implemented")

    override fun tapSensorTypeMapping() = TODO("Not yet implemented")

    override fun longPressSensorType() = TODO("Not yet implemented")

    override fun udfpsLongPressSensorType() = TODO("Not yet implemented")

    override fun quickPickupSensorType() = TODO("Not yet implemented")

    override fun pulseOnLongPressEnabled(user: Int) = TODO("Not yet implemented")

    override fun alwaysOnEnabled(user: Int) = TODO("Not yet implemented")

    override fun alwaysOnAvailable() = TODO("Not yet implemented")

    override fun alwaysOnAvailableForUser(user: Int) = TODO("Not yet implemented")

    override fun ambientDisplayComponent() = TODO("Not yet implemented")

    override fun accessibilityInversionEnabled(user: Int) = TODO("Not yet implemented")

    override fun ambientDisplayAvailable() = TODO("Not yet implemented")

    override fun dozeSuppressed(user: Int) = TODO("Not yet implemented")

    override fun disableDozeSettings(userId: Int) = TODO("Not yet implemented")

    override fun disableDozeSettings(shouldDisableNonUserConfigurable: Boolean, userId: Int) =
        TODO("Not yet implemented")

    override fun restoreDozeSettings(userId: Int) = TODO("Not yet implemented")
}
