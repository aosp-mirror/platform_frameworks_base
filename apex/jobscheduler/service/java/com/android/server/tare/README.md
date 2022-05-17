# Overview

Welcome to The Android Resource Economy (TARE for short). If you're reading this, you may be
wondering what all of this code is for and what it means. TARE is an attempt to apply economic
principles to resource (principally battery) management. It acknowledges that battery is a limited
resource on mobile devices and that the system must allocate and apportion those resources
accordingly. Every action (running a job, firing an alarm, using the network, using the CPU, etc.)
has a cost. Once that action has been performed and that bit of battery has been drained, it's no
longer available for someone else (another app) to use until the user charges the device again.

The key tenets of TARE are:

1. Charge for actions --- when an app performs an action, reduce its access to resources in the
   future. This should help remind everyone that everything they do has a cost.
1. Reward for good actions --- reward and encourage behavior that provides value to the user
1. Fine bad actions --- fine and discourage behavior that is bad for the user

In an ideal world, the system could be said to most efficiently allocate resources by maximizing its
profits &mdash; by maximizing the aggregate sum of the difference between an action's price (that
the app ends up paying) and the cost to produce by the system. This assumes that more important
actions have a higher price than less important actions and all actors have perfect information and
convey that information accurately. With these assumptions, maximizing profits implies that the
system runs the most important work first and proceeds in decreasing order of importance. Of course,
that also means the system will not run anything where an app would pay less for the action than the
system's cost to produce that action. Some of this breaks down when we throw TOP apps into the mix
&mdash; TOP apps pay 0 for all actions, even though the CTP may be greater than 0. This is to ensure
ideal user experience for the app the user is actively interacting with. Similar caveats exist for
system-critical processes (such as the OS itself) and apps running foreground services (since those
could be critical to user experience, as is the case for media and navigation apps). Excluding those
caveats/special situations, maximizing profits of actions performed by apps in the background should
be the target.

To achieve the goal laid out by TARE, we use Android Resource Credits (ARCs for short) as the
internal/representative currency of the system.

## How do ARCs work?

ARCs are required to perform any action while in the background. Some actions may have a fixed cost.
Others may be more dynamic (some may even allow apps to bid higher ARCs for some actions to have
them prioritized). If the app doesn't have enough ARCs, the action can't be performed. Apps are
granted ARCs (below a certain threshold) as the device charges. Apps are also granted ARCs for
providing user value (eg. for doing things that engage the user).

ARCs will be used across the entire system as one unified concept. When an app performs an action,
it pulls from the same account, regardless of the action. This means that apps can choose to do more
of one action in lieu of being able to do as much of another. For example, an app can choose to use
all of its ARCs for jobs if it doesn't want to schedule any alarms.

### Scaling

With the ARC system, we can limit the total number of ARCs in circulation, thus limiting how much
total work can be done, regardless of how many apps the user has installed.

## EconomicPolicy

An EconomicPolicy defines the actions and rewards a specific subsystem makes use of. Each subsystem
will likely have a unique set of actions that apps can perform, and may choose to reward apps for
certain behaviors. Generally, the app should be rewarded with ARCs for behaviors that indicate that
the app provided value to the user. The current set of behaviors that apps may be rewarded for
include 1) a user seeing a notification, 2) a user interacting with a notification, 3) the user
opening the app and/or staying in the app for some period of time, 4) the user interacting with a
widget, and 5) the user explicitly interacting with the app in some other way. These behaviors may
change as we determine better ways of identifying providing value to the user and/or user desire for
the app to perform the actions it's requesting.

### Consumption Limit

The consumption limit represents the maximum amount of resources available to be consumed. When the
battery is satiated (at 100%), then the amount of resources available to be consumed is equal to the
consumption limit. Each action has a cost to produce that action. When the action is performed,
those resources are consumed. Thus, when an action is performed, the action's CTP is deducted from
the remaining amount of resources available. In keeping with the tenet that resources are limited
and ARCs are a proxy for battery consumption, the amount of resources available to be consumed are
adjusted as the battery level changes. That is, the consumption limit is scaled based on the current
battery level, and if the amount currently available to be consumed is greater than the scaled
consumption limit, then the available resources are decreased to match the scaled limit.

### Regulation

Regulations are unique events invoked by the ~~government~~ system in order to get the whole economy
moving smoothly.

# Previous Implementations

## V0

The initial implementation/proposal combined the supply of resources with the allocation in a single
mechanism. It defined the maximum number of resources (ARCs) available at a time, and then divided
(allocated) that number among the installed apps, intending to have some left over that could be
allocated as part of the rewards. There were several problems with that mechanism:

1. Not all apps used their credits, which meant that allocating credits to those packages
   effectively permanently reduced the number of usable/re-allocatable ARCs.
1. Having a global maximum circulation spread across multiple apps meant that as more apps were
   installed, the allocation to each app decreased. Eventually (with enough apps installed), no app
   would be given enough credits to perform any actions.

These problems effectively meant that misallocation was a big problem, demand wasn't well reflected,
and some apps may not have been able to perform work even though they otherwise should have been.

Tare Improvement Proposal #1 (TIP1) separated allocation (to apps) from supply (by the system) and
allowed apps to accrue credits as appropriate while still limiting the total number of credits
consumed.

# Potential Future Changes

These are some ideas for further changes. There's no guarantee that they'll be implemented.

* Include additional components and policies for them. TARE may benefit from adding policies for
  components such as broadcast dispatching, network traffic, location requests, and sensor usage.
* Have a separate "account" for critical/special actions. In other words, have two accounts for each
  app, where one acts like a special savings account and is only allowed to be used for special
  actions such as expedited job execution. The second account would have a lower maximum than the
  main account, but would help to make sure that normal actions don't interfere too much with more
  critical actions.
* Transferring credits from one app to another. For apps that rely on others for some pieces of
  work, it may be beneficial to allow the requesting app to transfer, donate, or somehow make
  available some of its own credits to the app doing the work in order to make sure the working app
  has enough credits available to do the work.
* Formulate values based on device hardware. For example, adjust the consumption limit based on the
  battery size, or the price and/or CTP of actions based on hardware efficiency.
* Price discovery via an auction system. Instead of just setting a fixed price that may be modified
  by device and app states, let an app say how much it's willing to pay for a specific action and
  then have a small auction when the system needs to decide which app to perform the action for
  first or how much to charge the app.

# Definitions

* ARC: Android Resource Credits are the "currency" units used as an abstraction layer over the real
  battery drain. They allow the system to standardize costs and prices across various devices.
* Cake: A lie; also the smallest unit of an ARC (1 cake = one-billionth of an ARC = 1 nano-ARC).
  When the apps request to do something, we shall let them eat cake.
* Cost to produce (CTP): An economic term that refers to the total cost incurred by a business to
  produce a specific quantity of a product or offer a service. In TARE's context, CTP is meant to be
  the estimated cost t ohe system to accomplish a certain action. These "actions" are basically APIs
  that apps use to get something done. So the idea is to define the base cost for an app to use a
  specific API.
* Satiated: used to refer to when the device is fully charged (at 100% battery level)