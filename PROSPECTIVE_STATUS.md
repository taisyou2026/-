# Prospective Runtime integration

The canonical source identity is year(4)+venue(2)+meeting(2)+day(2)+race(2),
validated against official date/meeting/race blocks. UNRESOLVED is not sent to
Runtime. No F0 generator or stake sizing has been added.

Registration is an append-only FETCH record, not an inference from today's
calendar date. Results known, historical input, late registration, absent
identity/source proof or unverified clocks cannot authorize a new registration.
HTTP Date/receipt wall time/elapsed realtime are retained. Clock tolerance is
10000ms, with reboot/stale-receipt detection. Timechain uses the unchanged
1200±180,600±120,300±75,120±45 second windows. WorkManager is best effort; missed
windows are unavailable, never substituted or widened.

Six stores remain physically separate. Result links are discovered from official
menus and verified against body identity. Existing settleOnce handles payments;
the adapter scales official per100 payout by the EXISTING recorded stake only.
A missing stake/selection cannot be invented. No normal path generates a formal
BUY while F0 and the stake rule are unavailable. Paper aggregation reads actual
FORMAL BUY and SETTLEMENT rows; no new balance DB is used.

WorkManager uses unique race/stage tasks and persisted registrations. Android
rescheduling and source failures can delay observations. Explicit stop/relaunch
and repeat-work tests do not establish force-stop survival, power-loss recovery,
or manufacturer battery-policy behavior. These remain separate physical gates.

Live probes are read-only and report actual eligibility and missing milestones.
A passing no-retroactive-credit test is NOT a successful pre-race observation.
If execution is after the race day, registration/milestone success remains zero
and the real pre-race probe Gate remains NOT_MET until an eligible source window.

ANDROID_PHYSICAL_DEVICE=NOT_TESTED. REAL_MONEY=BLOCKED.
