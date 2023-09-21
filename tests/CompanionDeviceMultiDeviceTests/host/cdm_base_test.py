#!/usr/bin/env python3
# Lint as: python3
"""
Base class for setting up devices for CDM functionalities.
"""

from mobly import base_test
from mobly import utils
from mobly.controllers import android_device

CDM_SNIPPET_PACKAGE = 'android.companion.multidevices'


class BaseTestClass(base_test.BaseTestClass):

    def setup_class(self):
        # Declare that two Android devices are needed.
        self.sender, self.receiver = self.register_controller(
            android_device, min_number=2)
        self.sender_id = None
        self.receiver_id = None

        def _setup_device(device):
            device.load_snippet('cdm', CDM_SNIPPET_PACKAGE)
            device.adb.shell('input keyevent KEYCODE_WAKEUP')
            device.adb.shell('input keyevent KEYCODE_MENU')
            device.adb.shell('input keyevent KEYCODE_HOME')

            # Clean up existing associations
            device.cdm.disassociateAll()

        # Sets up devices in parallel to save time.
        utils.concurrent_exec(
            _setup_device,
            ((self.sender,), (self.receiver,)),
            max_workers=2,
            raise_on_exception=True)

    def associate_devices(self) -> tuple[int, int]:
        """Associate devices with each other and return association IDs for both"""
        # If association already exists, don't need another
        if self.sender_id and self.receiver_id:
            return (self.sender_id, self.receiver_id)

        receiver_name = self.receiver.cdm.becomeDiscoverable()
        self.receiver_id = self.sender.cdm.associate(receiver_name)

        sender_name = self.sender.cdm.becomeDiscoverable()
        self.sender_id = self.receiver.cdm.associate(sender_name)

        return (self.sender_id, self.receiver_id)

    def attach_transports(self):
        """Attach transports to both devices"""
        self.associate_devices()

        self.receiver.cdm.attachServerSocket(self.sender_id)
        self.sender.cdm.attachClientSocket(self.receiver_id)

    def teardown_class(self):
        """Clean up the opened sockets"""
        self.sender.cdm.closeAllSockets()
        self.receiver.cdm.closeAllSockets()

