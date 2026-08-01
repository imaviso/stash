# Context — Stash

Domain language and module map for architectural work. Use these nouns exactly.

## Domain terms

- **Account** — a named S3-compatible endpoint configuration (endpoint, region, access/secret keys). One account is *active* at a time. Stored encrypted (`ConfigRepository`).
- **Bucket** — top-level S3 container.
- **Object** — a file or folder inside a bucket. Folders are zero-byte objects whose key ends in `/`.
- **Object key** — the full path string of an object (`photos/2024/cat.jpg`). Grammar: `/`-separated segments, trailing `/` = folder. Owned by `ObjectKey` (data/model).
- **Prefix** — the current folder key the browser is navigated into (`""` = bucket root).
- **Transfer** — an upload or download of one or more objects, foreground or background. Owned by the transfer module; records have terminal states (COMPLETED/FAILED/CANCELLED) that are final.
- **Presigned URL** — time-limited signed URL for direct object access (sharing, streaming download, media preview). Produced by `Presigner`.
- **Preview** — in-app rendering of an object (image, PDF, video, audio, text). Policy (stream vs bytes, size cap) owned by `PreviewController`.
- **Share-to-upload** — receiving files via Android share sheet and uploading them to a bucket/prefix.
- **Storage stats** — per-bucket usage breakdown by file type.
- **App lock** — optional biometric gate on app entry, enforced by `AppLockGate` in both activities.

## Module map (deepening work)

- `data/model/ObjectKey.kt` — value type owning key grammar (join, parent/child, folder rule, encoding).
- `data/remote/S3Service.kt` + `data/remote/Presigner.kt` — S3 transport. Port seam: callers depend on the operations interface, AWS SDK is the production adapter, in-memory fake is the test adapter.
- `data/ObjectOperations.kt` — multi-object orchestration (recursive delete, paste/copy, folder download, storage stats).
- `data/transfer/TransferManager.kt` — single authority for transfer records + execution dispatch (WorkManager adapter for background, in-process adapter for foreground). Terminal states final.
- `data/repository/ConfigRepository.kt` — accounts (encrypted), active-account pointer, nav state, app-lock flag. Process-wide singleton.
- `data/PendingDeletes.kt` — application-scoped undo-delete commit owner (survives navigation).
- `ui/preview/PreviewController.kt` — preview policy + per-item source resolution; screens render thinly.
- `ui/viewmodel/NavigationHistory.kt`, `ui/viewmodel/ThumbnailCache.kt` — nav back-stack (+ persistence adapter) and presigned thumbnail TTL cache, extracted from ObjectsViewModel.
- `worker/` — WorkManager adapters (upload/download execution), route through the S3 port and transfer module.

## Architecture vocabulary

Follows `improve-codebase-architecture` LANGUAGE.md: **module** (anything with interface + implementation), **interface** (everything a caller must know), **depth** (leverage at the interface), **seam** (where behavior can be altered without editing in place), **adapter** (concrete thing satisfying an interface at a seam). One adapter = hypothetical seam; two adapters = real seam. The interface is the test surface.
