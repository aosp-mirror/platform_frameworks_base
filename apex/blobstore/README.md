<!--
  Copyright (C) 2021 The Android Open Source Project

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

# BlobStore Manager

## Introduction
* BlobStoreManager is a system service added in Android R release that facilitates sharing of
  data blobs among apps.
* Apps that would like to share data blobs with other apps can do so by contributing those
  data blobs with the System and can choose how they would like the System to share the data blobs
  with other apps.
* Apps can access data blobs shared by other apps from the System using checksum of the data blobs
  (plus some other data attributes. More details [below](#blob-handle)).
* The APIs provided by the BlobStoreManager are meant to reduce storage and network usage by
  reusing the data available on the device instead of downloading the same data again and having
  multiple copies of the same data on disk.
* It is not meant to provide access to the data which apps otherwise would not be able to access.
  In other words, if an app’s only means of obtaining access to certain data is through
  BlobStoreManager, then that use case is not really intended or supported.
* For example, if earlier an app was downloading certain shared data from a server, then by using
  BlobStoreManager, it can first check whether or not the data is already available on the device
  before downloading.

## Concepts
### Blob handle
Blob handle is the identifier of the data and it is what apps need to use for referring to the
data blobs. Currently, this is made of following bits of information:
* SHA256 checksum of data
* Data label: A user readable string that indicates what the data blob is.
  This is meant to be used when surfacing a list of blobs to the user.
* Data expiry time: A timestamp after which the data blob should be considered invalid and not
  allowed to be accessed by any app.
* Data tag: An opaque string associated with the blob. System does not interpret this in any way or
  use it for any purposes other than when checking whether two Blob handle identifiers are referring
  to the same data blob. This is meant to be used by the apps, either for categorization for
  data blobs or for adding additional identifiers. For example, an app can add tags like
  *machine_learning* or *media* depending on the data blob if necessary.

When comparing two Blob handles, the System will compare all the pieces of information above and
only when two Blob handles are equal, the data blobs corresponding to those identifiers are
considered equal.

### Blob sharing session
Session is a way to allow apps to contribute data over multiple time intervals. Each session is
associated with a unique Identifier that is created and obtained by the apps by calling
[BlobStoreManager#createSession](https://developer.android.com/reference/android/app/blob/BlobStoreManager#createSession(android.app.blob.BlobHandle)).
Apps can save the Identifier associated with a session and use it to open and close it
multiple times for contributing the data. For example, if an app is downloading
some content over the network, it can start a Session and start contributing this data to the
System immediately and if the network connection is lost for any reason, the app can close this
session. When the download resumes, the app can reopen the session and start contributing again.
Note that once the entire data is contributed, the app has no reason to hold on to the Session Id.

### Blob commit
Since a data blob can be contributed in a session over multiple time intervals, an app closing a
session does not imply that the contribution is completed. So, *commit* is added as an explicit
event / signal for the app to indicate that the contribution of the data blob is completed.
At this point, the System can verify the data blob does indeed correspond to the Blob handle used
by the app and prevent the app from making any further modifications to the data blob. Once the
data blob is committed and verified by the System, it is available for other applications to access.

### Access modes
When an application contributes a data blob to the System, it can choose to specify how it would
like the System to share this data blob with other applications. Access modes refer to the type of
access that apps specified when contributing a data blob. As of Android S release, there are
four access modes:
* Allow specific packages: Apps can specify a specific set of applications that are allowed to
  access their data blob.
* Allow packages with the same signature: Apps can specify that only the applications that are
  signed with the same certificate as them can access their data blob.
* Allow public access: Apps can specify that any other app on the device can access their data blob.
* Allow private access: Apps can specify that no other app can access their data blob unless they
  happen to contribute the same data blob.
  * Note that in this case, two apps might download the same data blob and contribute to the System
    in which case we are not saving anything in terms of bandwidth usage, but we would still be
    saving disk usage since we would be keeping only one copy of data on disk.

### Lease
Leasing a blob is a way to specify that an application is interested in using a data blob
and would like the System to not delete this data blob. Applications can also access a blob
without holding a lease on it, in which case the System can choose to delete the data blob at any
time. So, if an application wants to make sure a data blob is available for access for a certain
period, it is recommended that the application acquire a lease on the data blob. Applications can
either specify upfront how long they would like to hold the lease for (which is called the lease
expiry time), or they can acquire a lease without specifying a time period and release the lease
when they are done with the data blob.

## Sharing data blobs across users
By default, data blobs are only accessible to applications in the user in which the data blob was
contributed, but if an application holds the permission
[ACCESS_BLOBS_ACROSS_USERS](https://developer.android.com/reference/android/Manifest.permission#ACCESS_BLOBS_ACROSS_USERS),
then they are allowed to access blobs that are contributed by the applications in the other users.
As of Android S, this permission is only available to following set of applications:
* Apps signed with the platform certificate
* Privileged applications
* Applications holding the
  [ASSISTANT](https://developer.android.com/reference/android/app/role/RoleManager#ROLE_ASSISTANT)
  role
* Development applications

Note that the access modes that applications choose while committing the data blobs still apply
when these data blobs are accessed across users. So for example, if *appA* contributed a
data blob in *user0* and specified to share this data blob with only a specific set of
applications [*appB*, *appC*], then *appD* on *user10* will not be able to access this data blob
even if the app is granted the `ACCESS_BLOBS_ACROSS_USERS` permission.

When apps that are allowed to access blobs across users
(i.e. those holding the permission `ACCESS_BLOBS_ACROSS_USERS`) try to access a data blob,
they can do so as if it is any other data blob. In other words, the applications don’t need to
know where the data blob is contributed, because the System will automatically check and will
allow access if this data blob is available either on the user in which the calling application
is running in or other users.