/** Client for the user-deployed emergency cron. */
export interface EmergencyContactInput {
  id: string;
  address: string;
}

export interface EmergencyShareInput {
  contactId: string;
  ciphertext: string;
}

export interface EmergencyDraft {
  contacts: EmergencyContactInput[];
  shares: EmergencyShareInput[];
  inactivitySecs: number;
  reminderLeadSecs: number;
  graceSecs: number;
}

export interface EmergencyStatus {
  id: string;
  state: "armed" | "cancelled" | "released";
  lastCheckIn: number;
  inactivitySecs: number;
  reminderLeadSecs: number;
  graceSecs: number;
  reminderSent: boolean;
}

export class EmergencyClientError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "EmergencyClientError";
  }
}

const MAX_RESPONSE = 64 * 1024;

export class EmergencyCronClient {
  private readonly base: string;
  private readonly token: string;

  constructor(baseUrl: string, token: string) {
    let url: URL;
    try {
      url = new URL(baseUrl);
    } catch {
      throw new EmergencyClientError("cron URL is invalid");
    }
    if (url.protocol !== "https:" || url.username.length > 0 || url.password.length > 0) {
      throw new EmergencyClientError("cron URL must be HTTPS without credentials");
    }
    if (token.length < 32) throw new EmergencyClientError("cron token is too short");
    this.base = url.origin;
    this.token = token;
  }

  private async request(path: string, init: RequestInit = {}): Promise<Response> {
    const response = await fetch(`${this.base}${path}`, {
      ...init,
      headers: {
        authorization: `Bearer ${this.token}`,
        "content-type": "application/json",
        ...(init.headers ?? {}),
      },
      redirect: "error",
      credentials: "omit",
      cache: "no-store",
    });
    if (!response.ok) throw new EmergencyClientError(`cron request failed (${response.status})`);
    return response;
  }

  private async json(response: Response): Promise<unknown> {
    const text = await response.text();
    if (text.length > MAX_RESPONSE) throw new EmergencyClientError("cron response too large");
    try {
      return JSON.parse(text) as unknown;
    } catch {
      throw new EmergencyClientError("cron returned invalid JSON");
    }
  }

  async create(draft: EmergencyDraft): Promise<EmergencyStatus> {
    if (
      draft.contacts.length === 0 ||
      draft.contacts.length > 2 ||
      draft.shares.length !== draft.contacts.length ||
      draft.inactivitySecs === 0 ||
      draft.reminderLeadSecs >= draft.inactivitySecs
    ) {
      throw new EmergencyClientError("invalid emergency policy");
    }
    const response = await this.request("/policies", {
      method: "POST",
      body: JSON.stringify(draft),
    });
    return this.json(response) as Promise<EmergencyStatus>;
  }

  async status(id: string): Promise<EmergencyStatus> {
    return this.json(await this.request(`/policies/${encodeURIComponent(id)}`)) as Promise<EmergencyStatus>;
  }

  async checkIn(id: string): Promise<EmergencyStatus> {
    return this.json(
      await this.request(`/policies/${encodeURIComponent(id)}/checkin`, { method: "POST" }),
    ) as Promise<EmergencyStatus>;
  }

  async cancel(id: string): Promise<EmergencyStatus> {
    return this.json(
      await this.request(`/policies/${encodeURIComponent(id)}/cancel`, { method: "POST" }),
    ) as Promise<EmergencyStatus>;
  }
}
