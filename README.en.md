<div align="center">

<img src="docs/logo.png" width="120" alt="IRIS Music">

### A local music player

True refractive liquid glass · 4 layouts · custom palettes · fully offline · no account

*A player I wanted, so I built it.*

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
![Kotlin](https://img.shields.io/badge/Kotlin-1.9-7F52FF?logo=kotlin&logoColor=white)
![Compose](https://img.shields.io/badge/Jetpack%20Compose-UI-4285F4?logo=jetpackcompose&logoColor=white)
![Android](https://img.shields.io/badge/Android-7.0%2B-3DDC84?logo=android&logoColor=white)
![Network](https://img.shields.io/badge/Network-None-ee0000?logo=ghost&logoColor=white)

[中文](README.md) · [Features](#-features) · [Install](#-install) · [License](#%EF%B8%8F-license) · [Disclaimer](REMIND.md)

</div>

---

IRIS Music is a **personal project**: no team, no KPIs, not commercial. It just does what I wanted — spreading the music on your phone across simple cards, with every byte kept on-device.

> ⚠️ Please read [`REMIND.md`](REMIND.md) (license & disclaimer) first. This software is provided **AS IS**, with **no warranty**.

## ✨ Features

| | |
|:--|:--|
| 🎨 **Many themes** | Monochrome plus three color sets (Neon Pink / Deep Sea / Grass Green) and a **custom** mode — pick any primary/secondary color live, layered over dark / light / auto. |
| 🧠 **On-device recommendation** | Rare among local players. Scores your whole library from likes, play counts, skips and genre weighting, with a tunable "exploration" dial to balance favorites vs. discovery. |
| 🧭 **One library, four layouts** | Vertical list / horizontal cards / stacked / record wall. Layout, palette, light-dark and material are each an independent axis — mix freely, nothing hardcoded. The record-wall interaction is inspired by [Folia](https://github.com/chthollyphile/folia-major), used with the original author's permission; the code is an independent implementation. |
| ✨ **Novel touches** | Bass-driven haptic heartbeat, a year-long listening heatmap report, an equalizer you drag by anchors on the frequency-response curve, jelly spring animations, and more. |

> 🔒 **Offline by default.** Playlists, favorites and listening stats stay on your device. No ads, no telemetry. The only exception is the opt-in “Check for updates” switch (Settings → About, off by default): when enabled and manually tapped, it makes a single one-way GET to the GitHub API to read the latest version number — nothing is uploaded. Turning it off restores full offline mode.

## 📸 Screenshots

<table>
<tr>
<td align="center"><img src="docs/screenshots/Main_f.png" width="200"><br><sub>Playlist</sub></td>
<td align="center"><img src="docs/screenshots/Playcard1_f.png" width="200"><br><sub>Play card</sub></td>
<td align="center"><img src="docs/screenshots/Playcard2_f.png" width="200"><br><sub>Play card</sub></td>
</tr>
<tr>
<td align="center"><img src="docs/screenshots/Playcard3_f.png" width="200"><br><sub>Play card · stacked</sub></td>
<td align="center"><img src="docs/screenshots/Report_f.png" width="200"><br><sub>Listening report</sub></td>
<td></td>
</tr>
</table>

## 📥 Install

Grab `IRISMusic-v3.7.0-arm64-v8a.apk` from **[Releases](releases/latest)** (arm64 — most phones from the last few years) and install it.

Grant the "read audio" permission on first launch. For floating lyrics, also enable "display over other apps" in system settings.

## ⚖️ License

Copyright © 2026 **WWRJ**.

The **project's own source code** is licensed under the **GNU General Public License v3.0** ([`LICENSE`](LICENSE)). In short: **use, modify and redistribute freely; if you distribute a modified version, the whole derivative must also be open-sourced under GPL-3.0.** See [`REMIND.md`](REMIND.md) and [`NOTICE`](NOTICE).

This project ships no audio content; imported audio is the user's own responsibility.

<div align="center"><sub>Made with 🎧 by WWRJ</sub></div>