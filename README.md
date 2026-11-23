# ClearScan

Android batch document scanner with on-device **ML Kit OCR**, clarity enhancement filters, **Searchable PDF 1.4** generation, and image/PDF export.

## Features
- **Batch Camera Scanner (CameraX + Live OCR)**: Continuous multi-page capture with live OCR line counter, alignment grid, flash cycling, scan mode presets (`Document`, `Receipt / ID`, `Notes`, `Color Page`), and multi-photo import via Android Photo Picker.
- **Batch Studio & Smart Suggestions**:
  - One-tap **Title Suggestions** extracted from OCR headings and document types.
  - One-tap **Page Clarity & Border Suggestions** (`Auto-Trim Borders`, `Apply Crisp B&W`, `Boost with Magic Color`, `Rotate 90°`).
  - Interactive 4-corner crop, 90° rotation, page reordering, and clarity filters (`Original`, `Magic Color`, `Crisp B&W`, `Grayscale`).
- **Searchable PDF & Formatted OCR**:
  - Embeds crisp enhanced page scans with an invisible (`3 Tr`) selectable/searchable OCR text layer aligned to word bounding boxes.
  - Preserves column and paragraph layout for plain text and text-only PDF exports.
  - Full-text search across the document library and highlighted search matches directly over scanned pages.
  - Smart detected entity chips (`Email`, `URL`, `Phone`, `Amount`) in the document viewer.
- **Export PDF & Images**:
  - Save Searchable PDF or Formatted Text-Only PDF via Storage Access Framework.
  - Share PDFs or page JPEGs via `FileProvider`.
  - Export all page images directly to `Pictures/ClearScan` via `MediaStore`.

## Installing APK from GitHub

1. Push this repository to GitHub (`main` or `master` branch, or a `v*` tag).
2. GitHub Actions (`.github/workflows/build-apk.yml`) automatically runs the unit tests and builds `ClearScan-installable.apk`.
3. Download the APK on your Android phone from either:
   - **Releases** → `ClearScan Latest APK` → `ClearScan-installable.apk`, or
   - **Actions** → latest workflow run → **Artifacts** → `ClearScan-APK`.
4. Open the `.apk` file on your Android device and confirm installation.

### Optional Release Keystore Signing
To also produce `ClearScan-release.apk` signed with your own upload key in GitHub Actions, add these repository secrets:
- `KEYSTORE_BASE64`: Base64-encoded `.jks` keystore
- `STORE_PASSWORD`: Keystore password
- `KEY_PASSWORD`: `upload` key alias password
