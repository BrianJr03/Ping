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

Ping is split into two independent modules. Add whichever you need:

```kotlin
dependencies {
    // Core BLE profile exchange (required)
    implementation("com.github.BrianJr03.Ping:ping:0.8.2")

    // Optional: peer-to-peer image & file transfer via Nearby Connections
    implementation("com.github.BrianJr03.Ping:ping-nearby:0.8.2")
}
```

The two modules are fully independent — you can use either or both. See [NEARBY.md](NEARBY.md) for `ping-nearby` setup and usage.

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

### Runtime permissions

Use the built-in helper to request all required permissions at once:

```kotlin
// Register the launcher in your Activity/Fragment
val permissionLauncher = registerForActivityResult(
    ActivityResultContracts.RequestMultiplePermissions()
) { results ->
    if (results.values.all { it }) {
        // all granted — safe to start the service
    }
}

// Check and request
with(PingPermissions) {
    if (!hasPingPermissions()) {
        requestPingPermissions(permissionLauncher)
    }
}
```

`PING_PERMISSIONS` is also exposed as a top-level array if you need it directly (e.g. for `shouldShowRequestPermissionRationale`).

---

## Quick Start

### 1. Set the encounter callback

**Do this before starting the service.** `onEncounter` is a single static slot — setting it after the service has already found a device will miss that encounter.

```kotlin
PingService.onEncounter = { deviceAddress, remoteProfile ->
    val name   = remoteProfile.displayName
    val status = remoteProfile.customData["status"]?.asString()
    val score  = remoteProfile.customData["score"]?.asInt()
}
```

### 2. Build a profile

```kotlin
val profile = PingProfile(
    userId = "user_42",
    displayName = "Skye",
    customData = mapOf(
        "status" to "looking for a match".toPingValue(),
        "score"  to 1500.toPingValue(),
        "active" to true.toPingValue()
    )
)
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

## Notification Customization

By default, the foreground service notification shows **"Ping Active"** as the title and no body text. Override either before calling `startForegroundService`:

```kotlin
PingService.notificationTitle = "My App"          // replaces "Ping Active"
PingService.notificationText  = "Finding nearby players…"  // omit or set "" to show no body
```

| Property | Type | Default | Behaviour |
|---|---|---|---|
| `notificationTitle` | `String` | `"Ping Active"` | Sets the notification title |
| `notificationText` | `String?` | `null` | Sets the notification body; `null` or `""` skips `setContentText()` entirely |

Set these **before** starting the service so the initial notification reflects your values.

---

## PingProfile

| Field | Type | Description |
|---|---|---|
| `userId` | `String` | Stable identifier for the local user or device (e.g. `Build.MODEL`, a UUID). Shown on the receiver side as the sender's identity. |
| `displayName` | `String` | Human-readable name for this profile or session (e.g. a username or feature label). |
| `message` | `String` | Optional short status message |
| `customData` | `Map<String, PingValue>` | Arbitrary typed key-value data |
| `timestamp` | `Long` | Auto-set to current time on creation |
| `schemaVersion` | `Int` | Serialized as `_v`. Default `1`. Bump when making breaking changes to your `customData` format so receivers can detect stale profiles. |

All fields are optional — `PingProfile()` with no arguments is valid.

> **`userId` vs `displayName`:** Use `userId` for a stable device/user identifier that the receiver can use to attribute data (it's shown as the sender name in received data). Use `displayName` for a human-readable label or session descriptor. Both are free-form strings — the distinction is a convention, not enforced by the library.

---

## PingValue Types

Use extension functions to wrap values, and `as*` helpers to unpack them on the receiving end. All `as*` helpers return **nullable** values — always use `?.` or a safe fallback.

| Type | Wrap | Unpack |
|---|---|---|
| `String` | `"hello".toPingValue()` | `?.asString()` |
| `Int` / `Long` | `42.toPingValue()` | `?.asInt()` / `?.asLong()` |
| `Double` / `Float` | `3.14.toPingValue()` | `?.asDouble()` |
| `Boolean` | `true.toPingValue()` | `?.asBoolean()` |

```kotlin
// Sender
customData = mapOf(
    "rank"    to "gold".toPingValue(),
    "elo"     to 1800.toPingValue(),
    "online"  to true.toPingValue(),
    "winRate" to 0.63.toPingValue()
)

