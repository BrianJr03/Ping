# ping-nearby

An optional companion module for the Ping library that adds peer-to-peer image and data transfer using the [Google Nearby Connections API](https://developers.google.com/nearby/connections/overview). No internet connection is required — transfers happen entirely over local Wi-Fi and Bluetooth.

---

## Requirements

- **Google Play Services** — Nearby Connections is a GMS API. Devices without GMS (some Huawei models, custom ROMs, F-Droid builds) cannot use this module.
- Android 13+ (minSdk 33, same as the core `:ping` module)
- Both devices must call `start()` before they come within range

---

## Installation

Add the dependency alongside the core library:

```kotlin
dependencies {
    implementation("com.github.BrianJr03.Ping:ping:{version}")
    implementation("com.github.BrianJr03.Ping:ping-nearby:{version}")
}
```

---

## Permissions

Add to your `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.BLUETOOTH_SCAN"
    android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />
<uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES"
    android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.CHANGE_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

> The `ping-nearby` AAR merges these automatically via manifest merge, but declaring them explicitly in your app manifest makes permissions visible to Play Store reviewers.

Request at runtime before calling `start()`:

```kotlin
val permissionLauncher = registerForActivityResult(
    ActivityResultContracts.RequestMultiplePermissions()
) { results ->
    if (results.values.all { it }) {
        nearbyClient.start("Skye")
    }
}

with(PingNearbyPermissions) {
    if (!hasNearbyPermissions()) {
        requestNearbyPermissions(permissionLauncher)
    } else {
        nearbyClient.start("Skye")
    }
}
```

---

## Quick Start

```kotlin
val nearbyClient = PingNearbyClient(
    context   = this,
    serviceId = packageName   // use your app's package name
)

// Set callbacks before calling start()
nearbyClient.onEndpointFound = { endpointId, name ->
    Log.d("Nearby", "Found $name ($endpointId)")
}

nearbyClient.onConnected = { endpointId ->
    Log.d("Nearby", "Connected to $endpointId")
    // Safe to call sendImage / sendBytes from here
}

nearbyClient.onDisconnected = { endpointId ->
    Log.d("Nearby", "Disconnected from $endpointId")
}

nearbyClient.onBytesReceived = { endpointId, bytes ->
    val message = String(bytes, Charsets.UTF_8)
    Log.d("Nearby", "Bytes from $endpointId: $message")
}

nearbyClient.onImageReceived = { endpointId, bitmap ->
    imageView.setImageBitmap(bitmap)
}

nearbyClient.onTransferUpdate = { endpointId, progress ->
    progressBar.progress = (progress * 100).toInt()
}

nearbyClient.start(displayName = "Skye")
```

Stop when the session ends (e.g. `onDestroy`):

```kotlin
nearbyClient.stop()
```

---

## Sending Data

### Image

```kotlin
// Compresses to JPEG at quality 60 before sending
nearbyClient.sendImage(endpointId, bitmap)
```

### Bytes

```kotlin
val payload = "hello".toByteArray(Charsets.UTF_8)
nearbyClient.sendBytes(endpointId, payload)
```

---

## How It Works

- `start()` simultaneously calls `startAdvertising` and `startDiscovery` using `Strategy.P2P_CLUSTER`, allowing any number of devices to form a mesh.
- When a remote endpoint is discovered, a connection is requested automatically.
- When a connection is initiated from either side, it is accepted automatically without UI — matching the core Ping BLE auto-exchange philosophy.
- Image payloads are sent as Nearby file payloads for reliable large-data transfer. Temp files are created in the app's cache directory and cleaned up after the transfer completes.

---

## Differences from Core Ping (BLE)

| | Core `:ping` (BLE) | `:ping-nearby` |
|---|---|---|
| Transport | Bluetooth LE GATT | Nearby Connections (BLE + Wi-Fi) |
| Payload | `PingProfile` (MessagePack, ≤511 bytes) | Arbitrary bytes or images (no size limit) |
| Requires GMS | No | Yes |
| Background | Yes (foreground service) | App must be in foreground |
| Auto-exchange | Profiles exchanged on discovery | Connection auto-accepted; sending is manual |
