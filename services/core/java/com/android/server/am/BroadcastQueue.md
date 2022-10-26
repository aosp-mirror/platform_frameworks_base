# Broadcast Queue Design

Broadcast intents are one of the major building blocks of the Android platform,
generally intended for asynchronous notification of events. There are three
flavors of intents that can be broadcast:

* **Normal** broadcast intents are dispatched to relevant receivers.
* **Ordered** broadcast intents are dispatched in a specific order to
receivers, where each receiver has the opportunity to influence the final
"result" of a broadcast, including aborting delivery to any remaining receivers.
* **Sticky** broadcast intents are dispatched to relevant receivers, and are
then retained internally for immediate dispatch to any future receivers. (This
capability has been deprecated and its use is discouraged due to its system
health impact.)

And there are there two ways to receive these intents:

* Registered receivers (via `Context.registerReceiver()` methods) are
dynamically requested by a running app to receive intents. These requests are
only maintained while the process is running, and are discarded at process
death.
* Manifest receivers (via the `<receiver>` tag in `AndroidManifest.xml`) are
statically requested by an app to receive intents. These requests are delivered
regardless of process running state, and have the ability to cold-start a
process that isn't currently running.

## Per-process queues

The design of `BroadcastQueueModernImpl` is centered around maintaining a
separate `BroadcastProcessQueue` instance for each potential process on the
device. At this level, a process refers to the `android:process` attributes
defined in `AndroidManifest.xml` files, which means it can be defined and
populated regardless of the process state. (For example, a given
`android:process` can have multiple `ProcessRecord`/PIDs defined as it's
launched, killed, and relaunched over long periods of time.)

Each per-process queue has the concept of a _runnable at_ timestamp when it's
next eligible for execution, and that value can be influenced by a wide range
of policies, such as:

* Which broadcasts are pending dispatch to a given process. For example, an
"urgent" broadcast typically results in an earlier _runnable at_ time, or a
"delayed" broadcast typically results in a later _runnable at_ time.
* Current state of the process or UID. For example, a "cached" process
typically results in a later _runnable at_ time, or an "instrumented" process
typically results in an earlier _runnable at_ time.
* Blocked waiting for an earlier receiver to complete. For example, an
"ordered" or "prioritized" broadcast typically results in a _not currently
runnable_ value.

Each per-process queue represents a single remote `ApplicationThread`, and we
only dispatch a single broadcast at a time to each process to ensure developers
see consistent ordering of broadcast events. The flexible _runnable at_
policies above mean that no inter-process ordering guarantees are provided,
except for those explicitly provided by "ordered" or "prioritized" broadcasts.

## Parallel dispatch

Given a collection of per-process queues with valid _runnable at_ timestamps,
BroadcastQueueModernImpl is then willing to promote those _runnable_ queues
into a _running_ state. We choose the next per-process queue to promote based
on the sorted ordering of the _runnable at_ timestamps, selecting the
longest-waiting process first, which aims to reduce overall broadcast dispatch
latency.

To preserve system health, at most
`BroadcastConstants.MAX_RUNNING_PROCESS_QUEUES` processes are allowed to be in
the _running_ state at any given time, and at most one process is allowed to be
_cold started_ at any given time. (For background, _cold starting_ a process
by forking and specializing the zygote is a relatively heavy operation, so
limiting ourselves to a single pending _cold start_ reduces system-wide
resource contention.)

After each broadcast is dispatched to a given process, we consider dispatching
any additional pending broadcasts to that process, aimed at batching dispatch
to better amortize the cost of OOM adjustments.

## Starvation considerations

Careful attention is given to several types of potential resource starvation,
along with the mechanisms of mitigation:

* A per-process queue that has a delayed _runnable at_ policy applied can risk
growing very large. This is mitigated by
`BroadcastConstants.MAX_PENDING_BROADCASTS` bypassing any delays when the queue
grows too large.
* A per-process queue that has a large number of pending broadcasts can risk
monopolizing one of the limited _runnable_ slots. This is mitigated by
`BroadcastConstants.MAX_RUNNING_ACTIVE_BROADCASTS` being used to temporarily
"retire" a running process to give other processes a chance to run.
* An "urgent" broadcast dispatched to a process with a large backlog of
"non-urgent" broadcasts can risk large dispatch latencies. This is mitigated
by maintaining a separate `mPendingUrgent` queue of urgent events, which we
prefer to dispatch before the normal `mPending` queue.
* A process with a scheduled broadcast desires to execute, but heavy CPU
contention can risk the process not receiving enough resources before an ANR
timeout is triggered. This is mitigated by extending the "soft" ANR timeout by
up to double the original timeout length.
