# RenCam Live Photo — Fix Plan (round 2)

## Masalah baru yang dilaporkan
- [x] WhatsApp tidak bisa memutar penuh 3 detik — hanya "part flash" saja (TikTok bisa penuh).
- [x] Foto Live diambil 9:16 (portrait) tapi hasilnya jadi 16:9 (landscape).

## Akar masalah (hasil investigasi)
- [x] Filename Motion Photo HARUS diakhiri "MP" sebelum ekstensi (spec Google).
      RenCam menamai `IMG_...jpg` -> WhatsApp (yang mengikuti spec) mengabaikan video.
- [x] Saat trim video (MediaExtractor + MediaMuxer), rotasi video HILANG karena
      MediaMuxer tidak memakai setOrientationHint -> video jadi landscape.
- [x] Cover still diekstrak dari video tanpa menerapkan rotasi -> hasil 16:9.
- [x] Durasi video buffer kadang < 3s (frame terakhir tidak ter-flush).

## Rencana
- [x] 1. Pertahankan rotasi saat trim (muxer.setOrientationHint) -> video portrait benar
- [x] 2. Terapkan rotasi saat ekstrak cover frame -> still tidak lagi 16:9
- [x] 3. Crop still ke aspect ratio foto (picture size) supaya cocok dengan preview
- [x] 4. Tambah "MP" pada nama file Live Photo (spec Motion Photo)
- [x] 5. Beri margin perekaman supaya durasi video selalu >= 3s
- [x] 6. Build lokal (assembleDebug) -> verifikasi (BUILD SUCCESSFUL)
- [x] 7. Push ke GitHub (commit 11c523e) + GitHub Actions build APK (run 36574733243 SUCCESS, artifact RenCam-APK 8.5 MB)

## Catatan riset
- Spec: https://developer.android.com/media/platform/motion-photo-format
  - Filename regex: ^([^\s\/\\][^\/\\]*MP)\.(JPG|jpg|JPEG|jpeg|HEIC|heic|AVIF|avif)
  - "Readers may ignore the XMP metadata, the appended video file, or the video
    contents if the pattern is not followed."
- WhatsApp mendukung Motion Photo (rollout Sep 2025) dan mengikuti spec.
