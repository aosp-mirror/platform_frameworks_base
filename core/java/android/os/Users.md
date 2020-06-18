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

### Users and profiles

#### User

A user is a representation of a person using a device, with their own distinct application data
and some unique settings. Throughout this document, the word 'user' will be used in this technical
sense, i.e. for this virtual environment, whereas the word 'person' will be used to denote an actual
human interacting with the device.

Each user has a separate [`userId`](#int-userid).

#### Profile Group

Often, there is a 1-to-1 mapping of people who use a device to 'users'; e.g. there may be two users
on a device - the owner and a guest, each with their own separate home screen.

However, Android also supports multiple profiles for a single person, e.g. one for their private
life and one for work, both sharing a single home screen.
Each profile in a profile group is a distinct user, with a unique [`userId`](#int-userid), and have
a different set of apps and accounts,
but they share a single UI, single launcher, and single wallpaper.
All profiles of a profile group can be active at the same time.

You can list the profiles of a user via `UserManager#getEnabledProfiles` (you usually don't deal 
with disabled profiles)

#### Parent user

The main user of a profile group, to which the other profiles of the group 'belong'.
This is usually the personal (as opposed to work) profile. Get this via
`UserManager#getProfileParent` (returns `null` if the user does not have profiles).

#### Profile (Managed profile)

A profile of the parent user, i.e. a profile belonging to the same profile group as a parent user,
with whom they share a single home screen.
Currently, the only type of profile supported in AOSP is a 'Managed Profile'.
The name comes from the fact that these profiles are usually
managed by a device policy controller app. You can create a managed profile from within the device
policy controller app on your phone.

Note that, as a member of the profile group, the parent user may sometimes also be considered a
'profile', but generally speaking, the word 'profile' denotes a user that is subordinate to a
parent.

#### Foreground user vs background user

Only a single user can be in the foreground.
This is the user with whom the person using the device is currently interacting, or, in the case
of profiles, the parent profile of this user.
All other running users are background users.
Some users may not be running at all, neither in the foreground nor the background.

#### Account

An account of a user with a (usually internet based) service. E.g. aname@gmail.com or
aname@yahoo.com. Each user can have multiple accounts. A user does not have to have a
account.

#### System User

The user with [`userId`](#int-userid) 0 denotes the system user, which is always required to be
running.

On most devices, the system user is also used by the primary person using the device; however,
on certain types of devices, the system user may be a stand-alone user, not intended for direct
human interaction.

## Data types

### int userId

The id of a user. List all users via `adb shell dumpsys user`.
In code, these are sometimes marked as `@UserIdInt`.

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

If a user become inactive the system should stop all apps of this user from interacting
with other apps or the system.

Another important lifecycle event is `onUnlockUser`. Only for an unlocked user can you access
all data, e.g. which packages are installed.

You only want to deal with user profiles that

- are in the profile group of the foreground user
- the user profile is unlocked and not yet stopped
