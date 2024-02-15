# Rollback Manager

## Introduction

RollbackManager is a system service added in Android Q to support mainline
updatability efforts. RollbackManager adds support for rolling back an APK or
APEX update to the previous version installed on the device, and reverting any
APK or APEX data to the state it was in at the time of install.

## Rollback Basics

### How Rollbacks Work

A new install parameter ENABLE_ROLLBACK can be specified to enable rollback when
updating an application. For example:

```
$ adb install FooV1.apk
$ adb install --enable-rollback FooV2.apk
```

The ENABLE_ROLLBACK flag causes the following additional steps to be taken when
FooV2.apk is installed:

* A backup copy of FooV1.apk is made and stored on device.
* A backup copy of the userdata for the package com.example.foo is made and
stored on device.

For a limited time defaulting to 14 days after FooV2.apk is installed, a
rollback of the update to FooV2.apk can be requested. This can be requested from
the command line as follows:

```
$ adb shell pm rollback-app com.example.foo
```

When a rollback is requested, the following steps are taken:

* The backed up copy of FooV1.apk is installed as a downgrade install.
* The userdata for com.example.foo is replaced by the backed-up copy of the user
data taken when FooV2.apk was first installed.

See below for more details of shell commands for rollback.

### Rollback Triggers

#### Manually Triggered Rollback

As mentioned above, it is possible to trigger rollback on device using a shell
command. This is for testing purposes only. We do not expect this mechanism to
be used in production in practice.

#### Watchdog Triggered Rollback

Watchdog triggered rollback is intended to address severe issues with the
device. The platform provides several different watchdogs that can trigger
rollback.

##### Package Watchdog

There is a package watchdog service running on device that will trigger rollback
of an update if there are 5 ANRs or process crashes within a 1 minute window for
a package in the update.

##### Native Watchdog

If a native service crashes repeatedly after an update is installed, rollback
will be triggered. This particularly applies to updates that include APEXes
that may update native services, but note that as it is difficult to tell which
native services have been affected by an update, *any* crashing native service
will cause the rollback to be triggered.

##### Explicit Health Check

There is an explicit check to verify the network stack is functional after an
update. If there is no network connectivity within a certain time period after
an update, rollback is triggered.

#### Server Triggered Rollback
The RollbackManager API may be used by the installer to roll back an update
based on a request from the server.

## Rollback Details

### RollbackManager API

The RollbackManager API is an @SystemAPI guarded by the MANAGE_ROLLBACKS and
TEST_MANAGE_ROLLBACKS permissions. See RollbackManager.java for details about
the RollbackManager API.

### Rollback of APEX modules

Rollback is supported for APEX modules in addition to APK modules. In Q, there
was no concept of data associated with an APEX, so only the APEX itself is
rolled back. In R, data directories for APEXes were added, and the contents of
these directories are reverted when the APEX is rolled back.

APEX modules are responsible for ensuring they can gracefully handle rollback in
terms of any state they persist on the system (outside of the APEX data
directories). For example, FooV2.apex must not change the file format of some
state stored on the device in such a way that FooV1.apex cannot read the file.

### Rollback of MultiPackage Installs

Rollback can be enabled for multi-package installs. This requires that all
packages in the install session, including the parent session, have the
ENABLE_ROLLBACK flag set. If an application is updated as part of a
multi-package install session, then rollback of any single package in the
multi-package install session will cause all packages in the multi-package
install session to be rolled back. The rollback itself is performed as a
multi-package downgrade install to preserve atomicity.

For example, a "train" update may install multiple packages using a single
multi-package install session. If a problem with one of the included packages is
identified, rollback can be requested for that package, causing  all packages in
the train update to be rolled back.

If there is a problem enabling rollback for any package in the multi-package
install session, rollback will not be enabled for any package in the
multi-package install session.

### Rollback of Staged Installs

Rollback can be enabled for staged installs, which require reboot to take
effect. If reboot was required when the package was updated, then reboot is
required when the package is rolled back. If no reboot was required when the
package was updated, then no reboot is required when the package is rolled back.


### Rollbacks on Multi User Devices

Rollbacks should work properly on devices with multiple users. There is special
handling of user data backup to ensure app user data is properly backed up and
restored for all users, even for credential encrypted users that have not been
unlocked at various points during the flow.

### Rollback whitelist

Outside of testing, rollback may only be enabled for packages listed in the
sysconfig rollback whitelist - see
`SystemConfig#getRollbackWhitelistedPackages`. Attempts to enable rollback for
non-whitelisted packages will fail.

### Failure to Enable Rollback

There are a number of reasons why we may be unable to enable rollback for a
package, including:

* Timeout - There is a limit for how long we can spend attempting to enable
rollback for an individual package. The limit defaults to 10 seconds, but can
be reconfigured by changing the value of enable_rollback_timeout.
* Rollback is not allowed to be enabled, for example because the package is not
whitelisted.
* Insufficient space for apk or userdata backups.
* Internal error.

