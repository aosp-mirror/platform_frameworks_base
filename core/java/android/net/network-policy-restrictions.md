# Data Saver vs Battery Saver

The tables below show whether an app has network access while on background depending on the status of Data Saver mode, Battery Saver mode, and the app's allowlist on those restricted modes.

### How to read the tables

The 2 topmost rows define the Battery Saver mode and whether the app is allowlisted or not for it.
The 2  leftmost columns define the Data Saver mode and whether the app is allowlisted, not allowlisted, or denylisted for it.
The cells define the network status when the app is on background.

More specifically:

* **DS ON**: Data Saver Mode is on
* **DS OFF**: Data Saver Mode is off
* **BS ON**: Battery Saver Mode is on
* **BS OFF**: Battery Saver Mode is off
* **AL**: app is allowlisted
* **!AL**: app is not allowlisted
* **DL**: app is denylisted
* **ok**: network access granted while app on background (NetworkInfo's state/detailed state should be `CONNECTED` / `CONNECTED`)
* **blk**: network access blocked while app on background (NetworkInfo's state/detailed state should be `DISCONNECTED` / `BLOCKED`)


## On metered networks

|         |       | BS   | ON    | BS   | OFF   |
|:-------:|-------|------|-------|------|-------|
|         |       | *AL* | *!AL* | *AL* | *!AL* |
| **DS**  |  *AL* |  ok  | blk   |  ok  |  ok   |
| **ON**  | *!AL* | blk  | blk   | blk  | blk   |
|         |  *DL* | blk  | blk   | blk  | blk   |
| **DS**  |  *AL* |  ok  | blk   |  ok  |  ok   |
| **OFF** | *!AL* |  ok  | blk   |  ok  |  ok   |
|         |  *DL* | blk  | blk   | blk  | blk   |


## On non-metered networks

|         |       | BS   | ON    | BS   | OFF   |
|:-------:|-------|------|-------|------|-------|
|         |       | *AL* | *!AL* | *AL* | *!AL* |
| **DS**  |  *AL* |  ok  | blk   |  ok  |  ok   |
| **ON**  | *!AL* |  ok  | blk   |  ok  |  ok   |
|         |  *DL* |  ok  | blk   |  ok  |  ok   |
| **DS**  |  *AL* |  ok  | blk   |  ok  |  ok   |
| **OFF** | *!AL* |  ok  | blk   |  ok  |  ok   |
|         |  *DL* |  ok  | blk   |  ok  |  ok   |
