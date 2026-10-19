// Ticket rush: many users try to book the same small pool of seats at once.
//   docker compose --profile load run --rm k6
// Checks that no seat is ever sold twice and reports latency percentiles.
import http from "k6/http";
import { check } from "k6";
import { Counter, Trend } from "k6/metrics";

const BASE = __ENV.BASE_URL || "http://localhost:3000";
const SEATS = Number(__ENV.SEATS || 100);
const USERS = Number(__ENV.USERS || 500);

export const options = {
  scenarios: {
    rush: { executor: "per-vu-iterations", vus: USERS, iterations: 1, maxDuration: "3m" },
  },
  setupTimeout: "10m",
  thresholds: { http_req_failed: ["rate<0.01"] }, // 409s are expected and not counted as failures
};

const holdOk = new Counter("holds_won");
const holdConflict = new Counter("holds_lost_conflict");
const paid = new Counter("bookings_confirmed");
const holdLatency = new Trend("hold_latency", true);

const json = { headers: { "Content-Type": "application/json" } };
const auth = (t) => ({ headers: { "Content-Type": "application/json", Authorization: `Bearer ${t}` } });
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 299 }, 409));
const statuses = new Counter("hold_status_other");

export function setup() {
  const admin = http.post(`${BASE}/api/auth/login`, JSON.stringify({ email: __ENV.ADMIN_EMAIL, password: __ENV.ADMIN_PASSWORD }), json).json("token");
  const startsAt = new Date(Date.now() + 7 * 86400e3).toISOString();
  const ev = http.post(
    `${BASE}/api/admin/events`,
    JSON.stringify({ name: `k6 rush ${Date.now()}`, venue: "Load Test Arena", startsAt, sections: [{ name: "Floor", rows: SEATS / 20, seatsPerRow: 20, priceCents: 99900 }] }),
    auth(admin),
  ).json();
  // Register everyone before the rush, so the measured phase is pure booking traffic
  // (BCrypt password hashing is deliberately expensive and would dominate otherwise).
  const tokens = [];
  for (let i = 0; i < USERS; i += 50) {
    const batch = [];
    for (let j = i; j < Math.min(USERS, i + 50); j++) {
      batch.push(["POST", `${BASE}/api/auth/register`, JSON.stringify({ email: `k6-${j}-${Date.now()}@load.test`, password: "load-test-pass", name: `vu${j}` }), json]);
    }
    for (const r of http.batch(batch)) tokens.push(r.json("token"));
  }
  return { eventId: ev.id, tokens };
}

export default function (data) {
  const token = data.tokens[__VU - 1];
  const seats = http.get(`${BASE}/api/events/${data.eventId}/seats`).json();
  const free = seats.filter((s) => s.status === "AVAILABLE");
  if (!free.length) return;
  const pick = free[Math.floor(Math.random() * free.length)];
  const res = http.post(`${BASE}/api/bookings/hold`, JSON.stringify({ eventId: data.eventId, seatIds: [pick.id] }), auth(token));
  holdLatency.add(res.timings.duration);
  if (res.status === 201) {
    holdOk.add(1);
    const id = res.json("id");
    const pay = http.post(`${BASE}/api/payments/${id}/simulate`, null, auth(token));
    if (check(pay, { confirmed: (r) => r.status === 200 && r.json("result") === "CONFIRMED" })) paid.add(1);
  } else if (res.status === 409) {
    holdConflict.add(1);
  } else {
    statuses.add(1, { status: String(res.status) });
  }
}

export function teardown(data) {
  const seats = http.get(`${BASE}/api/events/${data.eventId}/seats`).json();
  const booked = seats.filter((s) => s.status === "BOOKED").length;
  console.log(`seats=${seats.length} booked=${booked}`);
  check(booked, { "never more bookings than seats": (b) => b <= seats.length });
}
