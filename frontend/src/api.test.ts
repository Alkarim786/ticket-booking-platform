import { describe, expect, it } from "vitest";
import { applySeatUpdates, layout, rupees, type Seat } from "./api";

const seat = (id: number, row: string, number: number, status: Seat["status"] = "AVAILABLE"): Seat => ({
  id,
  section: "Gold",
  row,
  number,
  priceCents: 150000,
  status,
});

describe("seat map helpers", () => {
  it("applies live updates without touching other seats", () => {
    const seats = [seat(1, "A", 1), seat(2, "A", 2), seat(3, "B", 1)];
    const next = applySeatUpdates(seats, [{ id: 2, status: "HELD" }]);
    expect(next.map((s) => s.status)).toEqual(["AVAILABLE", "HELD", "AVAILABLE"]);
    expect(next[0]).toBe(seats[0]); // unchanged seats keep identity (cheap re-render)
  });

  it("groups seats by section and row, ordered by number", () => {
    const l = layout([seat(3, "B", 1), seat(2, "A", 2), seat(1, "A", 1)]);
    expect(l).toHaveLength(1);
    const [, rows] = l[0]!;
    expect(rows.map(([r, list]) => [r, list.map((s) => s.number)])).toEqual([
      ["B", [1]],
      ["A", [1, 2]],
    ]);
  });

  it("formats rupees", () => {
    expect(rupees(150000)).toBe("₹1,500");
  });
});