If we are unable to enable rollback, the installation will proceed without
rollback enabled. Failing to enable rollback does not cause the installation to
fail.

### Failure to Commit Rollback

For the most part, a rollback will remain available after failure to commit it.
This allows the caller to retry the rollback if they have reason to believe it
will not fail again the next time the commit of the rollback is attempted.

### Installing Previously Rolled Back Packages
There is no logic in the platform itself to prevent installing a version of a
package that was previously rolled back.

The platform does maintain a list of 'cause' packages for each triggered
rollback. Whoever triggers the rollback provides the list of packages and
package versions believed to be the main source of the bad update. The list of
'cause' packages is not used by the platform, but it can be queried by an
installer to prevent reinstall of a previously rolled back package version if so
desired.

### Rollback Expiration

An available rollback is expired if the rollback lifetime has been exceeded or
if there is a new update to package associated with the rollback. When an
available rollback is expired, the backed up apk and userdata associated with
the rollback are deleted. Once a rollback is expired, it can no longer be
executed.

## Shell Commands for Rollback

### Installing an App with Rollback Enabled

The `adb install` command accepts the `--enable-rollback [0/1/2]` flag to install an app
with rollback enabled. For example:

```
$ adb install --enable-rollback FooV2.apk
```

The default rollback data policy is `ROLLBACK_DATA_POLICY_RESTORE` (0). To use
a different `RollbackDataPolicy`, like `ROLLBACK_DATA_POLICY_RETAIN` (1) or
`ROLLBACK_DATA_POLICY_WIPE` (2), provide the int value after
`--enable-rollback`. For example:

```
$ adb install --enable-rollback 1 FooV2.apk
```

### Triggering Rollback Manually

If rollback is available for an application, the pm command can be used to
trigger rollback manually on device:

```
$ adb shell pm rollback-app com.example.foo
```

For rollback of staged installs, you have to manually reboot the device for the
rollback to take effect after running the 'pm rollback-app' command.

### Listing the Status of Rollbacks on Device

You can get a list with details about available and recently committed rollbacks
using dumpsys. For example:

```
$ adb shell dumpsys rollback
469808841:
  -state: committed
  -timestamp: 2019-04-23T14:57:35.944Z
  -packages:
    com.android.tests.rollback.testapp.B 2 -> 1 [0]
  -causePackages:
  -committedSessionId: 1845805640
649899517:
  -state: committed
  -timestamp: 2019-04-23T12:55:21.342Z
  -stagedSessionId: 343374391
  -packages:
    com.android.tests.rollback.testapex 2 -> 1 [0]
  -causePackages:
  -committedSessionId: 2096717281
```

The example above shows two recently committed rollbacks. The update of
com.android.tests.rollback.testapp.B from version 1 to version 2 was rolled
back, and the update of com.android.tests.rollback.testapex from version 1 to
version 2 was rolled back. For each package the value inside '[' and ']'
indicates the `RollbackDataPolicy` for the rollback back.

The state is 'available' or 'committed'. The timestamp gives the time when the
rollback was first made available. If a stagedSessionId is present, then the
rollback is for a staged update; reboot will be required after the rollback
before it takes effect.

Nothing will be listed if there are no rollbacks available or recently
committed.

The list of rollbacks is also included in bug reports. Search for "DUMP OF
SERVICE rollback".

## Configuration Properties

### Rollback Lifetime

Rollback lifetime refers to the maximum duration of time after the rollback is
first enabled that it will be available. The default is for rollbacks to be
available for 14 days after the update. This lifetime can be adjusted using the
rollback_lifetime_in_millis flag:


```
$ adb shell device_config get rollback_boot rollback_lifetime_in_millis
$ adb shell device_config put rollback_boot rollback_lifetime_in_millis 172800000
```

The update will not take effect until after system server has been restarted.

### Enable Rollback Timeout

The enable rollback timeout is how long RollbackManager is allowed to take to
enable rollback when performing an update. This includes the time needed to make
backup copies of the apk and userdata for a package. If the time limit is
exceeded, the update will be installed without rollback enabled.

The default timeout is 10 seconds and can be adjusted using the
enable_rollback_timeout flag, which is in units of milliseconds:

`
$ adb shell device_config put rollback enable_rollback_timeout 10000
`

The update will take effect for the next install with rollback enabled.

## Limitations

* You cannot enable rollback for the first version of an application installed
on the device. Only updates to a package previously installed on the device can
have rollback enabled for them.
* Rolling back to the system version of an app requires the system version of
the apk be self-contained. For example, the apk itself must contain any
necessary classes.dex or .so files used by the app. It must not have classes.dex
stripped or have the .so files extracted. If the system apk is not self
contained, then the app will be broken after rollback and require manual
intervention to fix.
* Rollback only rolls back code and app user data. It does not roll back other
potential side effects that updating an app may have, such as changes to
persistent system state.

