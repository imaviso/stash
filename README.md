# Stash

A simple Android app for browsing S3-compatible storage. Built with Jetpack Compose and Material 3.

> **Note:** This is a personal project for personal use.

## Features

- **File browser** - Navigate buckets and folders with list or grid view
- **File operations** - Upload, download, rename, delete files and folders
- **Multi-select** - Copy, cut, paste, and bulk delete files
- **File preview** - View images, PDFs, play videos/audio with streaming support
- **Share-to-upload** - Share files from any app directly to S3
- **Presigned URL sharing** - Generate shareable links with configurable expiration
- **Background transfers** - Uploads and downloads continue when app is backgrounded
- **Storage stats** - View storage usage breakdown by file type
- **Recursive folder operations** - Download or delete entire folders
- **Search** - Find files within the current directory
- **Sorting** - Sort by name, date, or size (ascending/descending)
- **Breadcrumb navigation** - Quickly navigate folder hierarchy

## S3 Compatibility

Designed for [Garage](https://garagehq.deuxfleurs.fr/) but should work with any S3-compatible storage:
- AWS S3
- MinIO
- Backblaze B2
- Cloudflare R2
- DigitalOcean Spaces

## Requirements

- Android 7.0+ (API 24)
- S3-compatible storage endpoint

## Building

This project uses [Nix](https://nixos.org/) for reproducible builds.

```bash
# Enter development environment
nix develop

# Build debug APK
./gradlew assembleDebug

# Build release APK (signed)
./gradlew assembleRelease

# Install to connected device
./gradlew installDebug
```

### Without Nix

Requires:
- JDK 17
- Android SDK (API 34)
- Kotlin 1.9+

```bash
./gradlew assembleDebug
```

## APK Locations

- Debug: `app/build/outputs/apk/debug/app-debug.apk`
- Release: `app/build/outputs/apk/release/app-release.apk`

## Project Structure

```
app/src/main/java/com/imaviso/stash/
├── data/
│   ├── model/          # Data classes (S3Object, S3Config, etc.)
│   ├── remote/         # S3 service with AWS SDK
│   └── repository/     # DataStore persistence
├── ui/
│   ├── navigation/     # Navigation graph
│   ├── screens/        # Compose screens
│   ├── theme/          # Material 3 theming
│   └── viewmodel/      # ViewModels
├── worker/             # WorkManager for background transfers
├── MainActivity.kt
├── ShareReceiverActivity.kt  # Handles share intents
└── StashApplication.kt
```

## Configuration

When first launching the app, add an S3 account with:
- **Name** - Display name for the account
- **Endpoint** - S3 endpoint URL (e.g., `http://192.168.1.100:3900`)
- **Region** - S3 region (e.g., `garage`, `us-east-1`)
- **Access Key** - Your access key ID
- **Secret Key** - Your secret access key

## License

MIT License - see [LICENSE](LICENSE) file.

## Author

imaviso
