# TODO

## Current TODOs

* Investigate using Robolectric for testing, and eliminating any interfaces that don't serve a genuine semantic purpose,
  and only exist to enable testing. Add tests for any remaining code that is worth testing.

* Investigate automatically generating build artifacts.

## API Redesign Notes

These TODO items are intended for the eventual v2 API redesign. They may be more applicable to the server, but this is
the most organized place to keep them for now.

* Add the ability to submit multiple reports in a single request. Probably want some basic validation that all the
  submitted reports were actually received and processed.

* Eliminate the separate signature input. Figure out a way to just sign the entire report as submitted. This eliminates
  the need to match exact strings. The server can just validate the signature, then interpret the data as a report.
  Numbers can be sent as numbers instead of strings, etc.

* I'm not too concerned about rate limiting due to the project scope, but it makes sense to at least include the
  possibility of standard rate limiting responses and make sure the client can respect them.

## Far Future TODOs (if this program reaches a wider audience)

* Support for submitting to other services such as Dawarich or OwnTracks.

* Make update interval user configurable, instead of hardcoding 5 seconds.

* Along with the above, it may be the right time to investigate at least optionally using FusedLocationProviderClient
  for battery savings. In addition, the actual requested update interval could be adjusted, instead of implementing it
  only through self-throttling. Different users may have different targets on the accuracy/frequency/battery tradeoff.

* Look into adding the ability to restart on boot if already running. Permission issues seem like the biggest issue. It
  doesn't seem that you can launch a location foreground service from the boot receiver unless you have the background
  location permission. In addition, one source indicated that you shouldn't request both foreground location and
  background location at the same time. Further investigation is required.
