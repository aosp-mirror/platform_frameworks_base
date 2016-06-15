
The CTS shim is a package that resides on a device's /system partition in order
to verify certain upgrade scenarios. Not only must it not contain code, but, it
must specify the singular APK that can be used to upgrade it.

NOTE: The need to include a binary on the system image may be deprecated if a
solution involving a temporarily writable /system partition is implemented.

build:
    $ tapas CtsShim CtsShimPriv CtsShimPrivUpgrade CtsShimPrivUpgradeWrongSHA
    $ m

local testing:
    $ cp $OUT/system/priv-app/CtsShimPrivUpgrade/CtsShimPrivUpgrade.apk \
        cts/hostsidetests/appsecurity/test-apps/PrivilegedUpdateApp
    $ cp $OUT/system/priv-app/CtsShimPrivUpgradeWrongSHA/CtsShimPrivUpgradeWrongSHA.apk \
        cts/hostsidetests/appsecurity/test-apps/PrivilegedUpdateApp
    $ cp $OUT/system/priv-app/CtsShimPriv/CtsShimPriv.apk \
        frameworks/base/packages/CtsShim
    $ cp $OUT/system/app/CtsShim/CtsShim.apk \
        frameworks/base/packages/CtsShim

For final submission, the APKs should be downloaded from the build server, then
submitted to the cts/ and frameworks/base/ repos.

