# Test fixtures

`app/src/test/resources/epubs/` holds the EPUB fixtures from the CrossPoint firmware:

- Source: `crosspoint-reader/crosspoint-reader`, path `test/epubs/*.epub`
- Commit: `177d0694013feb89e6ae93e16569df28ac819746` (`develop`, 2026-11-22)
- License: MIT, same project; see `docs/porting/upstream.md`

Do not edit these files. They are the shared acceptance corpus, so a fixture change means the
upstream corpus changed. When that happens:

1. Re-copy the files from the pinned upstream commit.
2. Update the expected count in `EpubFixturesTest`.
3. Note the new commit in `docs/porting/upstream.md`.

What each fixture stresses (from the firmware's test names):

| Fixture | Stresses |
|---|---|
| `crosspoint-table-edge-cases.epub` | Table layout edge cases |
| `font-prewarm-benchmark.epub` | Font cache warm-up (generated; see its script upstream) |
| `test_br_section_break.epub` | `<br>` and section breaks |
| `test_dictionary_synonyms.epub` | Dictionary lookup with synonyms |
| `test_display_none.epub` | `display:none` |
| `test_emoji_ranges.epub` | Emoji and astral-plane glyphs |
| `test_force_paragraph_indents.epub` | Indent overrides |
| `test_hidden.epub` | Hidden elements |
| `test_jpeg_images.epub` | JPEG decoding and scaling |
| `test_kerning_ligature.epub` | Kerning and ligatures |
| `test_mixed_images.epub` | Mixed image formats |
| `test_png_images.epub` | PNG decoding |
| `test_reader_menu_progress.epub` | Reader menu progress display |
| `test_reader_rendering_matrix.epub` | Rendering option matrix |
| `test_supsub.epub` | Superscript and subscript |
| `test_synthetic_unicode_glyphs.epub` | Synthetic Unicode coverage |
| `test_tables.epub` | Full table rendering |
| `test_text_decorations.epub` | Decorations and emphasis |
