import { StrictMode, useCallback, useEffect, useMemo, useState } from "react";
import { createRoot } from "react-dom/client";
import { ApiError, api, applySeatUpdates, type Booking, type EventItem, hasToken, layout, rupees, type Seat, setToken, type User } from "./api";
import "./styles.css";

type Route = { page: "events" } | { page: "event"; id: number } | { page: "bookings" } | { page: "admin" } | { page: "login" };

function parseRoute(): Route {
  const h = location.hash.replace(/^#\/?/, "");
  if (h.startsWith("oauth/callback")) return { page: "events" };
  const [page, id] = h.split("/");
  if (page === "event" && id) return { page: "event", id: Number(id) };
  if (page === "bookings" || page === "admin" || page === "login") return { page };
  return { page: "events" };
}

function App() {
  const [route, setRoute] = useState<Route>(parseRoute);
  const [user, setUser] = useState<User | null>(null);

  useEffect(() => {
    // Google sign-in redirects back with #token=... in the URL fragment.
    const m = /token=([^&]+)/.exec(location.hash);
    if (m) {
      setToken(decodeURIComponent(m[1]!));
      location.hash = "#/events";
    }
    if (hasToken()) api.me().then(setUser).catch(() => setUser(null));
    const onHash = () => setRoute(parseRoute());
    window.addEventListener("hashchange", onHash);
    return () => window.removeEventListener("hashchange", onHash);
  }, []);

  const logout = () => {
    setToken(null);
    setUser(null);
    location.hash = "#/events";
  };

  return (
    <div className="app">
      <header>
        <a href="#/events" className="brand">
          🎟 SeatRush
        </a>
        <nav>
          <a href="#/events">Events</a>
          {user && <a href="#/bookings">My bookings</a>}
          {user?.role === "ADMIN" && <a href="#/admin">Admin</a>}
          {user ? (
            <button onClick={logout}>Log out ({user.name})</button>
          ) : (
            <a href="#/login" className="btn">
              Log in
            </a>
          )}
        </nav>
      </header>
      <main>
        {route.page === "events" && <Events />}
        {route.page === "event" && <EventPage id={route.id} user={user} />}
        {route.page === "bookings" && <Bookings />}
        {route.page === "admin" && <Admin />}
        {route.page === "login" && <Login onLogin={setUser} />}
      </main>
    </div>
  );
}

function Events() {
  const [events, setEvents] = useState<EventItem[]>([]);
  useEffect(() => void api.events().then(setEvents), []);
  return (
    <section>
      <h1>Upcoming events</h1>
      <div className="cards">
        {events.map((e) => (
          <a key={e.id} className="card" href={`#/event/${e.id}`}>
            <h3>{e.name}</h3>
            <p>{e.venue}</p>
            <p className="muted">{new Date(e.startsAt).toLocaleString()}</p>
          </a>
        ))}
        {!events.length && <p className="muted">No events yet.</p>}
      </div>
    </section>
  );
}

function EventPage({ id, user }: { id: number; user: User | null }) {
  const [event, setEvent] = useState<EventItem | null>(null);
  const [seats, setSeats] = useState<Seat[]>([]);
  const [picked, setPicked] = useState<number[]>([]);
  const [booking, setBooking] = useState<Booking | null>(null);
  const [error, setError] = useState("");
  const [live, setLive] = useState(false);

  useEffect(() => {
    api.event(id).then(setEvent);
    api.seats(id).then(setSeats);
    // Live seat map: the server pushes seat status changes as they commit.
    const es = new EventSource(`/api/events/${id}/seats/stream`);
    es.onopen = () => setLive(true);
    es.onerror = () => setLive(false);
    es.addEventListener("seats", (ev) => {
      const updates = JSON.parse((ev as MessageEvent).data);
      setSeats((cur) => applySeatUpdates(cur, updates));
    });
    return () => es.close();
  }, [id]);

  // Drop selections that someone else just grabbed.
  useEffect(() => {
    setPicked((p) => p.filter((sid) => seats.find((s) => s.id === sid)?.status === "AVAILABLE" || booking?.seats.some((b) => b.id === sid)));
  }, [seats, booking]);

  const total = useMemo(() => seats.filter((s) => picked.includes(s.id)).reduce((a, s) => a + s.priceCents, 0), [seats, picked]);

  const toggle = (s: Seat) => {
    if (s.status !== "AVAILABLE" || booking) return;
    setPicked((p) => (p.includes(s.id) ? p.filter((x) => x !== s.id) : p.length >= 10 ? p : [...p, s.id]));
  };

  const hold = async () => {
    setError("");
    if (!user) {
      location.hash = "#/login";
      return;
    }
    try {
      setBooking(await api.hold(id, picked));
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e));
    }
  };

  if (!event) return <p>Loading…</p>;
  return (
    <section className="event">
      <div>
        <h1>{event.name}</h1>
        <p className="muted">
          {event.venue} · {new Date(event.startsAt).toLocaleString()} · <span className={live ? "live" : "offline"}>{live ? "● live" : "○ reconnecting"}</span>
        </p>
        <div className="stage">STAGE</div>
        {layout(seats).map(([section, rows]) => (
          <div key={section} className="section">
            <h4>
              {section} · {rupees(rows[0]?.[1][0]?.priceCents ?? 0)}
            </h4>
            {rows.map(([row, list]) => (
              <div key={row} className="row">
                <span className="rowlabel">{row}</span>
                {list.map((s) => (
                  <button
                    key={s.id}
                    title={`${section} ${row}${s.number} · ${rupees(s.priceCents)} · ${s.status}`}
                    className={`seat ${s.status.toLowerCase()} ${picked.includes(s.id) ? "picked" : ""}`}
                    onClick={() => toggle(s)}
                    aria-label={`seat ${row}${s.number} ${s.status}`}
                  />
                ))}
              </div>
            ))}
          </div>
        ))}
        <div className="legend">
          <span className="seat available" /> available <span className="seat picked" /> selected <span className="seat held" /> held <span className="seat booked" /> booked
        </div>
      </div>
      <aside>
        {booking ? (
          <Checkout booking={booking} onDone={setBooking} />
        ) : (
          <>
            <h3>Your selection</h3>
            <p>
              {picked.length} seat(s) · <b>{rupees(total)}</b>
            </p>
            <button className="primary" disabled={!picked.length} onClick={hold}>
              Hold seats
            </button>
            {error && <p className="error">{error}</p>}
          </>
        )}
      </aside>
    </section>
  );
}

