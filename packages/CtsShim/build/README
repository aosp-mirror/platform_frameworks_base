
The CTS shim is a package that resides on a device's /system partition in order
to verify certain upgrade scenarios. Not only must it not contain code, but, it
must specify the singular APK that can be used to upgrade it.

NOTE: The need to include a binary on the system image may be deprecated if a
solution involving a temporarily writable /system partition is implemented.

For local testing, build the apk and put them in the following folders.
This is for arm:
    $ tapas CtsShim CtsShimPriv CtsShimPrivUpgrade CtsShimPrivUpgradeWrongSHA arm64
    $ m
    $ cp $OUT/system/priv-app/CtsShimPrivUpgrade/CtsShimPrivUpgrade.apk \
        cts/hostsidetests/appsecurity/test-apps/PrivilegedUpdateApp/apk/arm
    $ cp $OUT/system/priv-app/CtsShimPrivUpgrade/CtsShimPrivUpgrade.apk \
        vendor/xts/gts-tests/hostsidetests/packagemanager/app/apk/arm/GtsShimPrivUpgrade.apk
    $ cp $OUT/system/priv-app/CtsShimPrivUpgradeWrongSHA/CtsShimPrivUpgradeWrongSHA.apk \
        cts/hostsidetests/appsecurity/test-apps/PrivilegedUpdateApp/apk/arm
    $ cp $OUT/system/priv-app/CtsShimPriv/CtsShimPriv.apk \
        frameworks/base/packages/CtsShim/apk/arm
    $ cp $OUT/system/app/CtsShim/CtsShim.apk \
        frameworks/base/packages/CtsShim/apk/arm

This is for x86:
    $ tapas CtsShim CtsShimPriv CtsShimPrivUpgrade CtsShimPrivUpgradeWrongSHA x86_64
    $ m
    $ cp $OUT/system/priv-app/CtsShimPrivUpgrade/CtsShimPrivUpgrade.apk \
        cts/hostsidetests/appsecurity/test-apps/PrivilegedUpdateApp/apk/x86
    $ cp $OUT/system/priv-app/CtsShimPrivUpgrade/CtsShimPrivUpgrade.apk \
        vendor/xts/gts-tests/hostsidetests/packagemanager/app/apk/x86/GtsShimPrivUpgrade.apk
    $ cp $OUT/system/priv-app/CtsShimPrivUpgradeWrongSHA/CtsShimPrivUpgradeWrongSHA.apk \
        cts/hostsidetests/appsecurity/test-apps/PrivilegedUpdateApp/apk/x86
    $ cp $OUT/system/priv-app/CtsShimPriv/CtsShimPriv.apk \
        frameworks/base/packages/CtsShim/apk/x86
    $ cp $OUT/system/app/CtsShim/CtsShim.apk \
        frameworks/base/packages/CtsShim/apk/x86

For final submission, the APKs should be downloaded from the build server, then
submitted to the cts/ and frameworks/base/ repos.

