/**
 * Google's polyline encoding — the same format the field app writes and the
 * dashboard reads. Present here because snapping happens server-side: the Roads
 * API cannot be called from a browser (no CORS, and a referrer-restricted key is
 * meaningless to it), so the trail is decoded, snapped and re-encoded here.
 */

export function decodePath(encoded: string): { lat: number; lng: number }[] {
  const out: { lat: number; lng: number }[] = [];
  let index = 0;
  let lat = 0;
  let lng = 0;
  while (index < encoded.length) {
    const [dLat, n1] = decodeSigned(encoded, index);
    const [dLng, n2] = decodeSigned(encoded, n1);
    lat += dLat;
    lng += dLng;
    index = n2;
    out.push({ lat: lat / 1e5, lng: lng / 1e5 });
  }
  return out;
}

export function encodePath(points: { lat: number; lng: number }[]): string {
  let out = "";
  let prevLat = 0;
  let prevLng = 0;
  for (const p of points) {
    const eLat = Math.round(p.lat * 1e5);
    const eLng = Math.round(p.lng * 1e5);
    out += encodeSigned(eLat - prevLat) + encodeSigned(eLng - prevLng);
    prevLat = eLat;
    prevLng = eLng;
  }
  return out;
}

export function distanceMeters(lat1: number, lon1: number, lat2: number, lon2: number): number {
  const r = 6371e3;
  const p1 = (lat1 * Math.PI) / 180;
  const p2 = (lat2 * Math.PI) / 180;
  const dp = ((lat2 - lat1) * Math.PI) / 180;
  const dl = ((lon2 - lon1) * Math.PI) / 180;
  const a = Math.sin(dp / 2) ** 2 + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) ** 2;
  return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

function encodeSigned(value: number): string {
  let v = value < 0 ? ~(value << 1) : value << 1;
  let out = "";
  while (v >= 0x20) {
    out += String.fromCharCode((0x20 | (v & 0x1f)) + 63);
    v >>= 5;
  }
  return out + String.fromCharCode(v + 63);
}

function decodeSigned(encoded: string, start: number): [number, number] {
  let index = start;
  let shift = 0;
  let result = 0;
  let b: number;
  do {
    b = encoded.charCodeAt(index++) - 63;
    result |= (b & 0x1f) << shift;
    shift += 5;
  } while (b >= 0x20);
  return [result & 1 ? ~(result >> 1) : result >> 1, index];
}
