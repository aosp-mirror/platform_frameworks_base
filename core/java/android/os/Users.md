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

# Users for system developers

## Concepts

### User

A user of a device e.g. usually a human being. Each user has its own home screen.

#### User Profile

A user can have multiple profiles. E.g. one for the private life and one for work. Each profile
has a different set of apps and accounts but they share one home screen. All profiles of a
profile group can be active at the same time.

Each profile has a separate [`userId`](#int-userid). Unless needed user profiles are treated as
completely separate users.

#### Profile Group

All user profiles that share a home screen. You can list the profiles of a user via
`UserManager#getEnabledProfiles` (you usually don't deal with disabled profiles)

#### Foreground user vs background user

Only a single user profile group can be in the foreground. This is the user profile the user
currently interacts with.

#### Parent user (profile)

The main profile of a profile group, usually the personal (as opposed to work) profile. Get this via
`UserManager#getProfileParent` (returns `null` if the user does not have profiles)

#### Managed user (profile)

The other profiles of a profile group. The name comes from the fact that these profiles are usually
managed by a device policy controller app. You can create a managed profile from within the device
policy controller app on your phone.

#### Account

An account of a user profile with a (usually internet based) service. E.g. aname@gmail.com or
aname@yahoo.com. Each profile can have multiple accounts. A profile does not have to have a
account.

## Data types

### int userId

... usually marked as `@UserIdInt`

The id of a user profile. List all users via `adb shell dumpsys user`. There is no data type for a
user, all you can do is using the user id of the parent profile as a proxy for the user.

### int uid

Identity of an app. This is the same as a Linux uid, but in Android there is one uid per package,
per user.

It is highly discouraged, but uids can be shared between multiple packages using the
`android:sharedUserId` manifest attribute.

### class UserHandle

A wrapper for userId. Used esp. in public APIs instead of `int userId` as it clearly distinguishes
from uid.

## Security model

Multiple packages can share an uid by using `android:sharedUserId` manifest attribute. If packages
share a uid they can run in the same process via `android:process` manifest attribute. Further file
level access is also tracked by uid. Hence any security or privacy mechanism needs to be built on
a uid granularity.

On the other hand apps belonging to the same user cannot see each others files. They can only
interact via activity launches, broadcasts, providers, and service bindings. All of them can be be
protected by [permissions](../permission/Permissions.md). Hence any new general communication
mechanism should be access controlled by permissions.

## Lifecycle

A system service should deal with users being started and stopped by overriding
`SystemService.onSwitchUser` and `SystemService.onStopUser`.

If users profiles become inactive the system should stop all apps of this profile from interacting
with other apps or the system.

Another important lifecycle event is `onUnlockUser`. Only for unlocked user profiles you can access
all data, e.g. which packages are installed.

You only want to deal with user profiles that

- are in the profile group of the foreground user
- the user profile is unlocked and not yet stopped
