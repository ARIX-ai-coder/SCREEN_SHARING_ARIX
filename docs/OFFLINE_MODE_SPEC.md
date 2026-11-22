# ARIX Offline Mode — product specification

## Supported offline setup in the first offline release
- Phone and computer on the same Wi-Fi router; internet access is not needed.
- Phone hotspot enabled and computer joined to it.
- Computer hotspot enabled and phone joined to it.
- One device hosts the local ARIX service; the other joins its local IP address.

## Session behavior
- Pairing code is generated locally by the host service.
- Signaling uses the local network address.
- Once WebRTC has a working local candidate path, video packets flow directly over the LAN.
- If internet access disappears but the LAN stays up, the local media path can continue.
- If the Wi-Fi/hotspot link itself disconnects, media pauses; ARIX attempts reconnect when the same LAN becomes reachable again.

## No network at all
A wireless connection still requires a communication link. For no router and no internet, a later native implementation can evaluate Wi-Fi Direct / peer-to-peer Wi-Fi. A normal web page cannot independently turn a laptop Wi-Fi adapter into a universally compatible Wi-Fi Direct receiver. Bluetooth is not selected for screen video because bandwidth and latency are unsuitable.

## Security
- Explicit Android screen-capture consent is mandatory.
- Pairing codes expire and must be rate-limited in production.
- Use TLS/WSS for internet mode.
- LAN mode should still use authenticated pairing; local network does not mean trusted network.
- Provide a visible Stop Sharing action and stop capture immediately on revocation.
