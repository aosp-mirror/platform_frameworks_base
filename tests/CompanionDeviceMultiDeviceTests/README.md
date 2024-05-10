## CDM Multi-device Tests

### Device Setup
To test on physical devices, connect _two_ devices locally and enable USB debugging setting on both devices.

When running on a cloudtop or other remote setups, use pontis to connect the devices on remote set up by running `pontis start`.
Verify that pontis client is connected via `pontis status` and confirm that both devices are in "connected" state via `adb devices`.

See go/pontis for more details regarding this workflow.

To test on virtual devices, follow instructions to [set up netsim on cuttlefish](https://g3doc.corp.google.com/ambient/d2di/sim/g3doc/guide/cuttlefish.md?cl=head).
Launch _two_ instances of virtual devices by specifying `--num_instances=2` parameter.

### Running the Test
```
atest CompanionDeviceManagerMultiDeviceTestCases
```
