# RenCam

**RenCam** is a camera app for Android, forked from [Open Camera](https://sourceforge.net/projects/opencamera/)
and rebuilt by **RenzMc**. Its main purpose is simple: it brings **Live Photo** to *any* Android
phone — including old and budget devices that never had it.

A **Live Photo** is a still photo that also contains a short video of the moment around it
(the same idea as an iPhone Live Photo). RenCam saves it as a standard **Google Motion Photo**
(one `.jpg` file with the photo + a 3-second MP4 + the right metadata), so it works with Google
Photos, and can be shared like any normal photo.

---

## Features

- **Live Photo** — 1.5 s before + 1.5 s after the shutter (3 s total), like an iPhone.
- **Works on old phones** — no special hardware needed; it just records a short video.
- **Apple-style flash** — a short flash *burst* at the moment of capture, not a torch for the
  whole clip.
- **Front & back camera**, ultra-wide 0.5× toggle, and all the usual Open Camera controls
  (manual exposure, HDR, panorama, RAW, Camera2, etc.).
- **Bilingual UI** — English and Bahasa Indonesia.

---

## How to use Live Photo

1. Open RenCam and make sure you are in **photo mode** (not video).
2. Tap the **LIVE** badge in the corner of the viewfinder to turn Live Photo **on**.
   (You can also enable it in **Settings → Live Photo**.) The badge is bright when it is on.
3. Frame your shot and press the shutter. Hold still for a moment — RenCam records a short clip
   around the shot and saves it as a Live Photo.
4. Open it in the in-app gallery or Google Photos; tap **play** (or long-press) to see the motion.

> **Tip:** While Live Photo is on, the camera uses a video-capable session. Photo modes that
> can't record video (Panorama and the `X-…` extension modes) are automatically skipped so the
> Live Photo always works — RenCam shows a small message if you pick one.

---

## Install

1. Go to the **Actions** tab of this repository and open the latest **Build RenCam APK** run.
2. Download the **RenCam-APK** artifact (a `.zip` containing the APK).
3. Unzip, copy the `.apk` to your phone, and install it (allow "install from unknown sources").
4. Requires **Android 4.3 (Jelly Bean MR2)** or newer.

---

## Build from source

```bash
git clone https://github.com/RenzMc/RenCam.git
cd RenCam
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

You need the Android SDK and JDK 17. Pushing to `main` also builds the APK automatically via
GitHub Actions.

---

## Cara pakai (Bahasa Indonesia)

1. Buka RenCam, pastikan di **mode foto** (bukan video).
2. Ketuk lencana **LIVE** di sudut layar untuk mengaktifkan Live Photo (atau lewat
   **Pengaturan → Live Photo**). Lencana jadi terang kalau aktif.
3. Tekan tombol jepret dan tahan sebentar. RenCam merekam klip 3 detik di sekitar momen foto
   dan menyimpannya sebagai Live Photo.
4. Buka di galeri aplikasi atau Google Photos, lalu tekan **play** untuk melihat gerakannya.

---

## Credits & License

Based on **Open Camera** by Mark Harman. RenCam is licensed under the **GPLv3** (see `gpl-3.0.txt`).
