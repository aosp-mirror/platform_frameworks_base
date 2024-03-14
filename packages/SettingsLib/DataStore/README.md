# Datastore library

This library aims to manage datastore in a consistent way.

## Overview

A datastore is required to extend the `BackupRestoreStorage` class and implement
either `Observable` or `KeyedObservable` interface, which enforces:

-   Backup and restore: Datastore should support
    [data backup](https://developer.android.com/guide/topics/data/backup) to
    preserve user experiences on a new device.
-   Observer pattern: The
    [observer pattern](https://en.wikipedia.org/wiki/Observer_pattern) allows to
    monitor data change in the datastore and
    -   trigger
        [BackupManager.dataChanged](https://developer.android.com/reference/android/app/backup/BackupManager#dataChanged\(\))
        automatically.
    -   track data change event to log metrics.
    -   update internal state and take action.

### Backup and restore

The Android backup framework provides
[BackupAgentHelper](https://developer.android.com/reference/android/app/backup/BackupAgentHelper)
and
[BackupHelper](https://developer.android.com/reference/android/app/backup/BackupHelper)
to back up a datastore. However, there are several caveats when implement
`BackupHelper`:

-   performBackup: The data is updated incrementally but it is not well
    documented. The `ParcelFileDescriptor` state parameters are normally ignored
    and data is updated even there is no change.
-   restoreEntity: The implementation must take care not to seek or close the
    underlying data source, nor read more than size() bytes from the stream when
    restore (see
    [BackupDataInputStream](https://developer.android.com/reference/android/app/backup/BackupDataInputStream)).
    It is possible a `BackupHelper` prevents other `BackupHelper`s from
    restoring data.
-   writeNewStateDescription: Existing implementations rarely notice that this
    callback is invoked after all entities are restored, and check if necessary
    data are all restored in `restoreEntity` (e.g.
    [BatteryBackupHelper](https://cs.android.com/android/platform/superproject/main/+/main:packages/apps/Settings/src/com/android/settings/fuelgauge/BatteryBackupHelper.java;l=144;drc=cca804e1ed504e2d477be1e3db00fb881ca32736)),
    which is not robust sometimes.

This library provides more clear API and offers some improvements:

-   The implementation only needs to focus on the `BackupRestoreEntity`
    interface. The `InputStream` of restore will ensure bounded data are read,
    and close the stream will be no-op.
-   The library computes checksum of the backup data automatically, so that
    unchanged data will not be sent to Android backup system.
-   Data compression is supported:
    -   ZIP best compression is enabled by default, no extra effort needs to be
        taken.
    -   It is safe to switch between compression and no compression in future,
        the backup data will add 1 byte header to recognize the codec.
    -   To support other compression algorithms, simply wrap over the
        `InputStream` and `OutputStream`. Actually, the checksum is computed in
        this way by
        [CheckedInputStream](https://developer.android.com/reference/java/util/zip/CheckedInputStream)
        and
        [CheckedOutputStream](https://developer.android.com/reference/java/util/zip/CheckedOutputStream),
        see `BackupRestoreStorage` implementation for more details.
-   Enhanced forward compatibility for file is enabled: If a backup includes
    data that didn't exist in earlier versions of the app, the data can still be
    successfully restored in those older versions. This is achieved by extending
    the `BackupRestoreFileStorage` class, and `BackupRestoreFileArchiver` will
    treat each file as an entity and do the backup / restore.
-   Manual `BackupManager.dataChanged` call is unnecessary now, the library will
    do the invocation (see next section).

### Observer pattern

Manual `BackupManager.dataChanged` call is required by current backup framework.
In practice, it is found that `SharedPreferences` usages foget to invoke the
API. Besides, there are common use cases to log metrics when data is changed.
Consequently, observer pattern is employed to resolve the issues.

If the datastore is key-value based (e.g. `SharedPreferences`), implements the
`KeyedObservable` interface to offer fine-grained observer. Otherwise,
implements `Observable`. The library provides thread-safe implementations
(`KeyedDataObservable` / `DataObservable`), and Kotlin delegation will be
helpful.

Keep in mind that the implementation should call `KeyedObservable.notifyChange`
/ `Observable.notifyChange` whenever internal data is changed, so that the
registered observer will be notified properly.

## Usage and example

For `SharedPreferences` use case, leverage the `SharedPreferencesStorage`. To
back up other file based storage, extend the `BackupRestoreFileStorage` class.

Here is an example of customized datastore, which has a string to back up:

```kotlin
class MyDataStore : ObservableBackupRestoreStorage() {
    // Another option is make it a StringEntity type and maintain a String field inside StringEntity
    @Volatile // backup/restore happens on Binder thread
    var data: String? = null
        private set

    fun setData(data: String?) {
        this.data = data
        notifyChange(ChangeReason.UPDATE)
    }

    override val name: String
        get() = "MyData"

    override fun createBackupRestoreEntities(): List<BackupRestoreEntity> =
        listOf(StringEntity("data"))

    private inner class StringEntity(override val key: String) : BackupRestoreEntity {
        override fun backup(
            backupContext: BackupContext,
            outputStream: OutputStream,
        ) =
            if (data != null) {
                outputStream.write(data!!.toByteArray(UTF_8))
                EntityBackupResult.UPDATE
            } else {
                EntityBackupResult.DELETE
            }

        override fun restore(restoreContext: RestoreContext, inputStream: InputStream) {
            data = String(inputStream.readAllBytes(), UTF_8)
            // NOTE: The library will call notifyChange(ChangeReason.RESTORE) for you
        }
    }

    override fun onRestoreFinished() {
        // TODO: Update state with the restored data. Use this callback instead "restore()" in case
        //       the restore action involves several entities.
        // NOTE: The library will call notifyChange(ChangeReason.RESTORE) for you
    }
}
```

In the application class:

```kotlin
class MyApplication : Application() {
  override fun onCreate() {
    super.onCreate();
    BackupRestoreStorageManager.getInstance(this).add(MyDataStore());
  }
}
```

In the custom `BackupAgentHelper` class:

```kotlin
class MyBackupAgentHelper : BackupAgentHelper() {
  override fun onCreate() {
    BackupRestoreStorageManager.getInstance(this).addBackupAgentHelpers(this);
  }

  override fun onRestoreFinished() {
    BackupRestoreStorageManager.getInstance(this).onRestoreFinished();
  }
}
```
