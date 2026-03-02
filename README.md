# Ping

A lightweight Android library for discovering and exchanging profiles with nearby devices over Bluetooth Low Energy (BLE). When two devices running a Ping-enabled app come within range, they automatically exchange whatever data you put in their `PingProfile`.

---

## Installation

Add JitPack to your root `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

Add the dependency to your app's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.YourGitHubUsername:ping:1.0.0")
}
```

---

## Permissions

Add these to your `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

<uses-feature android:name="android.hardware.bluetooth_le" android:required="true" />
```

Register the service and boot receiver:

```xml
<service
    android:name="jr.brian.ping.PingService"
    android:foregroundServiceType="connectedDevice"
    android:exported="false" />

<receiver
    android:name="jr.brian.ping.BootReceiver"
    android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED" />
    </intent-filter>
</receiver>
```

Request `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, and `BLUETOOTH_ADVERTISE` at runtime before starting the service (Android 12+).

---

## Quick Start

### 1. Build a profile

```kotlin
val profile = PingProfile(
    displayName = "Skye",
    customData = mapOf(
        "status" to "looking for a match".toPingValue(),
        "score"  to 1500.toPingValue(),
        "active" to true.toPingValue()
    )
)
```

### 2. Listen for encounters

Set the callback before starting the service:

```kotlin
PingService.onEncounter = { deviceAddress, remoteProfile ->
    val name   = remoteProfile.displayName
    val status = remoteProfile.customData["status"].asString()
    val score  = remoteProfile.customData["score"].asInt()
}
```

### 3. Start the service

```kotlin
val intent = PingService.buildIntent(context, profile)
context.startForegroundService(intent)
```

### 4. Stop the service

```kotlin
context.stopService(Intent(context, PingService::class.java))
```

---

## PingProfile

| Field | Type | Description |
|---|---|---|
| `userId` | `String` | Optional identifier for the local user |
| `displayName` | `String` | Optional display name |
| `message` | `String` | Optional status message |
| `customData` | `Map<String, PingValue>` | Arbitrary typed key-value data |
| `timestamp` | `Long` | Auto-set to current time on creation |

All fields are optional — `PingProfile()` with no arguments is valid.

---

## PingValue Types

Use extension functions to wrap values, and `as*` helpers to unpack them on the receiving end.

| Type | Wrap | Unpack |
|---|---|---|
| `String` | `"hello".toPingValue()` | `.asString()` |
| `Int` / `Long` | `42.toPingValue()` | `.asInt()` / `.asLong()` |
| `Double` / `Float` | `3.14.toPingValue()` | `.asDouble()` |
| `Boolean` | `true.toPingValue()` | `.asBoolean()` |

```kotlin
// Sender
customData = mapOf(
    "rank"   to "gold".toPingValue(),
    "elo"    to 1800.toPingValue(),
    "online" to true.toPingValue(),
    "winRate" to 0.63.toPingValue()
)

// Receiver
val rank    = profile.customData["rank"].asString()
val elo     = profile.customData["elo"].asInt()
val online  = profile.customData["online"].asBoolean()
val winRate = profile.customData["winRate"].asDouble()
```

---

## Battery Optimization

For reliable background operation, prompt the user to disable battery optimization for your app:

```kotlin
PingUtil.requestBatteryOptimizationExemption(context)
```

---

## Publishing to JitPack

1. Push your repo to GitHub
2. Create a release/tag (e.g. `1.0.0`) on GitHub
3. Visit `https://jitpack.io/#YourGitHubUsername/Ping` and trigger a build for that tag
4. Use the dependency shown above in consuming apps

---

## Requirements

- Android 13+ (minSdk 33)
- Kotlin 2.x
- A device with BLE advertising support
