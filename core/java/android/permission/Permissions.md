<!--
  Copyright (C) 2020 The Android Open Source Project

  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License
  -->

# Android permissions for system developers

This document targets system developers. App developers should refer to the [public
 documentation](https://developer.android.com/guide/topics/permissions/overview).

## Definitions

Each app (often called package) has a unique name called package name. Each package has a manifest
file describing properties of the package. The android system server is in a special package named
"android".

When a package gets installed the package is (usually) assigned a unique identifier called [uid
](../os/Users.md#int-uid).
This is the same as a uid in Linux, but this often leads to confusion. It is easiest to see a uid
as a unique identifier for a package.

Usually an app is running in a container called a process. Each process has a unique id called
pid, but unlike the uid the pid changes each time the process is restarted and app that are not
currently running don't have a pid. The process container makes sure that other apps cannot
negatively interact with an app. Processes can only interact via controlled interactions called
remote procedure calls (RPCs). Android’s RPC mechanism is called _Binder_.

As no app code can be trusted the permission need to be checked on the receiving side of the
Binder call.

For more details please take a look at [Android's security model](../os/Users.md#security-model).

## Permissions for regular apps

### Install time permissions

The purpose of install time permissions is to control access to APIs where it does not makes sense
to involve the user. This can be either because the API is not sensitive, or because additional
checks exist.

Another benefit of install time permissions is that is becomes very easy to monitor which apps can
access certain APIs. E.g. by checking which apps have the `android.permission.INTERNET` permission
you can list all apps that are allowed to use APIs that can send data to the internet.

#### Defining a permission

Any package can define a permission. For that it simply adds an entry in the manifest file
`<permission android:name="com.example.myapp.myfirstpermission" />`

Any package can do this, including the system package. When talking about [permissions for system
 apps](#permissions-for-system-apps) we will see that it is important which package defines a
permission.

It is common good practice to prefix the permission name with the package name to avoid collisions.

#### Requesting a permission

Any app can request any permission via adding an entry in the manifest file like
`<uses-permission android:name="com.example.myapp.myfirstpermission" />`

A requested permission does not necessarily mean that the permission is granted. When and how a
permission is granted depends on the protection level of the permission. If no protection level is
set, the permission will always be granted. Such "normal" permissions can still be useful as it
will be easy to find apps using a certain functionality on app stores and by checking `dumpsys
package`.

#### Checking a permission

`Context.checkPermission(permission, pid, uid)` returns if the pid/uid has the permission. By far
the most common case is to check the permission on the receiving end of a binder call. In this case
the pid can be read as `Binder.callingPid()` and the uid as `Binder.callingUid()`. The uid is a
mandatory argument as permissions are maintained per uid. The pid can be set to -1
if not pid is available. The context class contains handy wrappers for `checkPermission`, such as
`enforeCallingPermission` which calls checkPermission with `Binder.callingPid`/`Binder.callingUid`
and throws a SecurityException when the permission is not granted.

#### Verifying an app has an install time permission

In `dumpsys package my.package.name` there are two sections. In requested permissions all
permissions of the `uses-permission` tags are listed. In install permission the permissions with
their grant state are listed. If an install time permission is not listed here, it is not granted.

```
Packages:
  Package [com.android.packageinstaller] (2eb7062):
    userId=10071
    [...]
    requested permissions:
      android.permission.MANAGE_USERS
      android.permission.INSTALL_PACKAGES
      android.permission.DELETE_PACKAGES
      android.permission.READ_INSTALL_SESSIONS
      android.permission.RECEIVE_BOOT_COMPLETED
      android.permission.HIDE_NON_SYSTEM_OVERLAY_WINDOWS
      android.permission.USE_RESERVED_DISK
      android.permission.UPDATE_APP_OPS_STATS
      android.permission.MANAGE_APP_OPS_MODES
      android.permission.INTERACT_ACROSS_USERS_FULL
      android.permission.SUBSTITUTE_NOTIFICATION_APP_NAME
      android.permission.PACKAGE_USAGE_STATS
    install permissions:
      android.permission.USE_RESERVED_DISK: granted=true
      android.permission.INSTALL_PACKAGES: granted=true
      android.permission.RECEIVE_BOOT_COMPLETED: granted=true
      android.permission.INTERACT_ACROSS_USERS_FULL: granted=true
      android.permission.PACKAGE_USAGE_STATS: granted=true
      android.permission.SUBSTITUTE_NOTIFICATION_APP_NAME: granted=true
      android.permission.READ_INSTALL_SESSIONS: granted=true
      android.permission.MANAGE_USERS: granted=true
      android.permission.HIDE_NON_SYSTEM_OVERLAY_WINDOWS: granted=true
      android.permission.MANAGE_APP_OPS_MODES: granted=true
      android.permission.UPDATE_APP_OPS_STATS: granted=true
      android.permission.DELETE_PACKAGES: granted=true
```

#### End-to-end: Protecting an RPC call via a permission

##### Service Manifest

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.android.example.myservice">
    <!-- Define a permission -->
    <permission android:name="com.android.example.myservice.MY_PERMISSION" />
    <application>
        <service android:name=".MyService" android:exported="true" />
    </application>
</manifest>
```

##### Service code

```kotlin
class MyService : Service() {
    override fun onBind(intent: Intent?): IBinder? {
        return object : IMyService.Stub() {
            override fun doSomething() {
                // Verify that calling UID has the permission
                enforceCallingPermission(
                    "com.android.example.myservice.MY_PERMISSION",
                    "Need to hold permission"
                )
                // do something
            }
        }.asBinder()
    }
}
```

##### Caller Manifest

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.android.example.myapp">
    <!-- request a permission -->
    <uses-permission android:name="com.android.example.myservice.MY_PERMISSION" />
    <application />
</manifest>
```

### Runtime permissions

Runtime permission must be granted by the user during runtime. This is needed if the API protects
data or functionality that is sensitive for the user. E.g. the users current location is protected
by a runtime permission.

Users want a system that is secure and privacy focused by default. User can also often not make a
good choice when asked at the wrong time without enough context. Hence in general runtime
permissions should be avoided and the API should be built in a way where no private data needs to be
leaked.

#### Defining a runtime permission

Runtime permissions are defined in the same way as install time permissions. To tag them as runtime
permissions the `protectionLevel` needs to be set to dangerous. Dangerous is a synonym for
runtime permissions in the Android platform.

```xml
<uses-permission android:name="com.example.myapp.myfirstruntimepermission"
    android:protectionLevel="dangerous" />
```

#### Requesting a runtime permission

Similar to install time permissions any app can request a runtime permission by adding the 
`<uses-permission android:name="com.example.myapp.myfirstruntimepermission" />`
to the manifest.

By default runtime permissions are not granted. The app needs to call `Activity.requestPermissions`
during runtime to ask the user for the permission. The user might then grant or deny and once the
decision is made the activity is called by via `Activity.onPermissionGranted`.

During development and testing a runtime permission can be granted via the `pm` shell command or by
using the `UiAutomator.grantRuntimePermission` API call. Please note that this does _not_ grant the
[app-op](#runtime-permissions-and-app-ops) synchronously. Unless the app needs to test the actual
permission grant flow it is recommended to grant the runtime permissions during install using
`adb install -g /my/package.apk`.

#### Checking a runtime permission

For runtime permissions defined by a 3rd party apps it is fine to check a runtime
permission like an install time permission. For system defined permissions you need to check all
runtime permissions by using the `PermissionChecker` utility. It is good practice to use the tool
anywhere possible.

The permission checker might return `PERMISSION_DENIED_APP_OP` which should lead to a silent
failure. This can only happen for system defined runtime permissions.

##### Runtime permissions and app-ops

> See [App-ops](../app/AppOps.md).

The PermissionChecker code fundamentally looks like this:

```kotlin
class PermissionChecker {
    fun checkCallingPermission(context: Context, permission: String) {
        if (isRuntimePermission(permission)) {
            if (context.checkPermission(uid, permission) == DENIED) {
                 return PERMISSION_DENIED
            }

            val appOpOfPermission = AppOpsManager.permissionToOp(permission)
            if (appOpOfPermission == null) {
                // not platform defined
                return PERMISSION_GRANTED
            }

            val appOpMode = appOpsManager.noteOp(appOpOfPermission)
            if (appOpMode == AppOpsManager.MODE_ALLOWED) {
                return PERMISSION_GRANTED
            } else {
                return PERMISSION_DENIED_APP_OP
            }
        } else {
            return PERMISSION_DENIED
        }
    }
}
```

For each platform defined runtime permission there is a matching app-op. When calling
`AppOpsManager.noteOp` this returns either `MODE_ALLOWED` or `MODE_IGNORED`.

This value is then used to decide between `PERMISSION_DENIED_APP_OP` and `PERMISSION_GRANTED`.

The primary purpose of the special `PERMISSION_DENIED_APP_OP` state was to support apps targeting an
SDK lower than 23. These apps do not understand the concept of denied runtime permissions. Hence
they would crash when getting a `SecurityException`. To protect the users' privacy while still not
crashing the app the special `PERMISSION_DENIED_APP_OP` mandates that the API should somehow
silently fail.

A secondary use case of the `AppOpsManager.noteOp` calls is to
[track](../app/AppOps.md#Appops-for-tracking) which apps perform what runtime protected actions.

#### Verifying an app has a runtime time permission

In `dumpsys package my.package.name` the runtime permissions are listed per uid. I.e. different
users might have different runtime permission grants and shared uids share a grant-set. If a runtime
permission is listed as requested but not in the runtime permission section it is in it’s initial
state, i.e. not granted.

```
Packages:
  Package [com.google.android.GoogleCamera] (ccb6af):
    userId=10181
    [...]
    requested permissions:
      android.permission.ACCESS_COARSE_LOCATION
      android.permission.ACCESS_FINE_LOCATION
      android.permission.ACCESS_NETWORK_STATE
      android.permission.ACCESS_NOTIFICATION_POLICY
      android.permission.ACCESS_WIFI_STATE
      android.permission.BIND_WALLPAPER
      android.permission.CAMERA
      android.permission.CHANGE_WIFI_STATE
      android.permission.INTERNET
      android.permission.GET_PACKAGE_SIZE
      android.permission.NFC
      android.permission.READ_SYNC_SETTINGS
      android.permission.RECEIVE_BOOT_COMPLETED
      android.permission.RECORD_AUDIO
      android.permission.SET_WALLPAPER
      android.permission.USE_CREDENTIALS
      android.permission.VIBRATE
      android.permission.WAKE_LOCK
      android.permission.WRITE_EXTERNAL_STORAGE [ ... ]
      android.permission.WRITE_SETTINGS
      android.permission.WRITE_SYNC_SETTINGS
      com.google.android.elmyra.permission.CONFIGURE_ASSIST_GESTURE
      com.google.android.providers.gsf.permission.READ_GSERVICES
      android.permission.FOREGROUND_SERVICE
      com.google.android.googlequicksearchbox.permission.LENSVIEW_BROADCAST
      android.permission.READ_EXTERNAL_STORAGE [ ... ]
    [...]
    User 0: [ ... ]
    overlay paths:
      runtime permissions:
        android.permission.ACCESS_FINE_LOCATION: granted=false [ ... ]
        android.permission.READ_EXTERNAL_STORAGE: granted=true [ ... ]
        android.permission.ACCESS_COARSE_LOCATION: granted=false [ ... ]
        android.permission.CAMERA: granted=true [ ... ]
        android.permission.WRITE_EXTERNAL_STORAGE: granted=true [ ... ]
        android.permission.RECORD_AUDIO: granted=true[ ... ]
```

#### End-to-end: Protecting an RPC call via a runtime permission

##### Service Manifest

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.android.example.myservice">
    <!-- Define a runtime permission -->
    <permission android:name="com.android.example.myservice.MY_RUNTIME_PERMISSION"
        android:protectionLevel="dangerous" />
    <application>
        <service android:name=".MyService" android:exported="true" />
    </application>
</manifest>
```

##### Service code

```kotlin
class MyService : Service() {
    override fun onBind(intent: Intent?): IBinder? {
        return object : IMyService.Stub() {
            override fun doSomething(callingPackage: String?, callingFeatureId: String?) {
                Objects.requireNonNull(callingPackageName)

                // Verify that calling UID has the permission
                when (run {
                    PermissionChecker.checkCallingPermission(
                        this@MyService,
                         "com.android.example.myservice.MY_RUNTIME_PERMISSION",
                        callingPackageName,
                        callingFeatureId,
                        "Did something"
                    )
                }) {
                    PERMISSION_GRANTED -> /* do something */
                    PERMISSION_DENIED_APP_OP -> /* silent failure, do nothing */
                    else -> throw SecurityException(
                        "Cannot do something as caller is missing "
                                + "com.android.example.myservice.MY_RUNTIME_PERMISSION"
                    )
                }
            }
        }.asBinder()
    }
}
```

##### Caller Manifest

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.android.example.myapp">
    <!-- request a permission -->
    <uses-permission android:name="com.android.example.myservice.MY_RUNTIME_PERMISSION" />
    <application />
</manifest>
```

##### Caller code

```kotlin
class MyActivity : Activity {
    fun callDoSomething() {
        if (checkSelfPermission("com.android.example.myservice.MY_RUNTIME_PERMISSION") == PERMISSION_DENIED) {
            // Interrupt operation and request permission
            requestPermissions(arrayOf("com.android.example.myservice.MY_RUNTIME_PERMISSION"), 23)
        } else {
            myService.doSomething(this@MyActivity.opPackageName, this@MyActivity.featureId)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        if (requestCode == 23 && grantResults[0] == PERMISSION_GRANTED) {
            // Finish operation
            callDoSomething()
        }
    }
}
```

#### Restricted permissions

Some runtime permissions are restricted. They are annotated in the platforms `AndroidManifest.xml`
has `hardRestricted` or `softRestricted`.

Restricted permissions behave uncommon when not whitelisted. When whitelisted the permissions
behave normally. What uncommon means depends on the whether they are hard or soft restricted.

They can either be whitelisted during upgrade P->Q, but the system or need to be whitelisted by the
installer via `PackageInstaller.SessionParams.setWhitelistedRestrictedPermissions`. If this method
is not used all permissions will be whitelisted.

Afterwards the app that originally installed the app can change the whitelisting state via
`PackageManager.addWhitelistedRestrictedPermission` and
`PackageManager.removeWhitelistedRestrictedPermission`.

The system tracks the source of the whitelisting by having three different flags
 `RESTRICTION_SYSTEM_EXEMPT`, `RESTRICTION_UPGRADE_EXEMPT`, and `RESTRICTION_INSTALLER_EXEMPT`,

The flags can be checked in `dumpsys package my.package.name`

```
User 0:
  [...]
  runtime permissions:
    android.permission.READ_EXTERNAL_STORAGE: granted=false, flags=[ RESTRICTION_UPGRADE_EXEMPT ]
    android.permission.ACCESS_FINE_LOCATION: granted=true, flags=[ RESTRICTION_SYSTEM_EXEMPT|RESTRICTION_UPGRADE_EXEMPT ]
```

##### Hard restricted

Hard restricted permissions need to be whitelisted to be grant-able.

##### Soft restricted

The behavior of non-whitelisted soft restricted permissions is not uniform. The behavior is
defined in the `SoftRestrictedPermissionPolicy`.

#### System fixed permission

Some runtime permissions are required for normal operation of the device. In this case the system
can grant the permission as `SYSTEM_FIXED`. In this case the permission can be seen in the
[permission management settings](#settings) but cannot be revoked by the user.

The flag can be checked in `dumpsys package my.package.name`
```
User 0:
  [...]
  runtime permissions:
    android.permission.READ_EXTERNAL_STORAGE: granted=true, flags=[ SYSTEM_FIXED|GRANTED_BY_DEFAULT ]
```

#### Background access

Whether the app is currently visible to the user is reflected in the `ActivityManager`'s proc state.
There is a lot of granularity to this, but runtime permissions are using the [app-ops services'
](../app/AppOps.md) definition of foreground and background.

Most runtime permissions are not affected by foreground/background-ness. Microphone and Camera are
foreground-only while Location is usually foreground-only, but background access can be added by
granting the `ACCESS_BACKGROUND_LOCATION` modifier runtime permission.

##### Microphone and Camera

Currently these only allow access while in the app is in foreground. There is a manual whitelist
for e.g. the voice interaction service.

This is currently (Mar 2020) reworked and will behave like [location](#location) soon.

##### Location

As described [above](#runtime-permissions-and-app-ops) the app-op mode for granted permissions is
`MODE_ALLOWED` to allow access or `MODE_IGNORED` to suppress access.

The important case is the case where the permission is granted and the app-op is `MODE_IGNORED`. In
the case of location this state causes the `LocationManagerService` to stop delivering locations to
the app. This is not a breaking behavior as the same scenarios happens if e.g. no satellites
could be found.

This behavior is used to implement the foregound/background behavior for location. If the app is
in the foreground the app-op mode is `MODE_ALLOWED` and works normally. If the app goes into
background the app-op mode changes to `MODE_IGNORED`. This means that locations are delivered while
the app is in foreground and while the app is background, the app won't get any locations.

The automatic switching between `MODE_ALLOWED` and `MODE_IGNORED` is done inside of
 [`AppOpsManager`](../app/AppOps.md#foreground).

Background access can be enabled by also granting the `ACCESS_BACKGROUND_LOCATION` to the app. In
this case the app-op mode will always be `MODE_ALLOWED`.

#### UI

##### Granting

An app following the best practices does not ask for any runtime permissions until absolutely
needed. Once needed the request should be made in context. I.e. the user should understand from the
current state of the app and the user's action why the request is made. E.g. if the user presses
a "show me the next ATM"-button the user is most likely expecting a request for the location
permission.

This is central premise to the runtime permission UI. It is the app's responsibility to avoid
showing permission requests dialogs to the user which might get denied. These dialogs are not
meant to be user-choices, they are meant to be user-confirmations.

Hence any denied permission dialog is probably due to the app asking for permissions the user
does not expect. If too many permission requests get denied the app is apparently trying to get
more than the user wants to give to the app. In this case the permission gets permanently denied
and all future requests will be denied automatically without showing a UI.

`Context.requestPermission` calls for more than one permission are allowed and might result in
multiple dialogs in sequence. This might make sense for e.g. getting microphone and camera
permission when starting a video call.

Each time the the user makes a choice (either to grant or the deny) a permission request the
permission is marked as `USER_SET`. If a permission gets permanently denied the permission is marked
as `USER_FIXED`.

This can be found in `dumpsys package my.package.name`
```
User 0:
  [...]
  runtime permissions:
    android.permission.READ_EXTERNAL_STORAGE: granted=false, flags=[ USER_SET|USER_FIXED ]
    android.permission.ACCESS_FINE_LOCATION: granted=true, flags=[ USER_SET ]
```

##### Settings

By far most interactions with the permission system are via the [permission grant flow](#granting).
The main purpose of the permission settings is to show the user the previous choices and allow
the user to revisit previous choices. In reality few users do that.

##### Grouping

There are too many runtime permissions for the user to individually manage. Hence the UI bundles the
permissions into groups. **Apps should never assume the grouping**. The grouping might change
with SDK updates, but also at any other time. Certain form factors or locales might use other
permission models and sometimes some of the permissions of a group cannot be granted for various
reasons. The grouping is defined inside the permission controller app.

If two permissions belong to a group and the first permission is already granted the second one
will be granted on request of the app without user interaction. For that reason a permission
group with at least one individual permission granted will show up as granted in the UI.

##### Alternate permission management

It is not allowed to build alternate permission management UIs. While restricting innovation is not
a good choice this is a required one to enforce a consistent, predictable, but flexible permission
model for users and app developers.

Further some data needed for permission management (e.g. the grouping) is not available outside
the permission controller app.

Hence all permission management UI needs to be integrated with AOSP.

#### Pre granting

Runtime permissions protect user private data. It is a violation of user trust to give the data
to an app without explicit user consent (i.e. the user [granting](#granting) the permission
). Still the user expects certain functionality (e.g. receiving a phone call) to work out of the
box.

Hence the `DefaultPermissionGrantPolicy` and roles allow to grant permission without the user
. The default permission grant policy grants permissions for three categories of apps
- Apps running in well defined [uids](../os/Users.md#int-uid) as they are considered as part of
 the platform
- Apps that are in certain predefined categories, e.g. the browser and the SMS app. This is
 meant for the most basic phone functionality, not for all pre-installed apps.
- Apps that are explicitly mentioned as a pre-grant-exceptions. This is meant to be used for setup
 and other highly critical use cases, not to improve the user experience. The exceptions are listed
 in xml files in `etc/` and follow the following syntax
```xml
<exceptions>
    <exception package="my.package.name">
        <permission name="android.permission.ACCESS_FINE_LOCATION" fixed="false"/>
    </exception>
</exceptions>
```

Pre-granted runtime permissions can still be revoked by the user in [settings](#settings) unless
they are granted as `SYSTEM_FIXED`.

Whether a permission was granted by the default can be checked in the permission flags of
`dumpsys package my.package.name`

```
User 0:
  [...]
  runtime permissions:
    android.permission.ACCESS_FINE_LOCATION: granted=true, flags=[ GRANTED_BY_DEFAULT ]
```

### Permission restricted components

As [publicly documented](https://developer.android.com/guide/topics/permissions/overview#permission_enforcement)
it is possible to restrict starting an activity/binding to a service by using permission.

It is a common pattern to

- define a permission in the platform as `signature`
- protect a service in an app by this permission using the `android:permission` attribute of the
 `<service>` tag

Then it is guaranteed that only the system can bind to such service. This is used for services
that provide extensions to platform functionality, such as auto-fill services, print services, and
accessibility services.

This does not work for app-op or runtime permissions as the way to check these permissions is
more complex than install time permissions.

#### End-to-end A service only the system can bind to

Make sure to set the `android:permission` flag for this service. As developers can forget this it is
a good habit to check this before binding to the service. This makes sure that the services are
implemented correctly and no random app can bind to the service.

The result is that the permission is granted only to the system. It is not granted to the service's
package, but this has no negative side-effects.

##### Permission definition

frameworks/base/core/res/AndroidManifest.xml:

```xml
<manifest>
[...]
    <permission android:name="android.permission.BIND_MY_SERVICE"
        android:label="@string/permlab_bindMyService"
        android:description="@string/permdesc_bindMyService"
        android:protectionLevel="signature" />
[...]
</manifest>
```

##### Service definition

Manifest of the service providing the functionality:

```xml
<manifest>
        <service android:name="com.my.ServiceImpl"
                 android:permission="android.permission.BIND_MY_SERVICE">
            <!-- add an intent filter so that the system can find this package -->
            <intent-filter>
                <action android:name="android.my.Service" />
            </intent-filter>
        </service>
</manifest>
```

##### System server code binding to service

```kotlin
val serviceConnections = mutableListOf<ServiceConnection>()

val potentialServices = context.packageManager.queryIntentServicesAsUser(
        Intent("android.my.Service"), GET_SERVICES or GET_META_DATA, userId)

for (val ri in potentialServices) {
    val serviceComponent = ComponentName(ri.serviceInfo.packageName, ri.serviceInfo.name)

    if (android.Manifest.permission.BIND_MY_SERVICE != ri.serviceInfo.permission) {
        Slog.w(TAG, "$serviceComponent is not protected by " +
                "${android.Manifest.permission.BIND_MY_SERVICE}")
        continue
    }

    val newConnection = object : ServiceConnection {
        ...
    }

    val wasBound = context.bindServiceAsUser(Intent().setComponent(serviceComponent),
            serviceConnection, BIND_AUTO_CREATE, UserHandle.of(userId))
    if (wasBound) {
        serviceConnections.add(newConnection)
    }
}
```

## Permissions for system apps

System apps need to integrate deeper with the system than regular apps. Hence they need to be
able to call APIs not available to other apps. This is implemented by granting permissions to
these system apps and then enforcing the permissions in the API similar to other [install time
permissions](#checking-a-permission).

System apps are not different from regular apps, but the protection levels (e.g.
[privileged](#privileged-permissions), [preinstalled](#preinstalled-permissions)) mentioned in this
section are more commonly used by system apps.

### Multiple permission levels

It is possible to assign multiple protection levels to a permission. Very common combinations are
for example adding `signature` to all permissions to make sure the platform signed apps can be
granted the permission, e.g. `privileged|signature`.

The permission will be granted if the app qualifies for _any_ of the permission levels.

### App-op permissions

> See [App-ops](../app/AppOps.md).

App-op permissions are user-switchable permissions that are not runtime permissions. This should
be used for permissions that are really only meant to be ever granted to a very small amount of
apps. Traditionally granting these permissions is intentionally very heavy weight so that the
user really needs to understand the use case. For example one use case is the
`INTERACT_ACROSS_PROFILES` permission that allows apps of different users within the same
[profile group](../os/Users.md#profile-group) to interact. Of course this is breaking a very basic
security container and hence should only ever be granted with a lot of care.

**Warning:** Most app-op permissions follow this logic, but most of them also have exceptions
and special behavior. Hence this section is a guideline, not a rule.

#### Defining an app-op permission

Only the platform can reasonably define an app-op permission. The permission is defined in the
platforms manifest using the `appop` protection level

```xml
<manifest package="android">
    <permission android:name="android.permission.MY_APPOP_PERMISSION"
        android:protectionLevel="appop|signature" />
</manifest>
```

Almost always the protection level is app-op | something else, like
[signature](#signature-permissions) (in the case above) or [privileged](#privileged-permissions).

#### Checking an app-op permission

The `PermissionChecker` utility can check app-op permissions with the [same syntax as runtime
permissions](#checking-a-runtime-permission).

The permission checker internally follows this flow

```kotlin
class PermissionChecker {
    fun checkCallingPermission(context: Context, permission: String) {
        if (isAppOpPermission(permission)) {
            val appopOfPermission = AppOpsManager.permissionToOp(permission)
            if (appopOfPermission == null) {
                // not platform defined
                return PERMISSION_DENIED
            }

            val appopMode = appOpsManager.noteOp(appopOfPermission)
            when (appopMode) {
                AppOpsManager.MODE_ALLOWED -> return PERMISSION_GRANTED
                AppOpsManager.MODE_IGNORED -> return PERMISSION_DENIED
                AppOpsManager.MODE_DEFAULT -> {
                    if (context.checkPermission(uid, permission) == GRANTED) {
                        return PERMISSION_GRANTED
                    } else {
                        return PERMISSION_DENIED
                    }
                }
            }
        } else {
            return PERMISSION_DENIED
        }
    }
}
```

#### Granting an app-op permission

The permission's grant state is only considered if the app-op's mode is `MODE_DEFAULT`. This
allows to have default grants while still being overridden by the app-op.

The permission is then granted by setting the app-op mode. This is usually done via dedicated APIs
for each use cases. Similarly whether and how an app can request the permission is different for
each app-op permission.

When implementing a new app-op permission, make sure to set the app-op mode using `AppOpsManager
.setUidMode` to make sure the permission is granted on the uid as this is the security domain.

During development app-ops can be grated to app via the `appops set` shell command. E.g.

```
adb shell appops set 10187 INTERACT_ACROSS_PROFILES allow
```

sets the `INTERACT_ACROSS_PROFILES` app-op for uid 10187 to allow thereby granting apps in this
uid the ability to interact across profiles.

##### UI

Most UIs for app-op permissions are in the "Special app access" section of the settings app.

In most cases the permission should only be granted with the user's explicit agreement, usually by
allowing the app to directly open the "Special app access" page for this permission and app.

To repeat: this is a guideline for app-op permissions and there are many exceptions.

### Signature permissions

Only apps signed with the defining app's certificate will be granted the permission. This is
used to restrict APIs to apps of the same developer.

This is frequently used to restrict permissions defined by the platform to apps also signed with
the platform's certificate. As this is a very tight restriction this is recommended for
permissions that are only used by apps built out of AOSP which are signed with the platform
certificate.

Please note that OEMs sign their platform them self. I.e. OEMs can implement new apps using these
permissions. It is unlikely that 3rd party apps will be able to use APIs protected by signature
permissions as they are usually not signed with the platform certificate.

Such permissions are defined and checked like an install time permission.

### Preinstalled permissions

This means that the app has to be pre-installed. There is no restriction what apps are pre-installed
on a particular device install there. Hence it can be really any app including 3rd party apps.

Hence this permission level is discouraged unless there are
[further restrictions](#restricted-by-tests).

Such permissions are defined and checked like an install time permission.

### Privileged permissions

This means that the app has to be pre-installed and in the `system/priv` directory in the
filesystem. There is no restriction what apps are in this directory on a particular device
install there. Hence it can be really any app including 3rd party apps.

An app is only ever granted privileged permissions requested by the pre-installed apk. I.e.
privileged permissions added in updates will never be granted.

Hence this permission level is discouraged unless there are
[further restrictions](#restricted-by-tests).

Such permissions are defined and checked like an install time permission.

#### Restricted by tests

As all apps that might get preinstalled or privilidged permissions need to be pre-installed and new
images need to pass compliance tests it is possible to use a test to whitelist the apps that can
request the permission.

Example of such a test:
```kotlin
/* Add new whitelisted packages to this list */
private val whitelistedPkgs = listOf("my.whitelisted.package")

@Test
fun onlySomeAppsAreAllowedToHavePermissionGranted() {
    assertThat(whitelistedPkgs).containsAllIn(
            context.packageManager.getInstalledPackages(MATCH_ALL)
                    .filter { pkg ->
                        context.checkPermission(android.Manifest.permission.MY_PRIVILEGED_PERMISSION, -1,
                                pkg.applicationInfo.uid) == PERMISSION_GRANTED
                    /* The permission is defined by the system and hence granted to it */
                    }.filter { pkg -> pkg.applicationInfo.uid != SYSTEM_UID }
                    .map { it.packageName }
    )
}
```

#### Whitelist

As mentioned above it is not suggested, but still common practice to install 3rd party apps as
privilidged. To verify and restrict which privilidged permissions those apps get granted all
privilidged permissions need to be explicitly whitelisted in a file `/etc`.

```xml
<permissions>
    <privapp-permissions package="my.privileged.package">
        <!-- allow the app to request a permission -->
        <permission name="android.permission.MY_PRIVILEGED_PERMISSION"/>

        <!-- Even though the app requests the permission, do not grant it -->
        <deny-permission name="android.permission.MY_OTHER_PRIVILEGED_PERMISSION"/>
    </privapp-permissions>
</permissions>
```

If the pre-installed apk of app requests a privileged permission that is not mentioned in any
whitelist or that is not denied the system will refuse to boot. As mentioned above privileged
permissions added in updates to the pre-installed app will never be granted.

### Limited permissions

E.g. installer, wellbeing, documenter, etc... This allows the system to restrict the permission to a
well defined app or set of apps. It is possible to add new types in `PackageManagerService`.

Which apps qualify for such a permission level is flexible and custom for each such level. Usually
they refer to a single or small set of apps, usually - but not always - apps defined in AOSP.

These permissions are defined and checked like an install time permission.

### Development permissions

> Not recommended

By adding the `development` protection level to any permissions the permission can be granted via
the `pm grant` shell command. This appears to be useful for development and testing, but it is very
highly discouraged. Any user can grant them permanently via adb, hence adding this tag removes
all guarantees the permission might otherwise provide.

### Other protection levels

There are other levels (such as `runtime`) but they are for special purposes on should not be
used by platform developers.