// Receiver — always null-safe
val rank    = profile.customData["rank"]?.asString()
val elo     = profile.customData["elo"]?.asInt()
val online  = profile.customData["online"]?.asBoolean()
val winRate = profile.customData["winRate"]?.asDouble()
```

---

## PingPermissions

| Function | Description |
|---|---|
| `Context.hasPingPermissions()` | Returns `true` if all BLE permissions are granted |
| `requestPingPermissions(launcher)` | Launches the system permission dialog for all required permissions |
| `requestBatteryOptimizationExemption(context)` | Prompts the user to disable battery optimization for reliable background operation |

```kotlin
with(PingPermissions) {
    if (!hasPingPermissions()) {
        requestPingPermissions(permissionLauncher)
    }
    requestBatteryOptimizationExemption(context)
}
```

---

## Concurrent Connections

When multiple Ping devices are in range simultaneously, Ping connects and exchanges profiles with all of them concurrently — each device gets its own coroutine and runs through the full GATT exchange in parallel. A semaphore caps simultaneous outgoing connections at **4** (the reliable limit for most Android BLE stacks). Devices beyond that queue and connect as slots free up, so no exchange is dropped — just briefly delayed.

Each connection has a **15-second timeout**. If a peripheral stops responding mid-exchange, its slot is released and the error is reported via `onEncounterError`.

---

## Timing & Cooldowns

Ping has two independent cooldowns that serve different purposes. Both are configurable — set them before calling `startForegroundService`:

| Property | Default | What it gates |
|---|---|---|
| `PingService.encounterCooldownMs` | 30,000 ms | How often `onEncounter` fires for the **same device**. This is the app-level deduplication window — your callback won't be invoked again for this address until the window expires. Tracked in a static map so it survives service restarts. |
| `PingService.reconnectCooldownMs` | 60,000 ms | How often a **GATT connection** is attempted to the same device. This is the BLE-level gate — even if the scan keeps seeing a device, Ping won't reconnect until this window expires. |

**Why two cooldowns?** They solve different problems. `encounterCooldownMs` controls how often your app hears about a device. `reconnectCooldownMs` controls how often the radio actually connects — keeping it longer reduces battery drain and BLE congestion even when `encounterCooldownMs` is short. Setting `reconnectCooldownMs` shorter than `encounterCooldownMs` is valid but wastes radio time since the connection will complete but `onEncounter` will be suppressed.

```kotlin
// Tighten for a high-traffic event where re-encounters are expected frequently
PingService.encounterCooldownMs = 10_000L   // 10s
PingService.reconnectCooldownMs = 20_000L   // 20s

// Loosen for a low-power background mode
PingService.encounterCooldownMs = 120_000L  // 2 min
PingService.reconnectCooldownMs = 300_000L  // 5 min
```

**Implication for testing:** After a successful exchange, you won't see another `onEncounter` from the same device until `encounterCooldownMs` has elapsed, even if you restart the service. Call `PingService.clearEncounters()` to reset the cooldown map and force immediate re-encounters:

```kotlin
PingService.clearEncounters()
```

---

## Payload Size

`PingProfile` is serialized as **MessagePack** (binary) and transferred over a single GATT characteristic. The requested MTU is **512 bytes**, giving a practical payload budget of roughly **511 bytes** for the entire serialized profile.

MessagePack is 20–40% more compact than JSON, which means roughly 100–200 extra bytes of headroom compared to the previous JSON encoding — more room for `customData` before hitting the MTU ceiling.

> **If the serialized profile exceeds the MTU, the exchange silently fails.** No error is thrown — the remote device simply won't receive a parseable profile.

Fields that contribute to the byte count:
- `userId`, `displayName`, `message` — plain strings, count directly
- `customData` — keys + values + MessagePack framing (~3–5 bytes per entry, vs ~10–15 for JSON)
- `timestamp` — fixed 8-byte Long
- `schemaVersion` — 1 byte

**Practical budgeting:**

A minimal empty profile uses ~15 bytes of overhead. For `customData`, prefer short keys and compact value strings:

```kotlin
// ~40 bytes for one entry — leaves room for many more entries vs JSON
customData = mapOf("t" to "id|name|#FF001122|#FF334455|false".toPingValue())

// Verbose keys still add up — avoid if sharing many values
customData = mapOf(
    "primaryColor" to "#FF001122".toPingValue(),
    "secondaryColor" to "#FF334455".toPingValue()
)
```

If you need to pack multiple structured items (e.g. a list of themes), encode them into a single value using a delimiter:

```kotlin
val packed = items.joinToString(";") { "${it.id}|${it.name}|${it.color}" }
customData = mapOf("items" to packed.toPingValue())
```

### Schema versioning

Every profile includes a `schemaVersion` field (serialized as `_v`, default `1`). If you introduce a breaking change to how you encode `customData`, bump this value so receivers can detect and handle profiles from older app versions gracefully:

```kotlin
PingService.onEncounter = { _, profile ->
    if (profile.schemaVersion < 2) return@onEncounter  // ignore legacy profiles
    // handle current format
}
```

---

## `onEncounter` is a Single Slot

`PingService.onEncounter` is a `static var` — only one callback can be registered at a time. If multiple parts of your app set it, each assignment overwrites the previous one.

**For apps where multiple components need to react to encounters**, implement a broadcaster that sets `onEncounter` once and forwards to all registered listeners:

```kotlin
@Singleton
class PingBroadcastManager @Inject constructor() {
    private val listeners = mutableListOf<(String, PingProfile) -> Unit>()

