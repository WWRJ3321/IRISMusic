<div align="center">

<img src="docs/logo.png" width="96" alt="IRIS Music">

# IRIS Music

**A quiet, local music player**

Your music, laid out as simple cards. No network, no account — your data stays with you.

[![Release](https://img.shields.io/github/v/release/WWRJ3321/IRISMusic?style=flat-square&color=111111&label=release)](https://github.com/WWRJ3321/IRISMusic/releases/latest)
[![License](https://img.shields.io/badge/license-GPL--3.0-111111?style=flat-square)](LICENSE)
![Android](https://img.shields.io/badge/Android-7.0%2B-111111?style=flat-square)
![Network](https://img.shields.io/badge/network-none-111111?style=flat-square)

[Download](https://github.com/WWRJ3321/IRISMusic/releases/latest) · [Features](#features) · [Screenshots](#screenshots) · [中文](README.md) · [Disclaimer](REMIND.md)

</div>

<br>

<table>
<tr>
<td align="center"><img src="docs/screenshots/Main_f.png" width="190"><br><sub>Library</sub></td>
<td align="center"><img src="docs/screenshots/Playcard1_f.png" width="190"><br><sub>Player card</sub></td>
<td align="center"><img src="docs/screenshots/Playcard3_f.png" width="190"><br><sub>Stack</sub></td>
</tr>
</table>

<br>

## What's new in 5.0

- **Rhythm haptics, rebuilt**: vibrates on the beat, stays still in quiet passages, and lines up automatically with speaker or Bluetooth latency.
- **Smoother**: playback progress refreshes on its own, so large libraries and track changes no longer drag the whole UI.
- **Tidier**: settings folded from 9 sections into 5; one consistent set of easing, expand and sheet animations.
- **Details**: a color-picker square for custom themes, translations grouped under lyrics, full-cover lyrics in stack and carousel, click-free effect toggles, and 20+ fixes.

Full notes in the [Release](https://github.com/WWRJ3321/IRISMusic/releases/latest).

## Features

**What you see**
- Four layouts: list, carousel, stack and poster wall. Layout, colors, light/dark and material are independent — mix freely.
- Three materials: solid, frosted glass, and true-refraction liquid glass.
- Colors: Mono, Ocean, Grass, or your own primary and secondary picked from a color square.
- Lyrics: full-screen scrolling lyrics, karaoke highlight, floating lyrics, cover lyrics.

**What you hear**
- Free-curve equalizer, plus virtual bass, wide surround, range enhancer, safe limiter and loudness normalization.
- Rhythm haptics: your phone taps along with the kick drum, harder on stronger beats.

**What you don't see**
- On-device recommendations scored from likes, plays and skips, with an adjustable "exploration" level.
- Listening report with your most-played songs and listening stats.
- Offline by default. The only request ever made is a GitHub API call when you turn on "Check for updates" yourself — no telemetry.

> The poster-wall interaction is inspired by [Folia](https://github.com/chthollyphile/folia-major), used with the author's permission and implemented independently.

## Screenshots

<table>
<tr>
<td align="center"><img src="docs/screenshots/Playcard2_f.png" width="190"><br><sub>Player card</sub></td>
<td align="center"><img src="docs/screenshots/Report_f.png" width="190"><br><sub>Listening report</sub></td>
<td align="center"><img src="docs/screenshots/Recordwall1_f.png" width="190"><br><sub>Poster wall</sub></td>
</tr>
</table>

## Install

Download `IRISMusic-*-arm64-v8a.apk` from [Releases](https://github.com/WWRJ3321/IRISMusic/releases/latest) (nearly every recent phone is arm64) and install it.

Grant "read audio" on first launch. Floating lyrics also need "display over other apps" in system settings.

## About

IRIS Music is a personal project — no team, no KPIs, not commercial. It's simply the player I want to use.

Please read [REMIND.md](REMIND.md) first. The software is provided as is, without warranty, and contains no music; you are responsible for the audio you import.

## License

Copyright © 2026 WWRJ

The original source code is licensed under [GPL-3.0](LICENSE): use, modify and distribute freely; if you distribute a modified version, the whole derivative work must also be open-sourced under GPL-3.0. See [REMIND.md](REMIND.md) and [NOTICE](NOTICE).

<br>
<div align="center"><sub>Made by WWRJ</sub></div>
