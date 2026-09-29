# RenCam

**RenCam** is a rebranded fork of [Open Camera](https://sourceforge.net/projects/opencamera/)
(originally by Mark Harman), customized by **RenzMc**.

RenCam keeps everything that makes Open Camera great — a fully featured, open-source
camera app with manual controls, HDR, panorama, RAW, and Camera2 API support — and adds:

* **Bilingual UI** — full **Indonesian (Bahasa Indonesia)** and **English** support, with an
  in-app language selector.
* **Live Photo (Motion Photo)** — capture a short video around the moment you press the
  shutter and save it as a single Google Motion Photo (JPEG + embedded MP4 + XMP).
* **Correct Live Photo flash logic** — the flash fires as a *momentary burst* at the instant
  of still capture, **not** as a continuous torch during the video.

---

## 1. Rebranding

Everything has been renamed from `opencamera` to `RenCam`:

| Item | Old | New |
|------|-----|-----|
| App name | Open Camera | **RenCam** |
| Java package | `net.sourceforge.opencamera` | `com.renzmc.rencam` |
| Application ID | `net.sourceforge.opencamera` | `com.renzmc.rencam` |
| Author | Mark Harman | **RenzMc** |
| Application class | `OpenCameraApplication` | `RenCamApplication` |

---

## 2. Languages (Indonesian + English)

* `res/values/strings.xml` — **English** (default).
* `res/values-in/strings.xml` — **Indonesian** translation.
* `Settings → Language` lets the user choose **System default / Indonesian / English**.
  The choice is applied in `MainActivity.attachBaseContext()` and the activity is recreated
  when the setting changes.

---

## 3. Live Photo (Motion Photo)

### 3.1 How it works

RenCam's Live Photo works the way Apple/Google Live Photos do, adapted for devices that
don't natively support the feature:

1. While the camera is open in **photo mode** with Live Photo enabled, a low-overhead video
   is **continuously buffered** to a temporary file (the *pre-roll*).
2. When you press the shutter, RenCam records the exact timestamp, captures the high-resolution
   still, and fires the flash as a **momentary burst** for the still.
3. Recording continues for the configured *post-roll*.
4. The buffered video is trimmed to `[shutter − preroll, shutter + postroll]` and packaged
   together with the still JPEG into a single **Motion Photo** file — a JPEG with an embedded
   MP4 and Google Motion Photo XMP metadata.

### 3.2 Settings

`Settings → Live Photo`:

| Setting | Description | Default |
|---------|-------------|---------|
| Enable Live Photo | Turn the feature on/off | off |
| Video duration | Total length of the Live Photo (1–6 s) | 3 s |
| Pre-roll | How much video *before* the shutter is kept | 1.5 s |
| Flash behaviour | `Burst` (recommended) / `Torch` / `Off` | Burst |
| Record audio | Include audio in the Live Photo video | off |

### 3.3 Flash logic (important)

The common mistake is to leave the flash on as a **continuous torch** while recording the
video. RenCam does **not** do this. Instead:

```
CAMERA START
  └─ start video buffer (flash OFF)
        └─ USER PRESSES SHUTTER
              ├─ determine exposure / focus
              ├─ FLASH BURST (momentary, for the still only)
              ├─ capture high-res still
              └─ continue video ±window
        └─ stop buffer
  └─ JPEG (still) + MP4 (video) → Motion Photo
```

The still corresponds to a specific timestamp inside the video, stored in the XMP attribute
`GCamera:MotionPhotoPresentationTimestampUs`.

### 3.4 Implementation map

| File | Responsibility |
|------|----------------|
| `livephoto/LivePhotoHelper.java` | Low-level Motion Photo packaging: XMP injection into JPEG, MP4 extraction, video trimming (MediaExtractor + MediaMuxer), frame extraction. |
| `livephoto/LivePhotoManager.java` | Orchestration: buffer lifecycle, shutter timing, flash-burst decision, clipping, packaging. |
| `preview/Preview.java` | Recording primitive: `startLivePhotoBuffer()` / `stopLivePhotoBuffer()`. |
| `MyApplicationInterface.java` | Integration hooks: `onPreviewStarted/Stopped()`, `onBeforeStillCapture()`, `onPictureTaken()`, `addLastImage()`. |

The Motion Photo format written by RenCam is compatible with Google Photos, which recognises
the file as a Motion Photo and plays the embedded video when the still is viewed.

---

## 4. Building

RenCam is a standard Gradle Android project. Open the `opencamera` folder in Android Studio
(or run `./gradlew assembleDebug` with the Android SDK installed).

* `compileSdkVersion 33`, `targetSdkVersion 33`, `minSdkVersion 15`.
* Live Photo requires **Android 4.3 (API 18)** or higher (for `MediaMuxer`); the feature is
  automatically disabled on older devices.

---

## 5. Credits & licence

RenCam is based on **Open Camera** by Mark Harman, licensed under the **GNU GPL v3**.
The video→Motion Photo conversion approach was studied from the
[MotionCraft](https://github.com/WeiErLiTeo/MotionCraft) project.

Modifications and the Live Photo feature © **RenzMc**.
