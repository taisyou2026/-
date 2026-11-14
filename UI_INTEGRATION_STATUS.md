# Launcher/UI integration scope

JRA-EDGE now has a native launcher Activity and URL-free read-only official
meeting/catalog discovery, venue/race selection, source-missing labels,
historical fixture browsing, PredictionProvider output, capability display and
an initial paper-account screen. The package and six existing audit stores are
unchanged. The original22-race fixture is not modified.

F0 is not generated. Missing F0 remains UNKNOWN and cannot create formal buys.
Obstacle races stop before FrozenMf0Engine. Historical final odds are not used
as prospective market inputs. The UI has no purchase submission path; supplied
F0 output is exercised only by explicit synthetic instrumentation input.

Remaining limitations: live official field coverage and availability must be
measured; fields absent/unrecognized remain UNKNOWN. The current live catalog
path does not establish REAL_PROSPECTIVE, a verified clock, a resolved race ID,
or the complete TIMECHAIN. It must not authorize formal paper buys.

WorkManager/background scheduling, result/settlement UI and existing-account
aggregation are not connected. They are explicitly labeled NOT_CONNECTED or
BLOCKED, not reported as operational. Empty dedicated UI audit stores allow
initial100000/zero totals; nonempty stores cause UNKNOWN until aggregation is
implemented. No stake sizing was invented.

Physical Android device: NOT_TESTED. An emulator pass does not change this.
