# Hardware test findings

This document intentionally omits Bluetooth MAC addresses and other device-identifying details.

## JBL Charge 4 — test1

Observed variant:

- model ID `0x1F29`;
- Connect+ generation;
- QCC-family advertisement mapping.

Confirmed behaviour:

- Android BLE connection succeeded;
- JBL service discovery succeeded;
- notification setup succeeded;
- speaker information and settings responses were received;
- stereo, left and right channel writes each received JBL acknowledgement `AA 00 02 15 00`.

Issues identified in test1:

- the full speaker-information response exceeded the default ATT payload size;
- repeated 20-byte response prefixes could be incorrectly joined into a fabricated frame;
- requests were sent immediately after GATT write completion, while JBL responses arrived later, making responses appear one command behind;
- bass writes reached the characteristic, but the test1 trace did not prove speaker-side application.

Resulting test2 changes:

- request MTU 517;
- pace initial requests;
- improve frame resynchronisation and repeated-prefix handling.

## JBL Charge 3 — test1

Observed variant:

- model ID `0x1EBC`;
- CSR-family implementation;
- Connect+ capable hardware revision.

Confirmed behaviour:

- Android BLE connection succeeded repeatedly;
- JBL service discovery succeeded repeatedly.

Failure identified in test1:

- the Charge 3 notification characteristic exposed no standard CCCD;
- test1 treated the missing descriptor as fatal and deliberately disconnected;
- the retry counter was reset too early and stale scheduled reconnects were not cancelled, producing an uncontrolled reconnect loop.

Resulting test2 changes:

- permit legacy local-notification setup without a CCCD;
- keep one cancellable reconnect task per speaker;
- ignore stale callbacks from superseded GATT objects;
- reset retry accounting only after the JBL session becomes ready;
- stop automatically after the configured retry limit.

## Next validation

Test2 should be tested against one powered speaker at a time before simultaneous operation:

1. Charge 3: connect, remain idle, refresh, identify, then one channel command.
2. Charge 4: repeat the same sequence and confirm the complete speaker-information response parses.
3. Power-cycle each speaker and inspect reconnect behaviour.
4. Finally power both speakers and confirm that one session cannot block the other.
