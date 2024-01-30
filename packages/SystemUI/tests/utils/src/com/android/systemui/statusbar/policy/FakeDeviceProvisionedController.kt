package com.android.systemui.statusbar.policy

class FakeDeviceProvisionedController : DeviceProvisionedController {
    @JvmField var deviceProvisioned = true

    override fun addCallback(listener: DeviceProvisionedController.DeviceProvisionedListener) {
        TODO("Not yet implemented")
    }

    override fun removeCallback(listener: DeviceProvisionedController.DeviceProvisionedListener) {
        TODO("Not yet implemented")
    }

    override fun isDeviceProvisioned() = deviceProvisioned

    override fun getCurrentUser(): Int {
        TODO("Not yet implemented")
    }

    override fun isUserSetup(user: Int): Boolean {
        TODO("Not yet implemented")
    }

    override fun isCurrentUserSetup(): Boolean {
        TODO("Not yet implemented")
    }

    override fun isFrpActive(): Boolean {
        TODO("Not yet implemented")
    }
}
