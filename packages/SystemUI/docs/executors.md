# Executors

go/sysui-executors

[TOC]

## TLDR

In SystemUI, we are encouraging the use of Java's [Executor][Executor] over
Android's [Handler][Handler] when shuffling a [Runnable][Runnable] between
threads or delaying the execution of a Runnable. We have an implementation of
Executor available, as well as our own sub-interface,
[DelayableExecutor][DelayableExecutor] available. For test,
[FakeExecutor][FakeExecutor] is available.

[Executor]: https://developer.android.com/reference/java/util/concurrent/Executor.html
[Handler]: https://developer.android.com/reference/android/os/Handler.html
[Runnable]: https://developer.android.com/reference/java/lang/Runnable.html
[DelayableExecutor]: /packages/SystemUI/src/com/android/systemui/util/concurrency/DelayableExecutor.java
[FakeExecutor]: /packages/SystemUI/tests/utils/src/com/android/systemui/util/concurrency/FakeExecutor.java

## Rationale

Executors make testing easier and are generally more flexible than Handlers.
They are defined as an interface, making it easy to swap in fake implementations
for testing. This also makes it easier to supply alternate implementations
generally speaking - shared thread pools; priority queues; etc.

For testing, whereas a handler involves trying to directly control its
underlying Looper (using things like `Thread.sleep()` as well as overriding
internal behaviors), an Executor implementation can be made to be directly
controllable and inspectable.

See also go/executors-for-the-android-engineer

## Available Executors

At present, there are two interfaces of Executor avaiable, each implemented, and
each with two instances - `@Background` and `@Main`.

### Executor

The simplest Executor available implements the interface directly, making
available one method: `Executor.execute()`. You can access an implementation of
this Executor through Dependency Injection:

```java
   public class Foobar {
       @Inject
       public Foobar(@Background Executor bgExecutor) {
           bgExecutor.execute(new Runnable() {
             // ...
           });
       }
   }
```

`@Main` will give you an Executor that runs on the ui thread. `@Background` will
give you one that runs on a _shared_ non-ui thread. If you ask for an
non-annotated Executor, you will get the `@Background` Executor.

We do not currently have support for creating an Executor on a new, virgin
thread. We do not currently support any sort of shared pooling of threads. If
you require either of these, please reach out.

### DelayableExecutor

[DelayableExecutor][DelayableExecutor] is the closest analogue we provide to
Handler. It adds `executeDelayed(Runnable r, long delayMillis)` and
`executeAtTime(Runnable r, long uptimeMillis)` to the interface, just like
Handler's [postDelayed][postDelayed] and [postAtTime][postAttime]. It also adds
the option to supply a [TimeUnit][TimeUnit] as a third argument.

A DelayableExecutor can be accessed via Injection just like a standard Executor.
In fact, at this time, it shares the same underlying thread as our basic
Executor.

```java
 public class Foobar {
     @Inject
     public Foobar(@Background DelayableExecutor bgExecutor) {
         bgExecutor.executeDelayed(new Runnable() {
           // ...
         }, 1, TimeUnit.MINUTES);
     }
 }
```

Unlike Handler, the added methods return a Runnable that, when run, cancels the
originally supplied Runnable if it has not yet started execution:

```java
 public class Foobar {
     @Inject
     public Foobar(@Background DelayableExecutor bgExecutor) {
         Runnable cancel = bgExecutor.executeDelayed(new Runnable() {
           // ...
         }, 1, TimeUnit.MINUTES);

         cancel.run();  // The supplied Runnable will (probably) not run.
     }
 }
```

[postDelayed]: https://developer.android.com/reference/android/os/Handler#postDelayed(java.lang.Runnable,%20long)
[postAttime]: https://developer.android.com/reference/android/os/Handler#postAtTime(java.lang.Runnable,%20long)
[TimeUnit]: https://developer.android.com/reference/java/util/concurrent/TimeUnit

## Moving From Handler

Most use cases of Handlers can easily be handled by the above two interfaces
above. A minor refactor makes the switch:

Handler       | Executor  | DelayableExecutor
------------- | --------- | -----------------
post()        | execute() | execute()
postDelayed() | `none`    | executeDelayed()
postAtTime()  | `none`    | executeAtTime()

There are some notable gaps in this implementation: `Handler.postAtFrontOfQueue()`.
If you require this method, or similar, please reach out. The idea of a
PriorityQueueExecutor has been floated, but will not be implemented until there
is a clear need.

Note also that "canceling" semantics are different. Instead of passing a `token`
object to `Handler.postDelayed()`, you receive a Runnable that, when run,
cancels the originally supplied Runnable.

### Message Handling

Executors have no concept of message handling. This is an oft used feature of
Handlers. There are (as of 2019-12-05) 37 places where we subclass Handler to
take advantage of this. However, by-and-large, these subclases take the
following form:

```Java
mHandler = new Handler(looper) {
    @Override
    public void handleMessage(Message msg) {
        switch (msg.what) {
            case MSG_A:
                handleMessageA();
                break;
            case MSG_B:
                handleMessageB((String) msg.obj);
                break;
            case MSG_C:
                handleMessageC((Foobar) msg.obj);
                break;
            // ...
        }
    }
};

// Elsewhere in the class
void doSomething() {
    mHandler.obtainMessage(MSG_B, "some string");
    mHandler.sendMessage(msg);
}
```

This could easily be replaced by equivalent, more direct Executor code:

```Java
void doSomething() {
    mExecutor.execute(() -> handleMessageB("some string"));
}
```

