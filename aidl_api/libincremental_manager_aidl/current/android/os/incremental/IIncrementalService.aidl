///////////////////////////////////////////////////////////////////////////////
// THIS FILE IS IMMUTABLE. DO NOT EDIT IN ANY CASE.                          //
///////////////////////////////////////////////////////////////////////////////

// This file is a snapshot of an AIDL interface (or parcelable). Do not try to
// edit this file. It looks like you are doing that because you have modified
// an AIDL interface in a backward-incompatible way, e.g., deleting a function
// from an interface or a field from a parcelable and it broke the build. That
// breakage is intended.
//
// You must not make a backward incompatible changes to the AIDL files built
// with the aidl_interface module type with versions property set. The module
// type is used to build AIDL files in a way that they can be used across
// independently updatable components of the system. If a device is shipped
// with such a backward incompatible change, it has a high risk of breaking
// later when a module using the interface is updated, e.g., Mainline modules.

package android.os.incremental;
/* @hide */
interface IIncrementalService {
  int openStorage(in @utf8InCpp String path);
  int createStorage(in @utf8InCpp String path, in android.content.pm.DataLoaderParamsParcel params, in android.content.pm.IDataLoaderStatusListener listener, int createMode);
  int createLinkedStorage(in @utf8InCpp String path, int otherStorageId, int createMode);
  int makeBindMount(int storageId, in @utf8InCpp String sourcePath, in @utf8InCpp String targetFullPath, int bindType);
  int deleteBindMount(int storageId, in @utf8InCpp String targetFullPath);
  int makeDirectory(int storageId, in @utf8InCpp String path);
  int makeDirectories(int storageId, in @utf8InCpp String path);
  int makeFile(int storageId, in @utf8InCpp String path, in android.os.incremental.IncrementalNewFileParams params);
  int makeFileFromRange(int storageId, in @utf8InCpp String targetPath, in @utf8InCpp String sourcePath, long start, long end);
  int makeLink(int sourceStorageId, in @utf8InCpp String sourcePath, int destStorageId, in @utf8InCpp String destPath);
  int unlink(int storageId, in @utf8InCpp String path);
  boolean isFileRangeLoaded(int storageId, in @utf8InCpp String path, long start, long end);
  byte[] getMetadataByPath(int storageId, in @utf8InCpp String path);
  byte[] getMetadataById(int storageId, in byte[] fileId);
  boolean startLoading(int storageId);
  void deleteStorage(int storageId);
  boolean configureNativeBinaries(int storageId, in @utf8InCpp String apkFullPath, in @utf8InCpp String libDirRelativePath, in @utf8InCpp String abi);
  const int CREATE_MODE_TEMPORARY_BIND = 1;
  const int CREATE_MODE_PERMANENT_BIND = 2;
  const int CREATE_MODE_CREATE = 4;
  const int CREATE_MODE_OPEN_EXISTING = 8;
  const int BIND_TEMPORARY = 0;
  const int BIND_PERMANENT = 1;
}