    fun addListener(listener: (String, PingProfile) -> Unit) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeListener(listener: (String, PingProfile) -> Unit) {
        listeners.remove(listener)
    }

    init {
        PingService.onEncounter = { address, profile ->
            listeners.forEach { it(address, profile) }
        }
    }
}
```

Each component then registers and unregisters its own listener:

```kotlin
// Register
pingBroadcastManager.addListener(myListener)

// Unregister when done
pingBroadcastManager.removeListener(myListener)
```

---

## Calling `startForegroundService` Again

Each call to `startForegroundService` with a new `PingService` intent **stops the current scan/advertise cycle and immediately restarts it** with the new profile. This is intentional and is the correct way to update the profile mid-session.

However, calling it in rapid succession (e.g. in a loop or on every recomposition) will interrupt ongoing GATT exchanges. Call it **once per session**, or once when the profile meaningfully changes:

```kotlin
// Good — called once when the user starts sharing
val intent = PingService.buildIntent(context, profile)
context.startForegroundService(intent)

// Bad — restarts the manager on every list item, cancelling in-progress exchanges
themes.forEach { theme ->
    context.startForegroundService(PingService.buildIntent(context, profileFor(theme)))
}
```

---

## Background Reliability

`PingService` is a foreground service and continues running when your app is backgrounded. However, Android's battery optimization can throttle or kill BLE scanning in the background.

**Always call `requestBatteryOptimizationExemption`** — without it, background exchanges are unreliable on most devices:

```kotlin
PingPermissions.requestBatteryOptimizationExemption(context)
```

For background exchanges to work:
- Both devices must have the service running
- Both devices must have battery optimization disabled for your app
- Neither app should be force-stopped (screen-off / screen-locked is fine)

---

## Both Devices Must Be Running the Service

Ping performs a **symmetric exchange** — both devices advertise and scan simultaneously. The initiating device (scanner) connects and writes its profile to the remote device, which responds by notifying its own profile back.

This means:
- If only one device is running the service, the other won't initiate a connection and no exchange occurs
- Both devices must call `startForegroundService` before they come within range

---

## Filtering Profiles

If your app uses Ping for multiple purposes, or if you want to ignore profiles from other Ping-enabled apps, use a marker in `customData` rather than relying solely on `displayName`. This keeps `displayName` free for user-facing content:

```kotlin
// Sender — mark this as a "theme" profile
customData = mapOf(
    "_type" to "theme".toPingValue(),
    "t"     to packedThemeData.toPingValue()
)

// Receiver — filter by marker
PingService.onEncounter = { _, profile ->
    if (profile.customData["_type"]?.asString() == "theme") {
        // handle theme profile
    }
}
```

---

## Service Lifecycle

| Behaviour | Detail |
|---|---|
| `START_STICKY` | The OS will restart the service automatically if it is killed |
| Boot receiver | The `BootReceiver` restarts the service after device reboot if it was running before |
| Service restart on new intent | Calling `startForegroundService` again stops the current `PingManager` and starts a fresh one with the new profile |
| Static encounter map | `lastEncounters` is static — cooldown tracking survives service restarts within the same process |

---

## Exchange Flow (for debugging)

Understanding the internal sequence helps diagnose failures. All discovered devices are processed concurrently — the flow below happens in parallel for each peer, up to 4 at a time:

```
Device A (scanner)                    Device B (advertiser + scanner)
      |                                         |
      |--- BLE scan finds Device B ------------>|
      |  [coroutine launched for Device B]      |
      |--- GATT connect ----------------------->|
      |--- Request MTU (512) ------------------>|
      |--- Discover services ------------------>|
      |--- Read characteristic (gets B profile)->|
      |<-- onEncounter(B's profile) fired        |
      |--- Write characteristic (sends A profile)>|
      |<-- onEncounter(A's profile) fired on B   |
      |--- Disconnect -------------------------->|

      [simultaneously, if Device C is also in range]
      |--- GATT connect ----------------------->| (Device C)
      |    ... same exchange in parallel ...    |
```

If `onEncounter` never fires, check:
1. Both devices have the service running
2. Battery optimization is disabled on both
3. The 30s encounter cooldown hasn't been hit — call `PingService.clearEncounters()` to reset
4. The serialized profile fits within ~511 bytes (MessagePack — significantly more headroom than JSON)

---

## Requirements

- Android 13+ (minSdk 33)
- Kotlin 2.x
- A device with BLE advertising support

`ping-nearby` additionally requires Google Play Services. See [NEARBY.md](NEARBY.md) for details.