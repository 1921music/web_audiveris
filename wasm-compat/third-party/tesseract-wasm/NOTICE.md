# Optional OCR dependencies

The selected backend's package licenses are copied from the pinned npm packages
into `ocr/licenses/`. tesseract-wasm 0.11.0 is BSD-2-Clause, copyright 2022
Robert Knight and tesseract-wasm contributors. Its bundled JavaScript includes
Comlink (Apache-2.0, Google LLC).

The published OCR core embeds Tesseract 5.3.0 (Apache-2.0) and Leptonica 1.83.1
(BSD-2-Clause), per tesseract-wasm v0.11.0's `third_party_versions.mk`.
Unmodified upstream license texts and their source hashes are included here.
The English recognition data comes from the pinned `@tesseract.js-data/eng`
1.0.0 npm package; its Tesseract model originates from the Apache-2.0
Tesseract tessdata project. The package wrapper declares MIT licensing.

Upstream sources:

- https://github.com/robertknight/tesseract-wasm/tree/v0.11.0
- https://github.com/tesseract-ocr/tesseract/tree/5.3.0
- https://github.com/DanBloomberg/leptonica/tree/1.83.1
- https://github.com/GoogleChromeLabs/comlink/tree/v4.4.2
- https://github.com/naptha/tessdata

The browser adapter and packaging changes in this repository do not modify
these third-party binaries or models.