function Checkout({ booking, onDone }: { booking: Booking; onDone: (b: Booking | null) => void }) {
  const [left, setLeft] = useState(0);
  const [status, setStatus] = useState(booking.status);
  const [msg, setMsg] = useState("");
  useEffect(() => {
    const t = setInterval(() => setLeft(Math.max(0, Math.round((Date.parse(booking.holdExpiresAt) - Date.now()) / 1000))), 250);
    return () => clearInterval(t);
  }, [booking]);
  const pay = async () => {
    try {
      await api.checkout(booking.id);
      const r = await api.simulatePay(booking.id);
      setStatus(r.result === "CONFIRMED" ? "CONFIRMED" : r.result);
    } catch (e) {
      setMsg(e instanceof ApiError ? e.message : String(e));
    }
  };
  const cancel = async () => {
    await api.cancel(booking.id);
    onDone(null);
  };
  if (status === "CONFIRMED")
    return (
      <div className="confirmed">
        <h3>✅ Booked!</h3>
        <p>{booking.seats.map((s) => `${s.section} ${s.row}${s.number}`).join(", ")}</p>
        <a href="#/bookings">View my bookings</a>
      </div>
    );
  return (
    <div>
      <h3>Complete payment</h3>
      <p>
        {booking.seats.length} seat(s) · <b>{rupees(booking.amountCents)}</b>
      </p>
      <p className={left < 60 ? "error" : "muted"}>
        Hold expires in {Math.floor(left / 60)}:{String(left % 60).padStart(2, "0")}
      </p>
      <button className="primary" onClick={pay} disabled={left === 0}>
        Pay (test mode)
      </button>
      <button onClick={cancel}>Release seats</button>
      {msg && <p className="error">{msg}</p>}
      <p className="muted small">Test mode: no real money moves. The server receives a signed webhook exactly like a real gateway.</p>
    </div>
  );
}

