# RevMarkup for Android

Pen-first PDF markup for the OnePlus Pad (Android 10+, arm64). Same PDF comment format as the desktop RevMarkup:
markups saved here stay editable on the PC and the other way round.

- **Build:** every push to `main` builds a signed APK (GitHub Actions) and publishes it as a Release.
- **Install:** on the tablet open the latest Release, tap `RevMarkup-x.y.z.apk` (allow "install unknown apps" once).
- **Use:** Pen draws · fingers pan / zoom · double-tap zoom · swipe = next sheet · two-finger tap = undo · pen button = eraser.

PDF engine: PDFium (bblanchon/pdfium-binaries chromium/8165, checksum-pinned), BSD-3-Clause / Apache-2.0.