If you are posting Runnables frequently and you worry that the cost of creating
anonymous Runnables is too high, consider creating pre-defined Runnables as
fields in your class.

If you feel that you have a use case that this does not cover, please reach out.

### ContentObserver

One notable place where Handlers have been a requirement in the past is with
[ContentObserver], which takes a Handler as an argument. However, we have created
[ExecutorContentObserver], which is a hidden API that accepts an [Executor] in its
constructor instead of a [Handler], and is otherwise identical.

[ContentObserver]: https://developer.android.com/reference/android/database/ContentObserver.html
[ExecutorContentObserver]: /core/java/android/database/ExecutorContentObserver.java

### Handlers Are Still Necessary

Handlers aren't going away. There are other Android APIs that still require them.
Avoid Handlers when possible, but use them where necessary.

## Testing (FakeExecutor)

We have a [FakeExecutor][FakeExecutor] available. It implements
DelayableExecutor (which in turn is an Executor). It takes a FakeSystemClock in
its constructor that allows you to control the flow of time, executing supplied
Runnables in a deterministic manner.

The implementation is well documented and tested. You are encouraged to read and
reference it, but here is a quick overview:

<table>
    <tr>
        <th>Method</th>
        <th>Description</th>
    </tr>
    <tr>
        <td>execute()</td>
        <td>
            Queues a Runnable so that it is "ready"
            to run. (A Runnable is "ready" when its
            scheduled time is less than or equal to
            the clock.)
        </td>
    </tr>
    <tr>
        <td>postDelayed() & postAtTime()</td>
        <td>
            Queues a runnable to be run at some
            point in the future.
        </td>
    </tr>
    <tr>
        <td>runNextReady()</td>
        <td>
            Run one runnable if it is ready to run
            according to the supplied clock.
        </td>
    </tr>
    <tr>
        <td>runAllReady()</td>
        <td>
            Calls runNextReady() in a loop until
            there are no more "ready" runnables.
        </td>
    </tr>
    <tr>
        <td>advanceClockToNext()</td>
        <td>
            Move the internal clock to the item at
            the front of the queue, making it
            "ready".
        </td>
    </tr>
    <tr>
        <td>advanceClockToLast()</td>
        <td>
            Makes all currently queued items ready.
        </td>
    </tr>
    <tr>
        <td>numPending()</td>
        <td>
            The number of runnables waiting to be run
            They are not necessarily "ready".
        </td>
    </tr>
    <tr>
        <td>(static method) exhaustExecutors()</td>
        <td>
            Given a number of FakeExecutors, it
            calls runAllReady() repeated on them
            until none of them have ready work.
            Useful if you have Executors that post
            work to one another back and forth.
        </td>
    </tr>
</table>

_If you advance the supplied FakeSystemClock directly, the FakeExecutor will
execute pending Runnables accordingly._ If you use the FakeExecutors
`advanceClockToNext()` and `advanceClockToLast()`, this behavior will not be
seen. You will need to tell the Executor to run its ready items. A quick example
shows the difference:

Here we advance the clock directly:

```java
FakeSystemClock clock = new FakeSystemClock();
FakeExecutor executor = new FakeExecutor(clock);
executor.execute(() -> {});             // Nothing run yet. Runs at time-0
executor.executeDelayed(() -> {}, 100); // Nothing run yet. Runs at time-100.
executor.executeDelayed(() -> {}, 500); // Nothing run yet. Runs at time-500.

clock.synchronizeListeners(); // The clock just told the Executor it's time-0.
                              // One thing run.
clock.setUptimeMillis(500);   // The clock just told the Executor it's time-500.
                              // Two more items run.
```

Here we have more fine-grained control:

```java
FakeSystemClock clock = new FakeSystemClock();
FakeExecutor executor = new FakeExecutor(clock);
executor.execute(() -> {});             // Nothing run yet. Runs at time-0
executor.executeDelayed(() -> {}, 100); // Nothing run yet. Runs at time-100.
executor.executeDelayed(() -> {}, 500); // Nothing run yet. Runs at time-500.

executor.runNextReady();        // One thing run.
executor.advanceClockToNext();  // One more thing ready to run.
executor.runNextReady();        // One thing run.
executor.runNextReady();        // Extra calls do nothing. (Returns false).
executor.advanceClockToNext();  // One more thing ready to run.
executor.runNextReady();        // Last item run.
```

One gotcha of direct-clock-advancement: If you have interleaved Runnables split
between two executors like the following:

```java
FakeSystemClock clock = new FakeSystemClock();
FakeExecutor executorA = new FakeExecutor(clock);
FakeExecutor executorB = new FakeExecutor(clock);
executorA.executeDelayed(() -> {}, 100);
executorB.executeDelayed(() -> {}, 200);
executorA.executeDelayed(() -> {}, 300);
executorB.executeDelayed(() -> {}, 400);
clock.setUptimeMillis(500);
```

The Runnables _will not_ interleave. All of one Executor's callbacks will run,
then all of the other's.

### Testing Handlers without Loopers

If a [Handler] is required because it is used by Android APIs, but is only
used in simple ways (i.e. just `Handler.post(Runnable)`), you may still
want the benefits of [FakeExecutor] when writing your tests, which
you can get by wrapping the [Executor] in a mock for testing. This can be
done with `com.android.systemui.util.concurrency.mockExecutorHandler` in
`MockExecutorHandler.kt`.

### TestableLooper.RunWithLooper

As long as you're using FakeExecutors in all the code under test (and no
Handlers or Loopers) you don't need it. Get rid of it. No more TestableLooper;
no more Looper at all, for that matter.
