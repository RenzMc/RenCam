# RenCam Live Photo — Fix Plan (round 5)

## Masalah yang dilaporkan (dari user)
- [x] **Aspect ratio tidak konsisten**: video (motion) 9:16 tapi cover (foto) 4:3 / 3:4, kadang malah 16:9.
      User mau Live Photo SELALU 9:16 (cover + video), tidak ada ukuran lain.
- [x] **Default setting** harus 9:16 (bukan 4:3 atau yang lain).
- [x] **Flash flicker kurang kelihatan** di video (kecepetan) — perlambat SEDIKIT.
- [x] **Riset timing flash iPhone** yang akurat: pre-flash → main flash → flicker → cover sample.
- [x] Commit + push ke GitHub.

## Akar masalah (hasil investigasi kode)
- [x] Cover (foto) di-crop pakai `getTargetStillAspectRatio()` = rasio picture size (mis. 4:3) → jadi 3:4,
      sedangkan video di-crop ke 9:16 → beda. Kalau picture size 0 (default) cover tidak di-crop sama sekali.
- [x] `getPhotoAspectRatio()` / `getVideoAspectRatio()` default = "default" (native) → 4:3/16:9.
- [x] `LivePhotoManager.cropToAspect()` meng-invert rasio untuk frame portrait → tidak bisa dipakai
      untuk memaksa 9:16 dari frame apa pun orientasinya (harus pakai crop literal di ImageSaver).
- [x] Flash flicker hanya 65ms (215-280ms) → di video 30fps cuma ~2 frame → kurang kelihatan.

## Rencana & hasil
- [x] 1. Live Photo SELALU 9:16:
      - `LivePhotoManager.getTargetVideoAspectRatio()` → selalu 9/16 (video di-crop 9:16).
      - `LivePhotoManager.getTargetStillAspectRatio()` → 0 (serahkan crop ke ImageSaver).
      - `ImageSaver`: kalau `live_photo_cover` → paksa aspect_ratio = 9/16 (crop literal, benar
        untuk frame landscape maupun portrait).
- [x] 2. Default setting 9:16:
      - `preferences.xml`: `preference_photo_aspect_ratio` & `preference_video_aspect_ratio`
        defaultValue → "9:16".
      - `MyApplicationInterface.getPhotoAspectRatio()` & `getVideoAspectRatio()` default → "9:16".
      - `MainActivity.migrateAspectRatioTo916()`: migrasi sekali (one-time) supaya user lama yang
        masih "default" otomatis jadi 9:16.
- [x] 3. Flash: perlambat + perpanjang flicker supaya kelihatan di video 30fps; pre-flash & main
      flash di-tuning dari hasil riset.
- [x] 4. Build lokal (assembleDebug) → BUILD SUCCESSFUL.
- [x] 5. Commit + push ke GitHub (token) → GitHub Actions build APK.

## Hasil riset timing flash iPhone (referensi)
Sumber: AppleInsider (iPhone 14 Pro Adaptive True Tone flash), panduan TTL flash metering,
panduan Slow Sync flash.

- **Pre-flash (TTL metering)**: burst cahaya low-power "almost imperceptible" sebelum main flash,
  dipakai kamera untuk mengukur exposure pantulan cahaya. Terjadi "within hundredths of a second".
  Jarak pre-flash → main flash di sistem TTL umumnya ~1/20 detik (≈ 50 ms) — ini yang kita pakai
  sebagai "dark gap" antara pre-flash dan main flash.
- **Main flash**: burst utama yang benar-benar menerangi subjek; di iPhone ini yang menerangi foto.
  Durasi burst elektronik sangat singkat (orde milidetik), tapi kita tahan window-nya lebih lama
  (~120 ms) supaya frame video 30fps pasti menangkapnya terang.
- **Flicker / Slow Sync**: iPhone (dan flash slow-sync umum) "fires the flash in intervals" —
  beberapa pulse cahaya selama exposure, bukan satu pulse. Ini yang bikin efek kedip khas Live Photo.
  Di video 30fps satu frame ≈ 33 ms, jadi pulse harus ≥ ~66 ms supaya muncul di ≥ 2 frame; kita pakai
  ~140 ms supaya jelas terlihat (sebelumnya 65 ms → cuma ~2 frame → "ga keliatan").
- **Hardware**: iPhone 14 Pro pakai 9 LED dalam grid 3×3 yang bisa dinyalakan per-segmen
  (Adaptive True Tone flash) — makanya pola kedipnya bisa "bertingkat". Kita tiru efeknya lewat
  urutan pre → main → flicker, bukan hardware LED.

### Timing final yang dipakai (LivePhotoManager)
| Tahap        | Mulai | Selesai | Durasi |
|--------------|-------|---------|--------|
| Pre-flash    | 0 ms  | 50 ms   | 50 ms  |
| (dark gap)   | 50 ms | 100 ms  | 50 ms (~1/20s) |
| Main flash   | 100 ms| 220 ms  | 120 ms |
| Flicker      | 280 ms| 420 ms  | 140 ms |
| Cover sample | 160 ms (di dalam window main flash) |

- **Cover sync**: `findFlashFrameMs()` sekarang memilih frame yang hampir paling terang
  (≥ 92% kecerahan maksimum) DAN paling dekat dengan waktu shutter yang diharapkan → selalu jatuh
  di main flash, bukan di flicker yang datang belakangan.
