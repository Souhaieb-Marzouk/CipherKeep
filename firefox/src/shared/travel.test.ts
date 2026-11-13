import { describe, expect, it } from "vitest";

import { assertTravelVisible, TravelModeActiveError } from "./travel";

describe("Travel Mode repository gate", () => {
  it("allows visible vault reads", () => {
    expect(() => assertTravelVisible(false)).not.toThrow();
  });

  it("refuses hidden vault reads", () => {
    expect(() => assertTravelVisible(true)).toThrow(TravelModeActiveError);
  });
});
