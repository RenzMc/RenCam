# RenCam Live Photo — Fix Plan (round 3)

## Masalah yang dilaporkan
- [x] Live Photo mati kalau HDR / fitur bawaan kamera diaktifkan (X-HDR, X-Night, X-Bokeh, X-Beauty, Panorama).
- [x] Timing kadang perfect kaya iPhone, kadang acak-acakan (tidak konsisten).
- [x] Format file harus sesuai / setara iPhone.
- [x] Cari 1 hal lain yang kurang dan perbaiki.
- [x] Bikin README.md singkat & clean.

## Akar masalah (hasil investigasi)
- [x] Camera2 extension session (X_*) & Panorama memblokir perekaman video
      (`BLOCK_FOR_EXTENSIONS()` melempar exception di `initVideoRecorderPrePrepare/PostPrepare`),
      jadi buffer Live Photo gagal start -> Live Photo tidak jalan.
- [x] Kalau buffer gagal start, shutter malah menghasilkan TIDAK ADA foto sama sekali
      (shutter di-swallow, tidak fallback ke foto biasa).
- [x] Buffer tidak pernah di-retry kalau gagal start -> Live Photo mati sampai preview restart.
- [x] Timeline video bisa drift dari wall-clock -> cover frame kadang kena flash, kadang tidak.

## Rencana
- [x] 1. Paksa mode kamera yang kompatibel video saat Live Photo aktif (fallback ke Standard).
- [x] 2. Retry start buffer kalau gagal.
- [x] 3. Fallback ke foto biasa kalau buffer benar-benar tidak bisa jalan (jangan sampai no photo).
- [x] 4. Jamin timing konsisten: cover frame di-sync ke flash (cari frame paling terang).
- [x] 5. Verifikasi format Motion Photo sesuai spec (XMP + MP4 di akhir + nama "MP").
- [x] 6. Build lokal (assembleDebug) -> BUILD SUCCESSFUL.
- [x] 7. Push ke GitHub + GitHub Actions build APK.
- [x] 8. README.md singkat & clean.

## "One more thing" (hal lain yang kurang)
- [x] Lencana LIVE di viewfinder kini bisa di-tap untuk ON/OFF cepat (terang = aktif,
      redup = nonaktif), tidak perlu masuk Pengaturan.

## Catatan
- Spec: https://developer.android.com/media/platform/motion-photo-format
