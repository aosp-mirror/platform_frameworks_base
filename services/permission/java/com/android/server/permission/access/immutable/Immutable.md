# Immutable Data Structures

## Introduction

The classes inside this package implements a way to manipulate data in an immutable way, which
allows achieving lock-free reads for performance-critical code paths, and organizing the
implementation of complex state transitions in a readable and maintainable way.

## Features

This implementation provides the following features:

- Immutability is implemented leveraging the Java/Kotlin type system.

    Each data structure has both an immutable and a mutable variant, so that the type system will be
    enforcing proper operations on the data during compilation and preventing any accidental
    mutations.

- Unmodified portion of the data is shared between mutations.

    Making a full copy of the entire state for any modification is often an overkill and bad for
    performance, so a path-copy approach is taken when mutating part of the data, which is also
    enforced by the type system.

- Consecutive modifications can be batched.

    This implementation keeps track of the mutation status of each object and reuses objects that
    are already copied to perform further mutations, so that temporary copies won't be unnecessarily
    created.

- No manual `freeze()` calls needed at the end of modifications.

    Thanks to the type system enforced immutability, a mutated data structure can simply be upcasted
    back to its immutable variant at the end of mutations, so that any future modification will
    require a new call to `toMutable()` which ensures a new copy is created. This eliminates a whole
    class of potential issues with a required manual `freeze()` call, which may either be forgotten
    for (part of) the data and result in hard-to-catch bugs, or require correct boilerplate code
    that properly propagates this information across the entire tree of objects.

- Android-specific data structures are included.

    Android has its own collection classes (e.g. `ArrayMap` and `SparseArray`) that are preferred
    (for typical amount of data) for performance reasons, and this implementation provides
    immutability for them via wrapper classes so that the same underlying implementation is used and
    the same performance goals are achieved.

- Android Runtime performance is considered.

    Both the immutable and mutable variants are defined as classes and their member methods are
    final (default in Kotlin), so that the method invocations will be `invoke-direct` and allow
    better AOT compilation.

    The data structure classes here also deliberately chose to not implement the standard
    Java/Kotlin collection interfaces, so that we can enforce that a number of standard Java/Kotlin
    utilities that may be bad for performance or generate interface calls (e.g. Java 8 streams,
    methods taking non-inlined lambdas and kotlin-stdlib extensions taking interfaces) won't be
    accidentally used. We will only add utility methods when necessary and with proper performance
    considerations (e.g. indexed iteration, taking class instead of interface).

## Implementation

### Immutable and mutable classes

In order to leverage the type system to enforce immutability, the core idea is to have both an
immutable and a mutable class for any data structure, where the latter extends the former
(important for `MutableReference` later).

### How mutation works

The primary difficulty in design comes when data structures are composed together in a tree-like
fashion, via map or custom data structures. Specifically, the mutation and copy-on-write would
first happen on the immediate data structure that is being mutated, which would produce a new
instance that contains the mutation, however it is the parent data structure that also needs to know
about this new instance and mutate itself to update its reference to the new child. This problem is
also referred to as "path copying" in persistent data structures.

This design difficulty is solved by the following convention in this implementation. Normally, the
immutable class is good for any read-only access. But when any mutations are needed, it can be
started by calling a `toMutable()` method on the root data structure, which would return a mutable
class over a shallow copy of the existing data. In order to perform the actual mutation deeper in
the tree, a chain of `mutateFoo()` calls will be needed to obtain mutable classes of child data
structures, while these `mutateFoo()` calls are also only available on mutable classes. This way,
proper chain of mutation is also enforced by the type system, and unmodified data is unchanged and
reused.

Here is an example of how this convention would work in the real-world. A read access would just
work as if this implementation isn't involved:

```kotlin
val permission = state.systemState.permissions[permissionName]
```

Whereas the write access would remain similar, which is natural and easy-to-use with safety
guaranteed by the type system:

```kotlin
val newState = state.toMutable()
newState.mutateSystemState().mutatePermissions().put(permission.name, permission)
state = newState
```

### The magic: `MutableReference`

The magic of the implementation for this convention comes from the `MutableReference` class, and
below is a simplified version of it.

```kotlin
class MutableReference<I : Immutable<M>, M : I>(
    private var immutable: I,
    private var mutable: M?
) {
    fun get(): I = immutable

    fun mutate(): M {
        mutable?.let { return it }
        return immutable.toMutable().also {
            immutable = it
            mutable = it
        }
    }

    fun toImmutable(): MutableReference<I, M> = MutableReference(immutable, null)
}

interface Immutable<M> {
    fun toMutable(): M
}
```

