# GVoice workday field test

Upstream never certified long recordings, screen-off use or wearable reconnection. These can only be checked on a real phone with a real Omi. This test gives repeatable results to act on.

## Before you start

1. Install the latest release. Charge both the phone and the Omi.
2. When GVoice asks to **keep recording with the screen off**, tap **Allow**. To fix it later: **Omi device → Allow background recording**.
3. **Samsung only:** Settings → Battery → Background usage limits → **Never sleeping apps** → add GVoice.
4. Keep notifications on, since the Stop control lives there.

## Scenarios

Note the start time of each scenario. Write down what you see; don't fix anything mid-test.

| # | Do this | Expected |
|---|---|---|
| 1 | Record 1 hour, phone screen off, Omi in normal range | One recording of about 60:00, transcript refines afterwards, "Transcript ready" notification |
| 2 | Record, then walk away from the phone for **1 minute**, come back | Recording continues. The library shows a continuation entry after the gap |
| 3 | Record, then walk away for **3 minutes**, come back | **Known limit:** recording stops after 2 minutes without Omi audio, and the earlier audio is kept. You must tap Connect again |
| 4 | Record, toggle Bluetooth off for 20 s, then on | Recording resumes |
| 5 | Record a full morning (3 to 4 hours) with normal use of the phone | One or a few entries, no crash. Check the battery drop |
| 6 | After 5, leave the phone idle and charging | Every recording ends up refined (no "Refining" chip left) |

## What to send back

- For each scenario: pass or fail, and the status line shown in the recorder panel at the moment it went wrong.
- Battery percentage at the start and end of scenario 5 (phone and Omi).
- Phone model and Android version.
- Anything surprising, with the time it happened.

## Known limit and the next decision

GVoice stops a recording after **2 minutes** without audio from the Omi (scenario 3). This rule is inherited from upstream. It keeps the radio from retrying forever, but it ends the session if you step away. The options are to keep the 2-minute limit, raise it (for example 30 minutes), or make it a setting. Scenario 3's results inform the choice.
