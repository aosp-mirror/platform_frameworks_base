package com.android.systemui.statusbar.policy

class FakeDeviceProvisionedController : DeviceProvisionedController {
    @JvmField var deviceProvisioned = true
    @JvmField var currentUser = 0

    private val callbacks = mutableSetOf<DeviceProvisionedController.DeviceProvisionedListener>()
    private val usersSetup = mutableSetOf<Int>()

    override fun addCallback(listener: DeviceProvisionedController.DeviceProvisionedListener) {
        callbacks.add(listener)
    }

    override fun removeCallback(listener: DeviceProvisionedController.DeviceProvisionedListener) {
        callbacks.remove(listener)
    }

    override fun isDeviceProvisioned() = deviceProvisioned

    @Deprecated("Deprecated in Java")
    override fun getCurrentUser(): Int {
        return currentUser
    }

    override fun isUserSetup(user: Int): Boolean {
        return user in usersSetup
    }

    override fun isCurrentUserSetup(): Boolean {
        return currentUser in usersSetup
    }

    override fun isFrpActive(): Boolean {
        TODO("Not yet implemented")
    }

    fun setCurrentUser(userId: Int) {
        currentUser = userId
        callbacks.toSet().forEach { it.onUserSwitched() }
    }

    fun setUserSetup(userId: Int, isSetup: Boolean = true) {
        if (isSetup) {
            usersSetup.add(userId)
        } else {
            usersSetup.remove(userId)
        }
        callbacks.toSet().forEach { it.onUserSetupChanged() }
    }

    fun setCurrentUserSetup(isSetup: Boolean) {
        setUserSetup(currentUser, isSetup)
    }
}
