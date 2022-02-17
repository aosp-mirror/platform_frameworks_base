Welcome to The Android Resource Economy (TARE for short). If you're reading this, you may be
wondering what all of this code is for and what it means. TARE is an attempt to apply economic
principles to resource (principally battery) management. It acknowledges that battery is a limited
resource on mobile devices and that the system must allocate and apportion those resources
accordingly. Every action (running a job, firing an alarm, using the network, using the CPU,
etc.) has a cost. Once that action has been performed and that bit of battery has been drained, it's
no longer available for someone else (another app) to use until the user charges the device again.

The key tenets of TARE are:

1. Charge for actions --- when an app performs an action, reduce its access to resources in the
   future. This should help remind everyone that everything they do has a cost.
1. Reward for good actions --- reward and encourage behavior that provides value to the user
1. Fine bad actions --- fine and discourage behavior that is bad for the user

# Details

To achieve the goal laid out by TARE, we introduce the concept of Android Resource Credits
(ARCs for short).

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

# Definitions

* ARC: Android Resource Credits are the "currency" units used as an abstraction layer over the real
  battery drain. They allow the system to standardize costs and prices across various devices.
* NARC: The smallest unit of an ARC. A narc is 1 nano-ARC.
* Satiated: used to refer to when the device is fully charged (at 100% battery level)