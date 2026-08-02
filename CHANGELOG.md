# Changelog

## 0.1.1-test2

Hardware-log-driven compatibility update:

- added legacy CSR notification fallback when a Charge 3 exposes no CCCD;
- added ATT MTU 517 negotiation before JBL protocol requests;
- paced initial requests so responses are not consistently attributed one command late;
- improved resynchronisation after repeated or truncated frame prefixes;
- made reconnect jobs cancellable and stale-GATT callbacks ignorable;
- reset retry counters only after a complete JBL session reaches `READY`;
- enforced a genuine automatic reconnect limit;
- expanded unit coverage for the Charge 4 response pattern.

## 0.1.0-test1

Initial clean Android test implementation:

- per-speaker GATT state and operation queue;
- scanning and model identification;
- information, firmware, feedback, speakerphone and bass requests;
- rename, identification, bass and channel commands;
- copyable diagnostics;
- experimental AUX pilot;
- no firmware flashing.
