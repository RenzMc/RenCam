# RenCam Live Photo — Fix Plan (round 4)

## Masalah yang dilaporkan (dari user)
- [x] Live Photo kadang jadi foto biasa (JPEG/PNG) — tidak konsisten jadi Live Photo.
- [x] Proses bikin Live Photo harus jalan di LATAR BELAKANG + minta izin berjalan di latar belakang.
- [x] Kalau ambil banyak Live Photo cepat, harus di-antri SATU-SATU sampai tiap Live Photo
      benar-benar sempurna (tidak ada yang jadi foto biasa).
- [x] Flash harus seperti iPhone: hardware flash + capture singkat, lalu flash lagi (efek Flicker).
- [x] DEEP SEARCH timing flash iPhone yang akurat (cari .mov Live Photo iPhone flash on, bongkar timing).
- [x] Push lagi ke GitHub.

## Akar masalah (hasil investigasi kode)
- [x] **Race single-slot**: `waiting_for_cover` + `pending_video_file` cuma 1 slot. Ada jendela
      antara `onPostRollElapsed()` (capturing=false) dan `processCapturedVideo()` (yang baru set
      waiting_for_cover=true) — kalau user mencet shutter di jendela ini, capture KEDUA mulai
      sementara video PERTAMA masih diproses → slot ketimpa → foto pertama jadi JPEG biasa.
- [x] `LivePhotoHelper.containsEmbeddedVideo()` scan 64KB per chunk TANPA overlap → kalau signature
      `ftyp` jatuh di batas chunk, verifikasi gagal → packaging dianggap gagal → JPEG biasa tertinggal.
- [x] Tidak ada retry saat packaging gagal (baik file maupun Uri).
- [x] Timeout 12s bisa menghapus video padahal save masih jalan (storage lambat / antrean save).
- [x] Tidak ada izin/layanan foreground → proses finalisasi bisa dimatikan OS saat app di background.
- [x] Flash: pre-flash + main flash saja; belum ada "flicker" setelah capture seperti iPhone.

## Rencana
- [x] 1. Serialisasi ketat: tambah counter `captures_in_flight`; hanya 1 capture boleh in-flight
      (record → proses → save → package selesai dulu) → hilangkan race single-slot.
- [x] 2. `containsEmbeddedVideo` boundary-safe (overlap 3 byte antar chunk).
- [x] 3. Retry packaging (2x) + fallback pakai video asli (tanpa crop) kalau crop gagal →
      jamin output SELALU Motion Photo, tidak pernah tertinggal sebagai JPEG biasa.
- [x] 4. Timeout cover dinaikkan (30s) + tidak menghapus video selama masih mungkin di-match.
- [x] 5. Foreground service (`LivePhotoService`) + izin `FOREGROUND_SERVICE` (dengan guard versi
      untuk minSdk 15) supaya finalisasi tetap jalan saat app di background.
- [x] 6. Flash iPhone-style: pre-flash → main flash (capture) → flicker flash setelah capture,
      timing di-tuning dari hasil riset web.
- [x] 7. Build lokal (assembleDebug) → BUILD SUCCESSFUL.
- [x] 8. Push ke GitHub (token) → GitHub Actions build APK.

## Hasil DEEP SEARCH timing flash iPhone
Sumber yang dipakai:
- CNET — "Slow Sync Flash" (kamera iPhone): flash dipicu berkali-kali dalam interval singkat
  (bukan satu kilatan), supaya subjek depan tetap tajam sekaligus latar belakang ikut terekspos.
- AppleInsider — iPhone 14 Pro "Adaptive True Tone flash": grid 3×3 berisi sembilan LED yang bisa
  dinyalakan satu-satu; inilah asal efek "kedip-kedip".
- Referensi TTL metering: jeda pre-flash → main-flash kira-kira 1/20 detik (~50ms).

Tidak ditemukan file `.mov` Live Photo iPhone asli (flash on) untuk dibongkar timing persisnya,
jadi dipakai timing hasil riset + timing yang dijelaskan user. Implementasi 3-pulse:
- pre-flash (metering):   0 → 50 ms
- main flash (cover):   100 → 165 ms  (cover di-sample pada 130 ms, di dalam window ini)
- flicker flash:        215 → 280 ms
Tiap pulse ≥ ~50ms supaya pasti tertangkap minimal 1 frame video 30fps.

## Catatan
- Spec Motion Photo: https://developer.android.com/media/platform/motion-photo-format
- Model Live Photo Apple: buffer 1.5s sebelum + 1.5s sesudah shutter (3s total).