Reference to any mutable data structure should be wrapped by this `MutableReference`, which
encapsulates the logic to mutate/copy a child data structure and update the reference to the new
child instance. It also remembers the mutated child instance so that it can be reused during further
mutations. These `MutableReference` objects should be kept private within a data structure, with the
`get()` method exposed on the immutable interface of the data structure as `getFoo()`, and the
`mutate()` method exposed on the mutable interface of the data structure as `mutateFoo()`. When the
parent data structure is mutated/copied, a new `MutableReference` object should be obtained with
`MutableReference.toImmutable()`, which creates a new reference with the state only being immutable
and prevents modifications to an object accessed with an immutable interface.

Here is how the usage of `MutableReference` would be like in an actual class:

```kotlin
private typealias PermissionsReference =
    MutableReference<IndexedMap<String, Permission>, MutableIndexedMap<String, Permission>>

sealed class SystemState(
    protected val permissionsReference: PermissionsReference
) {
    val permissions: IndexedMap<String, Permission>
        get() = permissionsReference.get()
}

class MutableSystemState(
    permissionsReference: PermissionsReference
) : SystemState(permissionsRef), Immutable<MutableSystemState> {
    fun mutatePermissions(): MutableIndexedMap<String, Permission> = permissionsReference.mutate()

    override fun toMutable(): MutableSystemState =
        MutableSystemState(permissionsReference.toImmutable())
}
```

For collection classes like `IndexedMap`, there are also classes like `IndexedReferenceMap` where
the values are held by `MutableReference`s, and a `mutate(key: K): V` method would help obtain a
mutable instance of map values.

## Comparison with similar solutions

### Persistent data structure

[Persistent data structure](https://www.wikiwand.com/en/Persistent_data_structure) is a special type
of data structure implementation that are designed to always preserve the previous version of itself
when it's modified. Copy-on-write data structure is a common example of it.

Theoretically, persistent data structure can help eliminate the need for locking even upon
mutations. However, in reality a lot of mutation operations may be updating multiple places in the
tree of states, and without locking the reader might see an inconsistent state that's right in the
middle of a mutation operation and make a wrong decision. As a result, we will still need locking
upon mutations.

Persistent data structure is also much more complex than a plain mutable data structure, both in
terms of complexity and in terms of performance, and vastly different from the Android-specific
collection classes that are recommended. Whereas this implementation is just a lightweight wrapper
around the Android-specific collection classes, which allows reusing them and following the
guidance for platform code.

### `Snappable` and `Watchable` in `PackageManagerService`

`Snappable` and `Watchable` is an alternative solution for lock contention and immutability.
Basically, all the mutable state classes will need to implement a way to snapshot themselves, and a
cache is used for each level of snapshot to reuse copies; the classes will also need to correctly
implement change notification, so that listeners can be registered to both invalidate snapshot cache
upon change and detect illegal mutations at run time.

Here are the pros and cons of this implementation, when compared with the snapshot solution:

|                        | Snapshot                                                                                                                                                                      | Immutable                                                                                                                                       |
|------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------|
| Locking for reads      | Locked reads when no cached snapshot, lockless when cached                                                                                                                    | Always lockless reads                                                                                                                           |
| Memory footprint       | Doubled memory usage for mutable data because a copy is kept in snapshot cache if ever read                                                                                   | Potentially more than necessary transient memory usage due to immutability instead of on-demand snapshot (may be mitigated for in-process code) |
| Immutability for reads | Enforced during run time by `seal()` and `Watchable`                                                                                                                          | Enforced during compile time by type system                                                                                                     |
| Integration complexity | A `SnapshotCache` field for every existing field, and a correctly implemented `snapshot()` method, keeps Java collection interfaces                                           | Two classes with straightforward accessors for `MutableReference` fields, less room for incorrect code, ditches Java collection interfaces      |
| ART performance        | Non-final methods (may be made final), potential interface calls for Java collection interfaces, `Snappable` and `Watchable` interface and `instanceof` check for `Snappable` | Final methods, can't have interface call for Java/Kotlin collection interfaces, `Immutable` interface but no `instanceof` check                 |

Unlike package state, permission state is far more frequently queried than mutated - mutations
mostly happen upon first boot, or when user changes their permission decision which is rare in terms
of the entire uptime of the system. So reads being always lockless is generally a more suitable
design in terms of performance, and it also allows flexibility in code that have to obtain external
state. This fact has a similar impact on the memory footprint, since most of the time the state will
be unchanged and only read, and we should avoid having to keep another copy of it. Compile time
enforcement of immutability for reads is safer than run time enforcement, and less room for
incorrect integration is also an upside when both require some form of code and permission code is
new. So all in all, the immutable data structure proposed in this document is more suitable for the
new permission implementation.
