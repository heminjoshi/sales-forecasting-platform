import type { Query, UiConfig } from "../types";

// The config-driven control bar. tenant comes from GET /api/v1/tenants; window/channel/k option sets
// + defaults come from GET /api/v1/config; mode is the fixed actuals/forecast toggle (default actuals,
// parity with the static dashboard). This is a controlled component — state lives in App.
export function Controls({
  tenants,
  config,
  value,
  loading,
  onChange,
  onTenantChange,
  onSubmit,
}: {
  tenants: string[];
  config: UiConfig;
  value: Query;
  loading: boolean;
  onChange: (patch: Partial<Query>) => void;
  onTenantChange: (tenantId: string) => void;
  onSubmit: () => void;
}) {
  return (
    <form
      className="controls"
      autoComplete="off"
      onSubmit={(e) => {
        e.preventDefault();
        onSubmit();
      }}
    >
      <label className="field">
        <span>Tenant</span>
        <select value={value.tenantId} onChange={(e) => onTenantChange(e.target.value)}>
          {tenants.map((id) => (
            <option key={id} value={id}>
              {id}
            </option>
          ))}
        </select>
      </label>

      <label className="field">
        <span>Mode</span>
        <select value={value.mode} onChange={(e) => onChange({ mode: e.target.value as Query["mode"] })}>
          <option value="actuals">actuals</option>
          <option value="forecast">forecast</option>
        </select>
      </label>

      <label className="field">
        <span>Window</span>
        <select value={value.window} onChange={(e) => onChange({ window: e.target.value })}>
          {config.windowOptions.map((w) => (
            <option key={w} value={w}>
              {w}
            </option>
          ))}
        </select>
      </label>

      <label className="field">
        <span>Channel</span>
        <select value={value.channel} onChange={(e) => onChange({ channel: e.target.value })}>
          {config.channelOptions.map((c) => (
            <option key={c} value={c}>
              {c}
            </option>
          ))}
        </select>
      </label>

      <label className="field">
        <span>k</span>
        <select value={String(value.k)} onChange={(e) => onChange({ k: Number(e.target.value) })}>
          {config.kOptions.map((n) => (
            <option key={n} value={n}>
              {n}
            </option>
          ))}
        </select>
      </label>

      <button type="submit" className="btn" disabled={loading}>
        Load
      </button>
    </form>
  );
}