function Bookings() {
  const [list, setList] = useState<Booking[]>([]);
  useEffect(() => void api.bookings().then(setList), []);
  return (
    <section>
      <h1>My bookings</h1>
      <table>
        <thead>
          <tr>
            <th>#</th>
            <th>Seats</th>
            <th>Amount</th>
            <th>Status</th>
            <th>Created</th>
          </tr>
        </thead>
        <tbody>
          {list.map((b) => (
            <tr key={b.id}>
              <td>{b.id}</td>
              <td>{b.seats.map((s) => `${s.section} ${s.row}${s.number}`).join(", ")}</td>
              <td>{rupees(b.amountCents)}</td>
              <td>
                <span className={`badge ${b.status.toLowerCase()}`}>{b.status}</span>
              </td>
              <td>{new Date(b.createdAt).toLocaleString()}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}

function Login({ onLogin }: { onLogin: (u: User) => void }) {
  const [mode, setMode] = useState<"login" | "register">("login");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [name, setName] = useState("");
  const [error, setError] = useState("");
  const submit = useCallback(
    async (e: React.FormEvent) => {
      e.preventDefault();
      setError("");
      try {
        const r = mode === "login" ? await api.login(email, password) : await api.register(email, password, name);
        setToken(r.token);
        onLogin(r.user);
        history.length > 1 ? history.back() : (location.hash = "#/events");
      } catch (err) {
        setError(err instanceof ApiError ? err.message : String(err));
      }
    },
    [mode, email, password, name, onLogin],
  );
  return (
    <section className="auth">
      <h1>{mode === "login" ? "Log in" : "Create account"}</h1>
      <form onSubmit={submit}>
        {mode === "register" && <input placeholder="Name" value={name} onChange={(e) => setName(e.target.value)} required />}
        <input type="email" placeholder="Email" value={email} onChange={(e) => setEmail(e.target.value)} required />
        <input type="password" placeholder="Password (min 8 chars)" value={password} onChange={(e) => setPassword(e.target.value)} required minLength={8} />
        <button className="primary" type="submit">
          {mode === "login" ? "Log in" : "Sign up"}
        </button>
      </form>
      <a href="/oauth2/authorization/google" className="muted small">
        Sign in with Google (when enabled on the server)
      </a>
      <p>
        <button className="link" onClick={() => setMode(mode === "login" ? "register" : "login")}>
          {mode === "login" ? "No account? Sign up" : "Have an account? Log in"}
        </button>
      </p>
      {error && <p className="error">{error}</p>}
    </section>
  );
}

function Admin() {
  const [form, setForm] = useState({ name: "", venue: "", startsAt: "", rows: 5, perRow: 20, price: 1500 });
  const [msg, setMsg] = useState("");
  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    try {
      const ev = await api.createEvent({
        name: form.name,
        venue: form.venue,
        startsAt: new Date(form.startsAt).toISOString(),
        sections: [{ name: "General", rows: form.rows, seatsPerRow: form.perRow, priceCents: form.price * 100 }],
      });
      location.hash = `#/event/${ev.id}`;
    } catch (err) {
      setMsg(err instanceof ApiError ? err.message : String(err));
    }
  };
  const set = (k: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement>) =>
    setForm({ ...form, [k]: e.target.type === "number" ? Number(e.target.value) : e.target.value });
  return (
    <section className="auth">
      <h1>Create event</h1>
      <form onSubmit={submit}>
        <input placeholder="Name" value={form.name} onChange={set("name")} required />
        <input placeholder="Venue" value={form.venue} onChange={set("venue")} required />
        <input type="datetime-local" value={form.startsAt} onChange={set("startsAt")} required />
        <label>
          Rows <input type="number" min={1} max={26} value={form.rows} onChange={set("rows")} />
        </label>
        <label>
          Seats per row <input type="number" min={1} max={100} value={form.perRow} onChange={set("perRow")} />
        </label>
        <label>
          Price (₹) <input type="number" min={0} value={form.price} onChange={set("price")} />
        </label>
        <button className="primary">Create</button>
      </form>
      {msg && <p className="error">{msg}</p>}
    </section>
  );
}

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
