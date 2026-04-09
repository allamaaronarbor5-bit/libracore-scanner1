# LibraCore Real Scanner System

Real-time QR scanning bridge between an Android phone and the LibraCore PC dashboard.

---

## Folder Structure

```
libracore-scanner/
├── LibraCore.html          ← Updated PC dashboard (drop-in replacement)
├── server/
│   ├── server.js           ← Node.js WebSocket relay server
│   └── package.json
└── android/                ← Android Studio project (open this folder)
    ├── settings.gradle
    ├── build.gradle
    └── app/
        ├── build.gradle
        └── src/main/
            ├── AndroidManifest.xml
            ├── java/com/libracore/scanner/
            │   └── MainActivity.kt
            └── res/
                ├── layout/activity_main.xml
                ├── drawable/  (button & input backgrounds, reticle)
                └── values/    (colors, strings, themes)
```

---

## Step 1 — Run the WebSocket Server

```bash
cd server
npm install
node server.js
# → LibraCore Scanner Server running on port 8765
```

Find your PC's local IP:
- Windows: ipconfig → IPv4 Address (e.g. 192.168.1.42)
- macOS/Linux: ifconfig or ip addr

Both PC and phone must be on the same Wi-Fi network.

---

## Step 2 — Open the HTML Dashboard

Open LibraCore.html in a browser on the same PC.

1. Click "Connect Scanner" in the sidebar.
2. You will see a 6-character session code (e.g. A3Z9KX).
3. Enter your server URL: ws://192.168.1.42:8765
4. Click Connect — status turns green when server is reached.

---

## Step 3 — Build & Install the Android App

1. Open the android/ folder in Android Studio (Electric Eel or newer).
2. Wait for Gradle sync.
3. Connect Android phone (USB debugging on) or use emulator.
4. Click Run.

On the phone:
1. Enter server URL: ws://192.168.1.42:8765
2. Enter the 6-character session code shown on PC.
3. Tap Connect to PC.
4. Camera opens — point at any LibraCore QR code.

---

## Data Flow

Android Camera
  → ML Kit detects QR (format: LIBRACORE:BOOK_ID:BOOK_TITLE)
  → OkHttp WebSocket → server.js (Node.js) → PC Browser WebSocket
  → simulateScanById(bookId) called
  → Existing Issue/Collect modal opens

---

## QR Code Format

  LIBRACORE:BOOK_ID:BOOK_TITLE
  Example: LIBRACORE:BK-001:Harry Potter

---

## Troubleshooting

- "Connection failed" on phone → check server running, correct IP, same Wi-Fi
- "Invalid code" → copy code exactly from PC screen
- Scan received but no modal → verify QR format is LIBRACORE:ID:TITLE
- Camera black → grant camera permission in Android Settings

---

## Existing Features — Unchanged

- All QR generation works
- All simulate scan buttons work
- All transactions / issue / collect / malpractice logic untouched
- CSS / layout / design identical
- simulateScanById() called directly with no wrappers
