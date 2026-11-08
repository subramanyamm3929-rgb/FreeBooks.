# Free Books (Android)
Read free, legal books (Project Gutenberg, via the Gutendex API) fully offline.

## Run it
1. Open this folder in Android Studio (latest stable). Let Gradle sync; bump versions if it suggests.
2. Run on a phone or emulator.

## How it works
- Discover tab: Sync (or search) to list books, then Download to save one for offline reading.
- My books tab: opens saved books with the internet blocked inside the reader.
- Sync checks the newest books (marked NEW) and re-checks your downloaded books for changes (marked UPDATED).
  It runs daily in the background when internet is available, or when you tap Sync.
- Network is used only by Net.kt (search, sync, download).
