# Optional FreeType components

The music glyph-mask and music-metrics components use FreeType 2.14.3 under the
FreeType License. Portions of this software are copyright © 2026 The FreeType
Project (www.freetype.org). All rights reserved.

The verified upstream source archive has SHA-256
`36bc4f1cc413335368ee656c42afca65c5a3987e8768cc28cf11ba775e785a5f`.
The archived runnable components include its `LICENSE.TXT`, `FTL.TXT`, `GPLv2.TXT`
and the original source archive. The source URL and compiler settings are recorded
in the build/provenance files.

Emscripten 4.0.20 runtime and musl notices are included alongside these artifacts.
Music font files are the upstream Audiveris resources and retain their original
font metadata and licensing.

The logical-symbol component also accepts explicit physical fonts for ASCII text-symbol
roles. Its verification bundle includes the JVM-resolved DejaVu Sans file, its SHA-256
and the distribution's DejaVu copyright/license file (`text-font-COPYRIGHT`). Runtime
font selection uses the supplied role bindings and bytes, with no OS font discovery.
