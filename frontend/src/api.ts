export interface User {
  id: number;
  email: string;
  name: string;
  role: "USER" | "ADMIN";
}
export interface EventItem {
  id: number;
  name: string;
  venue: string;
  startsAt: string;
  description: string | null;
}
export interface Seat {
  id: number;
  section: string;
  row: string;
  number: number;
  priceCents: number;
  status: "AVAILABLE" | "HELD" | "BOOKED";
}
export interface Booking {
  id: number;
  eventId: number;
  status: string;
  amountCents: number;
  holdExpiresAt: string;
  paymentOrderId: string | null;
  createdAt: string;
  seats: Seat[];
}

export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
  ) {
    super(message);
  }
}

// localStorage can be missing (tests, SSR) or throw (private mode, blocked storage).
const store = {
  get: () => {
    try {
      return globalThis.localStorage?.getItem("token") ?? null;
    } catch {
      return null;
    }
  },
  set: (t: string | null) => {
    try {
      if (t) globalThis.localStorage?.setItem("token", t);
      else globalThis.localStorage?.removeItem("token");
    } catch {
      /* keep the token in memory only */
    }
  },
};

let token: string | null = store.get();

export function setToken(t: string | null) {
  token = t;
  store.set(t);
}

export const hasToken = () => token !== null;

async function call<T>(method: string, path: string, body?: unknown): Promise<T> {
  const res = await fetch(path, {
    method,
    headers: {
      ...(body !== undefined ? { "Content-Type": "application/json" } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  if (res.status === 401 && token) setToken(null);
  const text = await res.text();
  const data = text ? JSON.parse(text) : null;
  if (!res.ok) throw new ApiError(res.status, data?.code ?? "ERROR", data?.detail ?? res.statusText);
  return data as T;
}

export const api = {
  register: (email: string, password: string, name: string) =>
    call<{ token: string; user: User }>("POST", "/api/auth/register", { email, password, name }),
  login: (email: string, password: string) => call<{ token: string; user: User }>("POST", "/api/auth/login", { email, password }),
  me: () => call<User>("GET", "/api/auth/me"),
  events: () => call<EventItem[]>("GET", "/api/events"),
  event: (id: number) => call<EventItem>("GET", `/api/events/${id}`),
  seats: (id: number) => call<Seat[]>("GET", `/api/events/${id}/seats`),
  hold: (eventId: number, seatIds: number[]) => call<Booking>("POST", "/api/bookings/hold", { eventId, seatIds }),
  bookings: () => call<Booking[]>("GET", "/api/bookings"),
  cancel: (id: number) => call<Booking>("POST", `/api/bookings/${id}/cancel`),
  checkout: (id: number) => call<{ orderId: string; amountCents: number }>("POST", `/api/payments/${id}/checkout`),
  simulatePay: (id: number) => call<{ result: string }>("POST", `/api/payments/${id}/simulate`),
  createEvent: (body: unknown) => call<EventItem>("POST", "/api/admin/events", body),
};

export const rupees = (cents: number) => `₹${(cents / 100).toLocaleString("en-IN")}`;

/** Apply SSE seat updates to the current seat list (pure, so it's unit-tested). */
export function applySeatUpdates(seats: Seat[], updates: { id: number; status: Seat["status"] }[]): Seat[] {
  const byId = new Map(updates.map((u) => [u.id, u.status]));
  return seats.map((s) => (byId.has(s.id) ? { ...s, status: byId.get(s.id)! } : s));
}

/** Group seats into section -> row -> seats for rendering the map. */
export function layout(seats: Seat[]): [string, [string, Seat[]][]][] {
  const sections = new Map<string, Map<string, Seat[]>>();
  for (const s of seats) {
    if (!sections.has(s.section)) sections.set(s.section, new Map());
    const rows = sections.get(s.section)!;
    if (!rows.has(s.row)) rows.set(s.row, []);
    rows.get(s.row)!.push(s);
  }
  return [...sections].map(([name, rows]) => [name, [...rows].map(([r, list]) => [r, list.sort((a, b) => a.number - b.number)])]);
}
