// SPDX-License-Identifier: AGPL-3.0-or-later
/** Render trusted interface copy as text, with English first and Japanese below. */
export function setBilingual(element, english, japanese) {
    const en = document.createElement('span'), ja = document.createElement('span');
    en.lang = 'en'; en.className = 'bilingual-en'; en.textContent = english;
    ja.lang = 'ja'; ja.className = 'bilingual-ja'; ja.textContent = japanese;
    element.replaceChildren(en, ja);
}
