/** Repository-layer Travel Mode gate. */
export class TravelModeActiveError extends Error {
  constructor() {
    super("Travel Mode is active.");
    this.name = "TravelModeActiveError";
  }
}

/** Throw when the vault is hidden, before any item read is attempted. */
export function assertTravelVisible(travelMode: boolean): void {
  if (travelMode) throw new TravelModeActiveError();
}
