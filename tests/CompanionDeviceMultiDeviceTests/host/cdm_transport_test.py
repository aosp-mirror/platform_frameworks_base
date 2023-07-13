# Lint as: python3
"""
Test E2E CDM functions on mobly.
"""

import cdm_base_test
import sys

from mobly import asserts
from mobly import test_runner

CDM_SNIPPET_PACKAGE = 'android.companion.multidevices'


class TransportTestClass(cdm_base_test.BaseTestClass):

    def test_permissions_sync(self):
        """This tests permissions sync from one device to another."""

        # associate and attach transports
        self.attach_transports()

        # start permissions sync
        self.sender.cdm.startPermissionsSync(self.receiver_id)


if __name__ == '__main__':
    try:
        # Take test args and remove standalone '--' from the list
        index = sys.argv.index('--')
        sys.argv = sys.argv[:1] + sys.argv[index + 1:]
    except ValueError:
        # Ignore if '--' is not in args
        pass

    test_runner.main()