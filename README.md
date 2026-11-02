<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="assets/hero-dark.svg">
    <img src="assets/hero-light.svg" width="100%" alt="Latitude. Room to grade. A camera app for Android that records footage built for DaVinci Resolve.">
  </picture>
</p>

<p align="center">
  <a href="#how-it-works">How it works</a>&nbsp;&nbsp;·&nbsp;&nbsp;<a href="#principles">Principles</a>&nbsp;&nbsp;·&nbsp;&nbsp;<a href="#status">Status</a>&nbsp;&nbsp;·&nbsp;&nbsp;<a href="#build">Build</a>
</p>

<p align="center">
  <b>Development prototype.</b> Built for the Xiaomi 15 Ultra. Not yet tested on a device.
</p>

<br>

<h2 align="center" id="how-it-works">Lands natively in Resolve.</h2>

<p align="center">
  Latitude records Apple Log on Rec.2020 primaries, a curve DaVinci Resolve already knows.<br>
  Tag the clip once and it arrives in DaVinci Wide Gamut, ready for the same look as every other camera.
</p>

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="assets/pipeline-dark.svg">
    <img src="assets/pipeline-light.svg" width="100%" alt="Locked capture, recorded as Apple Log in Rec.2020 and 10-bit HEVC, tagged as Apple Log in DaVinci Resolve, lands in DaVinci Wide Gamut with your look unchanged.">
  </picture>
</p>

<p align="center"><sub>Apple Log needs the phone’s image processor to honor a custom tone curve in 10-bit.<br>Where it doesn’t, Latitude records clean, locked HLG instead. It never fakes log from an HLG image.</sub></p>

<br>

<h2 align="center" id="principles">Built to be trusted.</h2>

<p align="center">
  A phone that changes exposure mid-take, sharpens every edge and drifts in frame rate is hard to grade.<br>
  Latitude is designed around the opposite.
</p>

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="assets/principles-dark.svg">
    <img src="assets/principles-light.svg" width="100%" alt="Ready instantly: opens straight into a record-ready preview, volume buttons start and stop. Nothing moves mid-clip: exposure, white balance and focus lock on record. Constant frame rate: 30p, stored as 29.97 like DJI and Sony do. No baked-in look: no sharpening, no LUT, no AI.">
  </picture>
</p>

<p align="center"><sub>Design goals. What already works is listed under Status.</sub></p>

<br>

<h2 align="center" id="status">Measure first.</h2>

<p align="center">
  Every stage ships only after it passes measurement on the device:<br>
  grey card and exposure series, banding, dynamic range and a frame-timing check with <code>ffprobe</code>.
</p>

<div align="center">

| Stage | What | Status |
| :--- | :--- | :--- |
| 0 | Capability probe: what the camera, ISP and encoder really offer | Done |
| 1 | Clean HLG 10-bit with exposure, white balance and focus locked | Done |
| 2 | Apple Log through the ISP tone curve, tagged so Resolve picks it up | Done |
| 3 | RAW to Apple Log on the GPU | Parked |

</div>

<p align="center">
  <b>Working today:</b> instant-start pipeline, volume-button recording, AE · AWB · AF lock,<br>
  open gate 4096×3072 HEVC 10-bit at 150 Mbit/s, Apple Log · HLG · Rec.709, matched colors on every lens,<br>
  24-bit PCM sound (Resolve on Linux decodes no AAC), crash-safe files, zebras, thermal warning.
</p>

<br>

<h2 align="center" id="build">Build and run.</h2>

<p align="center">
  Android 14 or later. Kotlin, Jetpack Compose and Camera2.<br>
  Needs JDK 21 and the Android SDK; the scripts expect them in <code>~/Android</code>.
</p>

```sh
scripts/setup-usb.sh   # once: allow adb to reach the phone over USB (asks for sudo)
scripts/phone.sh       # build the optimized app, install it and show the startup time
scripts/phone.sh pull  # copy probe results and test clips into testclips/
```

<p align="center"><sub>DaVinci Resolve picks the input color space by itself: <b>Apple Log</b>, Rec.2100 HLG or Rec.709.</sub></p>

<br>
<br>

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="assets/wordmark-dark.svg">
    <img src="assets/wordmark-light.svg" width="220" alt="Latitude">
  </picture>
</p>
