<p align="center">
  <img src="docs/assets/branding/ghxst-lens-logo-main.png" alt="GHXST Lens" width="320" />
</p>

<h1 align="center">GHXST Lens</h1>

<p align="center">
  <strong>Low-latency Android camera source for OBS Studio.</strong>
</p>

<p align="center">
  Turn an Android phone into a clean native OBS camera source over LAN — with very low CPU usage, native OBS integration, and no browser-source bloat.
</p>

<p align="center">
  <a href="https://github.com/ghxstlab/ghxst-lens/releases/tag/beta-0.5.0-beta.1">
    <img src="https://img.shields.io/badge/release-0.5.0--beta.1-8b5cf6?style=for-the-badge" alt="Release">
  </a>
  <img src="https://img.shields.io/badge/status-beta-22c55e?style=for-the-badge" alt="Status">
  <img src="https://img.shields.io/badge/platform-Windows%20%2B%20Android-0ea5e9?style=for-the-badge" alt="Platform">
  <img src="https://img.shields.io/badge/OBS-native%20plugin-f97316?style=for-the-badge" alt="OBS Plugin">
</p>

<p align="center">
  <a href="#features">Features</a> •
  <a href="#downloads">Downloads</a> •
  <a href="#quick-start">Quick Start</a> •
  <a href="#performance">Performance</a> •
  <a href="#roadmap">Roadmap</a>
</p>

---

## What is GHXST Lens?

**GHXST Lens** is a low-latency Android camera streaming system for **OBS Studio**.

It lets an Android phone act as a native OBS camera source over your local network using:

```text
Android Camera2 + MediaCodec
→ TCP LAN stream
→ Native OBS plugin
→ Direct GPU decode/render path
→ OBS scene
