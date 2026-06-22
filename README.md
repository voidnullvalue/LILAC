# LILAC

LILAC is an Android NFC batch-writing app for programming URI payloads onto NFC tags from a CSV file. It guides the operator row-by-row through writing, verifying, optionally locking, skipping, and reworking tags.

## Features

- Batch-load NFC URI payloads from CSV
- Write URI NDEF records to NFC tags
- Verify the same tag was tapped after writing
- Verify the stored URI matches the expected CSV value
- Optional permanent tag locking
- Read-only scan mode for inspecting existing tags
- Displays tag UID, URI, lock state, technologies, and NDEF size
- Audible and vibration cues for write, verify, read, and error states

## Requirements

- Android device with NFC
- Android 8.0 / API 26 or newer
- NFC tags that are NDEF-compatible or NDEF-formatable
- JDK 17 for local builds

## CSV Format

LILAC reads UTF-8 CSV files.

### Required columns

| Column | Required | Description |
|---|---:|---|
| `uri` | Yes | URI payload to write to the NFC tag |

### Optional columns

| Column | Required | Description |
|---|---:|---|
| `id` | No | Human-readable row identifier displayed in the app |

Column names are case-insensitive. Blank rows are ignored. Every non-blank data row must contain a non-empty `uri`.

Example:


id,uri
1001,https://example.com/equipment/1001
1002,https://example.com/equipment/1002
1003,lilac://asset?asset_id=1003

How It Works
Open LILAC on an NFC-capable Android device.
Tap Select CSV.
Choose a CSV file containing at least a uri column.
LILAC loads the rows and starts at row 1.
Tap an NFC tag to write the current row’s URI.
Tap the same tag again to verify it.
LILAC advances to the next row.
Repeat until the batch is complete.

During verification, LILAC checks:

The same tag UID was used when available
The tag contains a URI record
The URI exactly matches the CSV value
If locking is enabled, the tag is no longer writable
Locking Mode

By default, LILAC writes tags without locking them.

The bottom lock toggle enables permanent locking. When enabled, LILAC attempts to make each tag read-only after writing.

Warning: NFC tag locking is permanent on supported tags. Test with expendable tags before using this mode in production.

Read-Only Mode

Read-only mode scans tags without writing or locking anything.

It displays:

URI
Lock state
Tag ID in hex
Tag technologies
NDEF used size and max size

Use this mode to audit or troubleshoot existing tags.

Controls
Control	Purpose
Select CSV	Load a batch file
Pause / Resume	Stop or resume NFC handling
Skip	Move past the current row
Back	Return to a previous row for rewriting
Reset Batch	Clear the loaded CSV and restart
Lock toggle	Enable or disable permanent locking
Read-only mode	Scan tags without modifying them
Building

From the repository root:

./gradlew assembleDebug

The debug APK will be generated under:

app/build/outputs/apk/debug/

For a release build:

./gradlew assembleRelease
Development

This project is a native Android app using:

Kotlin
Jetpack Compose
Android Gradle Plugin
NFC NDEF APIs

Project structure:

app/src/main/java/com/lilac/nfcbatch/
  MainActivity.kt      UI and NFC reader-mode integration
  BatchViewModel.kt    Batch state machine
  CsvLoader.kt         CSV parsing and validation
  NfcWriter.kt         Tag writing and verification
  NfcTagReader.kt      Read-only tag inspection
  SoundCue.kt          Audio and vibration feedback
Troubleshooting
CSV must include a uri column

The selected CSV does not have a uri header. Add a uri column.

Missing uri at CSV row N

A non-blank row has an empty URI value. Fill it in or remove the row.

Unsupported tag

The tag is not NDEF-compatible or NDEF-formatable.

Tag is read-only

The tag is already locked and cannot be rewritten.

Tag is too small

The URI payload is larger than the tag’s available NDEF capacity.

Different tag tapped

Verification requires tapping the same tag that was just written.

URI mismatch

The tag contains a URI, but it does not exactly match the CSV row value.
